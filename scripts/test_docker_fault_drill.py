import sys
import types
import unittest
from pathlib import Path
from unittest import mock


sys.path.insert(0, str(Path(__file__).parent))

import docker_fault_drill


class DockerFaultDrillTest(unittest.TestCase):
    def test_requires_explicit_isolated_stack_confirmation(self):
        args = types.SimpleNamespace(confirm_isolated_stack=False)
        with self.assertRaises(SystemExit):
            docker_fault_drill.run(args)

    def test_redis_and_mysql_faults_hit_business_paths_and_recover(self):
        args = types.SimpleNamespace(
            confirm_isolated_stack=True,
            management_url="http://management.test",
            app_url="http://app.test",
            request_timeout=3,
            project_name="phase11-test",
            compose_file="docker-compose.yml",
        )
        waits = []

        def fake_wait(url, expected, timeout=180):
            waits.append((url, expected))
            return 503 if expected == {503} else 200

        authenticated = mock.Mock(side_effect=[200, 200, 503, 200])
        bad_logins = mock.Mock(
            side_effect=[401, 401, 401, 401, 401, 429]
        )
        with mock.patch.object(docker_fault_drill, "wait_for", side_effect=fake_wait), \
                mock.patch.object(docker_fault_drill, "register_and_login",
                                  return_value=("user", "password", "token")), \
                mock.patch.object(docker_fault_drill, "login_token",
                                  return_value="recovered-token"), \
                mock.patch.object(docker_fault_drill, "authenticated_status",
                                  authenticated), \
                mock.patch.object(docker_fault_drill, "status", bad_logins), \
                mock.patch.object(docker_fault_drill, "compose") as compose:
            report = docker_fault_drill.run(args)

        self.assertTrue(report["passed"])
        self.assertEqual(
            [mock.call(args, "stop", "redis"),
             mock.call(args, "start", "redis"),
             mock.call(args, "stop", "mysql"),
             mock.call(args, "start", "mysql")],
            compose.call_args_list,
        )
        redis, mysql = report["results"]
        self.assertEqual([401, 401, 401, 401, 401, 429],
                         redis["fallbackLoginStatuses"])
        self.assertEqual(200, redis["recoveredVoiceApi"])
        self.assertEqual(503, mysql["dbApiDuringFault"])
        self.assertEqual(200, mysql["recoveredVoiceApi"])
        self.assertIn(
            ("http://management.test/actuator/health/redis", {503}), waits
        )
        self.assertIn(
            ("http://management.test/actuator/health/readiness", {503}), waits
        )


if __name__ == "__main__":
    unittest.main()