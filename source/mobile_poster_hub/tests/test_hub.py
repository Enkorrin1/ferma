import os
import sqlite3
import tempfile
import unittest
from base64 import b64encode
from contextlib import closing
from datetime import UTC, datetime, timedelta
from pathlib import Path

TEST_TEMP_ROOT = Path(__file__).resolve().parents[3] / "artifacts" / "hub-test-temp"
TEST_TEMP_ROOT.mkdir(parents=True, exist_ok=True)

os.environ["HUB_RUNNER_TOKEN"] = "runner-token-0123456789-abcdef"
os.environ["HUB_ADMIN_TOKEN"] = "admin-token-0123456789-abcdef"
os.environ["HUB_DATA_DIR"] = tempfile.mkdtemp(prefix="mobile-poster-hub-import-", dir=TEST_TEMP_ROOT)

from fastapi.testclient import TestClient

import app as hub


class HubFlowTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="mobile-poster-hub-test-", dir=TEST_TEMP_ROOT)
        hub.DATA_DIR = Path(self.temp.name)
        hub.DATABASE_PATH = hub.DATA_DIR / "hub.sqlite3"
        hub.SCREENSHOTS_DIR = hub.DATA_DIR / "screenshots"
        self.client = TestClient(hub.app)
        self.client.__enter__()
        self.runner_headers = {
            "X-Hub-Token": os.environ["HUB_RUNNER_TOKEN"],
            "X-Device-Id": "android-agent-smoke",
        }
        self.admin_headers = {"X-Hub-Token": os.environ["HUB_ADMIN_TOKEN"]}

    def tearDown(self):
        self.client.__exit__(None, None, None)
        self.temp.cleanup()

    def create_job(self, key="job-key-0001", **overrides):
        body = {
            "target": "tiktok_post",
            "caption": "smoke test",
            "media_url": "https://example.com/video.mp4",
            "preferred_device_id": "android-agent-smoke",
        }
        body.update(overrides)
        headers = {**self.admin_headers, "Idempotency-Key": key}
        return self.client.post("/jobs", headers=headers, json=body)

    def register_device(self):
        return self.client.post(
            "/devices/register",
            headers=self.runner_headers,
            json={
                "device_id": "android-agent-smoke",
                "platform": "android",
                "mode": "remote_agent",
                "label": "Smoke phone",
                "account_label": "Main_Account",
            },
        )

    def claim(self):
        return self.client.post("/devices/android-agent-smoke/claim-next", headers=self.runner_headers)

    def lease_headers(self, job):
        return {**self.runner_headers, "X-Lease-Token": job["lease_token"]}

    def job_detail(self, job_id):
        return self.client.get(f"/jobs/{job_id}", headers=self.admin_headers).json()

    def test_job_is_claimed_only_by_eligible_registered_device(self):
        created = self.create_job()
        self.assertEqual(created.status_code, 201)
        job_id = created.json()["job_id"]
        self.assertEqual(self.register_device().status_code, 200)

        claimed = self.claim()
        self.assertEqual(claimed.status_code, 200)
        job = claimed.json()["job"]
        self.assertEqual(job["job_id"], job_id)
        self.assertEqual(job["target"], "tiktok_post")
        self.assertEqual(job["attempt_number"], 1)
        self.assertTrue(job["lease_token"])

        completed = self.client.post(
            f"/jobs/{job_id}/status",
            headers=self.lease_headers(job),
            json={
                "status": "succeeded",
                "message": "smoke test complete",
                "publication_verified": True,
                "publication_id": "test-publication-id",
            },
        )
        self.assertEqual(completed.status_code, 200)
        self.assertEqual(completed.json()["status"], "succeeded")

        event = self.client.post(
            f"/jobs/{job_id}/events",
            headers=self.runner_headers,
            json={"level": "info", "message": "published", "payload": {"attempt": 1}},
        )
        duplicate = self.client.post(
            f"/jobs/{job_id}/events",
            headers=self.runner_headers,
            json={"level": "info", "message": "published", "payload": {"attempt": 1}},
        )
        self.assertEqual(event.status_code, 201)
        self.assertEqual(duplicate.json()["event_id"], event.json()["event_id"])
        self.assertTrue(duplicate.json()["idempotent_replay"])
        observed = self.job_detail(job_id)
        self.assertEqual(observed["job"]["status"], "succeeded")
        self.assertEqual(observed["events"][-1]["payload"], {"attempt": 1})

    def test_rejects_missing_token(self):
        self.assertEqual(self.client.post("/devices/register", json={}).status_code, 401)

    def test_rejects_status_from_a_device_other_than_the_assignee(self):
        job_id = self.create_job(key="wrong-device-job").json()["job_id"]
        self.register_device()
        self.claim()
        wrong_device_headers = {
            "X-Hub-Token": os.environ["HUB_RUNNER_TOKEN"],
            "X-Device-Id": "android-agent-other",
        }
        response = self.client.post(
            f"/jobs/{job_id}/status",
            headers=wrong_device_headers,
            json={"status": "succeeded", "publication_verified": True},
        )
        self.assertEqual(response.status_code, 403)

    def test_idempotency_key_replays_and_rejects_payload_conflict(self):
        first = self.create_job(key="stable-request-key")
        second = self.create_job(key="stable-request-key")
        conflict = self.create_job(
            key="stable-request-key",
            target="instagram_reel",
            media_url="https://example.com/other.mp4",
        )
        self.assertEqual(first.status_code, 201)
        self.assertEqual(second.status_code, 201)
        self.assertEqual(second.json()["job_id"], first.json()["job_id"])
        self.assertTrue(second.json()["idempotent_replay"])
        self.assertEqual(conflict.status_code, 409)
        self.assertEqual(len(self.client.get("/jobs", headers=self.admin_headers).json()), 1)

    def test_schedule_withholds_future_job(self):
        future = (datetime.now(UTC) + timedelta(hours=1)).isoformat()
        self.assertEqual(self.create_job(key="future-job", publish_at=future).status_code, 201)
        self.register_device()
        self.assertIsNone(self.claim().json()["job"])

    def test_heartbeat_renews_lease_and_rejects_stale_token(self):
        job_id = self.create_job(key="heartbeat-job").json()["job_id"]
        self.register_device()
        job = self.claim().json()["job"]
        renewed = self.client.post(
            f"/jobs/{job_id}/heartbeat", headers=self.lease_headers(job)
        )
        self.assertEqual(renewed.status_code, 200)
        self.assertGreaterEqual(renewed.json()["lease_expires_at"], job["lease_expires_at"])
        stale = self.client.post(
            f"/jobs/{job_id}/heartbeat",
            headers={**self.runner_headers, "X-Lease-Token": "stale-token-000000"},
        )
        self.assertEqual(stale.status_code, 409)

    def test_expired_lease_retries_then_dead_letters_at_max_attempts(self):
        job_id = self.create_job(key="lease-recovery-job", max_attempts=2).json()["job_id"]
        self.register_device()
        first = self.claim().json()["job"]
        self.assertEqual(first["attempt_number"], 1)

        expired = (datetime.now(UTC) - timedelta(seconds=1)).isoformat()
        with closing(sqlite3.connect(hub.DATABASE_PATH)) as db:
            db.execute("UPDATE jobs SET lease_expires_at=? WHERE job_id=?", (expired, job_id))
            db.commit()
        reconciled = self.client.post("/admin/reconcile", headers=self.admin_headers)
        self.assertEqual(reconciled.json()["leases_expired"], 1)
        detail = self.job_detail(job_id)["job"]
        self.assertEqual(detail["status"], "retry_wait")
        self.assertIsNotNone(detail["next_retry_at"])

        with closing(sqlite3.connect(hub.DATABASE_PATH)) as db:
            db.execute("UPDATE jobs SET next_retry_at=? WHERE job_id=?", (expired, job_id))
            db.commit()
        second = self.claim().json()["job"]
        self.assertEqual(second["attempt_number"], 2)

        with closing(sqlite3.connect(hub.DATABASE_PATH)) as db:
            db.execute("UPDATE jobs SET lease_expires_at=? WHERE job_id=?", (expired, job_id))
            db.commit()
        self.client.post("/admin/reconcile", headers=self.admin_headers)
        final = self.job_detail(job_id)["job"]
        self.assertEqual(final["status"], "dead_letter")
        self.assertIsNotNone(final["completed_at"])
        self.assertIsNone(self.claim().json()["job"])

    def test_failed_attempt_backoff_and_nonretryable_dead_letter(self):
        job_id = self.create_job(key="retryable-job", max_attempts=3).json()["job_id"]
        self.register_device()
        job = self.claim().json()["job"]
        failed = self.client.post(
            f"/jobs/{job_id}/status",
            headers=self.lease_headers(job),
            json={"status": "failed", "retryable": True, "error_code": "network_timeout"},
        )
        self.assertEqual(failed.json()["status"], "retry_wait")
        self.assertIsNotNone(failed.json()["next_retry_at"])

        other_id = self.create_job(key="permanent-job", max_attempts=3).json()["job_id"]
        other = self.claim().json()["job"]
        permanent = self.client.post(
            f"/jobs/{other_id}/status",
            headers=self.lease_headers(other),
            json={"status": "failed", "retryable": False, "error_code": "account_blocked"},
        )
        self.assertEqual(permanent.json()["status"], "dead_letter")
        duplicate = self.client.post(
            f"/jobs/{other_id}/status",
            headers=self.runner_headers,
            json={"status": "running"},
        )
        self.assertEqual(duplicate.status_code, 200)
        self.assertEqual(duplicate.json()["status"], "dead_letter")
        self.assertTrue(duplicate.json()["ignored_report"])

    def test_strict_transitions_terminal_reports_and_unknown_status(self):
        job_id = self.create_job(key="transition-job").json()["job_id"]
        self.register_device()
        job = self.claim().json()["job"]
        missing_proof = self.client.post(
            f"/jobs/{job_id}/status",
            headers=self.lease_headers(job),
            json={"status": "succeeded"},
        )
        self.assertEqual(missing_proof.status_code, 409)
        self.assertEqual(self.job_detail(job_id)["job"]["status"], "claimed")

        running = self.client.post(
            f"/jobs/{job_id}/status",
            headers=self.lease_headers(job),
            json={"status": "running"},
        )
        self.assertEqual(running.json()["status"], "running")
        missing_publication_id = self.client.post(
            f"/jobs/{job_id}/status",
            headers=self.lease_headers(job),
            json={"status": "succeeded", "publication_verified": True},
        )
        self.assertEqual(missing_publication_id.status_code, 409)
        succeeded = self.client.post(
            f"/jobs/{job_id}/status",
            headers=self.lease_headers(job),
            json={
                "status": "succeeded",
                "publication_verified": True,
                "publication_id": "tiktok:strict-transition-proof",
            },
        )
        self.assertEqual(succeeded.json()["status"], "succeeded")
        duplicate = self.client.post(
            f"/jobs/{job_id}/status",
            headers=self.runner_headers,
            json={"status": "failed"},
        )
        self.assertEqual(duplicate.status_code, 200)
        self.assertEqual(duplicate.json()["status"], "succeeded")
        self.assertTrue(duplicate.json()["ignored_report"])
        unknown = self.client.post(
            f"/jobs/{job_id}/status",
            headers=self.runner_headers,
            json={"status": "mystery"},
        )
        self.assertEqual(unknown.status_code, 422)
        self.assertEqual(self.job_detail(job_id)["job"]["status"], "succeeded")

    def test_pinterest_pin_verify_reports_only_verified_existing_publication(self):
        created = self.create_job(
            key="pinterest-pin-verify-job",
            target="pinterest_pin_verify",
            media_url="https://example.com/existing-pin.png",
        )
        self.assertEqual(created.status_code, 201)
        job_id = created.json()["job_id"]
        self.register_device()
        job = self.claim().json()["job"]
        self.assertEqual(job["target"], "pinterest_pin_verify")

        running = self.client.post(
            f"/jobs/{job_id}/status",
            headers=self.lease_headers(job),
            json={"status": "running", "message": "Read-only publication verification"},
        )
        self.assertEqual(running.status_code, 200)

        unverified = self.client.post(
            f"/jobs/{job_id}/status",
            headers=self.lease_headers(job),
            json={"status": "succeeded", "publication_verified": False},
        )
        self.assertEqual(unverified.status_code, 409)
        self.assertEqual(self.job_detail(job_id)["job"]["status"], "running")

        verified = self.client.post(
            f"/jobs/{job_id}/status",
            headers=self.lease_headers(job),
            json={
                "status": "succeeded",
                "publication_verified": True,
                "publication_id": "pinterest:existing-pin-proof",
            },
        )
        self.assertEqual(verified.status_code, 200)
        detail = self.job_detail(job_id)["job"]
        self.assertEqual(detail["status"], "succeeded")
        self.assertEqual(detail["publication_id"], "pinterest:existing-pin-proof")

    def test_ready_to_publish_dry_run_never_becomes_succeeded(self):
        created = self.create_job(
            key="dry-run-job",
            target="pinterest_dry_run",
            media_url="https://example.com/dry-run.png",
        )
        self.assertEqual(created.status_code, 201)
        job_id = created.json()["job_id"]
        self.register_device()
        job = self.claim().json()["job"]
        self.assertEqual(job["target"], "pinterest_dry_run")
        heartbeat = self.client.post(
            f"/jobs/{job_id}/heartbeat", headers=self.lease_headers(job)
        )
        self.assertEqual(heartbeat.status_code, 200)
        running = self.client.post(
            f"/jobs/{job_id}/status",
            headers=self.lease_headers(job),
            json={"status": "running", "message": "dry-run automation active"},
        )
        self.assertEqual(running.json()["status"], "running")
        forbidden_success = self.client.post(
            f"/jobs/{job_id}/status",
            headers=self.lease_headers(job),
            json={"status": "succeeded", "publication_verified": True},
        )
        self.assertEqual(forbidden_success.status_code, 409)
        self.assertEqual(self.job_detail(job_id)["job"]["status"], "running")
        ready = self.client.post(
            f"/jobs/{job_id}/status",
            headers=self.lease_headers(job),
            json={"status": "ready_to_publish", "message": "dry-run stopped before publish"},
        )
        self.assertEqual(ready.json()["status"], "needs_review")
        self.assertIn("ui_state_verified", ready.json()["review_reason"])
        late_success = self.client.post(
            f"/jobs/{job_id}/status",
            headers=self.runner_headers,
            json={"status": "succeeded", "publication_verified": True},
        )
        self.assertEqual(late_success.status_code, 200)
        self.assertEqual(late_success.json()["status"], "needs_review")
        self.assertTrue(late_success.json()["ignored_report"])
        persisted = self.job_detail(job_id)["job"]
        self.assertEqual(persisted["target"], "pinterest_dry_run")
        self.assertEqual(persisted["status"], "needs_review")

    def test_social_dry_run_account_is_required_canonical_and_claimed_exactly(self):
        for index, target in enumerate(("instagram_reel_dry_run", "tiktok_post_dry_run"), start=1):
            for suffix, value in (("missing", None), ("empty", ""), ("space", "   ")):
                overrides = {"target": target, "media_url": f"https://example.com/{target}.png"}
                if value is not None:
                    overrides["account_label"] = value
                rejected = self.create_job(key=f"social-account-{index}-{suffix}", **overrides)
                self.assertEqual(rejected.status_code, 422)

        self.register_device()
        body = {
            "target": "instagram_reel_dry_run",
            "media_url": "https://example.com/account.png",
            "account_label": "  Main_Account  ",
            "platform_account_label": "  pinv768  ",
        }
        first = self.create_job(key="social-account-canonical", **body)
        replay = self.create_job(
            key="social-account-canonical",
            **{**body, "account_label": "Main_Account", "platform_account_label": "pinv768"},
        )
        conflict = self.create_job(
            key="social-account-canonical",
            **{**body, "account_label": "main_account"},
        )
        self.assertEqual(first.status_code, 201)
        self.assertTrue(replay.json()["idempotent_replay"])
        self.assertEqual(conflict.status_code, 409)
        platform_conflict = self.create_job(
            key="social-account-canonical",
            **{**body, "platform_account_label": "PinV768"},
        )
        self.assertEqual(platform_conflict.status_code, 409)
        claimed = self.claim().json()["job"]
        self.assertEqual(claimed["account_label"], "Main_Account")
        self.assertEqual(claimed["platform_account_label"], "pinv768")
        with hub.database() as db:
            row = db.execute(
                "SELECT account_label,platform_account_label FROM jobs WHERE job_id=?",
                (first.json()["job_id"],),
            ).fetchone()
        self.assertEqual(row["account_label"], "Main_Account")
        self.assertEqual(row["platform_account_label"], "pinv768")

        pinterest = self.create_job(
            key="pinterest-account-compat",
            target="pinterest_dry_run",
            media_url="https://example.com/pinterest.png",
        )
        self.assertEqual(pinterest.status_code, 201)

    def test_all_dry_run_targets_require_verified_current_attempt_evidence(self):
        self.register_device()
        targets = ("pinterest_dry_run", "instagram_reel_dry_run", "tiktok_post_dry_run")
        for index, target in enumerate(targets, start=1):
            with self.subTest(target=target):
                created = self.create_job(
                    key=f"multi-dry-run-{index}",
                    target=target,
                    media_url=f"https://example.com/{target}.png",
                    **({"account_label": "Main_Account"} if target != "pinterest_dry_run" else {}),
                )
                self.assertEqual(created.status_code, 201)
                job_id = created.json()["job_id"]
                job = self.claim().json()["job"]
                self.assertEqual(job["target"], target)
                heartbeat = self.client.post(
                    f"/jobs/{job_id}/heartbeat", headers=self.lease_headers(job)
                )
                self.assertEqual(heartbeat.status_code, 200)
                running = self.client.post(
                    f"/jobs/{job_id}/status",
                    headers=self.lease_headers(job),
                    json={"status": "running", "message": "dry-run automation active"},
                )
                self.assertEqual(running.json()["status"], "running")
                forbidden_success = self.client.post(
                    f"/jobs/{job_id}/status",
                    headers=self.lease_headers(job),
                    json={
                        "status": "succeeded",
                        "publication_verified": True,
                        "publication_id": f"forbidden-{index}",
                    },
                )
                self.assertEqual(forbidden_success.status_code, 409)
                self.assertEqual(self.job_detail(job_id)["job"]["status"], "running")
                png = b"\x89PNG\r\n\x1a\n" + target.encode()
                evidence = self.client.post(
                    f"/jobs/{job_id}/screenshots",
                    headers=self.lease_headers(job),
                    json={
                        "filename": f"{target}-editor.png",
                        "content_type": "image/png",
                        "content_base64": b64encode(png).decode(),
                    },
                )
                self.assertEqual(evidence.status_code, 201)
                ready = self.client.post(
                    f"/jobs/{job_id}/status",
                    headers=self.lease_headers(job),
                    json={
                        "status": "ready_to_publish",
                        "message": "stopped before final action",
                        "ui_state_verified": True,
                        "evidence_id": evidence.json()["evidence_id"],
                    },
                )
                self.assertEqual(ready.status_code, 200)
                self.assertEqual(ready.json()["status"], "ready_to_publish")
                late_success = self.client.post(
                    f"/jobs/{job_id}/status",
                    headers=self.runner_headers,
                    json={
                        "status": "succeeded",
                        "publication_verified": True,
                        "publication_id": f"late-{index}",
                    },
                )
                self.assertEqual(late_success.status_code, 200)
                self.assertTrue(late_success.json()["ignored_report"])
                persisted = self.job_detail(job_id)["job"]
                self.assertEqual(persisted["target"], target)
                self.assertEqual(persisted["status"], "ready_to_publish")
                self.assertIsNone(persisted["publication_id"])

    def test_verified_current_attempt_evidence_allows_ready_and_clears_stale_error(self):
        job_id = self.create_job(
            key="verified-ready-job",
            target="pinterest_dry_run",
            media_url="https://example.com/verified.png",
        ).json()["job_id"]
        self.register_device()
        job = self.claim().json()["job"]
        png = b"\x89PNG\r\n\x1a\n" + b"verified-editor"
        evidence = self.client.post(
            f"/jobs/{job_id}/screenshots",
            headers=self.lease_headers(job),
            json={
                "filename": "verified-editor.png",
                "content_type": "image/png",
                "content_base64": b64encode(png).decode(),
            },
        ).json()
        with closing(sqlite3.connect(hub.DATABASE_PATH)) as db:
            db.execute(
                """UPDATE jobs SET last_error_code='stale_error',status_message='stale failure',
                   next_retry_at='2026-01-01T00:00:00+00:00' WHERE job_id=?""",
                (job_id,),
            )
            db.commit()
        ready = self.client.post(
            f"/jobs/{job_id}/status",
            headers=self.lease_headers(job),
            json={
                "status": "ready_to_publish",
                "message": "editor verified",
                "ui_state_verified": True,
                "evidence_id": evidence["evidence_id"],
            },
        )
        self.assertEqual(ready.status_code, 200)
        self.assertEqual(ready.json()["status"], "ready_to_publish")
        self.assertEqual(ready.json()["evidence_id"], evidence["evidence_id"])
        detail = self.job_detail(job_id)
        persisted = detail["job"]
        self.assertEqual(persisted["status_message"], "editor verified")
        self.assertIsNone(persisted["last_error_code"])
        self.assertIsNone(persisted["next_retry_at"])
        self.assertEqual(detail["events"][-1]["screenshot_path"], evidence["screenshot_path"])
        self.assertEqual(detail["events"][-1]["payload"]["ui_state_verified"], True)

    def test_ready_rejects_stale_or_other_attempt_evidence_as_needs_review(self):
        job_id = self.create_job(
            key="stale-evidence-job",
            target="pinterest_dry_run",
            media_url="https://example.com/stale.png",
        ).json()["job_id"]
        self.register_device()
        job = self.claim().json()["job"]
        reviewed = self.client.post(
            f"/jobs/{job_id}/status",
            headers=self.lease_headers(job),
            json={
                "status": "ready_to_publish",
                "ui_state_verified": True,
                "evidence_id": "evidence-not-current",
            },
        )
        self.assertEqual(reviewed.status_code, 200)
        self.assertEqual(reviewed.json()["status"], "needs_review")
        self.assertIn("current attempt", reviewed.json()["review_reason"])

    def test_admin_reopen_is_same_job_bounded_audited_and_idempotent(self):
        job_id = self.create_job(
            key="reopen-dry-job",
            target="pinterest_dry_run",
            media_url="https://example.com/reopen.png",
        ).json()["job_id"]
        self.register_device()
        job = self.claim().json()["job"]
        reviewed = self.client.post(
            f"/jobs/{job_id}/status",
            headers=self.lease_headers(job),
            json={"status": "ready_to_publish", "ui_state_verified": False},
        )
        self.assertEqual(reviewed.json()["status"], "needs_review")
        headers = {**self.admin_headers, "Idempotency-Key": "reopen-review-0001"}
        body = {"reason": "Capture permission was unavailable during the verified run"}
        self.assertEqual(
            self.client.post(f"/admin/jobs/{job_id}/reopen", headers=self.runner_headers, json=body).status_code,
            401,
        )
        reopened = self.client.post(f"/admin/jobs/{job_id}/reopen", headers=headers, json=body)
        replay = self.client.post(f"/admin/jobs/{job_id}/reopen", headers=headers, json=body)
        self.assertEqual(reopened.status_code, 200)
        self.assertEqual(reopened.json()["job_id"], job_id)
        self.assertEqual(reopened.json()["status"], "retry_wait")
        self.assertEqual(reopened.json()["max_attempts"], 2)
        self.assertFalse(reopened.json()["idempotent_replay"])
        self.assertTrue(replay.json()["idempotent_replay"])
        self.assertEqual(replay.json()["action_id"], reopened.json()["action_id"])
        conflict = self.client.post(
            f"/admin/jobs/{job_id}/reopen",
            headers=headers,
            json={"reason": "A different reason must conflict with the same key"},
        )
        self.assertEqual(conflict.status_code, 409)
        detail = self.job_detail(job_id)
        self.assertEqual(detail["job"]["job_id"], job_id)
        self.assertEqual(detail["job"]["attempt_count"], 1)
        self.assertEqual(detail["job"]["max_attempts"], 2)
        self.assertEqual(sum(event["message"] == "admin_reopen" for event in detail["events"]), 1)
        self.assertEqual(len(self.client.get("/jobs", headers=self.admin_headers).json()), 1)

        real_id = self.create_job(
            key="reopen-real-job",
            target="pinterest_pin",
            media_url="https://example.com/real.png",
        ).json()["job_id"]
        with closing(sqlite3.connect(hub.DATABASE_PATH)) as db:
            db.execute("UPDATE jobs SET status='succeeded' WHERE job_id=?", (real_id,))
            db.commit()
        forbidden = self.client.post(
            f"/admin/jobs/{real_id}/reopen",
            headers={**self.admin_headers, "Idempotency-Key": "reopen-real-0001"},
            json={"reason": "Real publication must never be reopened through dry-run recovery"},
        )
        self.assertEqual(forbidden.status_code, 409)

    def test_admin_reopen_accepts_all_approved_dry_run_targets(self):
        for index, target in enumerate(
            ("pinterest_dry_run", "instagram_reel_dry_run", "tiktok_post_dry_run"), start=1
        ):
            overrides = {
                "target": target,
                "media_url": f"https://example.com/reopen-{index}.png",
            }
            if target in {"instagram_reel_dry_run", "tiktok_post_dry_run"}:
                overrides["account_label"] = "Main_Account"
            created = self.create_job(key=f"reopen-approved-dry-{index}", **overrides)
            job_id = created.json()["job_id"]
            with hub.database(write=True) as db:
                db.execute(
                    "UPDATE jobs SET status='needs_review',attempt_count=1,max_attempts=1 WHERE job_id=?",
                    (job_id,),
                )
            headers = {**self.admin_headers, "Idempotency-Key": f"reopen-approved-action-{index}"}
            body = {"reason": "Approved dry-run recovery preserves the same job and its history"}
            reopened = self.client.post(f"/admin/jobs/{job_id}/reopen", headers=headers, json=body)
            replay = self.client.post(f"/admin/jobs/{job_id}/reopen", headers=headers, json=body)
            self.assertEqual(reopened.status_code, 200)
            self.assertFalse(reopened.json()["idempotent_replay"])
            self.assertTrue(replay.json()["idempotent_replay"])
            self.assertEqual(reopened.json()["job_id"], job_id)
            self.assertEqual(reopened.json()["max_attempts"], 2)

    def test_admin_reopen_allows_guarded_retryable_dead_letter(self):
        job_id = self.create_job(
            key="reopen-dead-letter-retryable",
            target="pinterest_dry_run",
            media_url="https://example.com/retryable-dead-letter.png",
        ).json()["job_id"]
        self.register_device()
        job = self.claim().json()["job"]
        with closing(sqlite3.connect(hub.DATABASE_PATH)) as db:
            db.execute("UPDATE jobs SET max_attempts=1 WHERE job_id=?", (job_id,))
            db.commit()
        failed = self.client.post(
            f"/jobs/{job_id}/status",
            headers=self.lease_headers(job),
            json={
                "status": "failed",
                "message": "Transient accessibility timeout",
                "error_code": "accessibility_timeout",
                "retryable": True,
            },
        )
        self.assertEqual(failed.json()["status"], "dead_letter")
        before = self.job_detail(job_id)
        headers = {**self.admin_headers, "Idempotency-Key": "reopen-dead-letter-0001"}
        body = {
            "target_status": "pending",
            "reason": "Accessibility timeout was retryable and the device overlay has been fixed",
        }
        reopened = self.client.post(f"/admin/jobs/{job_id}/reopen", headers=headers, json=body)
        replay = self.client.post(f"/admin/jobs/{job_id}/reopen", headers=headers, json=body)
        self.assertEqual(reopened.status_code, 200)
        self.assertFalse(reopened.json()["idempotent_replay"])
        self.assertTrue(replay.json()["idempotent_replay"])
        self.assertEqual(reopened.json()["job_id"], job_id)
        self.assertEqual(reopened.json()["max_attempts"], 2)
        detail = self.job_detail(job_id)
        self.assertEqual(detail["job"]["status"], "pending")
        self.assertEqual(detail["job"]["attempt_count"], 1)
        self.assertEqual(detail["job"]["last_error_code"], None)
        self.assertEqual(len(detail["events"]), len(before["events"]) + 1)
        self.assertEqual(detail["events"][:-1], before["events"])
        self.assertEqual(detail["evidence"], before["evidence"])
        self.assertEqual(sum(event["message"] == "admin_reopen" for event in detail["events"]), 1)
        self.assertEqual(len(self.client.get("/jobs", headers=self.admin_headers).json()), 1)

    def test_admin_reopen_allows_startup_reconciled_lease_expiry_dead_letter(self):
        job_id = self.create_job(
            key="reopen-startup-lease-expired",
            target="pinterest_dry_run",
            media_url="https://example.com/startup-lease-expired.png",
            max_attempts=1,
        ).json()["job_id"]
        self.register_device()
        claimed = self.claim().json()["job"]
        expired = (datetime.now(UTC) - timedelta(seconds=1)).isoformat()
        with closing(sqlite3.connect(hub.DATABASE_PATH)) as db:
            db.execute("UPDATE jobs SET lease_expires_at=? WHERE job_id=?", (expired, job_id))
            db.commit()

        hub.initialize_database()
        before = self.job_detail(job_id)
        self.assertEqual(before["job"]["status"], "dead_letter")
        self.assertEqual(before["job"]["last_error_code"], "lease_expired")
        self.assertEqual(before["events"][-1]["message"], "lease_expired")
        self.assertEqual(before["events"][-1]["attempt_number"], claimed["attempt_number"])

        headers = {**self.admin_headers, "Idempotency-Key": "reopen-startup-lease-expired-0001"}
        body = {
            "target_status": "pending",
            "reason": "Startup reconciliation proved the current dry-run lease expired without publication",
        }
        reopened = self.client.post(f"/admin/jobs/{job_id}/reopen", headers=headers, json=body)
        replay = self.client.post(f"/admin/jobs/{job_id}/reopen", headers=headers, json=body)
        self.assertEqual(reopened.status_code, 200)
        self.assertFalse(reopened.json()["idempotent_replay"])
        self.assertTrue(replay.json()["idempotent_replay"])
        self.assertEqual(reopened.json()["max_attempts"], 2)
        detail = self.job_detail(job_id)
        self.assertEqual(detail["job"]["status"], "pending")
        self.assertEqual(detail["job"]["last_error_code"], None)
        self.assertEqual(detail["events"][:-1], before["events"])
        self.assertEqual(sum(event["message"] == "admin_reopen" for event in detail["events"]), 1)

    def test_admin_reopen_rejects_missing_or_forged_lease_expiry_recovery(self):
        self.register_device()

        def reconciled_dead_letter(case):
            job_id = self.create_job(
                key=f"reopen-lease-proof-{case}",
                target="pinterest_dry_run",
                media_url=f"https://example.com/reopen-lease-proof-{case}.png",
                max_attempts=1,
            ).json()["job_id"]
            job = self.claim().json()["job"]
            expired = (datetime.now(UTC) - timedelta(seconds=1)).isoformat()
            with closing(sqlite3.connect(hub.DATABASE_PATH)) as db:
                db.execute("UPDATE jobs SET lease_expires_at=? WHERE job_id=?", (expired, job_id))
                db.commit()
            hub.initialize_database()
            return job_id, job["attempt_number"]

        for case in ("missing", "payload", "event-key"):
            with self.subTest(case=case):
                job_id, attempt = reconciled_dead_letter(case)
                with closing(sqlite3.connect(hub.DATABASE_PATH)) as db:
                    if case == "missing":
                        db.execute(
                            "DELETE FROM events WHERE job_id=? AND attempt_number=? AND message='lease_expired'",
                            (job_id, attempt),
                        )
                    elif case == "payload":
                        db.execute(
                            """UPDATE events SET payload_json=?
                               WHERE job_id=? AND attempt_number=? AND message='lease_expired'""",
                            ('{"attempt":999,"outcome":"dead_letter","next_retry_at":null}', job_id, attempt),
                        )
                    else:
                        db.execute(
                            """UPDATE events SET event_key='forged:lease:key'
                               WHERE job_id=? AND attempt_number=? AND message='lease_expired'""",
                            (job_id, attempt),
                        )
                    db.commit()
                response = self.client.post(
                    f"/admin/jobs/{job_id}/reopen",
                    headers={**self.admin_headers, "Idempotency-Key": f"reopen-lease-proof-{case}-0001"},
                    json={"reason": "Forged or missing recovery evidence must never authorize a reopen"},
                )
                self.assertEqual(response.status_code, 409)

    def test_admin_reopen_rejects_unguarded_dead_letter_and_publication_proof(self):
        self.register_device()

        def make_dead_letter(key, error_code, retryable):
            job_id = self.create_job(
                key=key,
                target="pinterest_dry_run",
                media_url=f"https://example.com/{key}.png",
            ).json()["job_id"]
            job = self.claim().json()["job"]
            with closing(sqlite3.connect(hub.DATABASE_PATH)) as db:
                db.execute("UPDATE jobs SET max_attempts=1 WHERE job_id=?", (job_id,))
                db.commit()
            response = self.client.post(
                f"/jobs/{job_id}/status",
                headers=self.lease_headers(job),
                json={"status": "failed", "error_code": error_code, "retryable": retryable},
            )
            self.assertEqual(response.json()["status"], "dead_letter")
            return job_id

        nonretryable_id = make_dead_letter("reopen-nonretryable", "permission_denied", False)
        forbidden_nonretryable = self.client.post(
            f"/admin/jobs/{nonretryable_id}/reopen",
            headers={**self.admin_headers, "Idempotency-Key": "reopen-nonretryable-0001"},
            json={"reason": "A nonretryable failure must remain terminal"},
        )
        self.assertEqual(forbidden_nonretryable.status_code, 409)

        proof_id = make_dead_letter("reopen-publication-proof", "accessibility_timeout", True)
        with closing(sqlite3.connect(hub.DATABASE_PATH)) as db:
            db.execute("UPDATE jobs SET publication_id='proof-present' WHERE job_id=?", (proof_id,))
            db.commit()
        forbidden_proof = self.client.post(
            f"/admin/jobs/{proof_id}/reopen",
            headers={**self.admin_headers, "Idempotency-Key": "reopen-proof-0001"},
            json={"reason": "Publication proof must make recovery impossible"},
        )
        self.assertEqual(forbidden_proof.status_code, 409)

        succeeded_history_id = make_dead_letter(
            "reopen-succeeded-history", "accessibility_timeout", True
        )
        with hub.database(write=True) as db:
            hub.insert_event(
                db,
                succeeded_history_id,
                "info",
                "succeeded",
                {"publication_verified": True},
                None,
                1,
                "synthetic-succeeded-proof",
            )
        forbidden_history = self.client.post(
            f"/admin/jobs/{succeeded_history_id}/reopen",
            headers={**self.admin_headers, "Idempotency-Key": "reopen-succeeded-history-0001"},
            json={"reason": "Succeeded history must make recovery impossible"},
        )
        self.assertEqual(forbidden_history.status_code, 409)

        capped_id = make_dead_letter("reopen-hard-cap", "accessibility_timeout", True)
        with closing(sqlite3.connect(hub.DATABASE_PATH)) as db:
            db.execute(
                """UPDATE jobs SET attempt_count=20,max_attempts=20 WHERE job_id=?""",
                (capped_id,),
            )
            db.execute(
                """UPDATE events SET attempt_number=20,payload_json=?
                   WHERE job_id=? AND message='attempt_failed'""",
                (
                    '{"attempt":20,"error_code":"accessibility_timeout",'
                    '"retryable":true,"outcome":"dead_letter","next_retry_at":null}',
                    capped_id,
                ),
            )
            db.commit()
        allowed_cap_extension = self.client.post(
            f"/admin/jobs/{capped_id}/reopen",
            headers={**self.admin_headers, "Idempotency-Key": "reopen-hard-cap-0001"},
            json={"reason": "Allow the single guarded dry-run recovery attempt from twenty to twenty-one"},
        )
        self.assertEqual(allowed_cap_extension.status_code, 200)
        self.assertFalse(allowed_cap_extension.json()["idempotent_replay"])
        self.assertEqual(allowed_cap_extension.json()["max_attempts"], 21)
        replay_cap_extension = self.client.post(
            f"/admin/jobs/{capped_id}/reopen",
            headers={**self.admin_headers, "Idempotency-Key": "reopen-hard-cap-0001"},
            json={"reason": "Allow the single guarded dry-run recovery attempt from twenty to twenty-one"},
        )
        self.assertEqual(replay_cap_extension.status_code, 200)
        self.assertTrue(replay_cap_extension.json()["idempotent_replay"])
        with closing(sqlite3.connect(hub.DATABASE_PATH)) as db:
            db.execute(
                "UPDATE jobs SET status='needs_review',attempt_count=21,max_attempts=21 WHERE job_id=?",
                (capped_id,),
            )
            db.commit()
        allowed_cap_22 = self.client.post(
            f"/admin/jobs/{capped_id}/reopen",
            headers={**self.admin_headers, "Idempotency-Key": "reopen-hard-cap-0002"},
            json={"reason": "Allow the final guarded dry-run recovery attempt from twenty-one to twenty-two"},
        )
        self.assertEqual(allowed_cap_22.status_code, 200)
        self.assertFalse(allowed_cap_22.json()["idempotent_replay"])
        self.assertEqual(allowed_cap_22.json()["max_attempts"], 22)
        replay_cap_22 = self.client.post(
            f"/admin/jobs/{capped_id}/reopen",
            headers={**self.admin_headers, "Idempotency-Key": "reopen-hard-cap-0002"},
            json={"reason": "Allow the final guarded dry-run recovery attempt from twenty-one to twenty-two"},
        )
        self.assertEqual(replay_cap_22.status_code, 200)
        self.assertTrue(replay_cap_22.json()["idempotent_replay"])
        with closing(sqlite3.connect(hub.DATABASE_PATH)) as db:
            db.execute(
                "UPDATE jobs SET status='needs_review',attempt_count=22,max_attempts=22 WHERE job_id=?",
                (capped_id,),
            )
            db.commit()
        allowed_cap_23 = self.client.post(
            f"/admin/jobs/{capped_id}/reopen",
            headers={**self.admin_headers, "Idempotency-Key": "reopen-hard-cap-0003"},
            json={"reason": "Allow one guarded dry-run recovery attempt from twenty-two to twenty-three"},
        )
        self.assertEqual(allowed_cap_23.status_code, 200)
        self.assertFalse(allowed_cap_23.json()["idempotent_replay"])
        self.assertEqual(allowed_cap_23.json()["max_attempts"], 23)
        replay_cap_23 = self.client.post(
            f"/admin/jobs/{capped_id}/reopen",
            headers={**self.admin_headers, "Idempotency-Key": "reopen-hard-cap-0003"},
            json={"reason": "Allow one guarded dry-run recovery attempt from twenty-two to twenty-three"},
        )
        self.assertEqual(replay_cap_23.status_code, 200)
        self.assertTrue(replay_cap_23.json()["idempotent_replay"])
        with closing(sqlite3.connect(hub.DATABASE_PATH)) as db:
            db.execute(
                "UPDATE jobs SET status='needs_review',attempt_count=23,max_attempts=23 WHERE job_id=?",
                (capped_id,),
            )
            db.commit()
        allowed_cap_24 = self.client.post(
            f"/admin/jobs/{capped_id}/reopen",
            headers={**self.admin_headers, "Idempotency-Key": "reopen-hard-cap-0004"},
            json={"reason": "Allow one guarded dry-run recovery attempt from twenty-three to twenty-four"},
        )
        self.assertEqual(allowed_cap_24.status_code, 200)
        self.assertFalse(allowed_cap_24.json()["idempotent_replay"])
        self.assertEqual(allowed_cap_24.json()["max_attempts"], 24)
        replay_cap_24 = self.client.post(
            f"/admin/jobs/{capped_id}/reopen",
            headers={**self.admin_headers, "Idempotency-Key": "reopen-hard-cap-0004"},
            json={"reason": "Allow one guarded dry-run recovery attempt from twenty-three to twenty-four"},
        )
        self.assertEqual(replay_cap_24.status_code, 200)
        self.assertTrue(replay_cap_24.json()["idempotent_replay"])
        with closing(sqlite3.connect(hub.DATABASE_PATH)) as db:
            db.execute(
                "UPDATE jobs SET status='needs_review',attempt_count=24,max_attempts=24 WHERE job_id=?",
                (capped_id,),
            )
            db.commit()
        allowed_cap_25 = self.client.post(
            f"/admin/jobs/{capped_id}/reopen",
            headers={**self.admin_headers, "Idempotency-Key": "reopen-hard-cap-0005"},
            json={"reason": "Allow one guarded dry-run recovery attempt from twenty-four to twenty-five"},
        )
        self.assertEqual(allowed_cap_25.status_code, 200)
        self.assertFalse(allowed_cap_25.json()["idempotent_replay"])
        self.assertEqual(allowed_cap_25.json()["max_attempts"], 25)
        replay_cap_25 = self.client.post(
            f"/admin/jobs/{capped_id}/reopen",
            headers={**self.admin_headers, "Idempotency-Key": "reopen-hard-cap-0005"},
            json={"reason": "Allow one guarded dry-run recovery attempt from twenty-four to twenty-five"},
        )
        self.assertEqual(replay_cap_25.status_code, 200)
        self.assertTrue(replay_cap_25.json()["idempotent_replay"])
        with closing(sqlite3.connect(hub.DATABASE_PATH)) as db:
            db.execute(
                "UPDATE jobs SET status='needs_review',attempt_count=25,max_attempts=25 WHERE job_id=?",
                (capped_id,),
            )
            db.commit()
        allowed_cap_26 = self.client.post(
            f"/admin/jobs/{capped_id}/reopen",
            headers={**self.admin_headers, "Idempotency-Key": "reopen-hard-cap-0006"},
            json={"reason": "Allow one guarded dry-run recovery attempt from twenty-five to twenty-six"},
        )
        self.assertEqual(allowed_cap_26.status_code, 200)
        self.assertFalse(allowed_cap_26.json()["idempotent_replay"])
        self.assertEqual(allowed_cap_26.json()["max_attempts"], 26)
        replay_cap_26 = self.client.post(
            f"/admin/jobs/{capped_id}/reopen",
            headers={**self.admin_headers, "Idempotency-Key": "reopen-hard-cap-0006"},
            json={"reason": "Allow one guarded dry-run recovery attempt from twenty-five to twenty-six"},
        )
        self.assertEqual(replay_cap_26.status_code, 200)
        self.assertTrue(replay_cap_26.json()["idempotent_replay"])
        with closing(sqlite3.connect(hub.DATABASE_PATH)) as db:
            db.execute(
                "UPDATE jobs SET status='needs_review',attempt_count=26,max_attempts=26 WHERE job_id=?",
                (capped_id,),
            )
            db.commit()
        allowed_cap_27 = self.client.post(
            f"/admin/jobs/{capped_id}/reopen",
            headers={**self.admin_headers, "Idempotency-Key": "reopen-hard-cap-0007"},
            json={"reason": "Allow one guarded dry-run recovery attempt from twenty-six to twenty-seven"},
        )
        self.assertEqual(allowed_cap_27.status_code, 200)
        self.assertFalse(allowed_cap_27.json()["idempotent_replay"])
        self.assertEqual(allowed_cap_27.json()["max_attempts"], 27)
        replay_cap_27 = self.client.post(
            f"/admin/jobs/{capped_id}/reopen",
            headers={**self.admin_headers, "Idempotency-Key": "reopen-hard-cap-0007"},
            json={"reason": "Allow one guarded dry-run recovery attempt from twenty-six to twenty-seven"},
        )
        self.assertEqual(replay_cap_27.status_code, 200)
        self.assertTrue(replay_cap_27.json()["idempotent_replay"])
        with closing(sqlite3.connect(hub.DATABASE_PATH)) as db:
            action_count = db.execute(
                "SELECT COUNT(*) FROM admin_actions WHERE job_id=? AND idempotency_key=?",
                (capped_id, "reopen-hard-cap-0007"),
            ).fetchone()[0]
            self.assertEqual(action_count, 1)
            db.execute(
                "UPDATE jobs SET status='needs_review',attempt_count=27,max_attempts=27 WHERE job_id=?",
                (capped_id,),
            )
            db.commit()
        allowed_cap_28 = self.client.post(
            f"/admin/jobs/{capped_id}/reopen",
            headers={**self.admin_headers, "Idempotency-Key": "reopen-hard-cap-0008"},
            json={"reason": "Allow one guarded dry-run recovery attempt from twenty-seven to twenty-eight"},
        )
        self.assertEqual(allowed_cap_28.status_code, 200)
        self.assertFalse(allowed_cap_28.json()["idempotent_replay"])
        self.assertEqual(allowed_cap_28.json()["max_attempts"], 28)
        replay_cap_28 = self.client.post(
            f"/admin/jobs/{capped_id}/reopen",
            headers={**self.admin_headers, "Idempotency-Key": "reopen-hard-cap-0008"},
            json={"reason": "Allow one guarded dry-run recovery attempt from twenty-seven to twenty-eight"},
        )
        self.assertEqual(replay_cap_28.status_code, 200)
        self.assertTrue(replay_cap_28.json()["idempotent_replay"])
        with closing(sqlite3.connect(hub.DATABASE_PATH)) as db:
            action_count = db.execute(
                "SELECT COUNT(*) FROM admin_actions WHERE job_id=? AND idempotency_key=?",
                (capped_id, "reopen-hard-cap-0008"),
            ).fetchone()[0]
            self.assertEqual(action_count, 1)
            db.execute(
                "UPDATE jobs SET status='needs_review',attempt_count=28,max_attempts=28 WHERE job_id=?",
                (capped_id,),
            )
            db.commit()
        allowed_cap_29 = self.client.post(
            f"/admin/jobs/{capped_id}/reopen",
            headers={**self.admin_headers, "Idempotency-Key": "reopen-hard-cap-0009"},
            json={"reason": "Allow one guarded dry-run recovery attempt from twenty-eight to twenty-nine"},
        )
        self.assertEqual(allowed_cap_29.status_code, 200)
        self.assertFalse(allowed_cap_29.json()["idempotent_replay"])
        self.assertEqual(allowed_cap_29.json()["max_attempts"], 29)
        replay_cap_29 = self.client.post(
            f"/admin/jobs/{capped_id}/reopen",
            headers={**self.admin_headers, "Idempotency-Key": "reopen-hard-cap-0009"},
            json={"reason": "Allow one guarded dry-run recovery attempt from twenty-eight to twenty-nine"},
        )
        self.assertEqual(replay_cap_29.status_code, 200)
        self.assertTrue(replay_cap_29.json()["idempotent_replay"])
        with closing(sqlite3.connect(hub.DATABASE_PATH)) as db:
            action_count = db.execute(
                "SELECT COUNT(*) FROM admin_actions WHERE job_id=? AND idempotency_key=?",
                (capped_id, "reopen-hard-cap-0009"),
            ).fetchone()[0]
            self.assertEqual(action_count, 1)
            db.execute(
                "UPDATE jobs SET status='needs_review',attempt_count=29,max_attempts=29 WHERE job_id=?",
                (capped_id,),
            )
            db.commit()
        allowed_cap_30 = self.client.post(
            f"/admin/jobs/{capped_id}/reopen",
            headers={**self.admin_headers, "Idempotency-Key": "reopen-hard-cap-0010"},
            json={"reason": "Allow one guarded dry-run recovery attempt from twenty-nine to thirty"},
        )
        self.assertEqual(allowed_cap_30.status_code, 200)
        self.assertFalse(allowed_cap_30.json()["idempotent_replay"])
        self.assertEqual(allowed_cap_30.json()["max_attempts"], 30)
        replay_cap_30 = self.client.post(
            f"/admin/jobs/{capped_id}/reopen",
            headers={**self.admin_headers, "Idempotency-Key": "reopen-hard-cap-0010"},
            json={"reason": "Allow one guarded dry-run recovery attempt from twenty-nine to thirty"},
        )
        self.assertEqual(replay_cap_30.status_code, 200)
        self.assertTrue(replay_cap_30.json()["idempotent_replay"])
        with closing(sqlite3.connect(hub.DATABASE_PATH)) as db:
            action_count = db.execute(
                "SELECT COUNT(*) FROM admin_actions WHERE job_id=? AND idempotency_key=?",
                (capped_id, "reopen-hard-cap-0010"),
            ).fetchone()[0]
            self.assertEqual(action_count, 1)
            db.execute(
                "UPDATE jobs SET status='needs_review',attempt_count=30,max_attempts=30 WHERE job_id=?",
                (capped_id,),
            )
            db.commit()
        allowed_cap_31 = self.client.post(
            f"/admin/jobs/{capped_id}/reopen",
            headers={**self.admin_headers, "Idempotency-Key": "reopen-hard-cap-0011"},
            json={"reason": "Allow one guarded dry-run recovery attempt from thirty to thirty-one"},
        )
        self.assertEqual(allowed_cap_31.status_code, 200)
        self.assertFalse(allowed_cap_31.json()["idempotent_replay"])
        self.assertEqual(allowed_cap_31.json()["max_attempts"], 31)
        replay_cap_31 = self.client.post(
            f"/admin/jobs/{capped_id}/reopen",
            headers={**self.admin_headers, "Idempotency-Key": "reopen-hard-cap-0011"},
            json={"reason": "Allow one guarded dry-run recovery attempt from thirty to thirty-one"},
        )
        self.assertEqual(replay_cap_31.status_code, 200)
        self.assertTrue(replay_cap_31.json()["idempotent_replay"])
        with closing(sqlite3.connect(hub.DATABASE_PATH)) as db:
            action_count = db.execute(
                "SELECT COUNT(*) FROM admin_actions WHERE job_id=? AND idempotency_key=?",
                (capped_id, "reopen-hard-cap-0011"),
            ).fetchone()[0]
            self.assertEqual(action_count, 1)
            db.execute(
                "UPDATE jobs SET status='needs_review',attempt_count=31,max_attempts=31 WHERE job_id=?",
                (capped_id,),
            )
            db.commit()
        allowed_cap_32 = self.client.post(
            f"/admin/jobs/{capped_id}/reopen",
            headers={**self.admin_headers, "Idempotency-Key": "reopen-hard-cap-0012"},
            json={"reason": "Allow one guarded dry-run recovery attempt from thirty-one to thirty-two"},
        )
        self.assertEqual(allowed_cap_32.status_code, 200)
        self.assertFalse(allowed_cap_32.json()["idempotent_replay"])
        self.assertEqual(allowed_cap_32.json()["max_attempts"], 32)
        replay_cap_32 = self.client.post(
            f"/admin/jobs/{capped_id}/reopen",
            headers={**self.admin_headers, "Idempotency-Key": "reopen-hard-cap-0012"},
            json={"reason": "Allow one guarded dry-run recovery attempt from thirty-one to thirty-two"},
        )
        self.assertEqual(replay_cap_32.status_code, 200)
        self.assertTrue(replay_cap_32.json()["idempotent_replay"])
        with closing(sqlite3.connect(hub.DATABASE_PATH)) as db:
            action_count = db.execute(
                "SELECT COUNT(*) FROM admin_actions WHERE job_id=? AND idempotency_key=?",
                (capped_id, "reopen-hard-cap-0012"),
            ).fetchone()[0]
            self.assertEqual(action_count, 1)
            db.execute(
                "UPDATE jobs SET status='needs_review',attempt_count=32,max_attempts=32 WHERE job_id=?",
                (capped_id,),
            )
            db.commit()
        allowed_cap_33 = self.client.post(
            f"/admin/jobs/{capped_id}/reopen",
            headers={**self.admin_headers, "Idempotency-Key": "reopen-hard-cap-0013"},
            json={"reason": "Allow one guarded dry-run recovery attempt from thirty-two to thirty-three"},
        )
        self.assertEqual(allowed_cap_33.status_code, 200)
        self.assertFalse(allowed_cap_33.json()["idempotent_replay"])
        self.assertEqual(allowed_cap_33.json()["max_attempts"], 33)
        replay_cap_33 = self.client.post(
            f"/admin/jobs/{capped_id}/reopen",
            headers={**self.admin_headers, "Idempotency-Key": "reopen-hard-cap-0013"},
            json={"reason": "Allow one guarded dry-run recovery attempt from thirty-two to thirty-three"},
        )
        self.assertEqual(replay_cap_33.status_code, 200)
        self.assertTrue(replay_cap_33.json()["idempotent_replay"])
        with closing(sqlite3.connect(hub.DATABASE_PATH)) as db:
            action_count = db.execute(
                "SELECT COUNT(*) FROM admin_actions WHERE job_id=? AND idempotency_key=?",
                (capped_id, "reopen-hard-cap-0013"),
            ).fetchone()[0]
            self.assertEqual(action_count, 1)
            db.execute(
                "UPDATE jobs SET status='needs_review',attempt_count=33,max_attempts=33 WHERE job_id=?",
                (capped_id,),
            )
            db.commit()
        allowed_cap_34 = self.client.post(
            f"/admin/jobs/{capped_id}/reopen",
            headers={**self.admin_headers, "Idempotency-Key": "reopen-hard-cap-0014"},
            json={"reason": "Allow one guarded dry-run recovery attempt from thirty-three to thirty-four"},
        )
        self.assertEqual(allowed_cap_34.status_code, 200)
        self.assertFalse(allowed_cap_34.json()["idempotent_replay"])
        self.assertEqual(allowed_cap_34.json()["max_attempts"], 34)
        replay_cap_34 = self.client.post(
            f"/admin/jobs/{capped_id}/reopen",
            headers={**self.admin_headers, "Idempotency-Key": "reopen-hard-cap-0014"},
            json={"reason": "Allow one guarded dry-run recovery attempt from thirty-three to thirty-four"},
        )
        self.assertEqual(replay_cap_34.status_code, 200)
        self.assertTrue(replay_cap_34.json()["idempotent_replay"])
        with closing(sqlite3.connect(hub.DATABASE_PATH)) as db:
            action_count = db.execute(
                "SELECT COUNT(*) FROM admin_actions WHERE job_id=? AND idempotency_key=?",
                (capped_id, "reopen-hard-cap-0014"),
            ).fetchone()[0]
            self.assertEqual(action_count, 1)
            db.execute(
                "UPDATE jobs SET status='needs_review',attempt_count=34,max_attempts=34 WHERE job_id=?",
                (capped_id,),
            )
            db.commit()
        allowed_cap_35 = self.client.post(
            f"/admin/jobs/{capped_id}/reopen",
            headers={**self.admin_headers, "Idempotency-Key": "reopen-hard-cap-0015"},
            json={"reason": "Allow one guarded dry-run recovery attempt from thirty-four to thirty-five"},
        )
        self.assertEqual(allowed_cap_35.status_code, 200)
        self.assertFalse(allowed_cap_35.json()["idempotent_replay"])
        self.assertEqual(allowed_cap_35.json()["max_attempts"], 35)
        replay_cap_35 = self.client.post(
            f"/admin/jobs/{capped_id}/reopen",
            headers={**self.admin_headers, "Idempotency-Key": "reopen-hard-cap-0015"},
            json={"reason": "Allow one guarded dry-run recovery attempt from thirty-four to thirty-five"},
        )
        self.assertEqual(replay_cap_35.status_code, 200)
        self.assertTrue(replay_cap_35.json()["idempotent_replay"])
        with closing(sqlite3.connect(hub.DATABASE_PATH)) as db:
            action_count = db.execute(
                "SELECT COUNT(*) FROM admin_actions WHERE job_id=? AND idempotency_key=?",
                (capped_id, "reopen-hard-cap-0015"),
            ).fetchone()[0]
            self.assertEqual(action_count, 1)
            db.execute(
                "UPDATE jobs SET status='needs_review',attempt_count=35,max_attempts=35 WHERE job_id=?",
                (capped_id,),
            )
            db.commit()
        allowed_cap_36 = self.client.post(
            f"/admin/jobs/{capped_id}/reopen",
            headers={**self.admin_headers, "Idempotency-Key": "reopen-hard-cap-0016"},
            json={"reason": "Allow one guarded dry-run recovery attempt from thirty-five to thirty-six"},
        )
        self.assertEqual(allowed_cap_36.status_code, 200)
        self.assertFalse(allowed_cap_36.json()["idempotent_replay"])
        self.assertEqual(allowed_cap_36.json()["max_attempts"], 36)
        replay_cap_36 = self.client.post(
            f"/admin/jobs/{capped_id}/reopen",
            headers={**self.admin_headers, "Idempotency-Key": "reopen-hard-cap-0016"},
            json={"reason": "Allow one guarded dry-run recovery attempt from thirty-five to thirty-six"},
        )
        self.assertEqual(replay_cap_36.status_code, 200)
        self.assertTrue(replay_cap_36.json()["idempotent_replay"])
        with closing(sqlite3.connect(hub.DATABASE_PATH)) as db:
            action_count = db.execute(
                "SELECT COUNT(*) FROM admin_actions WHERE job_id=? AND idempotency_key=?",
                (capped_id, "reopen-hard-cap-0016"),
            ).fetchone()[0]
            self.assertEqual(action_count, 1)
            db.execute(
                "UPDATE jobs SET status='needs_review',attempt_count=36,max_attempts=36 WHERE job_id=?",
                (capped_id,),
            )
            db.commit()
        allowed_cap_37 = self.client.post(
            f"/admin/jobs/{capped_id}/reopen",
            headers={**self.admin_headers, "Idempotency-Key": "reopen-hard-cap-0017"},
            json={"reason": "Allow one guarded dry-run recovery attempt from thirty-six to thirty-seven"},
        )
        self.assertEqual(allowed_cap_37.status_code, 200)
        self.assertFalse(allowed_cap_37.json()["idempotent_replay"])
        self.assertEqual(allowed_cap_37.json()["max_attempts"], 37)
        replay_cap_37 = self.client.post(
            f"/admin/jobs/{capped_id}/reopen",
            headers={**self.admin_headers, "Idempotency-Key": "reopen-hard-cap-0017"},
            json={"reason": "Allow one guarded dry-run recovery attempt from thirty-six to thirty-seven"},
        )
        self.assertEqual(replay_cap_37.status_code, 200)
        self.assertTrue(replay_cap_37.json()["idempotent_replay"])
        with closing(sqlite3.connect(hub.DATABASE_PATH)) as db:
            action_count = db.execute(
                "SELECT COUNT(*) FROM admin_actions WHERE job_id=? AND idempotency_key=?",
                (capped_id, "reopen-hard-cap-0017"),
            ).fetchone()[0]
            self.assertEqual(action_count, 1)
            db.execute(
                "UPDATE jobs SET status='needs_review',attempt_count=37,max_attempts=37 WHERE job_id=?",
                (capped_id,),
            )
            db.commit()
        forbidden_cap_38 = self.client.post(
            f"/admin/jobs/{capped_id}/reopen",
            headers={**self.admin_headers, "Idempotency-Key": "reopen-hard-cap-0018"},
            json={"reason": "A thirty-eighth attempt must remain forbidden by the bounded recovery contract"},
        )
        self.assertEqual(forbidden_cap_38.status_code, 409)

        succeeded_id = self.create_job(
            key="reopen-succeeded-dry-run",
            target="pinterest_dry_run",
            media_url="https://example.com/succeeded.png",
        ).json()["job_id"]
        with closing(sqlite3.connect(hub.DATABASE_PATH)) as db:
            db.execute("UPDATE jobs SET status='succeeded' WHERE job_id=?", (succeeded_id,))
            db.commit()
        forbidden_succeeded = self.client.post(
            f"/admin/jobs/{succeeded_id}/reopen",
            headers={**self.admin_headers, "Idempotency-Key": "reopen-succeeded-0001"},
            json={"reason": "Succeeded jobs must never be reopened"},
        )
        self.assertEqual(forbidden_succeeded.status_code, 409)

    def test_dry_run_target_is_part_of_idempotency_fingerprint_and_openapi(self):
        dry = self.create_job(
            key="pinterest-mode-key",
            target="pinterest_dry_run",
            media_url="https://example.com/pinterest.png",
        )
        conflicting_real = self.create_job(
            key="pinterest-mode-key",
            target="pinterest_pin",
            media_url="https://example.com/pinterest.png",
        )
        real = self.create_job(
            key="pinterest-real-key",
            target="pinterest_pin",
            media_url="https://example.com/pinterest.png",
        )
        self.assertEqual(dry.status_code, 201)
        self.assertEqual(conflicting_real.status_code, 409)
        self.assertEqual(real.status_code, 201)
        self.assertNotEqual(dry.json()["job_id"], real.json()["job_id"])
        jobs = {item["job_id"]: item for item in self.client.get("/jobs", headers=self.admin_headers).json()}
        self.assertEqual(jobs[dry.json()["job_id"]]["target"], "pinterest_dry_run")
        self.assertEqual(jobs[real.json()["job_id"]]["target"], "pinterest_pin")
        target_enum = (
            self.client.get("/openapi.json").json()["components"]["schemas"]["JobCreate"]
            ["properties"]["target"]["enum"]
        )
        self.assertIn("pinterest_dry_run", target_enum)
        self.assertIn("pinterest_pin_verify", target_enum)
        self.assertIn("instagram_reel_dry_run", target_enum)
        self.assertIn("tiktok_post_dry_run", target_enum)
        status_schema = self.client.get("/openapi.json").json()["components"]["schemas"]["JobStatusUpdate"]
        self.assertIn("ui_state_verified", status_schema["properties"])
        self.assertIn("evidence_id", status_schema["properties"])

    def test_evidence_upload_is_observable_idempotent_and_reconciled(self):
        job_id = self.create_job(key="evidence-job", target="pinterest_pin", media_url="https://example.com/image.png").json()["job_id"]
        self.register_device()
        job = self.claim().json()["job"]
        png = b"\x89PNG\r\n\x1a\n" + b"test-image"
        body = {
            "filename": "debug.png",
            "content_type": "image/png",
            "content_base64": b64encode(png).decode(),
        }
        uploaded = self.client.post(
            f"/jobs/{job_id}/screenshots", headers=self.lease_headers(job), json=body
        )
        replay = self.client.post(
            f"/jobs/{job_id}/screenshots", headers=self.lease_headers(job), json=body
        )
        self.assertEqual(uploaded.status_code, 201)
        self.assertEqual(uploaded.json()["status"], "available")
        self.assertEqual(replay.json()["evidence_id"], uploaded.json()["evidence_id"])
        self.assertTrue(replay.json()["idempotent_replay"])
        screenshot_path = uploaded.json()["screenshot_path"]
        self.assertEqual(self.client.get(screenshot_path).status_code, 401)
        downloaded = self.client.get(screenshot_path, headers=self.admin_headers)
        self.assertEqual(downloaded.status_code, 200)
        self.assertEqual(downloaded.headers["content-type"], "image/png")

        report_body = {
            "level": "info",
            "message": "step evidence",
            "payload": {"step": "editor"},
            "screenshot_path": screenshot_path,
            "event_key": "evidence-report-key",
        }
        report = self.client.post(
            f"/jobs/{job_id}/events", headers=self.runner_headers, json=report_body
        )
        duplicate = self.client.post(
            f"/jobs/{job_id}/events", headers=self.runner_headers, json=report_body
        )
        self.assertEqual(report.json()["event_id"], duplicate.json()["event_id"])
        self.assertTrue(duplicate.json()["idempotent_replay"])
        unknown = self.client.post(
            f"/jobs/{job_id}/events",
            headers=self.runner_headers,
            json={
                "message": "unknown evidence",
                "screenshot_path": f"/jobs/{job_id}/screenshots/not-recorded.png",
            },
        )
        self.assertEqual(unknown.status_code, 409)
        detail = self.job_detail(job_id)
        self.assertEqual(len(detail["evidence"]), 1)
        self.assertEqual(detail["evidence"][0]["status"], "available")
        self.assertIsNotNone(detail["evidence"][0]["reported_at"])

    def test_openapi_declares_runner_and_admin_security(self):
        spec = self.client.get("/openapi.json").json()
        schemes = spec["components"]["securitySchemes"]
        self.assertIn("RunnerToken", schemes)
        self.assertIn("AdminToken", schemes)
        self.assertIn({"AdminToken": []}, spec["paths"]["/jobs"]["post"]["security"])
        self.assertIn(
            {"RunnerToken": []},
            spec["paths"]["/devices/{device_id}/claim-next"]["post"]["security"],
        )
        reopen = spec["paths"]["/admin/jobs/{job_id}/reopen"]["post"]
        self.assertIn({"AdminToken": []}, reopen["security"])
        idempotency = next(
            parameter for parameter in reopen["parameters"] if parameter["name"] == "Idempotency-Key"
        )
        self.assertTrue(idempotency["required"])

    def test_admin_can_idempotently_reconcile_a_physically_verified_real_publication(self):
        created = self.create_job(key="admin-publication-job", target="tiktok_post")
        self.assertEqual(created.status_code, 201)
        job_id = created.json()["job_id"]
        self.assertEqual(self.register_device().status_code, 200)
        claimed = self.claim().json()["job"]
        terminal = self.client.post(
            f"/jobs/{job_id}/status",
            headers=self.lease_headers(claimed),
            json={"status": "ready_to_publish", "ui_state_verified": False},
        )
        self.assertEqual(terminal.status_code, 200)
        self.assertEqual(terminal.json()["status"], "needs_review")

        receipt = b"\x89PNG\r\n\x1a\nphysical-profile-receipt-IEND"
        request = {
            "reason": "Public TikTok profile tile and account were physically verified",
            "publication_id": "tiktok:public-profile-tile:test-1",
            "filename": "tiktok-public-profile-receipt.png",
            "content_base64": b64encode(receipt).decode(),
            "content_type": "image/png",
        }
        headers = {**self.admin_headers, "Idempotency-Key": "verify-publication-test-1"}
        verified = self.client.post(
            f"/admin/jobs/{job_id}/verify-publication", headers=headers, json=request
        )
        self.assertEqual(verified.status_code, 200)
        self.assertEqual(verified.json()["status"], "succeeded")
        self.assertFalse(verified.json()["idempotent_replay"])
        evidence_id = verified.json()["evidence_id"]

        replay = self.client.post(
            f"/admin/jobs/{job_id}/verify-publication", headers=headers, json=request
        )
        self.assertEqual(replay.status_code, 200)
        self.assertTrue(replay.json()["idempotent_replay"])
        self.assertEqual(replay.json()["evidence_id"], evidence_id)
        conflict = self.client.post(
            f"/admin/jobs/{job_id}/verify-publication",
            headers=headers,
            json={**request, "publication_id": "tiktok:other"},
        )
        self.assertEqual(conflict.status_code, 409)

        detail = self.job_detail(job_id)
        self.assertEqual(detail["job"]["status"], "succeeded")
        self.assertEqual(detail["job"]["publication_id"], request["publication_id"])
        self.assertEqual(len([e for e in detail["events"] if e["message"] == "admin_publication_verified"]), 1)
        self.assertEqual(len([e for e in detail["evidence"] if e["evidence_id"] == evidence_id]), 1)
        with closing(sqlite3.connect(hub.DATABASE_PATH)) as db:
            action_count = db.execute(
                "SELECT COUNT(*) FROM admin_actions WHERE action_type='verify_publication'"
            ).fetchone()[0]
        self.assertEqual(action_count, 1)

    def test_admin_publication_verification_rejects_dry_run_and_active_jobs(self):
        dry = self.create_job(
            key="admin-publication-dry",
            target="tiktok_post_dry_run",
            account_label="Main_Account",
            platform_account_label="@pin.van4",
        )
        real = self.create_job(key="admin-publication-active", target="instagram_reel")
        request = {
            "reason": "Physical receipt verification must remain guarded",
            "publication_id": "guard-test",
            "filename": "receipt.png",
            "content_base64": b64encode(b"\x89PNG\r\n\x1a\nIEND").decode(),
            "content_type": "image/png",
        }
        for key, job_id in (("dry-guard-key", dry.json()["job_id"]), ("active-guard-key", real.json()["job_id"])):
            response = self.client.post(
                f"/admin/jobs/{job_id}/verify-publication",
                headers={**self.admin_headers, "Idempotency-Key": key},
                json=request,
            )
            self.assertEqual(response.status_code, 409)

    def test_threads_post_requires_verified_publication_and_persists_receipt(self):
        created = self.create_job(
            key="threads-real-publication",
            target="threads_post",
            caption="Threads integration receipt",
            media_url="https://example.com/threads.png",
            account_label="Main_Account",
            platform_account_label="@pinv786",
        )
        self.assertEqual(created.status_code, 201)
        job_id = created.json()["job_id"]
        self.register_device()
        claimed = self.claim()
        self.assertEqual(claimed.status_code, 200)
        job = claimed.json()["job"]
        self.assertEqual(job["job_id"], job_id)
        self.assertEqual(job["target"], "threads_post")
        self.assertEqual(job["platform_account_label"], "@pinv786")

        running = self.client.post(
            f"/jobs/{job_id}/status",
            headers=self.lease_headers(job),
            json={"status": "running"},
        )
        self.assertEqual(running.status_code, 200)
        unverified = self.client.post(
            f"/jobs/{job_id}/status",
            headers=self.lease_headers(job),
            json={"status": "succeeded", "publication_id": f"threads:{job_id}"},
        )
        self.assertEqual(unverified.status_code, 409)

        verified = self.client.post(
            f"/jobs/{job_id}/status",
            headers=self.lease_headers(job),
            json={
                "status": "succeeded",
                "publication_verified": True,
                "publication_id": f"threads:{job_id}",
            },
        )
        self.assertEqual(verified.status_code, 200)
        self.assertEqual(verified.json()["status"], "succeeded")
        persisted = self.job_detail(job_id)["job"]
        self.assertEqual(persisted["status"], "succeeded")
        self.assertEqual(persisted["publication_id"], f"threads:{job_id}")

        openapi = self.client.get("/openapi.json").json()
        target_schema = openapi["components"]["schemas"]["JobCreate"]["properties"]["target"]
        self.assertIn("threads_post", target_schema["enum"])

    def test_youtube_short_requires_verified_publication_and_persists_receipt(self):
        created = self.create_job(
            key="youtube-real-publication",
            target="youtube_short",
            caption="YouTube integration receipt",
            media_url="https://example.com/youtube.mp4",
            account_label="Main_Account",
            platform_account_label="@Ivanaicreator",
        )
        self.assertEqual(created.status_code, 201)
        job_id = created.json()["job_id"]
        self.register_device()
        job = self.claim().json()["job"]
        self.assertEqual(job["target"], "youtube_short")
        self.assertEqual(job["platform_account_label"], "@Ivanaicreator")
        self.assertEqual(
            self.client.post(
                f"/jobs/{job_id}/status",
                headers=self.lease_headers(job),
                json={"status": "succeeded", "publication_id": f"youtube:{job_id}"},
            ).status_code,
            409,
        )
        verified = self.client.post(
            f"/jobs/{job_id}/status",
            headers=self.lease_headers(job),
            json={
                "status": "succeeded",
                "publication_verified": True,
                "publication_id": f"youtube:{job_id}",
            },
        )
        self.assertEqual(verified.status_code, 200)
        self.assertEqual(self.job_detail(job_id)["job"]["publication_id"], f"youtube:{job_id}")
        target_schema = self.client.get("/openapi.json").json()["components"]["schemas"]["JobCreate"]["properties"]["target"]
        self.assertIn("youtube_short", target_schema["enum"])


class MigrationTest(unittest.TestCase):
    def test_additive_migration_preserves_legacy_job(self):
        with tempfile.TemporaryDirectory(prefix="mobile-poster-hub-migration-", dir=TEST_TEMP_ROOT) as directory:
            hub.DATA_DIR = Path(directory)
            hub.DATABASE_PATH = hub.DATA_DIR / "hub.sqlite3"
            hub.SCREENSHOTS_DIR = hub.DATA_DIR / "screenshots"
            with closing(sqlite3.connect(hub.DATABASE_PATH)) as db:
                db.executescript(
                    """
                    CREATE TABLE devices (
                      device_id TEXT PRIMARY KEY, platform TEXT NOT NULL, mode TEXT NOT NULL,
                      label TEXT NOT NULL, account_label TEXT, notes TEXT, tags_json TEXT NOT NULL,
                      automation_state TEXT, last_seen_at TEXT NOT NULL
                    );
                    CREATE TABLE jobs (
                      job_id TEXT PRIMARY KEY, target TEXT NOT NULL, caption TEXT NOT NULL,
                      title TEXT, description TEXT, link TEXT, board TEXT, media_url TEXT NOT NULL,
                      publish_at TEXT, preferred_device_id TEXT, account_label TEXT,
                      assigned_device_id TEXT, status TEXT NOT NULL, status_message TEXT,
                      created_at TEXT NOT NULL, updated_at TEXT NOT NULL
                    );
                    CREATE TABLE events (
                      event_id INTEGER PRIMARY KEY AUTOINCREMENT, job_id TEXT NOT NULL,
                      created_at TEXT NOT NULL, level TEXT NOT NULL, message TEXT NOT NULL,
                      payload_json TEXT NOT NULL, screenshot_path TEXT
                    );
                    INSERT INTO jobs(
                      job_id,target,caption,media_url,status,created_at,updated_at
                    ) VALUES('legacy-job','pinterest_pin','legacy','https://example.com/a.png','pending','2026-01-01T00:00:00+00:00','2026-01-01T00:00:00+00:00');
                    """
                )
                db.commit()
            hub.initialize_database()
            with closing(sqlite3.connect(hub.DATABASE_PATH)) as db:
                db.row_factory = sqlite3.Row
                columns = {row["name"] for row in db.execute("PRAGMA table_info(jobs)")}
                legacy = db.execute("SELECT * FROM jobs WHERE job_id='legacy-job'").fetchone()
                evidence_table = db.execute(
                    "SELECT name FROM sqlite_master WHERE type='table' AND name='evidence'"
                ).fetchone()
            self.assertIn("lease_expires_at", columns)
            self.assertIn("idempotency_key", columns)
            self.assertIn("attempt_count", columns)
            self.assertEqual(legacy["caption"], "legacy")
            self.assertEqual(legacy["status"], "pending")
            self.assertIsNotNone(evidence_table)
            with closing(sqlite3.connect(hub.DATABASE_PATH)) as db:
                admin_actions_table = db.execute(
                    "SELECT name FROM sqlite_master WHERE type='table' AND name='admin_actions'"
                ).fetchone()
            self.assertIsNotNone(admin_actions_table)


if __name__ == "__main__":
    unittest.main()
