import os
import sys
import tempfile
import unittest
from base64 import b64encode
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
HUB_ROOT = ROOT / "source" / "mobile_poster_hub"
TEST_TEMP_ROOT = ROOT / "artifacts" / "android-hub-contract-temp"
TEST_TEMP_ROOT.mkdir(parents=True, exist_ok=True)

os.environ["HUB_RUNNER_TOKEN"] = "contract-runner-token-0123456789"
os.environ["HUB_ADMIN_TOKEN"] = "contract-admin-token-0123456789"
os.environ["HUB_DATA_DIR"] = tempfile.mkdtemp(prefix="android-hub-import-", dir=TEST_TEMP_ROOT)
sys.path.insert(0, str(HUB_ROOT))

from fastapi.testclient import TestClient
import app as hub


class AndroidHubContractTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="android-hub-contract-", dir=TEST_TEMP_ROOT)
        hub.DATA_DIR = Path(self.temp.name)
        hub.DATABASE_PATH = hub.DATA_DIR / "hub.sqlite3"
        hub.SCREENSHOTS_DIR = hub.DATA_DIR / "screenshots"
        self.client = TestClient(hub.app)
        self.client.__enter__()
        self.runner_headers = {
            "X-Hub-Token": os.environ["HUB_RUNNER_TOKEN"],
            "X-Device-Id": "android-contract-agent",
        }
        self.admin_headers = {"X-Hub-Token": os.environ["HUB_ADMIN_TOKEN"]}

    def tearDown(self):
        self.client.__exit__(None, None, None)
        self.temp.cleanup()

    def test_claim_heartbeat_running_ready_and_evidence_contract(self):
        created = self.client.post(
            "/jobs",
            headers={**self.admin_headers, "Idempotency-Key": "android-contract-dry-run"},
            json={
                "target": "pinterest_dry_run",
                "caption": "offline contract only",
                "media_url": "https://example.com/contract.png",
                "preferred_device_id": "android-contract-agent",
            },
        )
        self.assertEqual(created.status_code, 201)
        job_id = created.json()["job_id"]
        registered = self.client.post(
            "/devices/register",
            headers=self.runner_headers,
            json={
                "device_id": "android-contract-agent",
                "platform": "android",
                "mode": "remote_agent",
                "label": "Offline Android contract",
            },
        )
        self.assertEqual(registered.status_code, 200)

        claimed = self.client.post(
            "/devices/android-contract-agent/claim-next",
            headers=self.runner_headers,
        )
        self.assertEqual(claimed.status_code, 200)
        job = claimed.json()["job"]
        self.assertTrue(job["lease_token"])
        self.assertTrue(job["lease_expires_at"])
        self.assertEqual(job["attempt_number"], 1)
        lease_headers = {**self.runner_headers, "X-Lease-Token": job["lease_token"]}

        heartbeat = self.client.post(f"/jobs/{job_id}/heartbeat", headers=lease_headers)
        self.assertEqual(heartbeat.status_code, 200)
        running = self.client.post(
            f"/jobs/{job_id}/status",
            headers=lease_headers,
            json={
                "status": "running",
                "message": "Android contract harness running",
                "retryable": True,
                "error_code": None,
                "publication_verified": False,
                "publication_id": None,
                "ui_state_verified": False,
                "evidence_id": None,
            },
        )
        self.assertEqual(running.json()["status"], "running")

        png = b"\x89PNG\r\n\x1a\nandroid-contract"
        screenshot_body = {
            "filename": "contract.png",
            "content_type": "image/png",
            "content_base64": b64encode(png).decode(),
        }
        screenshot = self.client.post(
            f"/jobs/{job_id}/screenshots", headers=lease_headers, json=screenshot_body
        )
        duplicate_screenshot = self.client.post(
            f"/jobs/{job_id}/screenshots", headers=lease_headers, json=screenshot_body
        )
        self.assertEqual(screenshot.status_code, 201)
        self.assertEqual(duplicate_screenshot.json()["evidence_id"], screenshot.json()["evidence_id"])
        self.assertTrue(duplicate_screenshot.json()["idempotent_replay"])

        event_body = {
            "level": "info",
            "message": "Android editor evidence",
            "payload": {"attempt": job["attempt_number"]},
            "screenshot_path": screenshot.json()["screenshot_path"],
            "event_key": "android-contract-editor-evidence",
        }
        evidence = self.client.post(
            f"/jobs/{job_id}/events", headers=lease_headers, json=event_body
        )
        duplicate_evidence = self.client.post(
            f"/jobs/{job_id}/events", headers=lease_headers, json=event_body
        )
        self.assertEqual(evidence.status_code, 201)
        self.assertEqual(duplicate_evidence.json()["event_id"], evidence.json()["event_id"])
        self.assertTrue(duplicate_evidence.json()["idempotent_replay"])

        stale = self.client.post(
            f"/jobs/{job_id}/heartbeat",
            headers={**self.runner_headers, "X-Lease-Token": "stale-token-000000"},
        )
        self.assertEqual(stale.status_code, 409)

        ready = self.client.post(
            f"/jobs/{job_id}/status",
            headers=lease_headers,
            json={
                "status": "ready_to_publish",
                "message": "Stopped before Publish by dry-run guard",
                "retryable": False,
                "error_code": None,
                "publication_verified": False,
                "publication_id": None,
                "ui_state_verified": True,
                "evidence_id": screenshot.json()["evidence_id"],
            },
        )
        self.assertEqual(ready.json()["status"], "ready_to_publish")
        late_success = self.client.post(
            f"/jobs/{job_id}/status",
            headers=lease_headers,
            json={
                "status": "succeeded",
                "message": "late report must be ignored",
                "retryable": False,
                "error_code": None,
                "publication_verified": True,
                "publication_id": "must-not-persist",
                "ui_state_verified": False,
                "evidence_id": None,
            },
        )
        self.assertEqual(late_success.status_code, 200)
        self.assertEqual(late_success.json()["status"], "ready_to_publish")
        self.assertTrue(late_success.json()["ignored_report"])

        persisted = self.client.get(f"/jobs/{job_id}", headers=self.admin_headers).json()
        self.assertEqual(persisted["job"]["status"], "ready_to_publish")
        self.assertNotIn(persisted["job"]["status"], {"succeeded", "published"})
        self.assertEqual(len(persisted["evidence"]), 1)
        self.assertIsNotNone(persisted["evidence"][0]["reported_at"])

    def test_unverified_ready_is_persisted_as_needs_review(self):
        created = self.client.post(
            "/jobs",
            headers={**self.admin_headers, "Idempotency-Key": "android-contract-needs-review"},
            json={
                "target": "pinterest_dry_run",
                "caption": "offline unverified contract only",
                "media_url": "https://example.com/unverified.png",
                "preferred_device_id": "android-contract-agent",
            },
        )
        self.assertEqual(created.status_code, 201)
        job_id = created.json()["job_id"]
        registered = self.client.post(
            "/devices/register",
            headers=self.runner_headers,
            json={
                "device_id": "android-contract-agent",
                "platform": "android",
                "mode": "remote_agent",
                "label": "Offline Android contract",
            },
        )
        self.assertEqual(registered.status_code, 200)
        claimed = self.client.post(
            "/devices/android-contract-agent/claim-next", headers=self.runner_headers
        )
        self.assertEqual(claimed.status_code, 200)
        job = claimed.json()["job"]
        lease_headers = {**self.runner_headers, "X-Lease-Token": job["lease_token"]}

        review = self.client.post(
            f"/jobs/{job_id}/status",
            headers=lease_headers,
            json={
                "status": "ready_to_publish",
                "message": "Editor screenshot unavailable",
                "retryable": False,
                "error_code": "ui_evidence_unavailable",
                "publication_verified": False,
                "publication_id": None,
                "ui_state_verified": False,
                "evidence_id": None,
            },
        )
        self.assertEqual(review.status_code, 200)
        self.assertEqual(review.json()["status"], "needs_review")
        persisted = self.client.get(f"/jobs/{job_id}", headers=self.admin_headers).json()
        self.assertEqual(persisted["job"]["status"], "needs_review")
        self.assertNotIn(persisted["job"]["status"], {"ready_to_publish", "succeeded", "published"})


if __name__ == "__main__":
    unittest.main()
