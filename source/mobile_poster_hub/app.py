"""Durable hub for Mobile Poster Agent."""
from __future__ import annotations

import base64
import binascii
import hashlib
import hmac
import json
import os
import sqlite3
import uuid
from contextlib import contextmanager
from datetime import UTC, datetime, timedelta
from pathlib import Path
from typing import Annotated, Literal

from fastapi import Cookie, Depends, FastAPI, Header, HTTPException, Request, Response, Security, status
from fastapi.responses import FileResponse, HTMLResponse
from fastapi.security import APIKeyHeader
from pydantic import BaseModel, Field, HttpUrl


DATA_DIR = Path(os.environ.get("HUB_DATA_DIR", Path(__file__).with_name("data"))).resolve()
DATABASE_PATH = DATA_DIR / "hub.sqlite3"
SCREENSHOTS_DIR = DATA_DIR / "screenshots"
QUEUE_MEDIA_DIR = Path(os.environ.get("FARM_QUEUE_MEDIA_DIR", DATA_DIR / "queue-media")).resolve()
RUNNER_TOKEN = os.environ.get("HUB_RUNNER_TOKEN", "")
ADMIN_TOKEN = os.environ.get("HUB_ADMIN_TOKEN", "")
DASHBOARD_PATH = Path(__file__).with_name("dashboard.html")
MAX_SCREENSHOT_BYTES = 8 * 1024 * 1024
LEASE_SECONDS = int(os.environ.get("HUB_LEASE_SECONDS", "900"))
RETRY_BASE_SECONDS = int(os.environ.get("HUB_RETRY_BASE_SECONDS", "30"))
RETRY_MAX_SECONDS = int(os.environ.get("HUB_RETRY_MAX_SECONDS", "3600"))
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

app = FastAPI(title="Mobile Poster Hub", version="0.2.0")
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
        db.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_jobs_idempotency ON jobs(idempotency_key) WHERE idempotency_key IS NOT NULL")
        db.execute("CREATE INDEX IF NOT EXISTS idx_jobs_dispatch ON jobs(status,next_retry_at,publish_at,created_at)")
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


@app.on_event("startup")
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
def dashboard_activity() -> dict:
    with database(write=False) as db:
        jobs = db.execute(
            """SELECT job_id,target,status,attempt_count,max_attempts,status_message,
                      created_at,updated_at,completed_at,publication_id
                 FROM jobs ORDER BY created_at DESC LIMIT 150"""
        ).fetchall()
        events = db.execute(
            """SELECT e.event_id,e.job_id,e.created_at,e.level,e.message,e.attempt_number,
                      e.screenshot_path,j.target,j.status
                 FROM events e JOIN jobs j ON j.job_id=e.job_id
                ORDER BY e.event_id DESC LIMIT 300"""
        ).fetchall()
        status_counts = {
            row["status"]: row["count"]
            for row in db.execute(
                "SELECT status,COUNT(*) AS count FROM jobs GROUP BY status"
            ).fetchall()
        }
        devices = db.execute(
            """SELECT device_id,label,automation_state,last_seen_at
                 FROM devices ORDER BY last_seen_at DESC"""
        ).fetchall()
    return {
        "generated_at": now(),
        "status_counts": status_counts,
        "devices": [dict(row) for row in devices],
        "jobs": [dict(row) for row in jobs],
        "events": [
            {
                **{key: row[key] for key in row.keys() if key != "screenshot_path"},
                "has_screenshot": bool(row["screenshot_path"]),
            }
            for row in events
        ],
    }


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
    with database(write=True) as db:
        existing = db.execute(
            "SELECT job_id,status,payload_fingerprint FROM jobs WHERE idempotency_key=?", (idempotency_key,)
        ).fetchone()
        if existing is not None:
            if existing["payload_fingerprint"] != fingerprint:
                raise HTTPException(status_code=409, detail="Idempotency-Key was already used with a different payload")
            return {"job_id": existing["job_id"], "status": existing["status"], "idempotent_replay": True}
        job_id = str(uuid.uuid4())
        db.execute(
            """INSERT INTO jobs(
                 job_id,target,caption,title,description,link,board,media_url,publish_at,
                 preferred_device_id,account_label,platform_account_label,assigned_device_id,status,status_message,
                 created_at,updated_at,idempotency_key,payload_fingerprint,attempt_count,max_attempts
               ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
            (
                job_id, job.target, job.caption, job.title, job.description,
                str(job.link) if job.link else None, job.board, str(job.media_url),
                normalize_time(job.publish_at), job.preferred_device_id, account_label, platform_account_label,
                None, "pending", None, timestamp, timestamp, idempotency_key, fingerprint, 0, job.max_attempts,
            ),
        )
    return {"job_id": job_id, "status": "pending", "idempotent_replay": False}


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
               ORDER BY COALESCE(publish_at, created_at), created_at LIMIT 1""",
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
