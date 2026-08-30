@{
    # Copy to config.psd1 outside the repository and replace every example path.
    # This file contains paths and policy only. Never put tokens or passwords here.
    AllowedRoots = @(
        'D:\Project\Ферма\source\mobile_poster_hub'
        'D:\Project\Ферма\artifacts\hub-windows-runtime'
        'D:\Media'
        'C:\ProgramData\FarmHub'
        'C:\Program Files\cloudflared'
    )
    StateDirectory = 'C:\ProgramData\FarmHub\state'
    LogDirectory = 'C:\ProgramData\FarmHub\logs'
    PollSeconds = 5
    HealthTimeoutSeconds = 5
    Restart = @{
        InitialDelaySeconds = 2
        MaximumDelaySeconds = 60
        StableResetSeconds = 300
    }
    LogRotation = @{
        MaximumBytes = 10485760
        KeepFiles = 7
    }
    Children = @(
        @{
            Name = 'hub'
            Enabled = $true
            Executable = 'D:\Project\Ферма\source\mobile_poster_hub\.venv\Scripts\python.exe'
            Arguments = @('-m', 'uvicorn', 'app:app', '--host', '127.0.0.1', '--port', '18082')
            WorkingDirectory = 'D:\Project\Ферма\source\mobile_poster_hub'
            HealthUrl = 'http://127.0.0.1:18082/health'
            Environment = @{
                HUB_DATA_DIR = 'D:\Project\Ферма\artifacts\hub-windows-runtime\data'
            }
        }
        @{
            Name = 'media'
            Enabled = $false
            Executable = 'D:\Project\Ферма\source\mobile_poster_hub\.venv\Scripts\python.exe'
            Arguments = @('-m', 'http.server', '8090', '--bind', '127.0.0.1', '--directory', 'D:\Media')
            WorkingDirectory = 'D:\Media'
            HealthUrl = 'http://127.0.0.1:8090/'
            Environment = @{}
        }
        @{
            Name = 'queues'
            Enabled = $true
            Executable = 'D:\Project\Ферма\source\mobile_poster_hub\.venv\Scripts\python.exe'
            Arguments = @('inbox_worker.py')
            WorkingDirectory = 'D:\Project\Ферма\source\mobile_poster_hub'
            HealthUrl = $null
            EnvironmentFile = 'D:\Project\Ферма\source\mobile_poster_hub\.env'
            EnvironmentFileKeys = @('HUB_ADMIN_TOKEN')
            Environment = @{
                HUB_DATA_DIR = 'D:\Project\Ферма\artifacts\hub-windows-runtime\data'
                HUB_LOCAL_URL = 'http://127.0.0.1:18082'
                HUB_PUBLIC_BASE_URL = 'http://127.0.0.1:18082'
                FARM_INBOX_POLL_SECONDS = '2'
                FARM_WORKER_HEARTBEAT = 'D:\Project\Ферма\artifacts\hub-windows-runtime\data\inbox-worker-heartbeat.json'
            }
        }
        @{
            Name = 'tunnel'
            Enabled = $false
            Executable = 'C:\Program Files\cloudflared\cloudflared.exe'
            # Use a named tunnel configuration and credentials file. Never pass a token here.
            Arguments = @('tunnel', '--config', 'C:\ProgramData\FarmHub\cloudflared\config.yml', 'run')
            WorkingDirectory = 'C:\ProgramData\FarmHub\cloudflared'
            HealthUrl = $null
            Environment = @{}
        }
    )
}
