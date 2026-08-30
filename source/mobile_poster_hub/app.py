"""Durable hub for Mobile Poster Agent."""
from __future__ import annotations

import base64
import asyncio
import binascii
import hashlib
import hmac
import ipaddress
import json
import os
import re
import secrets
import shutil
import sqlite3
import uuid
from contextlib import asynccontextmanager, contextmanager, suppress
from datetime import UTC, datetime, timedelta
from pathlib import Path
from typing import Annotated, Literal

from fastapi import Cookie, Depends, FastAPI, Header, HTTPException, Request, Response, Security, status
from fastapi.responses import FileResponse, HTMLResponse
from fastapi.security import APIKeyHeader
from pydantic import BaseModel, Field, HttpUrl

from usb_farm import DeviceState, UsbFarm


DATA_DIR = Path(os.environ.get("HUB_DATA_DIR", Path(__file__).with_name("data"))).resolve()
DATABASE_PATH = DATA_DIR / "hub.sqlite3"
SCREENSHOTS_DIR = DATA_DIR / "screenshots"
QUEUE_MEDIA_DIR = Path(os.environ.get("FARM_QUEUE_MEDIA_DIR", DATA_DIR / "queue-media")).resolve()
RUNNER_TOKEN = os.environ.get("HUB_RUNNER_TOKEN", "")
ADMIN_TOKEN = os.environ.get("HUB_ADMIN_TOKEN", "")
DASHBOARD_PATH = Path(__file__).with_name("dashboard.html")
WORKSPACE_ROOT = Path(__file__).resolve().parents[2]
MAX_SCREENSHOT_BYTES = 8 * 1024 * 1024
LEASE_SECONDS = int(os.environ.get("HUB_LEASE_SECONDS", "900"))
RETRY_BASE_SECONDS = int(os.environ.get("HUB_RETRY_BASE_SECONDS", "30"))
RETRY_MAX_SECONDS = int(os.environ.get("HUB_RETRY_MAX_SECONDS", "3600"))
RECONCILE_INTERVAL_SECONDS = max(5, int(os.environ.get("HUB_RECONCILE_INTERVAL_SECONDS", "10")))
TERMINAL_STATUSES = {"succeeded", "ready_to_publish", "needs_review", "dead_letter"}
ACTIVE_STATUSES = {"claimed", "running"}
ALLOWED_DEVICE_TRANSITIONS = {
    "claimed": {"running", "succeeded", "failed", "ready_to_publish"},
    "running": {"running", "succeeded", "failed", "ready_to_publish"},
}

# StaticFiles and FileResponse paths must exist before the first request.
DATA_DIR.mkdir(parents=True, exist_ok=True)
SCREENSHOTS_DIR.mkdir(exist_ok=True)
QUEUE_MEDIA_DIR.mkdir(parents=True, exist_ok=True)

async def reconcile_forever() -> None:
    while True:
        await asyncio.sleep(RECONCILE_INTERVAL_SECONDS)
        run_reconcile_cycle()


@asynccontextmanager
async def lifespan(_app: FastAPI):
    startup()
    reconciler = asyncio.create_task(reconcile_forever(), name="hub-lease-reconciler")
    try:
        yield
    finally:
        reconciler.cancel()
        with suppress(asyncio.CancelledError):
            await reconciler


app = FastAPI(title="Mobile Poster Hub", version="0.2.0", lifespan=lifespan)
runner_token_header = APIKeyHeader(
    name="X-Hub-Token",
    scheme_name="RunnerToken",
    description="Runner token installed only on publishing devices.",
    auto_error=False,
)
admin_token_header = APIKeyHeader(
    name="X-Hub-Token",
    scheme_name="AdminToken",
    description="Administrative token for job creation and observation.",
    auto_error=False,
)


class DeviceRegistration(BaseModel):
    device_id: str = Field(min_length=3, max_length=120)
    platform: Literal["android"]
    mode: Literal["remote_agent"]
    label: str = Field(min_length=1, max_length=120)
    account_label: str | None = Field(default=None, max_length=120)
    notes: str | None = Field(default=None, max_length=2000)
    tags: list[str] = Field(default_factory=list, max_length=30)


class DashboardLogin(BaseModel):
    token: str = Field(min_length=1, max_length=500)


class DashboardDeviceControl(BaseModel):
    action: Literal["start", "stop", "restart"]


class DashboardJobControl(BaseModel):
    action: Literal["pause", "resume", "cancel", "retry", "set_priority"]
    priority: int | None = Field(default=None, ge=0, le=100)


class DevicePairingClaim(BaseModel):
    code: str = Field(min_length=8, max_length=16, pattern=r"^[A-Z2-9]+$")
    device_id: str = Field(min_length=3, max_length=120)
    device_label: str = Field(min_length=1, max_length=120)


DRY_RUN_TARGETS = frozenset({
    "pinterest_dry_run",
    "instagram_reel_dry_run",
    "tiktok_post_dry_run",
})
SOCIAL_DRY_RUN_TARGETS = frozenset({"instagram_reel_dry_run", "tiktok_post_dry_run"})
REAL_PUBLICATION_TARGETS = frozenset({
    "instagram_reel",
    "tiktok_post",
    "pinterest_pin",
    "threads_post",
    "youtube_short",
})


class JobCreate(BaseModel):
    target: Literal[
        "instagram_reel",
        "instagram_reel_dry_run",
        "tiktok_post",
        "tiktok_post_dry_run",
        "threads_post",
        "youtube_short",
        "pinterest_pin",
        "pinterest_pin_verify",
        "pinterest_dry_run",
    ]
    caption: str = Field(default="", max_length=5000)
    title: str | None = Field(default=None, max_length=500)
    description: str | None = Field(default=None, max_length=5000)
    link: HttpUrl | None = None
    board: str | None = Field(default=None, max_length=500)
    media_url: HttpUrl
    publish_at: datetime | None = None
    preferred_device_id: str | None = Field(default=None, max_length=120)
    account_label: str | None = Field(
        default=None,
        max_length=120,
        description="Required and nonblank for Instagram and TikTok dry-run targets.",
    )
    platform_account_label: str | None = Field(
        default=None,
        max_length=120,
        description="Exact visible Instagram/TikTok account identity; separate from device routing account_label.",
    )
    priority: int = Field(default=50, ge=0, le=100)
    content_sha256: str | None = Field(
        default=None,
        min_length=64,
        max_length=64,
        pattern=r"^[0-9a-fA-F]{64}$",
        description="SHA-256 of the exact media bytes, used for duplicate protection.",
    )
    allow_duplicate: bool = False
    max_attempts: int = Field(default=5, ge=1, le=20)


class DeviceJobCreate(BaseModel):
    """A job created by the already authenticated publishing device UI."""

    target: Literal[
        "instagram_reel",
        "instagram_reel_dry_run",
        "tiktok_post",
        "tiktok_post_dry_run",
        "pinterest_pin",
        "pinterest_dry_run",
    ]
    caption: str = Field(default="", max_length=5000)
    title: str | None = Field(default=None, max_length=500)
    board: str | None = Field(default=None, max_length=500)
    media_url: HttpUrl
    publish_at: datetime | None = None
    platform_account_label: str | None = Field(default=None, max_length=120)
    priority: int = Field(default=50, ge=0, le=100)
    content_sha256: str | None = Field(
        default=None, min_length=64, max_length=64, pattern=r"^[0-9a-fA-F]{64}$"
    )
    allow_duplicate: bool = False


class JobStatusUpdate(BaseModel):
    status: Literal["running", "succeeded", "failed", "ready_to_publish"]
    message: str | None = Field(default=None, max_length=2000)
    retryable: bool = True
    error_code: str | None = Field(default=None, max_length=120)
    publication_verified: bool = False
    publication_id: str | None = Field(default=None, max_length=500)
    ui_state_verified: bool = False
    evidence_id: str | None = Field(default=None, min_length=8, max_length=120)


class JobReopenRequest(BaseModel):
    reason: str = Field(min_length=8, max_length=2000)
    target_status: Literal["retry_wait", "pending"] = "retry_wait"


class AdminPublicationVerification(BaseModel):
    reason: str = Field(min_length=8, max_length=2000)
    publication_id: str = Field(min_length=3, max_length=500)
    filename: str = Field(min_length=1, max_length=255)
    content_base64: str = Field(min_length=1)
    content_type: Literal["image/png"]


class JobEvent(BaseModel):
    level: Literal["info", "error", "warning"] = "info"
    message: str = Field(min_length=1, max_length=4000)
    payload: dict = Field(default_factory=dict)
    screenshot_path: str | None = Field(default=None, max_length=500)
    event_key: str | None = Field(default=None, min_length=8, max_length=200)


class ScreenshotUpload(BaseModel):
    filename: str = Field(min_length=1, max_length=255)
    content_base64: str = Field(min_length=1)
    content_type: Literal["image/png"]


def now() -> str:
    return datetime.now(UTC).isoformat()


def normalize_time(value: datetime | None) -> str | None:
    if value is None:
        return None
    if value.tzinfo is None:
        raise HTTPException(status_code=422, detail="publish_at must include a timezone")
    return value.astimezone(UTC).isoformat()


def add_seconds(timestamp: str, seconds: int) -> str:
    return (datetime.fromisoformat(timestamp) + timedelta(seconds=seconds)).isoformat()


def retry_delay_seconds(job_id: str, attempt_count: int) -> int:
    exponential = min(RETRY_MAX_SECONDS, RETRY_BASE_SECONDS * (2 ** max(0, attempt_count - 1)))
    jitter_window = max(1, exponential // 4)
    jitter = int.from_bytes(hashlib.sha256(f"{job_id}:{attempt_count}".encode()).digest()[:4], "big") % jitter_window
    return min(RETRY_MAX_SECONDS, exponential + jitter)


@contextmanager
def database(write: bool = False):
    connection = sqlite3.connect(DATABASE_PATH, timeout=10, isolation_level=None)
    connection.row_factory = sqlite3.Row
    try:
        if write:
            connection.execute("BEGIN IMMEDIATE")
        yield connection
        if write:
            connection.commit()
    except Exception:
        if write:
            connection.rollback()
        raise
    finally:
        connection.close()


def ensure_columns(db: sqlite3.Connection, table: str, definitions: dict[str, str]) -> None:
    existing = {row["name"] for row in db.execute(f"PRAGMA table_info({table})")}
    for name, definition in definitions.items():
        if name not in existing:
            db.execute(f"ALTER TABLE {table} ADD COLUMN {name} {definition}")


def initialize_database() -> None:
    DATA_DIR.mkdir(parents=True, exist_ok=True)
    SCREENSHOTS_DIR.mkdir(exist_ok=True)
    with database(write=True) as db:
        db.execute(
            """CREATE TABLE IF NOT EXISTS devices (
              device_id TEXT PRIMARY KEY, platform TEXT NOT NULL, mode TEXT NOT NULL,
              label TEXT NOT NULL, account_label TEXT, notes TEXT, tags_json TEXT NOT NULL,
              automation_state TEXT, last_seen_at TEXT NOT NULL
            )"""
        )
        db.execute(
            """CREATE TABLE IF NOT EXISTS jobs (
              job_id TEXT PRIMARY KEY, target TEXT NOT NULL, caption TEXT NOT NULL,
              title TEXT, description TEXT, link TEXT, board TEXT, media_url TEXT NOT NULL,
              publish_at TEXT, preferred_device_id TEXT, account_label TEXT,
              assigned_device_id TEXT, status TEXT NOT NULL, status_message TEXT,
              created_at TEXT NOT NULL, updated_at TEXT NOT NULL
            )"""
        )
        db.execute(
            """CREATE TABLE IF NOT EXISTS events (
              event_id INTEGER PRIMARY KEY AUTOINCREMENT, job_id TEXT NOT NULL,
              created_at TEXT NOT NULL, level TEXT NOT NULL, message TEXT NOT NULL,
              payload_json TEXT NOT NULL, screenshot_path TEXT
            )"""
        )
        ensure_columns(
            db,
            "jobs",
            {
                "idempotency_key": "TEXT",
                "payload_fingerprint": "TEXT",
                "lease_token": "TEXT",
                "lease_expires_at": "TEXT",
                "heartbeat_at": "TEXT",
                "attempt_count": "INTEGER NOT NULL DEFAULT 0",
                "max_attempts": "INTEGER NOT NULL DEFAULT 5",
                "next_retry_at": "TEXT",
                "last_error_code": "TEXT",
                "completed_at": "TEXT",
                "publication_id": "TEXT",
                "platform_account_label": "TEXT",
                "priority": "INTEGER NOT NULL DEFAULT 50",
                "content_sha256": "TEXT",
                "allow_duplicate": "INTEGER NOT NULL DEFAULT 0",
            },
        )
        ensure_columns(
            db,
            "events",
            {
                "event_key": "TEXT",
                "attempt_number": "INTEGER NOT NULL DEFAULT 0",
            },
        )
        db.execute(
            """CREATE TABLE IF NOT EXISTS evidence (
              evidence_id TEXT PRIMARY KEY, job_id TEXT NOT NULL,
              attempt_number INTEGER NOT NULL, original_filename TEXT NOT NULL,
              stored_filename TEXT NOT NULL, screenshot_path TEXT NOT NULL,
              content_sha256 TEXT NOT NULL, size_bytes INTEGER NOT NULL,
              status TEXT NOT NULL, created_at TEXT NOT NULL, reported_at TEXT
            )"""
        )
        db.execute(
            """CREATE TABLE IF NOT EXISTS admin_actions (
              action_id TEXT PRIMARY KEY, job_id TEXT NOT NULL, action_type TEXT NOT NULL,
              idempotency_key TEXT NOT NULL, payload_fingerprint TEXT NOT NULL,
              created_at TEXT NOT NULL
            )"""
        )
        db.execute(
            """CREATE TABLE IF NOT EXISTS controller_events (
              controller_event_id INTEGER PRIMARY KEY AUTOINCREMENT,
              created_at TEXT NOT NULL, level TEXT NOT NULL,
              category TEXT NOT NULL, message TEXT NOT NULL,
              payload_json TEXT NOT NULL
            )"""
        )
        db.execute(
            """CREATE TABLE IF NOT EXISTS device_pairing_codes (
              code_hash TEXT PRIMARY KEY, created_at TEXT NOT NULL,
              expires_at TEXT NOT NULL, used_at TEXT, device_id TEXT
            )"""
        )
        db.execute(
            "CREATE INDEX IF NOT EXISTS idx_controller_events_created ON controller_events(created_at)"
        )
        db.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_jobs_idempotency ON jobs(idempotency_key) WHERE idempotency_key IS NOT NULL")
        db.execute("CREATE INDEX IF NOT EXISTS idx_jobs_dispatch ON jobs(status,next_retry_at,publish_at,created_at)")
        db.execute("CREATE INDEX IF NOT EXISTS idx_jobs_priority_dispatch ON jobs(status,publish_at,priority DESC,created_at)")
        db.execute(
            """CREATE INDEX IF NOT EXISTS idx_jobs_content_dedupe
               ON jobs(target,content_sha256,account_label,platform_account_label,created_at)
               WHERE content_sha256 IS NOT NULL"""
        )
        db.execute("CREATE INDEX IF NOT EXISTS idx_jobs_lease ON jobs(status,lease_expires_at)")
        db.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_events_key ON events(job_id,event_key) WHERE event_key IS NOT NULL")
        db.execute(
            """CREATE UNIQUE INDEX IF NOT EXISTS idx_evidence_dedupe
               ON evidence(job_id,attempt_number,original_filename,content_sha256)"""
        )
        db.execute(
            """CREATE UNIQUE INDEX IF NOT EXISTS idx_admin_actions_idempotency
               ON admin_actions(action_type,idempotency_key)"""
        )
        db.execute(
            "UPDATE jobs SET status='dead_letter',completed_at=COALESCE(completed_at,updated_at) WHERE status='failed'"
        )
        reconcile_jobs(db, now())


def startup() -> None:
    if len(RUNNER_TOKEN) < 24 or len(ADMIN_TOKEN) < 24 or hmac.compare_digest(RUNNER_TOKEN, ADMIN_TOKEN):
        raise RuntimeError("Set distinct HUB_RUNNER_TOKEN and HUB_ADMIN_TOKEN values of at least 24 characters")
    if LEASE_SECONDS < 30 or RETRY_BASE_SECONDS < 1 or RETRY_MAX_SECONDS < RETRY_BASE_SECONDS:
        raise RuntimeError("Invalid lease or retry timing configuration")
    initialize_database()


def authenticate(expected: str, supplied: str | None) -> None:
    if supplied is None or not hmac.compare_digest(expected, supplied):
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid hub token")


def runner_auth(x_hub_token: Annotated[str | None, Security(runner_token_header)]) -> None:
    authenticate(RUNNER_TOKEN, x_hub_token)


def admin_auth(x_hub_token: Annotated[str | None, Security(admin_token_header)]) -> None:
    authenticate(ADMIN_TOKEN, x_hub_token)


def canonical_json(value: dict) -> str:
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False)


def job_fingerprint(
    job: JobCreate,
    canonical_account_label: str | None,
    canonical_platform_account_label: str | None,
) -> str:
    payload = job.model_dump(mode="json")
    payload["publish_at"] = normalize_time(job.publish_at)
    payload["account_label"] = canonical_account_label
    payload["platform_account_label"] = canonical_platform_account_label
    return hashlib.sha256(canonical_json(payload).encode()).hexdigest()


def job_payload(row: sqlite3.Row) -> dict:
    hidden = {
        "assigned_device_id",
        "status",
        "status_message",
        "created_at",
        "updated_at",
        "publish_at",
        "preferred_device_id",
        "idempotency_key",
        "payload_fingerprint",
        "next_retry_at",
        "last_error_code",
        "completed_at",
        "publication_id",
    }
    payload = {key: row[key] for key in row.keys() if key not in hidden}
    payload["attempt_number"] = row["attempt_count"]
    payload["media_path"] = None
    return payload


def admin_job_payload(row: sqlite3.Row) -> dict:
    return {key: row[key] for key in row.keys() if key not in {"payload_fingerprint", "lease_token"}}


def job_stage(status: str, last_message: str | None = None) -> tuple[str, str]:
    message = (last_message or "").lower()
    if status == "succeeded":
        return "published", "Опубликовано и подтверждено"
    if status == "ready_to_publish":
        return "ready", "Готово к финальной публикации"
    if status == "needs_review":
        return "review", "Нужна проверка"
    if status in {"dead_letter", "failed"}:
        return "failed", "Остановлено после ошибки"
    if status == "cancelled":
        return "cancelled", "Отменено"
    if status == "paused":
        return "paused", "Приостановлено"
    if status in {"pending", "retry_wait"}:
        return ("scheduled", "Ожидает запуска") if status == "pending" else ("retry", "Ожидает повтор")
    if any(token in message for token in ("publication", "publish", "post-confirm", "receipt")):
        return "confirming", "Проверяем публикацию"
    if any(token in message for token in ("editor", "composer", "caption", "title")):
        return "editing", "Заполняем публикацию"
    if any(token in message for token in ("media", "gallery", "picker", "prepared")):
        return "media", "Выбираем материал"
    if any(token in message for token in ("launch", "share", "started publish")):
        return "opening", "Открываем соцсеть"
    if status in {"claimed", "running"}:
        return "starting", "Телефон принял задание"
    return status, "Состояние обновляется"


def require_job_ownership(
    db: sqlite3.Connection, job_id: str, device_id: str | None
) -> sqlite3.Row:
    if not device_id:
        raise HTTPException(status_code=400, detail="X-Device-Id header is required")
    job = db.execute("SELECT * FROM jobs WHERE job_id=?", (job_id,)).fetchone()
    if job is None:
        raise HTTPException(status_code=404, detail="Job was not found")
    if job["assigned_device_id"] != device_id:
        raise HTTPException(status_code=403, detail="Job is not assigned to this device")
    return job


def require_lease(job: sqlite3.Row, lease_token: str | None, timestamp: str) -> None:
    if lease_token is not None and not hmac.compare_digest(job["lease_token"] or "", lease_token):
        raise HTTPException(status_code=409, detail="Lease token is stale")
    if job["lease_expires_at"] is None or job["lease_expires_at"] <= timestamp:
        raise HTTPException(status_code=409, detail="Job lease has expired")


def insert_event(
    db: sqlite3.Connection,
    job_id: str,
    level: str,
    message: str,
    payload: dict,
    screenshot_path: str | None,
    attempt_number: int,
    event_key: str,
) -> tuple[int, bool]:
    existing = db.execute(
        "SELECT event_id FROM events WHERE job_id=? AND event_key=?", (job_id, event_key)
    ).fetchone()
    if existing is not None:
        return existing["event_id"], True
    event_id = db.execute(
        """INSERT INTO events(job_id,created_at,level,message,payload_json,screenshot_path,event_key,attempt_number)
           VALUES(?,?,?,?,?,?,?,?)""",
        (job_id, now(), level, message, canonical_json(payload), screenshot_path, event_key, attempt_number),
    ).lastrowid
    return event_id, False


def reconcile_jobs(db: sqlite3.Connection, timestamp: str) -> dict[str, int]:
    counts = {"leases_expired": 0, "requeued": 0, "dead_lettered": 0}
    expired = db.execute(
        """SELECT * FROM jobs WHERE status IN ('claimed','running')
           AND (lease_expires_at IS NULL OR lease_expires_at<=?)""",
        (timestamp,),
    ).fetchall()
    for job in expired:
        counts["leases_expired"] += 1
        attempts = job["attempt_count"]
        if attempts >= job["max_attempts"]:
            target_status, next_retry, completed_at = "dead_letter", None, timestamp
            counts["dead_lettered"] += 1
        else:
            target_status = "retry_wait"
            next_retry = add_seconds(timestamp, retry_delay_seconds(job["job_id"], attempts))
            completed_at = None
        db.execute(
            """UPDATE jobs SET status=?,status_message=?,
               assigned_device_id=CASE WHEN ?='retry_wait' THEN NULL ELSE assigned_device_id END,lease_token=NULL,
               lease_expires_at=NULL,heartbeat_at=NULL,next_retry_at=?,last_error_code='lease_expired',
               completed_at=?,updated_at=? WHERE job_id=?""",
            (target_status, "Lease expired before a terminal report", target_status, next_retry, completed_at, timestamp, job["job_id"]),
        )
        insert_event(
            db,
            job["job_id"],
            "warning",
            "lease_expired",
            {"attempt": attempts, "outcome": target_status, "next_retry_at": next_retry},
            None,
            attempts,
            f"reconcile:lease:{attempts}",
        )
    due = db.execute(
        "SELECT job_id,attempt_count FROM jobs WHERE status='retry_wait' AND next_retry_at<=?",
        (timestamp,),
    ).fetchall()
    for job in due:
        db.execute(
            "UPDATE jobs SET status='pending',next_retry_at=NULL,status_message=NULL,updated_at=? WHERE job_id=?",
            (timestamp, job["job_id"]),
        )
        insert_event(
            db,
            job["job_id"],
            "info",
            "retry_ready",
            {"attempt": job["attempt_count"]},
            None,
            job["attempt_count"],
            f"reconcile:retry-ready:{job['attempt_count']}",
        )
        counts["requeued"] += 1
    return counts


def run_reconcile_cycle() -> dict[str, int]:
    """Persist lease/retry recovery even when no device or dashboard is polling."""
    timestamp = now()
    with database() as db:
        counts = reconcile_jobs(db, timestamp)
        if any(counts.values()):
            insert_controller_event(
                db,
                "warning" if counts["leases_expired"] else "info",
                "watchdog",
                "Фоновое восстановление очереди выполнено",
                counts,
            )
    return counts


def insert_controller_event(
    db: sqlite3.Connection,
    level: str,
    category: str,
    message: str,
    payload: dict | None = None,
) -> None:
    db.execute(
        """INSERT INTO controller_events(created_at,level,category,message,payload_json)
           VALUES(?,?,?,?,?)""",
        (now(), level, category, message, canonical_json(payload or {})),
    )


def pairing_code_hash(code: str) -> str:
    return hmac.new(ADMIN_TOKEN.encode(), code.encode(), hashlib.sha256).hexdigest()


def create_pairing_code(db: sqlite3.Connection, timestamp: str) -> tuple[str, str]:
    alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    expires_at = add_seconds(timestamp, 600)
    for _ in range(8):
        code = "".join(secrets.choice(alphabet) for _ in range(8))
        try:
            db.execute(
                """INSERT INTO device_pairing_codes(code_hash,created_at,expires_at)
                   VALUES(?,?,?)""",
                (pairing_code_hash(code), timestamp, expires_at),
            )
            return code, expires_at
        except sqlite3.IntegrityError:
            continue
    raise HTTPException(status_code=503, detail="Could not allocate a pairing code")


@app.get("/health")
def health() -> dict:
    return {"status": "ok"}


def dashboard_cookie_value() -> str:
    return hmac.new(
        ADMIN_TOKEN.encode("utf-8"),
        b"mobile-poster-dashboard-session-v1",
        hashlib.sha256,
    ).hexdigest()


def dashboard_auth(
    dashboard_session: Annotated[str | None, Cookie(alias="hub_dashboard_session")] = None,
) -> None:
    if not ADMIN_TOKEN or not dashboard_session or not hmac.compare_digest(
        dashboard_session, dashboard_cookie_value()
    ):
        raise HTTPException(status_code=401, detail="Dashboard login is required")


def is_local_dashboard_request(request: Request) -> bool:
    hostname = (request.url.hostname or "").lower()
    client_host = request.client.host if request.client else ""
    try:
        client_is_loopback = ipaddress.ip_address(client_host).is_loopback
    except ValueError:
        client_is_loopback = False
    return hostname in {"127.0.0.1", "localhost", "::1"} and client_is_loopback


def local_usb_farm() -> UsbFarm | None:
    configured = os.environ.get("FARM_ADB_PATH", "").strip()
    adb_path = configured or shutil.which("adb") or ""
    if not adb_path or not Path(adb_path).is_file():
        return None
    return UsbFarm(adb_path, port=18082)


def approved_agent_apk() -> Path | None:
    def matches(candidate: Path, expected: str) -> bool:
        if not candidate.is_file() or candidate.suffix.lower() != ".apk":
            return False
        digest = hashlib.sha256()
        with candidate.open("rb") as stream:
            for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                digest.update(chunk)
        return hmac.compare_digest(digest.hexdigest().lower(), expected.lower())

    configured = os.environ.get("FARM_AGENT_APK", "").strip()
    if configured:
        candidate = Path(configured).resolve()
        expected = os.environ.get("FARM_AGENT_APK_SHA256", "").strip()
        return candidate if re.fullmatch(r"[0-9a-fA-F]{64}", expected) and matches(candidate, expected) else None
    handoff = WORKSPACE_ROOT / "artifacts" / "runtime-smoke" / "apk"
    candidates = sorted(
        [
            *handoff.glob("mobile-poster-agent-controller-pairing-*.apk"),
            *handoff.glob("mobile-poster-agent-usb-control-*.apk"),
        ],
        key=lambda path: path.stat().st_mtime_ns,
        reverse=True,
    )
    for candidate in candidates:
        digest = re.search(r"-([0-9A-Fa-f]{64})\.apk$", candidate.name)
        if digest and matches(candidate, digest.group(1)):
            return candidate.resolve()
    return None


def local_usb_devices() -> list[dict]:
    farm = local_usb_farm()
    if farm is None:
        return []
    return [vars(device) for device in farm.inspect_many(farm.devices())]


@app.get("/dashboard", response_class=HTMLResponse)
def dashboard() -> HTMLResponse:
    if not DASHBOARD_PATH.is_file():
        raise HTTPException(status_code=404, detail="Dashboard asset is missing")
    return HTMLResponse(DASHBOARD_PATH.read_text(encoding="utf-8"))


@app.post("/dashboard/session")
def dashboard_login(login: DashboardLogin, response: Response) -> dict:
    if not ADMIN_TOKEN or not hmac.compare_digest(login.token, ADMIN_TOKEN):
        raise HTTPException(status_code=401, detail="Invalid admin token")
    response.set_cookie(
        key="hub_dashboard_session",
        value=dashboard_cookie_value(),
        httponly=True,
        samesite="strict",
        secure=False,
        max_age=12 * 60 * 60,
        path="/",
    )
    return {"authenticated": True}


@app.post("/dashboard/local-session")
def dashboard_local_login(request: Request, response: Response) -> dict:
    hostname = request.headers.get("host", "").split(":", 1)[0].lower()
    if not ADMIN_TOKEN or hostname not in {"127.0.0.1", "localhost", "::1"}:
        raise HTTPException(status_code=403, detail="Local dashboard login is unavailable")
    response.set_cookie(
        key="hub_dashboard_session",
        value=dashboard_cookie_value(),
        httponly=True,
        samesite="strict",
        secure=False,
        max_age=12 * 60 * 60,
        path="/",
    )
    return {"authenticated": True, "local": True}


@app.delete("/dashboard/session")
def dashboard_logout(response: Response) -> dict:
    response.delete_cookie("hub_dashboard_session", path="/")
    return {"authenticated": False}


@app.get("/dashboard/activity", dependencies=[Depends(dashboard_auth)])
def dashboard_activity(request: Request) -> dict:
    with database(write=False) as db:
        jobs = db.execute(
            """SELECT j.job_id,j.target,j.status,j.attempt_count,j.max_attempts,j.status_message,
                      j.created_at,j.updated_at,j.completed_at,j.publication_id,
                      j.assigned_device_id,j.preferred_device_id,j.account_label,
                      j.heartbeat_at,j.lease_expires_at,j.last_error_code,j.priority,j.publish_at,
                      j.content_sha256,j.allow_duplicate,
                      (SELECT MAX(e.created_at) FROM events e WHERE e.job_id=j.job_id) AS last_progress_at,
                      (SELECT e.message FROM events e WHERE e.job_id=j.job_id ORDER BY e.event_id DESC LIMIT 1) AS last_event_message
                 FROM jobs j ORDER BY j.created_at DESC LIMIT 150"""
        ).fetchall()
        events = db.execute(
            """SELECT e.event_id,e.job_id,e.created_at,e.level,e.message,e.attempt_number,
                      e.screenshot_path,j.target,j.status
                 FROM events e JOIN jobs j ON j.job_id=e.job_id
                ORDER BY e.event_id DESC LIMIT 300"""
        ).fetchall()
        controller_events = db.execute(
            """SELECT controller_event_id,created_at,level,category,message
                 FROM controller_events ORDER BY controller_event_id DESC LIMIT 100"""
        ).fetchall()
        status_counts = {
            row["status"]: row["count"]
            for row in db.execute(
                "SELECT status,COUNT(*) AS count FROM jobs GROUP BY status"
            ).fetchall()
        }
        metric_row = db.execute(
            """SELECT COUNT(*) AS total,
                      SUM(CASE WHEN status='succeeded' THEN 1 ELSE 0 END) AS succeeded,
                      SUM(CASE WHEN status IN ('needs_review','dead_letter','failed') THEN 1 ELSE 0 END) AS attention,
                      AVG(CASE WHEN completed_at IS NOT NULL
                          THEN (julianday(completed_at)-julianday(created_at))*86400 END) AS avg_duration_seconds
                 FROM jobs"""
        ).fetchone()
        devices = db.execute(
            """SELECT device_id,label,account_label,tags_json,notes,
                      automation_state,last_seen_at
                 FROM devices ORDER BY last_seen_at DESC"""
        ).fetchall()
    return {
        "generated_at": now(),
        "local_control": is_local_dashboard_request(request),
        "usb_devices": local_usb_devices() if is_local_dashboard_request(request) else [],
        "status_counts": status_counts,
        "devices": [
            {
                **{key: row[key] for key in row.keys() if key != "tags_json"},
                "tags": json.loads(row["tags_json"] or "[]"),
            }
            for row in devices
        ],
        "jobs": [
            {
                **dict(row),
                "stage": job_stage(row["status"], row["last_event_message"])[0],
                "stage_label": job_stage(row["status"], row["last_event_message"])[1],
            }
            for row in jobs
        ],
        "metrics": {
            "total": metric_row["total"] or 0,
            "succeeded": metric_row["succeeded"] or 0,
            "attention": metric_row["attention"] or 0,
            "success_rate": round(((metric_row["succeeded"] or 0) / max(metric_row["total"] or 0, 1)) * 100, 1),
            "avg_duration_seconds": round(metric_row["avg_duration_seconds"] or 0, 1),
        },
        "events": sorted([
            {
                **{key: row[key] for key in row.keys() if key != "screenshot_path"},
                "has_screenshot": bool(row["screenshot_path"]),
            }
            for row in events
        ] + [
            {
                "event_id": f"S{row['controller_event_id']}",
                "job_id": "system",
                "created_at": row["created_at"],
                "level": row["level"],
                "message": row["message"],
                "attempt_number": 0,
                "target": "system",
                "status": row["category"],
                "has_screenshot": False,
            }
            for row in controller_events
        ], key=lambda item: item["created_at"], reverse=True)[:300],
    }


@app.post("/dashboard/jobs/{job_id}/action", dependencies=[Depends(dashboard_auth)])
def dashboard_job_action(job_id: str, control: DashboardJobControl) -> dict:
    timestamp = now()
    with database(write=True) as db:
        job = db.execute("SELECT * FROM jobs WHERE job_id=?", (job_id,)).fetchone()
        if job is None:
            raise HTTPException(status_code=404, detail="Задание не найдено")
        status = job["status"]
        target_status = status
        message = job["status_message"]
        if control.action == "set_priority":
            if control.priority is None:
                raise HTTPException(status_code=422, detail="Укажите приоритет")
            db.execute("UPDATE jobs SET priority=?,updated_at=? WHERE job_id=?", (control.priority, timestamp, job_id))
            message = f"Приоритет изменён на {control.priority}"
        elif control.action == "pause":
            if status not in {"pending", "retry_wait"}:
                raise HTTPException(status_code=409, detail="Можно приостановить только ожидающее задание")
            target_status, message = "paused", "Приостановлено оператором"
        elif control.action == "resume":
            if status != "paused":
                raise HTTPException(status_code=409, detail="Задание не приостановлено")
            target_status, message = "pending", "Возвращено в очередь оператором"
        elif control.action == "cancel":
            if status in {"claimed", "running"}:
                raise HTTPException(status_code=409, detail="Сначала дождитесь безопасной остановки активной попытки")
            if status in {"succeeded", "cancelled"}:
                raise HTTPException(status_code=409, detail="Это задание уже завершено")
            target_status, message = "cancelled", "Отменено оператором"
        elif control.action == "retry":
            if status not in {"needs_review", "dead_letter", "failed"}:
                raise HTTPException(status_code=409, detail="Повтор доступен только для остановленного задания")
            target_status, message = "pending", "Возвращено на повтор оператором"
            next_max = max(job["max_attempts"], job["attempt_count"] + 1)
            db.execute("UPDATE jobs SET max_attempts=? WHERE job_id=?", (next_max, job_id))
        if target_status != status:
            db.execute(
                """UPDATE jobs SET status=?,status_message=?,assigned_device_id=NULL,
                          lease_token=NULL,lease_expires_at=NULL,heartbeat_at=NULL,next_retry_at=NULL,
                          last_error_code=NULL,completed_at=NULL,updated_at=? WHERE job_id=?""",
                (target_status, message, timestamp, job_id),
            )
        insert_event(
            db, job_id, "warning", "operator_action",
            {"action": control.action, "from_status": status, "to_status": target_status, "priority": control.priority},
            None, job["attempt_count"], f"operator:{control.action}:{uuid.uuid4().hex}",
        )
        insert_controller_event(db, "warning", "job_control", message or control.action, {"job_id": job_id})
        updated = db.execute("SELECT * FROM jobs WHERE job_id=?", (job_id,)).fetchone()
    return admin_job_payload(updated)


@app.post("/dashboard/devices/{serial}/action", dependencies=[Depends(dashboard_auth)])
def dashboard_device_action(serial: str, control: DashboardDeviceControl, request: Request) -> dict:
    if not is_local_dashboard_request(request):
        raise HTTPException(status_code=403, detail="USB device control is local-only")
    farm = local_usb_farm()
    if farm is None:
        raise HTTPException(status_code=503, detail="ADB is unavailable")
    connected = {device.serial: device for device in farm.devices()}
    device = connected.get(serial)
    if device is None:
        raise HTTPException(status_code=404, detail="USB device is not connected")
    result = farm.control(device, control.action)
    with database(write=True) as db:
        insert_controller_event(
            db,
            "info" if result.agent_running or control.action == "stop" else "warning",
            "device_control",
            f"USB-телефон {serial}: {control.action} — {result.message}",
            {"serial": serial, "action": control.action, "ready": result.agent_running and result.accessibility_ready},
        )
    return vars(result)


@app.get("/dashboard/devices/{serial}/diagnostics", dependencies=[Depends(dashboard_auth)])
def dashboard_device_diagnostics(serial: str, request: Request) -> dict:
    if not is_local_dashboard_request(request):
        raise HTTPException(status_code=403, detail="USB device diagnostics are local-only")
    farm = local_usb_farm()
    if farm is None:
        raise HTTPException(status_code=503, detail="ADB is unavailable")
    connected = {device.serial: device for device in farm.devices()}
    device = connected.get(serial)
    if device is None:
        raise HTTPException(status_code=404, detail="USB device is not connected")
    return vars(farm.diagnose(device))


@app.post("/dashboard/devices/{serial}/install", dependencies=[Depends(dashboard_auth)])
def dashboard_device_install(serial: str, request: Request) -> dict:
    if not is_local_dashboard_request(request):
        raise HTTPException(status_code=403, detail="USB agent installation is local-only")
    farm = local_usb_farm()
    apk = approved_agent_apk()
    if farm is None or apk is None:
        raise HTTPException(status_code=503, detail="Approved agent APK is unavailable")
    connected = {device.serial: device for device in farm.devices()}
    device = connected.get(serial)
    if device is None:
        raise HTTPException(status_code=404, detail="USB device is not connected")
    result = farm.install_agent(device, apk)
    with database(write=True) as db:
        insert_controller_event(
            db,
            "info" if result["installed"] else "warning",
            "agent_install",
            f"USB-телефон {serial}: {result['message']}",
            {"serial": serial, "installed": result["installed"], "apk": apk.name},
        )
    return result


@app.post("/dashboard/devices/{serial}/pair", dependencies=[Depends(dashboard_auth)])
def dashboard_device_pair(serial: str, request: Request) -> dict:
    if not is_local_dashboard_request(request):
        raise HTTPException(status_code=403, detail="USB device pairing is local-only")
    farm = local_usb_farm()
    if farm is None:
        raise HTTPException(status_code=503, detail="ADB is unavailable")
    connected = {device.serial: device for device in farm.devices()}
    device = connected.get(serial)
    if device is None:
        raise HTTPException(status_code=404, detail="USB device is not connected")
    with database(write=True) as db:
        code, expires_at = create_pairing_code(db, now())
    result = farm.pair_agent(device, code)
    with database(write=True) as db:
        insert_controller_event(
            db,
            "info" if result["paired"] else "warning",
            "device_pairing",
            f"USB-телефон {serial}: {result['message']}",
            {"serial": serial, "paired": result["paired"], "expires_at": expires_at},
        )
    return result


@app.post("/dashboard/devices/action", dependencies=[Depends(dashboard_auth)])
def dashboard_all_devices_action(control: DashboardDeviceControl, request: Request) -> dict:
    if not is_local_dashboard_request(request):
        raise HTTPException(status_code=403, detail="USB device control is local-only")
    farm = local_usb_farm()
    if farm is None:
        raise HTTPException(status_code=503, detail="ADB is unavailable")
    connected = [device for device in farm.devices() if device.adb_state == "device"]
    results = [vars(device) for device in farm.control_many(connected, control.action)]
    ready = sum(1 for item in results if item["agent_running"] and item["accessibility_ready"])
    with database(write=True) as db:
        insert_controller_event(
            db,
            "info" if control.action == "stop" or ready == len(results) else "warning",
            "fleet_control",
            f"Массовая команда {control.action}: обработано {len(results)}, полностью готово {ready}",
            {"action": control.action, "total": len(results), "ready": ready},
        )
    return {
        "action": control.action,
        "total": len(results),
        "ready": ready,
        "devices": results,
    }


@app.post("/dashboard/reconcile", dependencies=[Depends(dashboard_auth)])
def dashboard_reconcile(request: Request) -> dict:
    if not is_local_dashboard_request(request):
        raise HTTPException(status_code=403, detail="Queue recovery is local-only")
    with database(write=True) as db:
        result = reconcile_jobs(db, now())
        insert_controller_event(
            db,
            "warning" if result["leases_expired"] else "info",
            "queue_recovery",
            (
                f"Восстановление очереди: просрочено {result['leases_expired']}, "
                f"возвращено {result['requeued']}, остановлено {result['dead_lettered']}"
            ),
            result,
        )
        return result


@app.post("/devices/pair")
def pair_device(claim: DevicePairingClaim, request: Request) -> dict:
    if not is_local_dashboard_request(request) or not RUNNER_TOKEN:
        raise HTTPException(status_code=403, detail="USB pairing is local-only")
    timestamp = now()
    digest = pairing_code_hash(claim.code)
    with database(write=True) as db:
        pairing = db.execute(
            """SELECT expires_at,used_at FROM device_pairing_codes WHERE code_hash=?""",
            (digest,),
        ).fetchone()
        if pairing is None or pairing["used_at"] is not None or pairing["expires_at"] < timestamp:
            raise HTTPException(status_code=409, detail="Pairing code is invalid or expired")
        db.execute(
            "UPDATE device_pairing_codes SET used_at=?,device_id=? WHERE code_hash=? AND used_at IS NULL",
            (timestamp, claim.device_id, digest),
        )
        insert_controller_event(
            db,
            "info",
            "device_pairing",
            f"Телефон {claim.device_label} безопасно подключён к Hub",
            {"device_id": claim.device_id, "device_label": claim.device_label},
        )
    return {"runner_token": RUNNER_TOKEN, "hub_url": "http://127.0.0.1:18082"}


@app.post("/devices/register", dependencies=[Depends(runner_auth)])
def register_device(device: DeviceRegistration) -> dict:
    timestamp = now()
    automation_state = device.notes.split("automation=")[-1] if device.notes and "automation=" in device.notes else None
    with database(write=True) as db:
        db.execute(
            """INSERT INTO devices(device_id,platform,mode,label,account_label,notes,tags_json,automation_state,last_seen_at)
               VALUES(?,?,?,?,?,?,?,?,?)
               ON CONFLICT(device_id) DO UPDATE SET platform=excluded.platform,mode=excluded.mode,label=excluded.label,
               account_label=excluded.account_label,notes=excluded.notes,tags_json=excluded.tags_json,
               automation_state=excluded.automation_state,last_seen_at=excluded.last_seen_at""",
            (device.device_id, device.platform, device.mode, device.label, device.account_label, device.notes, canonical_json(device.tags), automation_state, timestamp),
        )
    return {"device_id": device.device_id, "registered_at": timestamp}


@app.post("/jobs", dependencies=[Depends(admin_auth)], status_code=201)
def create_job(
    job: JobCreate,
    idempotency_key: Annotated[str, Header(alias="Idempotency-Key", min_length=8, max_length=200)],
) -> dict:
    timestamp = now()
    account_label = job.account_label.strip() if job.account_label is not None else None
    account_label = account_label or None
    platform_account_label = (
        job.platform_account_label.strip() if job.platform_account_label is not None else None
    )
    platform_account_label = platform_account_label or None
    if job.target in SOCIAL_DRY_RUN_TARGETS:
        if account_label is None:
            raise HTTPException(status_code=422, detail="account_label is required for social dry-run targets")
        platform_account_label = platform_account_label or account_label
    fingerprint = job_fingerprint(job, account_label, platform_account_label)
    content_sha256 = job.content_sha256.lower() if job.content_sha256 else None
    with database(write=True) as db:
        existing = db.execute(
            "SELECT job_id,status,payload_fingerprint FROM jobs WHERE idempotency_key=?", (idempotency_key,)
        ).fetchone()
        if existing is not None:
            if existing["payload_fingerprint"] != fingerprint:
                raise HTTPException(status_code=409, detail="Idempotency-Key was already used with a different payload")
            return {"job_id": existing["job_id"], "status": existing["status"], "idempotent_replay": True}
        if content_sha256 and not job.allow_duplicate:
            duplicate = db.execute(
                """SELECT job_id,status FROM jobs
                   WHERE target=? AND content_sha256=?
                     AND COALESCE(account_label,'')=COALESCE(?, '')
                     AND COALESCE(platform_account_label,'')=COALESCE(?, '')
                     AND status NOT IN ('failed','dead_letter','cancelled')
                   ORDER BY created_at DESC LIMIT 1""",
                (job.target, content_sha256, account_label, platform_account_label),
            ).fetchone()
            if duplicate is not None:
                return {
                    "job_id": duplicate["job_id"],
                    "status": duplicate["status"],
                    "idempotent_replay": True,
                    "duplicate_prevented": True,
                }
        job_id = str(uuid.uuid4())
        db.execute(
            """INSERT INTO jobs(
                 job_id,target,caption,title,description,link,board,media_url,publish_at,
                 preferred_device_id,account_label,platform_account_label,assigned_device_id,status,status_message,
                 created_at,updated_at,idempotency_key,payload_fingerprint,attempt_count,max_attempts,
                 priority,content_sha256,allow_duplicate
               ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
            (
                job_id, job.target, job.caption, job.title, job.description,
                str(job.link) if job.link else None, job.board, str(job.media_url),
                normalize_time(job.publish_at), job.preferred_device_id, account_label, platform_account_label,
                None, "pending", None, timestamp, timestamp, idempotency_key, fingerprint, 0, job.max_attempts,
                job.priority, content_sha256, 1 if job.allow_duplicate else 0,
            ),
        )
    return {
        "job_id": job_id,
        "status": "pending",
        "idempotent_replay": False,
        "duplicate_prevented": False,
    }


@app.post(
    "/devices/{device_id}/jobs",
    dependencies=[Depends(runner_auth)],
    status_code=201,
)
def create_device_job(
    device_id: str,
    request: DeviceJobCreate,
    x_idempotency_key: Annotated[
        str, Header(alias="Idempotency-Key", min_length=8, max_length=200)
    ],
    x_device_id: Annotated[str | None, Header()] = None,
) -> dict:
    if x_device_id != device_id:
        raise HTTPException(status_code=403, detail="X-Device-Id must match the creating device")
    with database() as db:
        device = db.execute(
            "SELECT account_label FROM devices WHERE device_id=?", (device_id,)
        ).fetchone()
    if device is None:
        raise HTTPException(status_code=404, detail="Publishing device is not registered")
    account_label = device["account_label"]
    if request.target in SOCIAL_DRY_RUN_TARGETS and not account_label:
        raise HTTPException(status_code=422, detail="Registered device account_label is required")
    job = JobCreate(
        target=request.target,
        caption=request.caption,
        title=request.title,
        board=request.board,
        media_url=request.media_url,
        publish_at=request.publish_at,
        preferred_device_id=device_id,
        account_label=account_label,
        platform_account_label=request.platform_account_label,
        priority=request.priority,
        content_sha256=request.content_sha256,
        allow_duplicate=request.allow_duplicate,
        max_attempts=5,
    )
    return create_job(job, x_idempotency_key)


@app.post("/devices/{device_id}/claim-next", dependencies=[Depends(runner_auth)])
def claim_next(device_id: str, x_device_id: Annotated[str | None, Header()] = None) -> dict:
    if x_device_id != device_id:
        raise HTTPException(status_code=403, detail="X-Device-Id must match the claimed device")
    timestamp = now()
    with database(write=True) as db:
        reconcile_jobs(db, timestamp)
        device = db.execute("SELECT account_label FROM devices WHERE device_id=?", (device_id,)).fetchone()
        if device is None:
            raise HTTPException(status_code=404, detail="Device is not registered")
        job = db.execute(
            """SELECT * FROM jobs WHERE status='pending' AND (publish_at IS NULL OR publish_at<=?)
               AND (preferred_device_id IS NULL OR preferred_device_id=?)
               AND (account_label IS NULL OR account_label=?)
               ORDER BY priority DESC, COALESCE(publish_at, created_at), created_at LIMIT 1""",
            (timestamp, device_id, device["account_label"]),
        ).fetchone()
        if job is None:
            return {"job": None}
        lease_token = uuid.uuid4().hex
        lease_expires_at = add_seconds(timestamp, LEASE_SECONDS)
        attempt_number = job["attempt_count"] + 1
        db.execute(
            """UPDATE jobs SET status='claimed',assigned_device_id=?,attempt_count=?,lease_token=?,
               lease_expires_at=?,heartbeat_at=?,next_retry_at=NULL,updated_at=? WHERE job_id=?""",
            (device_id, attempt_number, lease_token, lease_expires_at, timestamp, timestamp, job["job_id"]),
        )
        claimed = db.execute("SELECT * FROM jobs WHERE job_id=?", (job["job_id"],)).fetchone()
        insert_event(
            db, job["job_id"], "info", "job_claimed",
            {"device_id": device_id, "attempt": attempt_number, "lease_expires_at": lease_expires_at},
            None, attempt_number, f"claim:{attempt_number}",
        )
    return {"job": job_payload(claimed)}


@app.post("/jobs/{job_id}/heartbeat", dependencies=[Depends(runner_auth)])
def heartbeat_job(
    job_id: str,
    x_lease_token: Annotated[str, Header(alias="X-Lease-Token", min_length=16, max_length=200)],
    x_device_id: Annotated[str | None, Header()] = None,
) -> dict:
    timestamp = now()
    with database(write=True) as db:
        job = require_job_ownership(db, job_id, x_device_id)
        if job["status"] not in ACTIVE_STATUSES:
            raise HTTPException(status_code=409, detail="Job is not active")
        require_lease(job, x_lease_token, timestamp)
        lease_expires_at = add_seconds(timestamp, LEASE_SECONDS)
        db.execute(
            "UPDATE jobs SET heartbeat_at=?,lease_expires_at=?,updated_at=? WHERE job_id=?",
            (timestamp, lease_expires_at, timestamp, job_id),
        )
    return {"job_id": job_id, "status": job["status"], "lease_expires_at": lease_expires_at}


@app.post("/jobs/{job_id}/status", dependencies=[Depends(runner_auth)])
def update_status(
    job_id: str,
    update: JobStatusUpdate,
    x_device_id: Annotated[str | None, Header()] = None,
    x_lease_token: Annotated[str | None, Header(alias="X-Lease-Token")] = None,
) -> dict:
    timestamp = now()
    with database(write=True) as db:
        job = require_job_ownership(db, job_id, x_device_id)
        current = job["status"]
        if current in TERMINAL_STATUSES:
            return {"job_id": job_id, "status": current, "idempotent_replay": True, "ignored_report": True}
        if current not in ALLOWED_DEVICE_TRANSITIONS or update.status not in ALLOWED_DEVICE_TRANSITIONS[current]:
            raise HTTPException(status_code=409, detail=f"Transition {current} -> {update.status} is not allowed")
        try:
            require_lease(job, x_lease_token, timestamp)
        except HTTPException:
            reconcile_jobs(db, timestamp)
            raise
        if job["target"].endswith("_dry_run") and update.status == "succeeded":
            raise HTTPException(status_code=409, detail="dry_run targets cannot transition to succeeded")
        if update.status == "succeeded" and not update.publication_verified:
            raise HTTPException(status_code=409, detail="succeeded requires publication_verified=true")
        if update.status == "succeeded" and not (update.publication_id or "").strip():
            raise HTTPException(status_code=409, detail="succeeded requires a nonblank publication_id")
        if update.status == "running":
            lease_expires_at = add_seconds(timestamp, LEASE_SECONDS)
            db.execute(
                """UPDATE jobs SET status='running',status_message=?,heartbeat_at=?,lease_expires_at=?,
                   updated_at=? WHERE job_id=?""",
                (update.message, timestamp, lease_expires_at, timestamp, job_id),
            )
            return {"job_id": job_id, "status": "running", "lease_expires_at": lease_expires_at}
        if update.status in {"succeeded", "ready_to_publish"}:
            terminal_status = update.status
            terminal_message = update.message
            review_reason = None
            evidence = None
            if update.status == "ready_to_publish":
                if not update.ui_state_verified:
                    review_reason = "ready_to_publish requires ui_state_verified=true"
                elif not update.evidence_id:
                    review_reason = "ready_to_publish requires evidence_id"
                else:
                    evidence = db.execute(
                        """SELECT evidence_id,screenshot_path FROM evidence
                           WHERE evidence_id=? AND job_id=? AND attempt_number=? AND status='available'""",
                        (update.evidence_id, job_id, job["attempt_count"]),
                    ).fetchone()
                    if evidence is None:
                        review_reason = "ready_to_publish evidence must be available for the current attempt"
                if review_reason is not None:
                    terminal_status = "needs_review"
                    terminal_message = review_reason
            db.execute(
                """UPDATE jobs SET status=?,status_message=?,lease_token=NULL,lease_expires_at=NULL,
                   heartbeat_at=NULL,next_retry_at=NULL,last_error_code=NULL,completed_at=?,publication_id=?,updated_at=?
                   WHERE job_id=?""",
                (
                    terminal_status,
                    terminal_message,
                    timestamp,
                    update.publication_id if terminal_status == "succeeded" else None,
                    timestamp,
                    job_id,
                ),
            )
            insert_event(
                db,
                job_id,
                "warning" if terminal_status == "needs_review" else "info",
                terminal_status,
                {
                    "attempt": job["attempt_count"],
                    "requested_status": update.status,
                    "publication_verified": update.publication_verified,
                    "publication_id": update.publication_id,
                    "ui_state_verified": update.ui_state_verified,
                    "evidence_id": update.evidence_id if evidence is not None else None,
                    "review_reason": review_reason,
                },
                evidence["screenshot_path"] if evidence is not None else None,
                job["attempt_count"],
                f"terminal:{job['attempt_count']}:{terminal_status}",
            )
            return {
                "job_id": job_id,
                "status": terminal_status,
                "requested_status": update.status,
                "review_reason": review_reason,
                "evidence_id": update.evidence_id if evidence is not None else None,
                "idempotent_replay": False,
            }
        should_retry = update.retryable and job["attempt_count"] < job["max_attempts"]
        target_status = "retry_wait" if should_retry else "dead_letter"
        next_retry_at = (
            add_seconds(timestamp, retry_delay_seconds(job_id, job["attempt_count"])) if should_retry else None
        )
        db.execute(
            """UPDATE jobs SET status=?,status_message=?,
               assigned_device_id=CASE WHEN ?='retry_wait' THEN NULL ELSE assigned_device_id END,lease_token=NULL,
               lease_expires_at=NULL,heartbeat_at=NULL,next_retry_at=?,last_error_code=?,completed_at=?,updated_at=?
               WHERE job_id=?""",
            (
                target_status, update.message, target_status, next_retry_at, update.error_code,
                None if should_retry else timestamp, timestamp, job_id,
            ),
        )
        insert_event(
            db, job_id, "error", "attempt_failed",
            {"attempt": job["attempt_count"], "error_code": update.error_code,
             "retryable": update.retryable, "outcome": target_status, "next_retry_at": next_retry_at},
            None, job["attempt_count"], f"attempt-failed:{job['attempt_count']}",
        )
    return {"job_id": job_id, "status": target_status, "next_retry_at": next_retry_at}


@app.post("/jobs/{job_id}/events", dependencies=[Depends(runner_auth)], status_code=201)
def add_event(job_id: str, event: JobEvent, x_device_id: Annotated[str | None, Header()] = None) -> dict:
    with database(write=True) as db:
        job = require_job_ownership(db, job_id, x_device_id)
        if event.screenshot_path:
            evidence = db.execute(
                "SELECT evidence_id FROM evidence WHERE job_id=? AND screenshot_path=? AND status='available'",
                (job_id, event.screenshot_path),
            ).fetchone()
            if evidence is None:
                raise HTTPException(status_code=409, detail="Screenshot evidence is unknown or unavailable")
        payload_json = canonical_json(event.payload)
        event_key = event.event_key or hashlib.sha256(
            canonical_json(
                {
                    "attempt": job["attempt_count"], "level": event.level, "message": event.message,
                    "payload": event.payload, "screenshot_path": event.screenshot_path,
                }
            ).encode()
        ).hexdigest()
        event_id, replay = insert_event(
            db, job_id, event.level, event.message, event.payload, event.screenshot_path,
            job["attempt_count"], event_key,
        )
        if event.screenshot_path:
            db.execute(
                "UPDATE evidence SET reported_at=COALESCE(reported_at,?) WHERE job_id=? AND screenshot_path=?",
                (now(), job_id, event.screenshot_path),
            )
    return {"event_id": event_id, "idempotent_replay": replay}


@app.post("/jobs/{job_id}/screenshots", dependencies=[Depends(runner_auth)], status_code=201)
def upload_screenshot(
    job_id: str,
    upload: ScreenshotUpload,
    x_device_id: Annotated[str | None, Header()] = None,
    x_lease_token: Annotated[str | None, Header(alias="X-Lease-Token")] = None,
) -> dict:
    try:
        content = base64.b64decode(upload.content_base64, validate=True)
    except binascii.Error as error:
        raise HTTPException(status_code=422, detail="content_base64 is invalid") from error
    if not content.startswith(b"\x89PNG\r\n\x1a\n") or len(content) > MAX_SCREENSHOT_BYTES:
        raise HTTPException(status_code=422, detail="Screenshot must be a PNG smaller than 8 MiB")
    content_sha256 = hashlib.sha256(content).hexdigest()
    with database(write=True) as db:
        job = require_job_ownership(db, job_id, x_device_id)
        existing = db.execute(
            """SELECT * FROM evidence WHERE job_id=? AND attempt_number=?
               AND original_filename=? AND content_sha256=?""",
            (job_id, job["attempt_count"], upload.filename, content_sha256),
        ).fetchone()
        if existing is not None:
            return {
                "evidence_id": existing["evidence_id"], "status": existing["status"],
                "screenshot_path": existing["screenshot_path"], "idempotent_replay": True,
            }
        if job["status"] not in ACTIVE_STATUSES:
            raise HTTPException(status_code=409, detail="New evidence is accepted only for an active attempt")
        require_lease(job, x_lease_token, now())
        evidence_id = str(uuid.uuid4())
        stored_filename = f"{job_id}_{uuid.uuid4().hex}.png"
        screenshot_path = f"/jobs/{job_id}/screenshots/{stored_filename}"
        path = SCREENSHOTS_DIR / stored_filename
        try:
            path.write_bytes(content)
            db.execute(
                """INSERT INTO evidence(
                     evidence_id,job_id,attempt_number,original_filename,stored_filename,screenshot_path,
                     content_sha256,size_bytes,status,created_at
                   ) VALUES(?,?,?,?,?,?,?,?,?,?)""",
                (
                    evidence_id, job_id, job["attempt_count"], upload.filename, stored_filename,
                    screenshot_path, content_sha256, len(content), "available", now(),
                ),
            )
            insert_event(
                db, job_id, "info", "evidence_uploaded",
                {"evidence_id": evidence_id, "size_bytes": len(content), "content_sha256": content_sha256},
                screenshot_path, job["attempt_count"], f"evidence:{evidence_id}",
            )
        except Exception:
            path.unlink(missing_ok=True)
            raise
    return {
        "evidence_id": evidence_id, "status": "available", "screenshot_path": screenshot_path,
        "idempotent_replay": False,
    }


@app.get("/jobs/{job_id}/screenshots/{filename}", dependencies=[Depends(admin_auth)])
def download_screenshot(job_id: str, filename: str) -> FileResponse:
    if Path(filename).name != filename or not filename.startswith(f"{job_id}_"):
        raise HTTPException(status_code=404, detail="Screenshot was not found")
    path = SCREENSHOTS_DIR / filename
    if not path.is_file():
        raise HTTPException(status_code=404, detail="Screenshot was not found")
    return FileResponse(path, media_type="image/png", filename=filename)


@app.get("/queue-media/{filename}")
def download_queue_media(filename: str) -> FileResponse:
    if Path(filename).name != filename:
        raise HTTPException(status_code=404, detail="Media was not found")
    path = (QUEUE_MEDIA_DIR / filename).resolve()
    if path.parent != QUEUE_MEDIA_DIR or not path.is_file():
        raise HTTPException(status_code=404, detail="Media was not found")
    return FileResponse(path, filename=filename)


@app.post("/admin/reconcile", dependencies=[Depends(admin_auth)])
def reconcile() -> dict:
    with database(write=True) as db:
        result = reconcile_jobs(db, now())
    return result


@app.post("/admin/jobs/{job_id}/verify-publication", dependencies=[Depends(admin_auth)])
def verify_publication(
    job_id: str,
    request: AdminPublicationVerification,
    x_idempotency_key: Annotated[
        str, Header(alias="Idempotency-Key", min_length=8, max_length=200)
    ],
) -> dict:
    """Reconcile a physically verified real publication with durable screenshot proof.

    This is intentionally AdminToken-only and cannot publish or reopen a job.  It exists
    for the narrow case where the platform completed the final action but its receipt UI
    differed from the runner's known classifier.  The supplied receipt becomes immutable
    Hub evidence and every change is idempotently audited.
    """
    try:
        content = base64.b64decode(request.content_base64, validate=True)
    except binascii.Error as error:
        raise HTTPException(status_code=422, detail="content_base64 is invalid") from error
    if not content.startswith(b"\x89PNG\r\n\x1a\n") or len(content) > MAX_SCREENSHOT_BYTES:
        raise HTTPException(status_code=422, detail="Publication evidence must be a PNG smaller than 8 MiB")
    content_sha256 = hashlib.sha256(content).hexdigest()
    timestamp = now()
    fingerprint = hashlib.sha256(
        canonical_json(
            {
                "job_id": job_id,
                "reason": request.reason,
                "publication_id": request.publication_id,
                "filename": request.filename,
                "content_sha256": content_sha256,
            }
        ).encode()
    ).hexdigest()
    path: Path | None = None
    with database(write=True) as db:
        existing = db.execute(
            """SELECT action_id,job_id,payload_fingerprint FROM admin_actions
               WHERE action_type='verify_publication' AND idempotency_key=?""",
            (x_idempotency_key,),
        ).fetchone()
        if existing is not None:
            if existing["job_id"] != job_id or existing["payload_fingerprint"] != fingerprint:
                raise HTTPException(
                    status_code=409,
                    detail="Idempotency-Key conflicts with another publication verification",
                )
            event = db.execute(
                "SELECT payload_json FROM events WHERE job_id=? AND event_key=?",
                (job_id, f"admin-publication-verified:{existing['action_id']}"),
            ).fetchone()
            payload = json.loads(event["payload_json"]) if event is not None else {}
            return {
                "job_id": job_id,
                "status": "succeeded",
                "publication_id": request.publication_id,
                "evidence_id": payload.get("evidence_id"),
                "action_id": existing["action_id"],
                "idempotent_replay": True,
            }
        job = db.execute("SELECT * FROM jobs WHERE job_id=?", (job_id,)).fetchone()
        if job is None:
            raise HTTPException(status_code=404, detail="Job was not found")
        if job["target"] not in REAL_PUBLICATION_TARGETS:
            raise HTTPException(status_code=409, detail="Only real publication targets can be verified")
        if job["status"] not in {"needs_review", "dead_letter"}:
            raise HTTPException(
                status_code=409,
                detail="Only needs_review or dead_letter jobs can receive an administrative publication receipt",
            )
        if job["publication_id"] is not None:
            raise HTTPException(status_code=409, detail="Job already has publication proof")
        succeeded = db.execute(
            "SELECT 1 FROM events WHERE job_id=? AND message='succeeded' LIMIT 1", (job_id,)
        ).fetchone()
        if succeeded is not None:
            raise HTTPException(status_code=409, detail="Job already has succeeded history")
        duplicate_publication = db.execute(
            "SELECT job_id FROM jobs WHERE publication_id=? AND job_id<>? LIMIT 1",
            (request.publication_id, job_id),
        ).fetchone()
        if duplicate_publication is not None:
            raise HTTPException(status_code=409, detail="publication_id is already assigned to another job")
        action_id = str(uuid.uuid4())
        evidence_id = str(uuid.uuid4())
        stored_filename = f"{job_id}_{uuid.uuid4().hex}.png"
        screenshot_path = f"/jobs/{job_id}/screenshots/{stored_filename}"
        path = SCREENSHOTS_DIR / stored_filename
        try:
            path.write_bytes(content)
            db.execute(
                """INSERT INTO evidence(
                     evidence_id,job_id,attempt_number,original_filename,stored_filename,screenshot_path,
                     content_sha256,size_bytes,status,created_at,reported_at
                   ) VALUES(?,?,?,?,?,?,?,?,?,?,?)""",
                (
                    evidence_id,
                    job_id,
                    job["attempt_count"],
                    request.filename,
                    stored_filename,
                    screenshot_path,
                    content_sha256,
                    len(content),
                    "available",
                    timestamp,
                    timestamp,
                ),
            )
            db.execute(
                """UPDATE jobs SET status='succeeded',status_message=?,publication_id=?,
                   last_error_code=NULL,lease_token=NULL,lease_expires_at=NULL,heartbeat_at=NULL,
                   next_retry_at=NULL,completed_at=?,updated_at=? WHERE job_id=?""",
                (
                    f"Admin-verified publication: {request.reason}",
                    request.publication_id,
                    timestamp,
                    timestamp,
                    job_id,
                ),
            )
            db.execute(
                """INSERT INTO admin_actions(
                     action_id,job_id,action_type,idempotency_key,payload_fingerprint,created_at
                   ) VALUES(?,?,'verify_publication',?,?,?)""",
                (action_id, job_id, x_idempotency_key, fingerprint, timestamp),
            )
            insert_event(
                db,
                job_id,
                "warning",
                "admin_publication_verified",
                {
                    "action_id": action_id,
                    "reason": request.reason,
                    "from_status": job["status"],
                    "publication_id": request.publication_id,
                    "publication_verified": True,
                    "evidence_id": evidence_id,
                    "content_sha256": content_sha256,
                },
                screenshot_path,
                job["attempt_count"],
                f"admin-publication-verified:{action_id}",
            )
        except Exception:
            path.unlink(missing_ok=True)
            raise
    return {
        "job_id": job_id,
        "status": "succeeded",
        "publication_id": request.publication_id,
        "evidence_id": evidence_id,
        "action_id": action_id,
        "idempotent_replay": False,
    }


@app.post("/admin/jobs/{job_id}/reopen", dependencies=[Depends(admin_auth)])
def reopen_job(
    job_id: str,
    request: JobReopenRequest,
    x_idempotency_key: Annotated[
        str, Header(alias="Idempotency-Key", min_length=8, max_length=200)
    ],
) -> dict:
    timestamp = now()
    fingerprint = hashlib.sha256(
        canonical_json(
            {"job_id": job_id, "reason": request.reason, "target_status": request.target_status}
        ).encode()
    ).hexdigest()
    with database(write=True) as db:
        existing = db.execute(
            """SELECT action_id,job_id,payload_fingerprint FROM admin_actions
               WHERE action_type='reopen' AND idempotency_key=?""",
            (x_idempotency_key,),
        ).fetchone()
        if existing is not None:
            if existing["job_id"] != job_id or existing["payload_fingerprint"] != fingerprint:
                raise HTTPException(status_code=409, detail="Idempotency-Key conflicts with another reopen request")
            current = db.execute("SELECT status,max_attempts FROM jobs WHERE job_id=?", (job_id,)).fetchone()
            return {
                "job_id": job_id,
                "status": current["status"],
                "max_attempts": current["max_attempts"],
                "action_id": existing["action_id"],
                "idempotent_replay": True,
            }
        job = db.execute("SELECT * FROM jobs WHERE job_id=?", (job_id,)).fetchone()
        if job is None:
            raise HTTPException(status_code=404, detail="Job was not found")
        if job["target"] not in DRY_RUN_TARGETS:
            raise HTTPException(status_code=409, detail="Only approved dry-run jobs can be reopened")
        if job["publication_id"] is not None:
            raise HTTPException(status_code=409, detail="Jobs with publication proof cannot be reopened")
        if job["status"] not in {"ready_to_publish", "needs_review", "dead_letter"}:
            raise HTTPException(
                status_code=409,
                detail="Only ready_to_publish, needs_review, or guarded dead_letter can be reopened",
            )
        if job["status"] == "dead_letter":
            succeeded = db.execute(
                "SELECT 1 FROM events WHERE job_id=? AND message='succeeded' LIMIT 1",
                (job_id,),
            ).fetchone()
            terminal_event = db.execute(
                """SELECT message,payload_json,event_key,attempt_number FROM events
                   WHERE job_id=? AND attempt_number=?
                   ORDER BY event_id DESC LIMIT 1""",
                (job_id, job["attempt_count"]),
            ).fetchone()
            failure = json.loads(terminal_event["payload_json"]) if terminal_event is not None else {}
            retryable_failure = (
                terminal_event is not None
                and terminal_event["message"] == "attempt_failed"
                and failure.get("retryable") is True
                and failure.get("outcome") == "dead_letter"
                and failure.get("error_code") == job["last_error_code"]
                and bool(job["last_error_code"])
            )
            lease_expiry_recovery = (
                terminal_event is not None
                and terminal_event["message"] == "lease_expired"
                and terminal_event["attempt_number"] == job["attempt_count"]
                and terminal_event["event_key"] == f"reconcile:lease:{job['attempt_count']}"
                and job["last_error_code"] == "lease_expired"
                and failure.get("attempt") == job["attempt_count"]
                and failure.get("outcome") == job["status"] == "dead_letter"
                and failure.get("next_retry_at") is None
            )
            if succeeded is not None or not (retryable_failure or lease_expiry_recovery):
                raise HTTPException(
                    status_code=409,
                    detail=(
                        "dead_letter requires a retryable latest failure or an exact current-attempt "
                        "lease-expiry recovery proof, and no succeeded history"
                    ),
                )
        next_max_attempts = job["attempt_count"] + 1
        if next_max_attempts > 37:
            raise HTTPException(status_code=409, detail="The bounded reopen attempt limit has been reached")
        action_id = str(uuid.uuid4())
        next_retry_at = timestamp if request.target_status == "retry_wait" else None
        db.execute(
            """UPDATE jobs SET status=?,status_message=?,assigned_device_id=NULL,lease_token=NULL,
               lease_expires_at=NULL,heartbeat_at=NULL,next_retry_at=?,last_error_code=NULL,
               completed_at=NULL,publication_id=NULL,max_attempts=?,updated_at=? WHERE job_id=?""",
            (
                request.target_status,
                f"Admin reopen: {request.reason}",
                next_retry_at,
                next_max_attempts,
                timestamp,
                job_id,
            ),
        )
        db.execute(
            """INSERT INTO admin_actions(
                 action_id,job_id,action_type,idempotency_key,payload_fingerprint,created_at
               ) VALUES(?,?,'reopen',?,?,?)""",
            (action_id, job_id, x_idempotency_key, fingerprint, timestamp),
        )
        insert_event(
            db,
            job_id,
            "warning",
            "admin_reopen",
            {
                "action_id": action_id,
                "reason": request.reason,
                "from_status": job["status"],
                "to_status": request.target_status,
                "previous_attempt": job["attempt_count"],
                "max_attempts": next_max_attempts,
            },
            None,
            job["attempt_count"],
            f"admin-reopen:{action_id}",
        )
    return {
        "job_id": job_id,
        "status": request.target_status,
        "max_attempts": next_max_attempts,
        "action_id": action_id,
        "idempotent_replay": False,
    }


@app.get("/jobs", dependencies=[Depends(admin_auth)])
def list_jobs() -> list[dict]:
    with database() as db:
        rows = db.execute("SELECT * FROM jobs ORDER BY created_at DESC LIMIT 100").fetchall()
    return [admin_job_payload(row) for row in rows]


@app.get("/jobs/{job_id}", dependencies=[Depends(admin_auth)])
def get_job(job_id: str) -> dict:
    with database() as db:
        job = db.execute("SELECT * FROM jobs WHERE job_id=?", (job_id,)).fetchone()
        if job is None:
            raise HTTPException(status_code=404, detail="Job was not found")
        events = db.execute(
            """SELECT event_id,created_at,level,message,payload_json,screenshot_path,event_key,attempt_number
               FROM events WHERE job_id=? ORDER BY event_id""",
            (job_id,),
        ).fetchall()
        evidence = db.execute(
            """SELECT evidence_id,attempt_number,original_filename,screenshot_path,content_sha256,
                      size_bytes,status,created_at,reported_at
               FROM evidence WHERE job_id=? ORDER BY created_at""",
            (job_id,),
        ).fetchall()
    return {
        "job": admin_job_payload(job),
        "events": [
            {**dict(event), "payload": json.loads(event["payload_json"]), "payload_json": None}
            for event in events
        ],
        "evidence": [dict(item) for item in evidence],
    }


@app.get("/devices", dependencies=[Depends(admin_auth)])
def list_devices() -> list[dict]:
    with database() as db:
        devices = db.execute("SELECT * FROM devices ORDER BY last_seen_at DESC").fetchall()
    return [{**dict(device), "tags": json.loads(device["tags_json"]), "tags_json": None} for device in devices]
