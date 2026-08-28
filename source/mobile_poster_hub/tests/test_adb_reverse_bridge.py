import unittest

from adb_reverse_bridge import bridge_present
from inbox_worker import is_safe_public_base_url, submission_id


class AdbReverseBridgeTest(unittest.TestCase):
    def test_accepts_exact_port_mapping(self):
        self.assertTrue(bridge_present("serial tcp:18082 tcp:18082\n", 18082))

    def test_rejects_missing_or_other_port_mapping(self):
        self.assertFalse(bridge_present("", 18082))
        self.assertFalse(bridge_present("serial tcp:18083 tcp:18083\n", 18082))

    def test_queue_accepts_https_or_loopback_bridge_only(self):
        self.assertTrue(is_safe_public_base_url("https://hub.example"))
        self.assertTrue(is_safe_public_base_url("http://127.0.0.1:18082"))
        self.assertTrue(is_safe_public_base_url("http://localhost:18082"))
        self.assertFalse(is_safe_public_base_url("http://192.168.100.210:18082"))
        self.assertFalse(is_safe_public_base_url("ftp://127.0.0.1:18082"))

    def test_submission_id_is_stable_for_one_arrival_and_platform_specific(self):
        from pathlib import Path
        from tempfile import TemporaryDirectory

        with TemporaryDirectory() as directory:
            media = Path(directory) / "clip.mp4"
            media.write_bytes(b"video")
            first = submission_id("Threads", media, "content-hash")
            self.assertEqual(first, submission_id("Threads", media, "content-hash"))
            self.assertNotEqual(first, submission_id("TikTok", media, "content-hash"))
            self.assertNotEqual(
                submission_id("Threads", media, "content-hash", "arrival-one"),
                submission_id("Threads", media, "content-hash", "arrival-two"),
            )


if __name__ == "__main__":
    unittest.main()
