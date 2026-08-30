"""One-button Windows control panel for a local USB-connected phone farm."""
from __future__ import annotations

import hashlib
import ctypes
import os
import queue
import re
import shutil
import subprocess
import sys
import threading
import time
import tkinter as tk
import urllib.request
import webbrowser
from pathlib import Path
from tkinter import messagebox, ttk

from usb_farm import DeviceState, UsbFarm


ROOT = Path(__file__).resolve().parent
PYTHON = ROOT / ".venv" / "Scripts" / "python.exe"
ADB_CANDIDATES = (
    Path(os.environ.get("ANDROID_HOME", "")) / "platform-tools" / "adb.exe",
    Path(r"D:\AndroidSDK\platform-tools\adb.exe"),
)
HUB_URL = "http://127.0.0.1:18082"
ERROR_ALREADY_EXISTS = 183
WORKER_START_GRACE_SECONDS = 30
WORKER_HEARTBEAT_MAX_AGE_SECONDS = 180
BACKUP_INTERVAL_SECONDS = 6 * 60 * 60
BACKUP_RETRY_SECONDS = 60 * 60


def default_data_dir() -> Path:
    production = ROOT.parent.parent / "artifacts" / "physical-p0-runtime" / "data"
    return production if production.is_dir() else ROOT / "data"


def approved_agent_apk() -> tuple[Path, str] | None:
    directory = ROOT.parent.parent / "artifacts" / "runtime-smoke" / "apk"
    candidates = sorted(
        [
            *directory.glob("mobile-poster-agent-controller-pairing-*.apk"),
            *directory.glob("mobile-poster-agent-usb-control-*.apk"),
        ],
        key=lambda path: path.stat().st_mtime_ns,
        reverse=True,
    )
    for candidate in candidates:
        match = re.search(r"-([0-9A-Fa-f]{64})\.apk$", candidate.name)
        if not match:
            continue
        digest = hashlib.sha256(candidate.read_bytes()).hexdigest()
        if digest.lower() == match.group(1).lower():
            return candidate.resolve(), digest
    return None


def load_env(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    if not path.exists():
        return values
    for raw in path.read_text(encoding="utf-8-sig").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        if key.strip() and key.strip().replace("_", "").isalnum():
            values[key.strip()] = value.strip().strip('"').strip("'")
    return values


def acquire_single_instance():
    if os.name != "nt":
        return object()
    handle = ctypes.windll.kernel32.CreateMutexW(None, False, "Local\\FermaUsbFarmController")
    if not handle or ctypes.windll.kernel32.GetLastError() == ERROR_ALREADY_EXISTS:
        return None
    return handle


def device_requires_recovery(state: DeviceState) -> bool:
    return state.adb_state == "device" and not (
        state.bridge_ready and state.agent_running and state.accessibility_ready
    )


class FarmController:
    def __init__(self, emit):
        self.emit = emit
        self.stop_event = threading.Event()
        self.processes: dict[str, subprocess.Popen] = {}
        self.process_started: dict[str, float] = {}
        self.process_lock = threading.RLock()
        self.thread: threading.Thread | None = None
        self.managed_serials: set[str] = set()
        self.last_backup_attempt = 0.0
        self.adb = next((item for item in ADB_CANDIDATES if item.is_file()), None)

    def start(self):
        if self.thread and self.thread.is_alive():
            return
        if not PYTHON.is_file():
            raise RuntimeError("Не найдено окружение Hub")
        if not self.adb:
            raise RuntimeError("Не найден Android ADB")
        self.stop_event.clear()
        self.thread = threading.Thread(target=self._loop, daemon=True)
        self.thread.start()

    def stop(self):
        self.stop_event.set()
        with self.process_lock:
            processes = list(self.processes.items())
            self.processes.clear()
            self.process_started.clear()
        for name, process in processes:
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    process.kill()
            self.emit("log", f"{name}: остановлен")
        self.managed_serials.clear()
        self.emit("devices", [])
        self.emit("hub", False)
        self.emit("running", False)

    def _spawn(self, name: str, arguments: list[str], env: dict[str, str]):
        with self.process_lock:
            if self.stop_event.is_set():
                return None
            process = subprocess.Popen(
                [str(PYTHON), *arguments], cwd=ROOT, env=env,
                stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
                encoding="utf-8", errors="replace",
                creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
            )
            self.processes[name] = process
            self.process_started[name] = time.time()
        threading.Thread(target=self._read_log, args=(name, process), daemon=True).start()
        self.emit("log", f"{name}: запущен")
        return process

    def _stop_named_process(self, name: str):
        with self.process_lock:
            process = self.processes.pop(name, None)
            self.process_started.pop(name, None)
        if not process or process.poll() is not None:
            return
        process.terminate()
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            process.kill()

    def _worker_healthy(self, process: subprocess.Popen | None, heartbeat: Path) -> bool:
        if not process or process.poll() is not None:
            return False
        started = self.process_started.get("Очереди", time.time())
        try:
            age = time.time() - heartbeat.stat().st_mtime
        except OSError:
            return time.time() - started < WORKER_START_GRACE_SECONDS
        return age <= WORKER_HEARTBEAT_MAX_AGE_SECONDS

    def _backup_due(self, destination: Path, timestamp: float | None = None) -> bool:
        current = time.time() if timestamp is None else timestamp
        if current - self.last_backup_attempt < BACKUP_RETRY_SECONDS:
            return False
        try:
            latest = max((item.stat().st_mtime for item in destination.glob("backup-*") if item.is_dir()), default=0)
        except OSError:
            return False
        return current - latest >= BACKUP_INTERVAL_SECONDS

    def _backup_if_due(self, data_dir: Path):
        destination = data_dir.parent / "backups"
        if not self._backup_due(destination):
            return
        self.last_backup_attempt = time.time()
        script = ROOT / "infrastructure" / "windows" / "backup.ps1"
        pwsh = shutil.which("pwsh.exe")
        database = data_dir / "hub.sqlite3"
        evidence = data_dir / "screenshots"
        if not pwsh or not script.is_file() or not database.is_file() or not evidence.is_dir():
            self.emit("log", "Резервная копия: необходимые компоненты пока недоступны")
            return
        result = subprocess.run(
            [
                pwsh, "-NoLogo", "-NoProfile", "-NonInteractive", "-File", str(script),
                "-DatabasePath", str(database), "-EvidenceDirectory", str(evidence),
                "-DestinationRoot", str(destination), "-AllowedRoot", str(data_dir.parent),
                "-PythonPath", str(PYTHON), "-RetentionCount", "14",
            ],
            cwd=ROOT,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            timeout=180,
            creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
        )
        self.emit("log", "Резервная копия: готова" if result.returncode == 0 else "Резервная копия: ошибка, повтор через час")

    def _read_log(self, name: str, process: subprocess.Popen):
        if process.stdout:
            for line in process.stdout:
                text = line.strip()
                if text:
                    self.emit("log", f"{name}: {text}")

    def _healthy(self) -> bool:
        try:
            with urllib.request.urlopen(f"{HUB_URL}/health", timeout=2) as response:
                return response.status == 200
        except OSError:
            return False

    def _loop(self):
        env = os.environ.copy()
        env.update(load_env(ROOT / ".env"))
        env.setdefault("HUB_DATA_DIR", str(default_data_dir()))
        data_dir = Path(env["HUB_DATA_DIR"]).resolve()
        env["HUB_PUBLIC_BASE_URL"] = HUB_URL
        env["HUB_LOCAL_URL"] = HUB_URL
        env["FARM_ADB_PATH"] = str(self.adb)
        agent_apk = approved_agent_apk()
        if agent_apk:
            env["FARM_AGENT_APK"] = str(agent_apk[0])
            env["FARM_AGENT_APK_SHA256"] = agent_apk[1]
        env["FARM_INBOX_POLL_SECONDS"] = "2"
        worker_heartbeat = data_dir / "inbox-worker-heartbeat.json"
        env["FARM_WORKER_HEARTBEAT"] = str(worker_heartbeat)
        env.setdefault("FARM_DEVICE_ACCOUNT_LABEL", "main_account")
        env.setdefault("FARM_INSTAGRAM_ACCOUNT", "pinv786")
        env.setdefault("FARM_TIKTOK_ACCOUNT", "pin.van4")
        env.setdefault("FARM_THREADS_ACCOUNT", "pinv786")
        env.setdefault("FARM_YOUTUBE_ACCOUNT", "Ivanaicreator")
        hub_args = ["-m", "uvicorn", "app:app", "--host", "127.0.0.1", "--port", "18082"]
        worker_args = ["inbox_worker.py"]
        usb = UsbFarm(self.adb)
        last_devices: list[DeviceState] = []
        previous_healthy = False
        self.emit("running", True)
        while not self.stop_event.is_set():
            hub = self.processes.get("Hub")
            healthy = self._healthy()
            if (not hub or hub.poll() is not None) and not healthy:
                self._spawn("Hub", hub_args, env)
                self.stop_event.wait(1)
                healthy = self._healthy()
            if healthy and not previous_healthy:
                self.managed_serials.clear()
            previous_healthy = healthy
            self.emit("hub", healthy)
            worker = self.processes.get("Очереди")
            if healthy and not self._worker_healthy(worker, worker_heartbeat):
                self._stop_named_process("Очереди")
                self.emit("log", "Очереди: watchdog выполняет восстановление")
                self._spawn("Очереди", worker_args, env)
            if healthy and self._worker_healthy(self.processes.get("Очереди"), worker_heartbeat):
                self._backup_if_due(data_dir)
            try:
                connected = usb.devices()
                connected_serials = {device.serial for device in connected}
                self.managed_serials.intersection_update(connected_serials)
                inspected = usb.inspect_many(connected)
                degraded = [state for state in inspected if device_requires_recovery(state)]
                recovered = {state.serial: state for state in usb.control_many(degraded, "start")}
                self.managed_serials.update(device.serial for device in connected if device.adb_state == "device")
                last_devices = [recovered.get(state.serial, state) for state in inspected]
            except (OSError, subprocess.SubprocessError) as error:
                self.emit("log", f"USB: временная ошибка {type(error).__name__}")
            self.emit("devices", last_devices)
            self.stop_event.wait(3)
        self.stop()


class FarmWindow(tk.Tk):
    def __init__(self):
        super().__init__()
        self.title("Панель фермы")
        self.geometry("900x620")
        self.minsize(760, 520)
        self.protocol("WM_DELETE_WINDOW", self.close)
        self.events: queue.Queue = queue.Queue()
        self.controller = FarmController(self.emit)
        self._build()
        self.after(100, self.consume_events)
        self.after(250, self.start)

    def _build(self):
        style = ttk.Style(self)
        style.configure("Title.TLabel", font=("Segoe UI", 20, "bold"))
        style.configure("Status.TLabel", font=("Segoe UI", 11))
        outer = ttk.Frame(self, padding=20)
        outer.pack(fill="both", expand=True)
        ttk.Label(outer, text="Панель USB-фермы", style="Title.TLabel").pack(anchor="w")
        ttk.Label(outer, text="Hub, очереди и все подключённые телефоны — в одном месте.").pack(anchor="w", pady=(2, 16))
        buttons = ttk.Frame(outer)
        buttons.pack(fill="x")
        self.start_button = ttk.Button(buttons, text="▶ Запустить ферму", command=self.start)
        self.start_button.pack(side="left")
        self.stop_button = ttk.Button(buttons, text="■ Остановить", command=self.controller.stop, state="disabled")
        self.stop_button.pack(side="left", padx=8)
        ttk.Button(buttons, text="Открыть панель", command=lambda: webbrowser.open(f"{HUB_URL}/dashboard")).pack(side="left", padx=8)
        ttk.Button(buttons, text="Открыть папки", command=self.open_inbox).pack(side="left")
        self.hub_status = ttk.Label(outer, text="Hub: остановлен", style="Status.TLabel")
        self.hub_status.pack(anchor="w", pady=(18, 8))
        columns = ("serial", "model", "usb", "agent", "status")
        self.devices = ttk.Treeview(outer, columns=columns, show="headings", height=7)
        for key, title, width in (("serial", "Телефон", 180), ("model", "Модель", 150), ("usb", "USB", 100), ("agent", "Агент", 100), ("status", "Статус", 260)):
            self.devices.heading(key, text=title)
            self.devices.column(key, width=width, anchor="w")
        self.devices.pack(fill="x", pady=(0, 14))
        ttk.Label(outer, text="Журнал").pack(anchor="w")
        self.log = tk.Text(outer, height=14, state="disabled", font=("Consolas", 9), wrap="word")
        self.log.pack(fill="both", expand=True, pady=(4, 0))

    def emit(self, kind: str, value):
        self.events.put((kind, value))

    def consume_events(self):
        while True:
            try:
                kind, value = self.events.get_nowait()
            except queue.Empty:
                break
            if kind == "hub":
                self.hub_status.config(text="Hub: работает" if value else "Hub: запускается…")
            elif kind == "running":
                self.start_button.config(state="disabled" if value else "normal")
                self.stop_button.config(state="normal" if value else "disabled")
            elif kind == "devices":
                self.devices.delete(*self.devices.get_children())
                for item in value:
                    self.devices.insert("", "end", values=(item.serial, item.model or "—", "готов" if item.bridge_ready else "нет", "работает" if item.agent_running else "нет", item.message))
            elif kind == "log":
                self.log.config(state="normal")
                self.log.insert("end", f"{time.strftime('%H:%M:%S')}  {value}\n")
                self.log.see("end")
                self.log.config(state="disabled")
        self.after(100, self.consume_events)

    def start(self):
        try:
            self.controller.start()
        except RuntimeError as error:
            messagebox.showerror("Не удалось запустить", str(error))

    def open_inbox(self):
        data_dir = Path(load_env(ROOT / ".env").get("HUB_DATA_DIR", default_data_dir()))
        inbox = data_dir / "inbox"
        inbox.mkdir(parents=True, exist_ok=True)
        os.startfile(inbox)

    def close(self):
        self.controller.stop()
        self.destroy()


if __name__ == "__main__":
    instance_handle = acquire_single_instance()
    if instance_handle is None:
        root = tk.Tk()
        root.withdraw()
        messagebox.showinfo("Ферма уже запущена", "Окно управления фермой уже открыто.")
        root.destroy()
    else:
        FarmWindow().mainloop()
