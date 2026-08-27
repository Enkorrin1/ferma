"""Turn files dropped into platform folders into idempotent Hub jobs.

Required environment variables are HUB_ADMIN_TOKEN and HUB_PUBLIC_BASE_URL.
The public base URL must route /queue-media back to this Hub host.
"""
from __future__ import annotations

import hashlib
import json
import os
import shutil
import time
import urllib.error
import urllib.request
from pathlib import Path


DATA_DIR = Path(os.environ.get("HUB_DATA_DIR", Path(__file__).with_name("data"))).resolve()
INBOX = Path(os.environ.get("FARM_INBOX_DIR", DATA_DIR / "inbox")).resolve()
MEDIA = Path(os.environ.get("FARM_QUEUE_MEDIA_DIR", DATA_DIR / "queue-media")).resolve()
HUB_LOCAL_URL = os.environ.get("HUB_LOCAL_URL", "http://127.0.0.1:18082").rstrip("/")
PUBLIC_BASE_URL = os.environ.get("HUB_PUBLIC_BASE_URL", "").rstrip("/")
ADMIN_TOKEN = os.environ.get("HUB_ADMIN_TOKEN", "")
POLL_SECONDS = max(2, int(os.environ.get("FARM_INBOX_POLL_SECONDS", "5")))

TARGETS = {
    "Pinterest": "pinterest_pin",
    "Instagram": "instagram_reel",
    "TikTok": "tiktok_post",
    "Threads": "threads_post",
    "YouTube": "youtube_short",
}
MEDIA_EXTENSIONS = {".png", ".jpg", ".jpeg", ".webp", ".mp4", ".mov"}


def ensure_layout() -> None:
    MEDIA.mkdir(parents=True, exist_ok=True)
    for platform in (*TARGETS, "YouTube"):
        for state in ("Incoming", "Queued", "Published", "NeedsReview"):
            (INBOX / platform / state).mkdir(parents=True, exist_ok=True)


def submit(platform: str, source: Path) -> None:
    content_hash = hashlib.sha256(source.read_bytes()).hexdigest()
    public_name = f"{content_hash}{source.suffix.lower()}"
    public_path = MEDIA / public_name
    if not public_path.exists():
        shutil.copy2(source, public_path)
    caption_path = source.with_suffix(".txt")
    caption = caption_path.read_text(encoding="utf-8-sig") if caption_path.exists() else ""
    payload = {
        "target": TARGETS[platform],
        "caption": caption,
        "media_url": f"{PUBLIC_BASE_URL}/queue-media/{public_name}",
        "account_label": os.environ.get("FARM_DEVICE_ACCOUNT_LABEL") or None,
        "platform_account_label": os.environ.get(f"FARM_{platform.upper()}_ACCOUNT") or None,
        "board": os.environ.get("FARM_PINTEREST_BOARD") if platform == "Pinterest" else None,
        "max_attempts": 5,
    }
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    request = urllib.request.Request(
        f"{HUB_LOCAL_URL}/jobs",
        data=body,
        method="POST",
        headers={
            "Content-Type": "application/json",
            "X-Hub-Token": ADMIN_TOKEN,
            "Idempotency-Key": f"folder-{platform.lower()}-{content_hash}",
        },
    )
    with urllib.request.urlopen(request, timeout=30) as response:
        result = json.loads(response.read().decode("utf-8"))
    destination = INBOX / platform / "Queued" / source.name
    source.replace(destination)
    if caption_path.exists():
        caption_path.replace(destination.with_suffix(".txt"))
    receipt = destination.with_name(destination.name + ".farm-job.json")
    receipt.write_text(
        json.dumps({"job_id": result["job_id"], "platform": platform, "media": destination.name}),
        encoding="utf-8",
    )


def reconcile_submitted() -> None:
    for platform in TARGETS:
        queued = INBOX / platform / "Queued"
        for receipt in queued.glob("*.farm-job.json"):
            try:
                metadata = json.loads(receipt.read_text(encoding="utf-8"))
                request = urllib.request.Request(
                    f"{HUB_LOCAL_URL}/jobs/{metadata['job_id']}",
                    headers={"X-Hub-Token": ADMIN_TOKEN},
                )
                with urllib.request.urlopen(request, timeout=15) as response:
                    status = json.loads(response.read().decode("utf-8"))["job"]["status"]
                if status == "succeeded":
                    state = "Published"
                elif status in {"ready_to_publish", "needs_review", "dead_letter"}:
                    state = "NeedsReview"
                else:
                    continue
                media = queued / metadata["media"]
                target = INBOX / platform / state
                if media.exists():
                    media.replace(target / media.name)
                caption = media.with_suffix(".txt")
                if caption.exists():
                    caption.replace(target / caption.name)
                receipt.replace(target / receipt.name)
            except (KeyError, OSError, ValueError, urllib.error.URLError):
                continue


def scan_once() -> None:
    if not ADMIN_TOKEN or not PUBLIC_BASE_URL.startswith("https://"):
        return
    reconcile_submitted()
    for platform in TARGETS:
        incoming = INBOX / platform / "Incoming"
        for source in sorted(incoming.iterdir()):
            if not source.is_file() or source.suffix.lower() not in MEDIA_EXTENSIONS:
                continue
            if time.time() - source.stat().st_mtime < 3:
                continue
            try:
                submit(platform, source)
            except (OSError, ValueError, urllib.error.URLError):
                # Keep the original available for inspection and a later retry.
                continue


def main() -> None:
    ensure_layout()
    while True:
        scan_once()
        time.sleep(POLL_SECONDS)


if __name__ == "__main__":
    main()
