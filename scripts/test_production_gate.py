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

    def test_stateful_mix_uses_phase10_business_weights(self):
        scenarios = production_gate.scenario_paths("mixed", None)
        counts = {name: sum(1 for scenario, _ in scenarios if scenario == name)
                  for name in ("login", "voices", "tasks", "courseware")}

        self.assertEqual(
            {"login": 1, "voices": 4, "tasks": 3, "courseware": 2}, counts
        )
        self.assertEqual(scenarios, production_gate.scenario_paths("redis_mixed", None))

    def test_redis_mix_requires_healthy_redis_before_load(self):
        args = types.SimpleNamespace(
            scenario="redis_mixed",
            task_id=None,
            management_url="http://management.test",
            timeout=1,
            output=None,
        )
        unhealthy = {"name": "redis", "status": 503, "passed": False}
        with mock.patch.object(production_gate, "check_http", return_value=unhealthy) as check:
            report = production_gate.run_stability(args)

        self.assertFalse(report["passed"])
        self.assertEqual(1, check.call_count)

    def test_selects_required_resource_metrics_without_high_cardinality_labels(self):
        payload = b"""# HELP ignored ignored
process_cpu_usage 0.25
jvm_memory_used_bytes{area="heap",id="G1 Old Gen"} 42
executor_active_threads{name="fctts.async.tasks"} 1
http_server_requests_seconds_count{uri="/api/tasks/123"} 9
"""

        metrics = production_gate.selected_prometheus_metrics(payload)

        self.assertEqual(0.25, metrics["process_cpu_usage"])
        self.assertEqual(
            42, metrics['jvm_memory_used_bytes{area="heap",id="G1 Old Gen"}']
        )
        self.assertNotIn(
            'http_server_requests_seconds_count{uri="/api/tasks/123"}', metrics
        )

    def test_resource_validation_requires_start_end_and_gate_series(self):
        sample = {
            "prometheus": {name: 0 for name in production_gate.REQUIRED_RESOURCE_METRICS},
            "dockerStats": {},
            "processRssBytes": 0,
            "uploadBytes": 0,
            "uploadFileCount": 0,
            "tempFileCount": 0,
            "pendingCleanupCount": 0,
            "backendLogBytes": 0,
            "redisErrorLines": 0,
        }

        self.assertEqual([], production_gate.validate_resource_samples([sample, sample]))
        self.assertTrue(production_gate.validate_resource_samples([sample]))

    def test_stability_scheduler_executes_declared_mix(self):
        class InlineExecutor:
            def __init__(self, **_kwargs):
                pass

            def __enter__(self):
                return self

            def __exit__(self, *_args):
                pass

            def map(self, function, values):
                function(next(iter(values)))
                return [None]

        args = types.SimpleNamespace(
            scenario="mixed",
            task_id=None,
            base_url="http://example.test",
            timeout=1,
            duration_seconds=10,
            concurrency=10,
            min_requests=10,
            max_error_rate=0.005,
            output=None,
            management_url="http://management.test",
        )
        clock = iter([0, 0, *[value for index in range(10) for value in (index, index)], 10, 10])
        with mock.patch.object(production_gate, "ThreadPoolExecutor", InlineExecutor), \
                mock.patch.object(production_gate, "login", return_value="token"), \
                mock.patch.object(production_gate, "request", return_value=(200, b"", 1)), \
                mock.patch.object(production_gate.time, "perf_counter", side_effect=clock), \
                mock.patch.dict(production_gate.os.environ,
                                {"LOAD_TEST_USERNAME": "user", "LOAD_TEST_PASSWORD": "password"}):
            report = production_gate.run_stability(args)

        self.assertEqual(
            {"login": 1, "voices": 4, "tasks": 3, "courseware": 2},
            report["scenarioCounts"],
        )

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
