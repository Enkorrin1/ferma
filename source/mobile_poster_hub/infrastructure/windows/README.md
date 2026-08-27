# Farm Hub Windows supervision

This package runs the Hub, the media origin, and a **named** Cloudflare tunnel as owned child
processes on Windows. It is deliberately separate from the application code and does not put
credentials in command lines, configuration, logs, PID state, or backups.

## Safety model

- Every executable, working directory, state directory, log directory, backup, and restore target
  must resolve below an explicit `AllowedRoots` entry.
- The supervisor records PID, executable path, and process start time. It only stops a process when
  all three still match; a recycled PID is never killed.
- Health failures and exits use bounded exponential restart delay. A stable child resets the delay.
- Shutdown first asks an owned process to close, then force-stops only the still-identical owned PID.
- Logs rotate by size and never contain environment values. Cloudflare must use a credentials file
  referenced by a named-tunnel YAML, never a token argument.
- SQLite backup uses the online backup API, then `integrity_check`; evidence is copied and every file
  is covered by a SHA-256 manifest. Restore is offline, new-target-only, and verifies the manifest
  before copying.
- Restore rejects rooted/UNC/traversal/ADS/invalid manifest paths, duplicate case-insensitive paths,
  path-prefix escapes, and reparse points/junctions. A manifest must contain exactly one root
  `hub.sqlite3`; restore never follows a manifest path outside the selected backup and new target.
- Backup validates every existing path component from `AllowedRoot` to the database, evidence,
  destination, and newly created target. It revalidates immediately before evidence copy and before
  every retention delete; a junction/reparse candidate fails closed and is never recursively removed.
- Evidence is copied file-by-file, not with recursive `Copy-Item`. Before each file, source, target
  subtree, `target\evidence`, and the destination parent are revalidated as real non-reparse paths;
  the completed target tree is checked again before its manifest is generated.

## Stable endpoint

Use a Cloudflare named tunnel with two DNS routes, for example:

```yaml
tunnel: FARM_TUNNEL_UUID
credentials-file: C:\ProgramData\FarmHub\cloudflared\FARM_TUNNEL_UUID.json
ingress:
  - hostname: hub.example.com
    service: http://127.0.0.1:18082
  - hostname: media.example.com
    service: http://127.0.0.1:8090
  - service: http_status:404
```

Lock the YAML, credentials JSON, Hub `.env`, and any service account credential using
`protect-secrets.ps1`. Separate hostnames keep Hub and media lifecycle failures independent.

## Installation

1. Copy `config.example.psd1` to `C:\ProgramData\FarmHub\config.psd1` and replace paths.
2. Create runtime, state, log, backup, and cloudflared directories under the allowed roots.
3. Lock secret files (run elevated):

   ```powershell
   .\protect-secrets.ps1 -Paths @('D:\secure\hub.env','C:\ProgramData\FarmHub\cloudflared\tunnel.json') -ServiceIdentity 'DOMAIN\farm-hub-service'
   ```

4. Validate manually in an isolated data directory. Never point the first run at production data.
5. Register startup supervision (run elevated):

   ```powershell
   .\install-task.ps1 -SupervisorPath .\supervisor.ps1 -ConfigPath C:\ProgramData\FarmHub\config.psd1 -ServiceIdentity 'SYSTEM'
   Start-ScheduledTask -TaskName FarmHubSupervisor
   ```

For a dedicated identity, pass a `Get-Credential` result via `-Credential`; it is used in memory by
Task Scheduler and is never written by this package. Never embed its password in a script. `SYSTEM`
is the default because it avoids storing another password.

## Operations

```powershell
Get-ScheduledTaskInfo -TaskName FarmHubSupervisor
Get-Content C:\ProgramData\FarmHub\state\supervisor-state.json
Invoke-WebRequest http://127.0.0.1:18082/health
Stop-ScheduledTask -TaskName FarmHubSupervisor
.\uninstall-task.ps1 -TaskName FarmHubSupervisor
```

The state file is operational metadata only. It contains names, PIDs, executable paths, and start
times—never tokens or environment values.

## Backup and restore

Online backup while the isolated Hub is active:

```powershell
.\backup.ps1 -DatabasePath D:\runtime\data\hub.sqlite3 -EvidenceDirectory D:\runtime\data\evidence -DestinationRoot D:\runtime\backups -AllowedRoot D:\runtime
```

Offline restore to a **new** directory:

```powershell
.\restore.ps1 -BackupDirectory D:\runtime\backups\backup-... -NewTargetDirectory D:\runtime\restore-check -AllowedRoot D:\runtime -SupervisorStatePath D:\runtime\state\supervisor-state.json
```

Never overwrite the live data directory. After validation, switch configuration during a controlled
maintenance window; retain the prior directory for rollback.

## Required rollout gate

Run `tests\supervisor-isolated.Tests.ps1`. The test uses only a fresh temporary directory and checks
path refusal, PID ownership, graceful/forced stop boundaries, log rotation, SQLite backup/restore,
evidence hashes, manifest tamper rejection, and broad-ACL detection. Before production rollout also:

1. Record production DB hash and active PID/listeners.
2. Prove isolated start → health.
3. Kill each isolated child and observe a bounded restart with a new owned PID.
4. Make the isolated health endpoint unhealthy and observe restart/backoff.
5. Verify graceful supervisor stop leaves no owned child.
6. Verify secrets are absent from logs/state and ACL has no Users, Authenticated Users, or Everyone.
7. Backup while the isolated Hub is active, restore to a new directory, and verify DB integrity and
   every evidence hash.
8. Confirm production DB hash, live production PIDs/listeners, and phone state are unchanged.

Rollback is: stop the scheduled task, confirm owned children exited, restore the previous config/data
selection, and start the task. Do not delete either data set until integrity and evidence are audited.
