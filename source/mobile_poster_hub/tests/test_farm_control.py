import importlib.util
import threading
import tempfile
import time
import unittest
from importlib.machinery import SourceFileLoader
from pathlib import Path
from unittest.mock import patch


MODULE_PATH = Path(__file__).resolve().parents[1] / "farm_control.pyw"
LOADER = SourceFileLoader("farm_control_test_module", str(MODULE_PATH))
SPEC = importlib.util.spec_from_loader(LOADER.name, LOADER)
farm_control = importlib.util.module_from_spec(SPEC)
LOADER.exec_module(farm_control)


class FakeProcess:
    def __init__(self):
        self.terminated = False
        self.stdout = None

    def poll(self):
        return None if not self.terminated else 0

    def terminate(self):
        self.terminated = True

    def wait(self, timeout=None):
        return 0


class FarmControllerLifecycleTest(unittest.TestCase):
    def test_any_degraded_connected_device_requires_recovery(self):
        ready = farm_control.DeviceState("one", "device", "Phone", True, True, "ok", True)
        no_bridge = farm_control.DeviceState("two", "device", "Phone", False, True, "bad", True)
        no_agent = farm_control.DeviceState("three", "device", "Phone", True, False, "bad", True)
        no_accessibility = farm_control.DeviceState("four", "device", "Phone", True, True, "bad", False)
        unauthorized = farm_control.DeviceState("five", "unauthorized", "Phone", False, False, "bad", False)

        self.assertFalse(farm_control.device_requires_recovery(ready))
        self.assertTrue(farm_control.device_requires_recovery(no_bridge))
        self.assertTrue(farm_control.device_requires_recovery(no_agent))
        self.assertTrue(farm_control.device_requires_recovery(no_accessibility))
        self.assertFalse(farm_control.device_requires_recovery(unauthorized))

    def test_stop_clears_owned_processes_devices_and_hub_state(self):
        emitted = []
        controller = farm_control.FarmController(lambda kind, value: emitted.append((kind, value)))
        process = FakeProcess()
        controller.processes["Hub"] = process
        controller.managed_serials.add("phone-1")

        controller.stop()

        self.assertTrue(process.terminated)
        self.assertEqual(controller.processes, {})
        self.assertEqual(controller.managed_serials, set())
        self.assertIn(("devices", []), emitted)
        self.assertIn(("hub", False), emitted)
        self.assertIn(("running", False), emitted)

    def test_worker_watchdog_detects_fresh_missing_and_stale_heartbeat(self):
        controller = farm_control.FarmController(lambda *_: None)
        process = FakeProcess()
        controller.process_started["Очереди"] = time.time()
        with tempfile.TemporaryDirectory() as directory:
            heartbeat = Path(directory) / "heartbeat.json"
            self.assertTrue(controller._worker_healthy(process, heartbeat))
            heartbeat.write_text("{}", encoding="utf-8")
            self.assertTrue(controller._worker_healthy(process, heartbeat))
            old = time.time() - farm_control.WORKER_HEARTBEAT_MAX_AGE_SECONDS - 1
            import os
            os.utime(heartbeat, (old, old))
            self.assertFalse(controller._worker_healthy(process, heartbeat))

    def test_backup_schedule_is_bounded_and_uses_recent_success(self):
        controller = farm_control.FarmController(lambda *_: None)
        with tempfile.TemporaryDirectory() as directory:
            destination = Path(directory)
            now = time.time()
            self.assertTrue(controller._backup_due(destination, now))
            controller.last_backup_attempt = now
            self.assertFalse(controller._backup_due(destination, now + 30))
            controller.last_backup_attempt = 0
            backup = destination / "backup-20260830T120000000Z"
            backup.mkdir()
            import os
            os.utime(backup, (now, now))
            self.assertFalse(controller._backup_due(destination, now + 30))
            self.assertTrue(controller._backup_due(destination, now + farm_control.BACKUP_INTERVAL_SECONDS + 1))

    def test_spawn_is_rejected_after_stop_admission_closes(self):
        controller = farm_control.FarmController(lambda *_: None)
        controller.stop_event.set()

        with patch.object(farm_control.subprocess, "Popen") as popen:
            result = controller._spawn("Hub", ["-m", "uvicorn"], {})

        self.assertIsNone(result)
        popen.assert_not_called()
        self.assertEqual(controller.processes, {})

    def test_stop_and_spawn_race_never_leaves_an_owned_process(self):
        controller = farm_control.FarmController(lambda *_: None)
        entered = threading.Event()
        release = threading.Event()
        created = []

        def fake_popen(*_args, **_kwargs):
            entered.set()
            release.wait(timeout=2)
            process = FakeProcess()
            created.append(process)
            return process

        with patch.object(farm_control.subprocess, "Popen", side_effect=fake_popen):
            spawn_thread = threading.Thread(target=controller._spawn, args=("Hub", ["-m", "uvicorn"], {}))
            spawn_thread.start()
            self.assertTrue(entered.wait(timeout=1))
            stop_thread = threading.Thread(target=controller.stop)
            stop_thread.start()
            release.set()
            spawn_thread.join(timeout=2)
            stop_thread.join(timeout=2)

        self.assertEqual(len(created), 1)
        self.assertTrue(created[0].terminated)
        self.assertEqual(controller.processes, {})


if __name__ == "__main__":
    unittest.main()
