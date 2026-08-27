# Folder queue

Drop media into one of these folders:

- `inbox/Pinterest/Incoming`
- `inbox/Instagram/Incoming`
- `inbox/TikTok/Incoming`
- `inbox/Threads/Incoming`
- `inbox/YouTube/Incoming`

An optional sidecar text file with the same base name supplies the caption, for
example `video.mp4` and `video.txt`. Successfully queued files move to
`Queued`. Only a Hub-confirmed real publication moves to `Published`; terminal
errors or manual-review outcomes move to `NeedsReview`. The content hash is the
idempotency key, so the same file cannot create a duplicate job for the same platform.

The worker requires `HUB_ADMIN_TOKEN`, `HUB_PUBLIC_BASE_URL`, and the same
`HUB_DATA_DIR` as Hub. Run `python inbox_worker.py` under the existing Windows
supervisor.
