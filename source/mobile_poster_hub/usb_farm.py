"""USB device discovery and local Hub bridging for the desktop farm controller."""
from __future__ import annotations

import re
import subprocess
import time
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass
from pathlib import Path


PACKAGE = "com.elevium.mobileposteragent"
RECEIVER = f"{PACKAGE}/.service.UsbFarmControlReceiver"
ACCESSIBILITY_COMPONENT = f"{PACKAGE}/{PACKAGE}.service.AgentAccessibilityService"
ACTION = f"{PACKAGE}.action.ENABLE_USB_FARM_MODE"
ACTION_PAIR = f"{PACKAGE}.action.PAIR_USB_FARM_AGENT"
ACTION_STATUS = f"{PACKAGE}.action.REPORT_USB_FARM_STATUS"
CONTROL_ACTIONS = {
    "start": f"{PACKAGE}.action.START_USB_FARM_AGENT",
    "stop": f"{PACKAGE}.action.STOP_USB_FARM_AGENT",
    "restart": f"{PACKAGE}.action.RESTART_USB_FARM_AGENT",
}
HUB_URL = "http://127.0.0.1:18082"
SERIAL_PATTERN = re.compile(r"^[A-Za-z0-9._:-]{1,128}$")


@dataclass(frozen=True)
class DeviceState:
    serial: str
    adb_state: str
    model: str = ""
    bridge_ready: bool = False
    agent_running: bool = False
    message: str = ""
    accessibility_ready: bool = False


@dataclass(frozen=True)
class DeviceDiagnostics:
    serial: str
    model: str
    android_version: str
    sdk: str
    battery_percent: int | None
    charging: bool
    free_storage_mb: int | None
    agent_version: str
    bridge_ready: bool
    agent_running: bool
    accessibility_ready: bool
    capture_permission: bool = False
    capture_verified: bool = False


def parse_agent_status(output: str) -> tuple[bool, bool, bool]:
    values = {}
    for item in output.strip().split(";"):
        key, separator, value = item.partition("=")
        if separator:
            values[key.strip()] = value.strip().lower() == "true"
    return (
        values.get("capture", False),
        values.get("capture_verified", False),
        values.get("accessibility", False),
    )


def parse_adb_devices(output: str) -> list[DeviceState]:
    devices: list[DeviceState] = []
    for raw in output.splitlines()[1:]:
        line = raw.strip()
        if not line:
            continue
        fields = line.split()
        serial = fields[0]
        if not SERIAL_PATTERN.fullmatch(serial):
            continue
        state = fields[1] if len(fields) > 1 else "unknown"
        model = next((part.split(":", 1)[1] for part in fields[2:] if part.startswith("model:")), "")
        devices.append(DeviceState(serial=serial, adb_state=state, model=model))
    return devices


def _text_property(output: str, name: str) -> str | None:
    match = re.search(rf"(?m)^\s*{re.escape(name)}\s*[:=]\s*(.+?)\s*$", output)
    return match.group(1).strip() if match else None


def _int_property(output: str, name: str) -> int | None:
    value = _text_property(output, name)
    if value is None:
        return None
    try:
        return int(value)
    except ValueError:
        return None


def _bool_property(output: str, name: str) -> bool:
    return (_text_property(output, name) or "").lower() in {"1", "true", "yes"}


def _available_storage_mb(output: str) -> int | None:
    rows = [line.split() for line in output.splitlines() if line.strip()]
    if len(rows) < 2 or len(rows[-1]) < 4:
        return None
    try:
        return max(0, int(rows[-1][3]) // 1024)
    except ValueError:
        return None


class UsbFarm:
    def __init__(self, adb_path: str | Path, port: int = 18082, timeout: float = 8.0):
        self.adb_path = str(Path(adb_path).resolve())
        self.port = int(port)
        self.timeout = timeout

    def _run(self, *args: str) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [self.adb_path, *args],
            text=True,
            capture_output=True,
            timeout=self.timeout,
            creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
            check=False,
        )

    def _run_with_timeout(self, timeout: float, *args: str) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [self.adb_path, *args],
            text=True,
            capture_output=True,
            timeout=timeout,
            creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
            check=False,
        )

    def devices(self) -> list[DeviceState]:
        result = self._run("devices", "-l")
        return parse_adb_devices(result.stdout) if result.returncode == 0 else []

    @staticmethod
    def _workers(count: int) -> int:
        return max(1, min(16, count))

    def inspect_many(self, devices: list[DeviceState]) -> list[DeviceState]:
        if not devices:
            return []
        with ThreadPoolExecutor(max_workers=self._workers(len(devices)), thread_name_prefix="farm-inspect") as pool:
            return list(pool.map(self._safe_inspect, devices))

    def control_many(self, devices: list[DeviceState], action: str) -> list[DeviceState]:
        if action not in CONTROL_ACTIONS:
            raise ValueError("Unsupported device action")
        if not devices:
            return []
        with ThreadPoolExecutor(max_workers=self._workers(len(devices)), thread_name_prefix="farm-control") as pool:
            return list(pool.map(lambda device: self._safe_control(device, action), devices))

    def _safe_inspect(self, device: DeviceState) -> DeviceState:
        try:
            return self.inspect(device)
        except (OSError, subprocess.SubprocessError):
            return DeviceState(
                device.serial, device.adb_state, device.model,
                message="Телефон не ответил вовремя",
            )

    def _safe_control(self, device: DeviceState, action: str) -> DeviceState:
        try:
            return self.control(device, action)
        except (OSError, subprocess.SubprocessError):
            return DeviceState(
                device.serial, device.adb_state, device.model,
                message="Команда телефону превысила время ожидания",
            )

    def _device(self, serial: str, *args: str) -> subprocess.CompletedProcess[str]:
        if not SERIAL_PATTERN.fullmatch(serial):
            raise ValueError("Invalid ADB serial")
        return self._run("-s", serial, *args)

    def _accessibility_ready(self, device: DeviceState) -> bool:
        setting = self._device(
            device.serial, "shell", "settings", "get", "secure", "enabled_accessibility_services"
        )
        enabled = {item for item in setting.stdout.strip().split(":") if item and item != "null"}
        if setting.returncode != 0 or ACCESSIBILITY_COMPONENT not in enabled:
            return False
        state = self._device(device.serial, "shell", "dumpsys", "activity", "services", PACKAGE)
        return (
            state.returncode == 0
            and "AgentAccessibilityService" in state.stdout
            and "requested=true received=true hasBound=true" in state.stdout
        )

    def _ensure_accessibility(self, device: DeviceState) -> bool:
        current = self._device(
            device.serial, "shell", "settings", "get", "secure", "enabled_accessibility_services"
        )
        if current.returncode != 0:
            return False
        enabled = [item for item in current.stdout.strip().split(":") if item and item != "null"]
        if ACCESSIBILITY_COMPONENT in enabled and not self._accessibility_ready(device):
            without_agent = [item for item in enabled if item != ACCESSIBILITY_COMPONENT]
            removed = self._device(
                device.serial,
                "shell", "settings", "put", "secure", "enabled_accessibility_services",
                ":".join(without_agent),
            )
            if removed.returncode != 0:
                return False
            enabled = without_agent
        if ACCESSIBILITY_COMPONENT not in enabled:
            enabled.append(ACCESSIBILITY_COMPONENT)
        saved = self._device(
            device.serial,
            "shell", "settings", "put", "secure", "enabled_accessibility_services", ":".join(enabled),
        )
        if saved.returncode != 0:
            return False
        switched = self._device(
            device.serial, "shell", "settings", "put", "secure", "accessibility_enabled", "1"
        )
        return switched.returncode == 0

    def prepare(self, device: DeviceState) -> DeviceState:
        if device.adb_state != "device":
            return DeviceState(device.serial, device.adb_state, device.model, message="Подтвердите USB-отладку")
        mapping = f"tcp:{self.port}"
        reverse = self._device(device.serial, "reverse", mapping, mapping)
        if reverse.returncode != 0:
            return DeviceState(device.serial, device.adb_state, device.model, message="Не удалось включить USB-связь")
        provision = self._device(
            device.serial,
            "shell", "run-as", PACKAGE, "am", "broadcast", "--user", "0",
            "-n", RECEIVER, "-a", ACTION, "--es", "hub_url", HUB_URL,
        )
        if provision.returncode != 0 or "Security exception" in provision.stdout + provision.stderr:
            return DeviceState(device.serial, device.adb_state, device.model, True, False, "Обновите приложение телефона")
        services = self._device(device.serial, "shell", "dumpsys", "activity", "services", PACKAGE)
        running = "AgentForegroundService" in services.stdout
        accessibility_ready = self._accessibility_ready(device)
        return DeviceState(
            device.serial,
            device.adb_state,
            device.model,
            bridge_ready=True,
            agent_running=running,
            message="Готов" if running and accessibility_ready else "Нужно включить Accessibility",
            accessibility_ready=accessibility_ready,
        )

    def inspect(self, device: DeviceState) -> DeviceState:
        if device.adb_state != "device":
            return DeviceState(device.serial, device.adb_state, device.model, message="Подтвердите USB-отладку")
        mapping = f"tcp:{self.port}"
        reverse = self._device(device.serial, "reverse", "--list")
        bridge_ready = reverse.returncode == 0 and any(
            line.split()[-2:] == [mapping, mapping]
            for line in reverse.stdout.splitlines()
            if len(line.split()) >= 3
        )
        services = self._device(device.serial, "shell", "dumpsys", "activity", "services", PACKAGE)
        running = services.returncode == 0 and "AgentForegroundService" in services.stdout
        accessibility_ready = self._accessibility_ready(device)
        return DeviceState(
            device.serial,
            device.adb_state,
            device.model,
            bridge_ready=bridge_ready,
            agent_running=running,
            message=(
                "Готов" if bridge_ready and running and accessibility_ready
                else "Нужно включить Accessibility" if bridge_ready and running
                else "Агент остановлен" if bridge_ready else "Нет USB-связи"
            ),
            accessibility_ready=accessibility_ready,
        )

    def diagnose(self, device: DeviceState) -> DeviceDiagnostics:
        state = self.inspect(device)
        android_version = self._device(device.serial, "shell", "getprop", "ro.build.version.release").stdout.strip()
        sdk = self._device(device.serial, "shell", "getprop", "ro.build.version.sdk").stdout.strip()
        battery = self._device(device.serial, "shell", "dumpsys", "battery").stdout
        battery_percent = _int_property(battery, "level")
        charging = any(
            _bool_property(battery, key)
            for key in ("AC powered", "USB powered", "Wireless powered")
        )
        storage = self._device(device.serial, "shell", "df", "-k", "/data").stdout
        free_storage_mb = _available_storage_mb(storage)
        package = self._device(device.serial, "shell", "dumpsys", "package", PACKAGE).stdout
        version = _text_property(package, "versionName") or "неизвестно"
        status_result = self._device(
            device.serial,
            "shell", "run-as", PACKAGE, "am", "broadcast", "--user", "0",
            "-n", RECEIVER, "-a", ACTION_STATUS,
        )
        status_file = self._device(
            device.serial, "shell", "run-as", PACKAGE, "cat", "files/usb_farm_status.txt"
        )
        capture_permission, capture_verified, reported_accessibility = parse_agent_status(
            status_file.stdout if status_result.returncode == 0 else ""
        )
        return DeviceDiagnostics(
            serial=device.serial,
            model=device.model,
            android_version=android_version or "неизвестно",
            sdk=sdk or "неизвестно",
            battery_percent=battery_percent,
            charging=charging,
            free_storage_mb=free_storage_mb,
            agent_version=version,
            bridge_ready=state.bridge_ready,
            agent_running=state.agent_running,
            accessibility_ready=state.accessibility_ready or reported_accessibility,
            capture_permission=capture_permission,
            capture_verified=capture_verified,
        )

    def install_agent(self, device: DeviceState, apk_path: str | Path) -> dict:
        apk = Path(apk_path).resolve()
        if device.adb_state != "device":
            return {"installed": False, "message": "Сначала подтвердите USB-отладку на телефоне"}
        if not apk.is_file() or apk.suffix.lower() != ".apk":
            return {"installed": False, "message": "Проверенный APK агента не найден"}
        result = self._run_with_timeout(180, "-s", device.serial, "install", "-r", str(apk))
        output = (result.stdout + "\n" + result.stderr).strip()
        installed = result.returncode == 0 and "Success" in output
        if installed:
            self._device(
                device.serial, "shell", "monkey", "-p", PACKAGE,
                "-c", "android.intent.category.LAUNCHER", "1",
            )
        return {
            "installed": installed,
            "message": (
                "Агент установлен. Завершите безопасную настройку на телефоне."
                if installed else "Android отклонил установку агента"
            ),
        }

    def pair_agent(self, device: DeviceState, code: str) -> dict:
        if device.adb_state != "device":
            return {"paired": False, "message": "Сначала подтвердите USB-отладку на телефоне"}
        mapping = f"tcp:{self.port}"
        reverse = self._device(device.serial, "reverse", mapping, mapping)
        if reverse.returncode != 0:
            return {"paired": False, "message": "Не удалось включить USB-связь"}
        if not self._ensure_accessibility(device):
            return {"paired": False, "message": "Не удалось включить Accessibility"}
        result = self._device(
            device.serial,
            "shell", "run-as", PACKAGE, "am", "broadcast", "--user", "0",
            "-n", RECEIVER, "-a", ACTION_PAIR,
            "--es", "hub_url", HUB_URL,
            "--es", "pairing_code", code,
        )
        accepted = result.returncode == 0 and "Security exception" not in result.stdout + result.stderr
        return {
            "paired": accepted,
            "message": (
                "Код подключения передан телефону; агент завершает настройку."
                if accepted else "Телефон отклонил подключение"
            ),
        }

    def control(self, device: DeviceState, action: str) -> DeviceState:
        if action not in CONTROL_ACTIONS:
            raise ValueError("Unsupported device action")
        if device.adb_state != "device":
            return DeviceState(device.serial, device.adb_state, device.model, message="Подтвердите USB-отладку")
        if action in {"start", "restart"}:
            mapping = f"tcp:{self.port}"
            reverse = self._device(device.serial, "reverse", mapping, mapping)
            if reverse.returncode != 0:
                return DeviceState(device.serial, device.adb_state, device.model, message="Не удалось включить USB-связь")
            if not self._ensure_accessibility(device):
                return DeviceState(device.serial, device.adb_state, device.model, True, False, "Не удалось включить Accessibility")
        command = [
            "shell", "run-as", PACKAGE, "am", "broadcast", "--user", "0",
            "-n", RECEIVER, "-a", CONTROL_ACTIONS[action],
        ]
        if action in {"start", "restart"}:
            command.extend(["--es", "hub_url", HUB_URL])
        result = self._device(device.serial, *command)
        if result.returncode != 0 or "Security exception" in result.stdout + result.stderr:
            return DeviceState(device.serial, device.adb_state, device.model, message="Команда телефона отклонена")
        state = self.inspect(device)
        for _ in range(4):
            if action == "stop" or (state.agent_running and state.accessibility_ready):
                break
            time.sleep(0.5)
            state = self.inspect(device)
        return state
