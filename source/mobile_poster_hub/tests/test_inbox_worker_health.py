import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import inbox_worker


class InboxWorkerHealthTest(unittest.TestCase):
    def test_heartbeat_is_atomic_secret_free_and_parseable(self):
        with tempfile.TemporaryDirectory() as directory:
            heartbeat = Path(directory) / "worker-heartbeat.json"
            with patch.object(inbox_worker, "HEARTBEAT", heartbeat):
                inbox_worker.write_heartbeat("idle")

            payload = json.loads(heartbeat.read_text(encoding="utf-8"))
            self.assertEqual(payload["phase"], "idle")
            self.assertGreater(payload["updated_unix"], 0)
            self.assertGreater(payload["pid"], 0)
            self.assertEqual(list(Path(directory).glob("*.tmp")), [])
            self.assertNotIn("token", heartbeat.read_text(encoding="utf-8").lower())


if __name__ == "__main__":
    unittest.main()
