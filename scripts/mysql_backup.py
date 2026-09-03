import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import uuid
from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import Path


SAFE_DRILL_SCHEMA = re.compile(r"tts_restore_verify_[A-Za-z0-9_]+")
SAFE_DATABASE_IDENTIFIER = re.compile(r"[A-Za-z0-9_$]+")


@dataclass(frozen=True)
class DatabaseConfig:
    host: str
    port: str
    database: str
    username: str
    password: str
    admin_username: str
    admin_password: str

    @staticmethod
    def from_environment():
        required = ("DB_HOST", "DB_PORT", "DB_NAME", "DB_USERNAME", "DB_PASSWORD")
        missing = [name for name in required if not os.getenv(name)]
        if missing:
            raise SystemExit("Missing database environment variables: " + ", ".join(missing))
        require_safe_database_identifier(os.environ["DB_NAME"])
        return DatabaseConfig(
            host=os.environ["DB_HOST"],
            port=os.environ["DB_PORT"],
            database=os.environ["DB_NAME"],
            username=os.environ["DB_USERNAME"],
            password=os.environ["DB_PASSWORD"],
            admin_username=os.getenv("DB_ADMIN_USERNAME", os.environ["DB_USERNAME"]),
            admin_password=os.getenv("DB_ADMIN_PASSWORD", os.environ["DB_PASSWORD"]),
        )


def command(program, config, username, database=None, execute=None):
    result = [
        program,
        "--protocol=tcp",
        "--host", config.host,
        "--port", config.port,
        "--user", username,
        "--default-character-set=utf8mb4",
    ]
    if database:
        result.append(database)
    if execute:
        result.extend(["--execute", execute])
    return result


def client_environment(password):
    environment = os.environ.copy()
    environment["MYSQL_PWD"] = password
    return environment


def executable(environment_name, default_name):
    configured = os.getenv(environment_name)
    resolved = configured or shutil.which(default_name)
    if not resolved:
        raise SystemExit(f"{default_name} was not found; set {environment_name}")
    return resolved


def create_backup(config, output, overwrite=False):
    output = Path(output).resolve()
    if output.exists() and not overwrite:
        raise SystemExit(f"Backup already exists: {output}")
    output.parent.mkdir(parents=True, exist_ok=True)
    partial = output.with_suffix(output.suffix + ".partial")
    dump = executable("MYSQLDUMP_CLIENT", "mysqldump")
    args = command(dump, config, config.username)
    args.extend([
        "--single-transaction",
        "--quick",
        "--triggers",
        "--hex-blob",
        "--set-gtid-purged=OFF",
        config.database,
    ])
    try:
        with partial.open("wb") as stream:
            subprocess.run(
                args,
                check=True,
                stdout=stream,
                env=client_environment(config.password),
            )
        partial.replace(output)
    finally:
        partial.unlink(missing_ok=True)
    digest = sha256(output)
    manifest = {
        "schema": config.database,
        "createdAt": datetime.now(UTC).isoformat(),
        "bytes": output.stat().st_size,
        "sha256": digest,
    }
    output.with_suffix(output.suffix + ".json").write_text(
        json.dumps(manifest, indent=2) + "\n", encoding="utf-8"
    )
    return manifest


def verify_restore(config, backup, drill_schema=None):
    backup = Path(backup).resolve()
    if not backup.is_file():
        raise SystemExit(f"Backup does not exist: {backup}")
    verify_manifest(backup)
    drill_schema = drill_schema or "tts_restore_verify_" + uuid.uuid4().hex
    require_safe_drill_schema(drill_schema)
    mysql = executable("MYSQL_CLIENT", "mysql")
    admin_env = client_environment(config.admin_password)
    create_sql = (
        f"CREATE DATABASE `{drill_schema}` CHARACTER SET utf8mb4 "
        "COLLATE utf8mb4_unicode_ci"
    )
    drop_sql = f"DROP DATABASE IF EXISTS `{drill_schema}`"
    subprocess.run(
        command(mysql, config, config.admin_username, execute=create_sql),
        check=True,
        env=admin_env,
    )
    try:
        with backup.open("rb") as stream:
            subprocess.run(
                command(mysql, config, config.admin_username, database=drill_schema),
                check=True,
                stdin=stream,
                env=admin_env,
            )
        source_tables = table_count(mysql, config, config.database, admin_env)
        restored_tables = table_count(mysql, config, drill_schema, admin_env)
        if source_tables != restored_tables or restored_tables < 1:
            raise RuntimeError(
                f"Restore table count mismatch: source={source_tables}, restored={restored_tables}"
            )
        return {
            "verifiedAt": datetime.now(UTC).isoformat(),
            "sourceSchema": config.database,
            "drillSchema": drill_schema,
            "tableCount": restored_tables,
            "sha256": sha256(backup),
            "passed": True,
        }
    finally:
        subprocess.run(
            command(mysql, config, config.admin_username, execute=drop_sql),
            check=True,
            env=admin_env,
        )


def table_count(mysql, config, schema, environment):
    require_safe_database_identifier(schema)
    sql = (
        "SELECT COUNT(*) FROM information_schema.tables "
        f"WHERE table_schema='{schema}'"
    )
    args = command(mysql, config, config.admin_username, execute=sql)
    args.append("--skip-column-names")
    result = subprocess.run(
        args,
        check=True,
        capture_output=True,
        text=True,
        env=environment,
    )
    return int(result.stdout.strip())


def require_safe_drill_schema(schema):
    if not SAFE_DRILL_SCHEMA.fullmatch(schema):
        raise ValueError("Restore drill schema must match tts_restore_verify_[A-Za-z0-9_]+")


def require_safe_database_identifier(identifier):
    if not SAFE_DATABASE_IDENTIFIER.fullmatch(identifier):
        raise ValueError("Database name must match [A-Za-z0-9_$]+")


def verify_manifest(backup):
    manifest_path = backup.with_suffix(backup.suffix + ".json")
    if not manifest_path.is_file():
        raise RuntimeError("Backup manifest does not exist")
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    if manifest.get("sha256") != sha256(backup):
        raise RuntimeError("Backup SHA-256 does not match its manifest")


def sha256(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main():
    parser = argparse.ArgumentParser(description="FCTTS MySQL backup and restore drill")
    subparsers = parser.add_subparsers(dest="command_name", required=True)
    backup = subparsers.add_parser("backup")
    backup.add_argument("--output", required=True)
    backup.add_argument("--overwrite", action="store_true")
    verify = subparsers.add_parser("verify")
    verify.add_argument("--backup", required=True)
    verify.add_argument("--drill-schema")
    args = parser.parse_args()
    config = DatabaseConfig.from_environment()
    if args.command_name == "backup":
        report = create_backup(config, args.output, args.overwrite)
    else:
        report = verify_restore(config, args.backup, args.drill_schema)
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
