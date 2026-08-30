import unittest
import time
import subprocess
from threading import Lock
from subprocess import CompletedProcess

from usb_farm import (
    ACCESSIBILITY_COMPONENT,
    DeviceState,
    PACKAGE,
    UsbFarm,
    _available_storage_mb,
    _bool_property,
    _int_property,
    parse_adb_devices,
    parse_agent_status,
)


class UsbFarmTest(unittest.TestCase):
    def test_parses_private_agent_permission_status(self):
        output = "capture=true;capture_verified=false;accessibility=true"
        self.assertEqual((True, False, True), parse_agent_status(output))
        self.assertEqual((False, False, False), parse_agent_status("Broadcast completed"))

    def test_parses_multiple_authorized_devices(self):
        output = """List of devices attached
abc123 device product:olive model:Redmi_8 device:olive transport_id:4
phone-2 device product:x model:Pixel_7 device:y transport_id:5
"""
        devices = parse_adb_devices(output)
        self.assertEqual(["abc123", "phone-2"], [item.serial for item in devices])
        self.assertEqual(["Redmi_8", "Pixel_7"], [item.model for item in devices])

    def test_keeps_unauthorized_state_and_rejects_unsafe_serial(self):
        output = """List of devices attached
safe_serial unauthorized transport_id:1
bad;command device model:Evil
"""
        devices = parse_adb_devices(output)
        self.assertEqual(1, len(devices))
        self.assertEqual("unauthorized", devices[0].adb_state)

    def test_local_control_uses_private_receiver_and_reports_service_state(self):
        class FakeFarm(UsbFarm):
            def __init__(self):
                super().__init__("adb.exe")
                self.calls = []

            def _run(self, *args):
                self.calls.append(args)
                if args[-2:] == ("reverse", "--list"):
                    return CompletedProcess(args, 0, "usb-1 tcp:18082 tcp:18082\n", "")
                if "settings" in args and "get" in args:
                    return CompletedProcess(args, 0, ACCESSIBILITY_COMPONENT + "\n", "")
                if "dumpsys" in args:
                    return CompletedProcess(
                        args,
                        0,
                        "AgentForegroundService AgentAccessibilityService "
                        "requested=true received=true hasBound=true",
                        "",
                    )
                return CompletedProcess(args, 0, "Broadcast completed: result=0", "")

        farm = FakeFarm()
        result = farm.control(DeviceState("usb-1", "device", "Redmi_8"), "restart")
        flattened = [item for call in farm.calls for item in call]
        self.assertIn("com.elevium.mobileposteragent.action.RESTART_USB_FARM_AGENT", flattened)
        self.assertIn(PACKAGE, flattened)
        self.assertTrue(result.bridge_ready)
        self.assertTrue(result.agent_running)
        self.assertTrue(result.accessibility_ready)
        self.assertEqual("Готов", result.message)
        pairing = farm.pair_agent(DeviceState("usb-1", "device", "Redmi_8"), "ABCD2345")
        self.assertTrue(pairing["paired"])
        paired_calls = [call for call in farm.calls if "PAIR_USB_FARM_AGENT" in " ".join(call)]
        self.assertEqual(1, len(paired_calls))

    def test_control_rejects_unknown_action_and_unsafe_serial(self):
        farm = UsbFarm("adb.exe")
        with self.assertRaises(ValueError):
            farm.control(DeviceState("safe", "device"), "erase")
        with self.assertRaises(ValueError):
            farm.control(DeviceState("bad;serial", "device"), "stop")

    def test_parses_battery_and_storage_diagnostics(self):
        battery = "  AC powered: false\n  USB powered: true\n  level: 73\n"
        storage = "Filesystem 1K-blocks Used Available Use% Mounted on\n/data 100000 20000 81920 20% /data\n"
        self.assertEqual(73, _int_property(battery, "level"))
        self.assertTrue(_bool_property(battery, "USB powered"))
        self.assertFalse(_bool_property(battery, "AC powered"))
        self.assertEqual(80, _available_storage_mb(storage))

    def test_inspects_many_devices_concurrently_and_preserves_order(self):
        class ConcurrentFarm(UsbFarm):
            def __init__(self):
                super().__init__("adb.exe")
                self.active = 0
                self.max_active = 0
                self.lock = Lock()

            def inspect(self, device):
                with self.lock:
                    self.active += 1
                    self.max_active = max(self.max_active, self.active)
                time.sleep(0.03)
                with self.lock:
                    self.active -= 1
                return device

        farm = ConcurrentFarm()
        devices = [DeviceState(f"usb-{index}", "device") for index in range(6)]
        result = farm.inspect_many(devices)
        self.assertEqual([item.serial for item in devices], [item.serial for item in result])
        self.assertGreater(farm.max_active, 1)

    def test_one_timed_out_phone_does_not_block_the_rest_of_the_fleet(self):
        class TimeoutFarm(UsbFarm):
            def inspect(self, device):
                if device.serial == "slow":
                    raise subprocess.TimeoutExpired("adb", 1)
                return DeviceState(
                    device.serial, "device", bridge_ready=True, agent_running=True,
                    message="Готов", accessibility_ready=True,
                )

        states = TimeoutFarm("adb.exe").inspect_many(
            [DeviceState("slow", "device"), DeviceState("ready", "device")]
        )
        self.assertEqual("Телефон не ответил вовремя", states[0].message)
        self.assertTrue(states[1].agent_running)


if __name__ == "__main__":
    unittest.main()
