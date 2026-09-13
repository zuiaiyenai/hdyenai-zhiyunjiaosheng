import argparse
import hashlib
import json
import os
import secrets
import signal
import shutil
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid
from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import Path


CREATE_NO_WINDOW = 0x08000000 if os.name == "nt" else 0
TEST_PORTS = (3307, 6381, 8080, 8081, 8082, 9091, 9092, 9880, 9977)


@dataclass
class ManagedProcess:
    name: str
    process: subprocess.Popen
    stdout: object
    stderr: object

    def stop(self, timeout=10):
        if self.process.poll() is None:
            self.process.kill()
            try:
                self.process.wait(timeout=timeout)
            except subprocess.TimeoutExpired:
                self.process.terminate()
                self.process.wait(timeout=timeout)
        self.stdout.close()
        self.stderr.close()

    def close_logs(self):
        if self.process.poll() is not None:
            self.stdout.close()
            self.stderr.close()


def utc_now():
    return datetime.now(UTC).isoformat()


def save_json(path, payload):
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n",
                    encoding="utf-8")


def sha256(payload):
    return hashlib.sha256(payload).hexdigest()


def port_open(port, timeout=0.3):
    try:
        with socket.create_connection(("127.0.0.1", port), timeout=timeout):
            return True
    except OSError:
        return False


def wait_for(predicate, description, timeout=60, interval=0.25):
    deadline = time.monotonic() + timeout
    last = None
    while time.monotonic() < deadline:
        try:
            last = predicate()
            if last:
                return last
        except Exception as error:
            last = f"{type(error).__name__}: {error}"
        time.sleep(interval)
    raise RuntimeError(f"Timed out waiting for {description}; last={last}")


def http_result(url, method="GET", body=None, headers=None, timeout=10):
    data = body
    request_headers = dict(headers or {})
    if isinstance(body, dict):
        data = json.dumps(body).encode("utf-8")
        request_headers.setdefault("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(
                urllib.request.Request(url, data=data, headers=request_headers,
                                       method=method), timeout=timeout) as response:
            return response.status, response.read()
    except urllib.error.HTTPError as error:
        return error.code, error.read()
    except (urllib.error.URLError, TimeoutError, OSError) as error:
        return 0, str(error).encode("utf-8", errors="replace")


def wait_http(url, expected, timeout=60):
    result = {"status": None}

    def ready():
        result["status"], _ = http_result(url, timeout=3)
        return result["status"] in expected

    wait_for(ready, f"HTTP {sorted(expected)} from {url}", timeout=timeout, interval=0.5)
    return result["status"]


def require_status(label, actual, expected):
    if actual not in expected:
        raise RuntimeError(f"{label} returned HTTP {actual}; expected {sorted(expected)}")
    return actual


def parse_json(payload):
    return json.loads(payload.decode("utf-8"))


def run_command(command, *, cwd=None, env=None, timeout=180, check=True):
    result = subprocess.run(command, cwd=cwd, env=env, timeout=timeout,
                            capture_output=True, text=True,
                            creationflags=CREATE_NO_WINDOW)
    if check and result.returncode != 0:
        tail = (result.stdout + "\n" + result.stderr)[-2000:]
        raise RuntimeError(f"Command failed with exit {result.returncode}: {tail}")
    return result


def run_command_to_log(command, log_path, *, cwd=None, env=None, timeout=180):
    with log_path.open("ab") as output:
        result = subprocess.run(command, cwd=cwd, env=env, timeout=timeout,
                                stdout=output, stderr=subprocess.STDOUT,
                                creationflags=CREATE_NO_WINDOW)
    if result.returncode != 0:
        tail = log_path.read_text(encoding="utf-8", errors="replace")[-2000:]
        raise RuntimeError(f"Command failed with exit {result.returncode}: {tail}")
    return result


def start_logged(processes, results_root, name, command, *, cwd=None, env=None):
    stdout = (results_root / f"{name}.stdout.log").open("ab")
    stderr = (results_root / f"{name}.stderr.log").open("ab")
    try:
        process = subprocess.Popen(command, cwd=cwd, env=env, stdout=stdout, stderr=stderr,
                                   creationflags=CREATE_NO_WINDOW)
    except Exception:
        stdout.close()
        stderr.close()
        raise
    managed = ManagedProcess(name, process, stdout, stderr)
    processes.append(managed)
    return managed


def multipart(audio, filename):
    boundary = "----fctts-phase14-" + uuid.uuid4().hex
    chunks = [
        f"--{boundary}\r\nContent-Disposition: form-data; name=\"language\"\r\n\r\nzh\r\n".encode(),
        (f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; "
         f"filename=\"{filename}\"\r\nContent-Type: audio/mpeg\r\n\r\n").encode(),
        audio,
        f"\r\n--{boundary}--\r\n".encode(),
    ]
    return b"".join(chunks), f"multipart/form-data; boundary={boundary}"


def auth_headers(token):
    return {"Authorization": "Bearer " + token}


def register_and_login(base_url):
    username = "phase14_" + uuid.uuid4().hex
    password = "P14-" + secrets.token_hex(12) + "aA9!"
    status, _ = http_result(base_url + "/user/register", method="POST",
                            body={"username": username, "password": password})
    require_status("registration", status, {201})
    status, payload = http_result(base_url + "/user/login", method="POST",
                                  body={"username": username, "password": password})
    require_status("login", status, {200})
    token = parse_json(payload).get("token")
    if not token:
        raise RuntimeError("Login response did not contain a token")
    return username, password, token


def submit_asr(base_url, token, sample_bytes, label):
    payload, content_type = multipart(sample_bytes + label.encode(), f"{label}.mp3")
    headers = auth_headers(token)
    headers["Content-Type"] = content_type
    status, body = http_result(base_url + "/asr/transcribe", method="POST",
                               body=payload, headers=headers, timeout=15)
    require_status("ASR task submission", status, {202})
    task_id = parse_json(body).get("taskId")
    if not task_id:
        raise RuntimeError("ASR task response did not contain taskId")
    return task_id


def task_view(base_url, token, task_id):
    status, body = http_result(base_url + "/api/tasks/" + task_id,
                               headers=auth_headers(token), timeout=10)
    if status == 429:
        return {"status": "RATE_LIMITED", "httpStatus": 429}
    require_status("task status", status, {200})
    return parse_json(body)


def wait_task(base_url, token, task_id, desired, timeout=45):
    history = []
    last_status = None

    def reached():
        nonlocal last_status
        view = task_view(base_url, token, task_id)
        status = view.get("status")
        if status != last_status:
            history.append({"at": utc_now(), "status": status,
                            "attempts": view.get("attempts")})
            last_status = status
        if status in desired:
            return view
        if status in {"SUCCESS", "FAILED", "CANCELLED", "TIMEOUT"}:
            raise RuntimeError(f"Task {task_id} reached unexpected terminal status {status}")
        return None

    view = wait_for(reached, f"task {task_id} in {sorted(desired)}", timeout=timeout,
                    interval=1.0)
    return view, history


def validate_delete_target(target, parent):
    resolved_target = target.resolve()
    resolved_parent = parent.resolve()
    if resolved_target == resolved_parent or resolved_parent not in resolved_target.parents:
        raise RuntimeError(f"Unsafe cleanup target: {resolved_target}")


def run(args):
    project_root = Path(__file__).resolve().parents[1]
    timestamp = datetime.now(UTC).strftime("%Y%m%d%H%M%S")
    results_root = project_root / "target" / f"phase14-live-{timestamp}"
    results_root.mkdir(parents=True, exist_ok=False)
    raw_path = results_root / "phase14-raw-evidence.json"
    local_config = project_root / "config" / "application-local.yml"
    jar = project_root / "target" / "tts-0.0.1-SNAPSHOT.jar"
    mysql_base = Path(args.mysql_base).resolve()
    mysql_bin = mysql_base / "bin" / "mysqld.exe"
    mysql_cli = mysql_base / "bin" / "mysql.exe"
    mysql_admin = mysql_base / "bin" / "mysqladmin.exe"
    redis_server = Path(args.redis_server).resolve()
    python_exe = Path(args.python_exe).resolve()
    sample_file = project_root / "src" / "main" / "resources" / "static" / "audio-library" / "male-news.mp3"
    test_tmp = results_root / "test-tmp"
    mysql_data = results_root / "mysql-data"
    mysql_pid_file = results_root / "mysql-phase14.pid"
    object_root = results_root / "objects"
    redis_config = results_root / "redis-phase14.conf"
    tts_delay = results_root / "tts-delay-once"
    asr_delay = results_root / "asr-delay-once"
    asr_active = results_root / "asr-delay-active"
    schema = "fctts_phase14_" + timestamp
    db_user = "phase14"
    db_password = secrets.token_hex(20)
    redis_password = secrets.token_hex(20)
    jwt_secret = secrets.token_hex(32)
    processes = []
    nginx_started = False
    mysql_current = None
    redis_current = None
    backend1 = None
    backend2 = None
    tts_stub = None
    asr_stub = None
    counters = {"mysql": 0, "redis": 0, "backend-1": 0, "backend-2": 0,
                "tts-stub": 0, "asr-stub": 0}
    daily_before = {"mysql_3306": port_open(3306), "redis_6379": port_open(6379)}
    evidence = {
        "schema_version": 1,
        "phase": 14,
        "started_at": utc_now(),
        "application_git_sha": run_command(
            ["git", "rev-parse", "HEAD"], cwd=project_root).stdout.strip(),
        "isolation": {
            "mysql": f"dedicated mysqld on 127.0.0.1:3307 with schema {schema}",
            "redis": "dedicated non-persistent redis on 127.0.0.1:6381 DB 15",
            "daily_services_before": daily_before,
        },
        "dependency_scope": {
            "gpt_sovits": "loopback HTTP contract stub; real model process not used",
            "funasr": "loopback HTTP contract stub; real model process not used",
        },
        "results": [],
        "passed": False,
    }
    save_json(raw_path, evidence)

    def next_name(kind):
        counters[kind] += 1
        return f"{kind}-{counters[kind]}"

    def start_mysql():
        nonlocal mysql_current
        mysql_current = start_logged(processes, results_root, next_name("mysql"), [
            str(mysql_bin), "--no-defaults", f"--basedir={mysql_base}",
            f"--datadir={mysql_data}", "--port=3307", "--bind-address=127.0.0.1",
            "--mysqlx=OFF", "--skip-log-bin", f"--pid-file={mysql_pid_file}",
            "--max-connections=100", "--character-set-server=utf8mb4",
            "--collation-server=utf8mb4_unicode_ci", "--console",
        ], cwd=project_root)
        wait_for(lambda: port_open(3307), "isolated MySQL port 3307", timeout=90)
        run_command([str(mysql_admin), "-h", "127.0.0.1", "-P", "3307", "-u", "root",
                     "--protocol=tcp", "ping"], timeout=15)
        return mysql_current

    def stop_mysql():
        nonlocal mysql_current
        if mysql_current is None:
            return
        if port_open(3307):
            run_command([str(mysql_admin), "-h", "127.0.0.1", "-P", "3307",
                         "-u", "root", "--protocol=tcp", "shutdown"],
                        timeout=30, check=False)
            try:
                wait_for(lambda: not port_open(3307), "isolated MySQL shutdown", timeout=30)
            except RuntimeError:
                if not mysql_pid_file.exists():
                    raise
                pid = int(mysql_pid_file.read_text(encoding="utf-8").strip())
                os.kill(pid, signal.SIGTERM)
                wait_for(lambda: not port_open(3307), "forced isolated MySQL shutdown",
                         timeout=15)
        if mysql_current.process.poll() is None:
            try:
                mysql_current.process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                mysql_current.stop()
        mysql_current.close_logs()

    def mysql_sql(sql):
        return run_command([str(mysql_cli), "-h", "127.0.0.1", "-P", "3307", "-u", "root",
                            "--protocol=tcp", "--batch", "--skip-column-names", "-e", sql],
                           timeout=30).stdout.strip()

    def start_redis():
        nonlocal redis_current
        redis_current = start_logged(processes, results_root, next_name("redis"),
                                     [str(redis_server), str(redis_config)], cwd=project_root)

        def ping():
            password = redis_password.encode("ascii")
            command = (b"*2\r\n$4\r\nAUTH\r\n$" + str(len(password)).encode("ascii")
                       + b"\r\n" + password + b"\r\n*1\r\n$4\r\nPING\r\n")
            try:
                with socket.create_connection(("127.0.0.1", 6381), timeout=2) as client:
                    client.sendall(command)
                    response = b""
                    while b"+PONG\r\n" not in response and len(response) < 128:
                        chunk = client.recv(128 - len(response))
                        if not chunk:
                            break
                        response += chunk
                return response.startswith(b"+OK\r\n") and b"+PONG\r\n" in response
            except OSError:
                return False

        wait_for(ping, "isolated Redis PONG", timeout=30)
        return redis_current

    def start_stub(mode):
        kind = f"{mode}-stub"
        delay = tts_delay if mode == "tts" else asr_delay
        active = results_root / f"{mode}-delay-active"
        managed = start_logged(processes, results_root, next_name(kind), [
            str(python_exe), str(project_root / "performance" / "fault_dependency_stub.py"),
            "--mode", mode, "--port", "9880" if mode == "tts" else "9977",
            "--delay-file", str(delay), "--active-file", str(active),
        ], cwd=project_root)
        url = "http://127.0.0.1:9880/tts" if mode == "tts" else "http://127.0.0.1:9977/health"
        wait_http(url, {200}, timeout=30)
        return managed

    common_env = os.environ.copy()
    common_env.update({
        "TEMP": str(test_tmp),
        "TMP": str(test_tmp),
        "SPRING_DATASOURCE_URL": f"jdbc:mysql://127.0.0.1:3307/{schema}?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true",
        "SPRING_DATASOURCE_USERNAME": db_user,
        "SPRING_DATASOURCE_PASSWORD": db_password,
        "SPRING_DATA_REDIS_HOST": "127.0.0.1",
        "SPRING_DATA_REDIS_PORT": "6381",
        "SPRING_DATA_REDIS_PASSWORD": redis_password,
        "SPRING_DATA_REDIS_DATABASE": "15",
        "JWT_SECRET": jwt_secret,
        "OBJECT_STORAGE_PROVIDER": "local",
        "OBJECT_STORAGE_LOCAL_ROOT": str(object_root),
        "TTS_API_URL": "http://127.0.0.1:9880/tts",
        "TTS_HEALTH_URL": "http://127.0.0.1:9880/tts",
        "TTS_API_TIMEOUT": "2s",
        "ASR_API_URL": "http://127.0.0.1:9977/asr",
        "ASR_HEALTH_URL": "http://127.0.0.1:9977/health",
        "EXTERNAL_SERVICES_REQUIRED": "true",
        "EXTERNAL_SERVICE_PROBE_TIMEOUT": "1s",
        "REDIS_ENABLED": "true",
        "TASK_WORKER_COUNT": "1",
        "TASK_POLL_INTERVAL": "100ms",
        "TASK_TIMEOUT": "30s",
        "TASK_HEARTBEAT_INTERVAL": "1s",
        "TASK_STALE_AFTER": "3s",
        "TASK_RECOVERY_INTERVAL": "1s",
        "TASK_RETRY_BASE_DELAY": "500ms",
        "TASK_RETRY_MAX_DELAY": "1s",
        "TASK_SHUTDOWN_GRACE": "1s",
        "DB_CONNECTION_TIMEOUT": "2000",
    })

    def start_backend(number):
        nonlocal backend1, backend2
        server_port = 8081 if number == 1 else 8082
        management_port = 9091 if number == 1 else 9092
        kind = f"backend-{number}"
        env = common_env.copy()
        managed = start_logged(processes, results_root, next_name(kind), [
            args.java_exe, "-Xms128m", "-Xmx384m", f"-Djava.io.tmpdir={test_tmp}",
            "-jar", str(jar),
            "--spring.profiles.active=local",
            "--spring.config.additional-location=file:///" + str(local_config).replace("\\", "/"),
            f"--server.port={server_port}", f"--management.server.port={management_port}",
            "--spring.flyway.baseline-on-migrate=false",
            "--app.storage.provider=local", f"--app.storage.local-root={object_root}",
            "--app.redis.enabled=true", "--management.health.redis.enabled=true",
            "--app.observability.external-services-required=true",
            "--app.observability.probe-timeout=1s",
            "--tts.api.url=http://127.0.0.1:9880/tts", "--tts.api.timeout=2s",
            "--asr.api.url=http://127.0.0.1:9977/asr",
            "--http.client.connect-timeout=2s", "--http.client.read-timeout=5s",
            "--app.tasks.worker-count=1", "--app.tasks.poll-interval=100ms",
            "--app.tasks.timeout=30s", "--app.tasks.heartbeat-interval=1s",
            "--app.tasks.stale-after=3s", "--app.tasks.recovery-interval=1s",
            "--app.tasks.retry-base-delay=500ms", "--app.tasks.retry-max-delay=1s",
            "--app.tasks.shutdown-grace=1s", "--app.tasks.global-queue-limit=50",
            f"--app.observability.environment=phase14-{kind}",
        ], cwd=project_root, env=env)
        wait_http(f"http://127.0.0.1:{management_port}/actuator/health/readiness",
                  {200}, timeout=180)
        if number == 1:
            backend1 = managed
        else:
            backend2 = managed
        return managed

    try:
        required = [local_config, mysql_bin, mysql_cli, mysql_admin, redis_server,
                    python_exe, sample_file]
        missing = [str(path) for path in required if not path.exists()]
        if missing:
            raise RuntimeError("Missing required Phase 14 paths: " + ", ".join(missing))
        if not all(daily_before.values()):
            raise RuntimeError("Daily 3306/6379 services must be reachable before isolation proof")
        occupied = [port for port in TEST_PORTS if port_open(port)]
        if occupied:
            raise RuntimeError(f"Phase 14 test ports already occupied: {occupied}")

        test_tmp.mkdir(parents=True)
        build_env = os.environ.copy()
        build_env.update({"TEMP": str(test_tmp), "TMP": str(test_tmp),
                          "MAVEN_OPTS": f"-Djava.io.tmpdir={test_tmp}"})
        build = run_command([args.maven_exe, f"-Djava.io.tmpdir={test_tmp}",
                             "-DskipTests", "package"], cwd=project_root,
                            env=build_env, timeout=300)
        (results_root / "maven-package.log").write_text(
            build.stdout + build.stderr, encoding="utf-8", errors="replace")
        if not jar.exists():
            raise RuntimeError(f"Missing application jar after package: {jar}")

        init_log = results_root / "mysql-initialize.log"
        init = run_command([str(mysql_bin), "--no-defaults", "--initialize-insecure",
                            f"--basedir={mysql_base}", f"--datadir={mysql_data}",
                            "--console"], cwd=project_root, timeout=180)
        init_log.write_text(init.stdout + init.stderr, encoding="utf-8", errors="replace")
        start_mysql()
        mysql_sql(
            f"CREATE DATABASE `{schema}` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci; "
            f"CREATE USER '{db_user}'@'%' IDENTIFIED BY '{db_password}'; "
            f"GRANT ALL PRIVILEGES ON `{schema}`.* TO '{db_user}'@'%'; FLUSH PRIVILEGES;"
        )
        redis_config.write_text(
            "bind 127.0.0.1\nprotected-mode yes\nport 6381\nsave \"\"\n"
            "appendonly no\ndaemonize no\nlogfile \"\"\ndatabases 16\n"
            f"requirepass {redis_password}\n", encoding="utf-8")
        start_redis()
        tts_stub = start_stub("tts")
        asr_stub = start_stub("asr")
        start_backend(1)
        start_backend(2)
        nginx_started = True
        run_command_to_log(["cmd.exe", "/d", "/c", "start-frontend-nginx.bat"],
                           results_root / "nginx-start.log", cwd=project_root,
                           timeout=60)
        wait_http("http://127.0.0.1:8080/", {200}, timeout=30)

        username, password, token = register_and_login("http://127.0.0.1:8081")
        voice_path = "/voice_library/search?name=phase14&page=0&size=5"
        status, voice_payload = http_result("http://127.0.0.1:8081" + voice_path,
                                            headers=auth_headers(token))
        require_status("baseline voice query", status, {200})
        baseline_voice_hash = sha256(voice_payload)
        evidence["baseline"] = {"readiness_backend_1": 200, "readiness_backend_2": 200,
                                "voice_payload_sha256": baseline_voice_hash}
        save_json(raw_path, evidence)

        redis_started = time.monotonic()
        redis_current.stop()
        redis_down_seconds = time.monotonic() - redis_started
        redis_health = wait_http("http://127.0.0.1:9091/actuator/health/redis", {503})
        redis_readiness = wait_http("http://127.0.0.1:9091/actuator/health/readiness", {503})
        redis_liveness = wait_http("http://127.0.0.1:9091/actuator/health/liveness", {200})
        missing_user = "phase14_missing_" + uuid.uuid4().hex
        fallback_statuses = []
        for _ in range(6):
            code, _ = http_result("http://127.0.0.1:8081/user/login", method="POST",
                                  body={"username": missing_user, "password": "WrongPass9!"})
            fallback_statuses.append(code)
        if fallback_statuses != [401, 401, 401, 401, 401, 429]:
            raise RuntimeError(f"Redis fallback status mismatch: {fallback_statuses}")
        recovery_started = time.monotonic()
        start_redis()
        wait_http("http://127.0.0.1:9091/actuator/health/redis", {200})
        wait_http("http://127.0.0.1:9091/actuator/health/readiness", {200})
        status, recovered_voice = http_result("http://127.0.0.1:8081" + voice_path,
                                              headers=auth_headers(token))
        require_status("voice query after Redis recovery", status, {200})
        if sha256(recovered_voice) != baseline_voice_hash:
            raise RuntimeError("Voice payload changed after Redis recovery")
        evidence["results"].append({
            "fault": "redis_restart", "passed": True,
            "process_stop_seconds": round(redis_down_seconds, 3),
            "health_during": redis_health, "readiness_during": redis_readiness,
            "liveness_during": redis_liveness,
            "fallback_login_statuses": fallback_statuses,
            "recovery_seconds": round(time.monotonic() - recovery_started, 3),
            "voice_api_after": status,
        })
        save_json(raw_path, evidence)

        stop_started = time.monotonic()
        stop_mysql()
        mysql_stop_seconds = time.monotonic() - stop_started
        mysql_health = wait_http("http://127.0.0.1:9091/actuator/health", {503})
        mysql_readiness = wait_http("http://127.0.0.1:9091/actuator/health/readiness", {503})
        mysql_liveness = wait_http("http://127.0.0.1:9091/actuator/health/liveness", {200})
        api_started = time.monotonic()
        mysql_api_status, _ = http_result("http://127.0.0.1:8081" + voice_path,
                                          headers=auth_headers(token), timeout=10)
        mysql_api_seconds = time.monotonic() - api_started
        require_status("DB API during MySQL outage", mysql_api_status, {500, 503})
        recovery_started = time.monotonic()
        start_mysql()
        wait_http("http://127.0.0.1:9091/actuator/health/readiness", {200}, timeout=90)
        status, recovered_voice = http_result("http://127.0.0.1:8081" + voice_path,
                                              headers=auth_headers(token))
        require_status("voice query after MySQL recovery", status, {200})
        if sha256(recovered_voice) != baseline_voice_hash:
            raise RuntimeError("Voice payload changed after MySQL recovery")
        evidence["results"].append({
            "fault": "mysql_restart", "passed": True,
            "process_stop_seconds": round(mysql_stop_seconds, 3),
            "health_during": mysql_health, "readiness_during": mysql_readiness,
            "liveness_during": mysql_liveness, "db_api_during": mysql_api_status,
            "db_api_latency_seconds": round(mysql_api_seconds, 3),
            "recovery_seconds": round(time.monotonic() - recovery_started, 3),
            "voice_api_after": status,
        })
        save_json(raw_path, evidence)

        tts_body = {"text": "欢迎来到课堂", "voice": "default"}
        status, audio = http_result("http://127.0.0.1:8081/voice/synthesize", method="POST",
                                    body=tts_body, headers=auth_headers(token), timeout=10)
        require_status("baseline TTS", status, {200})
        baseline_audio_hash = sha256(audio)
        tts_stub.stop()
        external_health = wait_http(
            "http://127.0.0.1:9091/actuator/health/externalServices", {503})
        external_readiness = wait_http(
            "http://127.0.0.1:9091/actuator/health/readiness", {503})
        tts_started = time.monotonic()
        tts_status, tts_error = http_result(
            "http://127.0.0.1:8081/voice/synthesize", method="POST", body=tts_body,
            headers=auth_headers(token), timeout=10)
        tts_seconds = time.monotonic() - tts_started
        require_status("TTS during endpoint outage", tts_status, {503})
        tts_stub = start_stub("tts")
        wait_http("http://127.0.0.1:9091/actuator/health/readiness", {200})
        tts_delay.write_text("5", encoding="utf-8")
        slow_started = time.monotonic()
        slow_status, _ = http_result(
            "http://127.0.0.1:8081/voice/synthesize", method="POST", body=tts_body,
            headers=auth_headers(token), timeout=10)
        slow_seconds = time.monotonic() - slow_started
        require_status("TTS during slow response", slow_status, {503})
        if slow_seconds > 4:
            raise RuntimeError(f"TTS timeout was not bounded: {slow_seconds:.3f}s")
        status, recovered_audio = http_result(
            "http://127.0.0.1:8081/voice/synthesize", method="POST", body=tts_body,
            headers=auth_headers(token), timeout=10)
        require_status("TTS after recovery", status, {200})
        if sha256(recovered_audio) != baseline_audio_hash:
            raise RuntimeError("TTS contract payload changed after recovery")
        evidence["results"].append({
            "fault": "gpt_sovits_unavailable", "passed": True,
            "health_during": external_health, "readiness_during": external_readiness,
            "request_during": tts_status, "fast_fail_seconds": round(tts_seconds, 3),
            "error_code": parse_json(tts_error).get("code"),
            "slow_response_status": slow_status,
            "slow_response_timeout_seconds": round(slow_seconds, 3),
            "request_after": status, "audio_sha256": baseline_audio_hash,
            "scope": "HTTP contract stub, not the real GPT-SoVITS model process",
        })
        save_json(raw_path, evidence)

        sample_bytes = sample_file.read_bytes()
        baseline_task = submit_asr("http://127.0.0.1:8081", token, sample_bytes,
                                   "baseline-" + uuid.uuid4().hex)
        baseline_view, _ = wait_task("http://127.0.0.1:8081", token, baseline_task,
                                     {"SUCCESS"})
        asr_stub.stop()
        asr_health = wait_http(
            "http://127.0.0.1:9091/actuator/health/externalServices", {503})
        asr_readiness = wait_http(
            "http://127.0.0.1:9091/actuator/health/readiness", {503})
        failed_started = time.monotonic()
        failed_task = submit_asr("http://127.0.0.1:8081", token, sample_bytes,
                                 "unavailable-" + uuid.uuid4().hex)
        failed_view, failed_history = wait_task(
            "http://127.0.0.1:8081", token, failed_task, {"FAILED"}, timeout=30)
        failed_seconds = time.monotonic() - failed_started
        asr_stub = start_stub("asr")
        wait_http("http://127.0.0.1:9091/actuator/health/readiness", {200})
        recovered_task = submit_asr("http://127.0.0.1:8081", token, sample_bytes,
                                    "recovered-" + uuid.uuid4().hex)
        recovered_view, recovered_history = wait_task(
            "http://127.0.0.1:8081", token, recovered_task, {"SUCCESS"})
        evidence["results"].append({
            "fault": "funasr_unavailable", "passed": True,
            "baseline_task_status": baseline_view.get("status"),
            "health_during": asr_health, "readiness_during": asr_readiness,
            "submission_during": 202, "terminal_status": failed_view.get("status"),
            "terminal_attempts": failed_view.get("attempts"),
            "terminal_error_code": failed_view.get("errorCode"),
            "terminal_seconds": round(failed_seconds, 3),
            "failure_history": failed_history,
            "recovered_task_status": recovered_view.get("status"),
            "recovered_history": recovered_history,
            "scope": "HTTP contract stub, not the real FunASR model process",
        })
        save_json(raw_path, evidence)

        backend1.stop()
        wait_for(lambda: not port_open(8081), "backend-1 port closure", timeout=15)
        statuses = []
        failover_started = time.monotonic()
        for _ in range(20):
            code, payload = http_result("http://127.0.0.1:8080" + voice_path,
                                        headers=auth_headers(token), timeout=10)
            statuses.append(code)
            if code == 200 and sha256(payload) != baseline_voice_hash:
                raise RuntimeError("Nginx failover changed DB payload")
        if statuses != [200] * 20:
            raise RuntimeError(f"Nginx failover statuses: {statuses}")
        backend2_readiness = wait_http(
            "http://127.0.0.1:9092/actuator/health/readiness", {200})
        restart_started = time.monotonic()
        start_backend(1)
        evidence["results"].append({
            "fault": "nginx_upstream_failure", "passed": True,
            "backend_1_port_during": "CLOSED", "backend_2_readiness": backend2_readiness,
            "nginx_request_statuses": statuses,
            "twenty_requests_seconds": round(time.monotonic() - failover_started, 3),
            "backend_1_restart_seconds": round(time.monotonic() - restart_started, 3),
        })
        save_json(raw_path, evidence)

        backend2.stop()
        wait_for(lambda: not port_open(8082), "backend-2 port closure", timeout=15)
        asr_delay.write_text("20", encoding="utf-8")
        crash_task = submit_asr("http://127.0.0.1:8081", token, sample_bytes,
                                "worker-crash-" + uuid.uuid4().hex)
        running_view, running_history = wait_task(
            "http://127.0.0.1:8081", token, crash_task, {"RUNNING"}, timeout=15)
        wait_for(asr_active.exists, "delayed ASR request to become active", timeout=10)
        crash_started = time.monotonic()
        backend1.stop()
        wait_for(lambda: not port_open(8081), "crashed backend-1 port closure", timeout=15)
        start_backend(2)
        recovered_view, crash_history = wait_task(
            "http://127.0.0.1:8082", token, crash_task, {"SUCCESS"}, timeout=45)
        worker_recovery_seconds = time.monotonic() - crash_started
        restart_started = time.monotonic()
        start_backend(1)
        status, payload = http_result("http://127.0.0.1:8080" + voice_path,
                                      headers=auth_headers(token), timeout=10)
        require_status("Nginx after worker recovery", status, {200})
        if sha256(payload) != baseline_voice_hash:
            raise RuntimeError("Payload changed after worker recovery")
        evidence["results"].append({
            "fault": "worker_crash_and_backend_restart", "passed": True,
            "task_status_before_crash": running_view.get("status"),
            "task_attempts_before_crash": running_view.get("attempts"),
            "running_history": running_history,
            "task_status_after_recovery": recovered_view.get("status"),
            "task_attempts_after_recovery": recovered_view.get("attempts"),
            "recovery_history": crash_history,
            "worker_recovery_seconds": round(worker_recovery_seconds, 3),
            "backend_1_restart_seconds": round(time.monotonic() - restart_started, 3),
            "nginx_after": status,
        })
        evidence["passed"] = all(item.get("passed") for item in evidence["results"])
    except Exception as error:
        evidence["error"] = {"type": type(error).__name__, "message": str(error)}
        raise
    finally:
        evidence["completed_at"] = utc_now()
        if nginx_started:
            stopped = run_command(["cmd.exe", "/d", "/c", "stop-frontend-nginx.bat"],
                                  cwd=project_root, timeout=60, check=False)
            (results_root / "nginx-stop.log").write_text(
                stopped.stdout + stopped.stderr, encoding="utf-8", errors="replace")
        for managed in reversed(processes):
            if managed is mysql_current and managed.process.poll() is None:
                stop_mysql()
            elif managed.process.poll() is None:
                managed.stop()
            else:
                managed.close_logs()
        for sensitive in (redis_config, tts_delay, asr_delay, asr_active,
                          results_root / "tts-delay-active"):
            sensitive.unlink(missing_ok=True)
        ports_closed = {}
        for port in TEST_PORTS:
            try:
                wait_for(lambda port=port: not port_open(port), f"port {port} closure",
                         timeout=15)
                ports_closed[str(port)] = True
            except RuntimeError:
                ports_closed[str(port)] = False
        mysql_data_removed = False
        if mysql_data.exists() and not args.keep_mysql_data and ports_closed["3307"]:
            validate_delete_target(mysql_data, results_root)
            shutil.rmtree(mysql_data)
            mysql_data_removed = True
        daily_after = {"mysql_3306": port_open(3306), "redis_6379": port_open(6379)}
        evidence["cleanup"] = {
            "test_ports_closed": ports_closed,
            "mysql_data_removed": mysql_data_removed,
            "redis_config_removed": not redis_config.exists(),
            "daily_services_after": daily_after,
            "daily_services_unchanged": daily_after == daily_before,
        }
        evidence["passed"] = bool(evidence.get("passed")) and all(ports_closed.values()) \
            and daily_after == daily_before
        save_json(raw_path, evidence)
    print(f"PHASE14_RESULTS={results_root}")
    return evidence, raw_path


def main():
    parser = argparse.ArgumentParser(description="Run isolated Phase 14 failure drills")
    parser.add_argument("--mysql-base",
                        default=r"D:\mysql-8.0.41-winx64\mysql-8.0.41-winx64")
    parser.add_argument("--redis-server", default=r"D:\redis\redis-server.exe")
    parser.add_argument("--python-exe", default=sys.executable)
    parser.add_argument("--java-exe", default="java.exe")
    parser.add_argument("--maven-exe", default="mvn.cmd")
    parser.add_argument("--keep-mysql-data", action="store_true")
    args = parser.parse_args()
    evidence, _ = run(args)
    if not evidence["passed"]:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
