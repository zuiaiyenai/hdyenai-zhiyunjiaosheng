import json
import sys
import tempfile
import threading
import types
import unittest
from unittest import mock
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path


sys.path.insert(0, str(Path(__file__).parent))

import mysql_backup
import production_gate


class ProbeHandler(BaseHTTPRequestHandler):
    def do_HEAD(self):
        self.send_response(405)
        self.end_headers()

    def do_GET(self):
        bodies = {
            "/actuator/health": b'{"status":"UP"}',
            "/actuator/health/readiness": b'{"status":"UP"}',
            "/actuator/prometheus": b"jvm_memory_used_bytes 1\n",
            "/health": b'{"status":"UP"}',
        }
        if self.path == "/tts":
            self.send_response(422)
            self.end_headers()
            return
        body = bodies.get(self.path)
        if body is None:
            self.send_response(404)
            self.end_headers()
            return
        self.send_response(200)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, format, *args):
        pass


class ProductionGateTest(unittest.TestCase):
    def setUp(self):
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), ProbeHandler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()

    def test_probe_accepts_live_metrics_tts_and_asr(self):
        base = f"http://127.0.0.1:{self.server.server_port}"
        args = types.SimpleNamespace(
            management_url=base,
            tts_url=base + "/tts",
            asr_health_url=base + "/health",
            timeout=2,
            output=None,
        )

        report = production_gate.run_probe(args)

        self.assertTrue(report["passed"])
        self.assertEqual(5, len(report["checks"]))

    def test_backup_commands_never_include_password(self):
        config = mysql_backup.DatabaseConfig(
            "localhost", "3306", "tts", "user", "secret",
            "admin", "admin-secret",
        )

        args = mysql_backup.command("mysql", config, config.username, database="tts")

        self.assertNotIn("secret", " ".join(args))
        self.assertNotIn("admin-secret", " ".join(args))

    def test_restore_drill_rejects_non_dedicated_schema(self):
        with self.assertRaises(ValueError):
            mysql_backup.require_safe_drill_schema("production")
        mysql_backup.require_safe_drill_schema("tts_restore_verify_abc123")

    def test_database_identifier_rejects_sql_metacharacters(self):
        mysql_backup.require_safe_database_identifier("tts_phase9_backup_source")
        with self.assertRaises(ValueError):
            mysql_backup.require_safe_database_identifier(
                "tts'; DROP DATABASE production;--")

    def test_table_count_disables_column_headers(self):
        config = mysql_backup.DatabaseConfig(
            "localhost", "3306", "tts", "user", "secret",
            "admin", "admin-secret",
        )
        with mock.patch("mysql_backup.subprocess.run") as run:
            run.return_value.stdout = "1\n"

            count = mysql_backup.table_count("mysql", config, "tts", {})

        self.assertEqual(1, count)
        self.assertIn("--skip-column-names", run.call_args.args[0])

    def test_manifest_detects_modified_backup(self):
        with tempfile.TemporaryDirectory() as directory:
            backup = Path(directory) / "backup.sql"
            backup.write_bytes(b"original")
            with self.assertRaises(RuntimeError):
                mysql_backup.verify_manifest(backup)

            manifest = {"sha256": mysql_backup.sha256(backup)}
            backup.with_suffix(".sql.json").write_text(json.dumps(manifest), encoding="utf-8")
            backup.write_bytes(b"modified")

            with self.assertRaises(RuntimeError):
                mysql_backup.verify_manifest(backup)


if __name__ == "__main__":
    unittest.main()
