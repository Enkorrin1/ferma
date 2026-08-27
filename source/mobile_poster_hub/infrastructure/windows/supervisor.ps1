[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)] [string] $ConfigPath,
    [switch] $LibraryOnly,
    [switch] $Once
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Resolve-ExactPath {
    param([Parameter(Mandatory = $true)][string]$Path, [switch]$AllowMissing)
    $full = [System.IO.Path]::GetFullPath($Path)
    if (-not $AllowMissing -and -not (Test-Path -LiteralPath $full)) {
        throw "Required path does not exist: $full"
    }
    return $full.TrimEnd([System.IO.Path]::DirectorySeparatorChar)
}

function Assert-PathUnderAllowedRoot {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string[]]$AllowedRoots,
        [switch]$AllowMissing
    )
    $candidate = Resolve-ExactPath -Path $Path -AllowMissing:$AllowMissing
    foreach ($rootValue in $AllowedRoots) {
        $root = (Resolve-ExactPath -Path $rootValue -AllowMissing).TrimEnd('\') + '\'
        if (($candidate + '\').StartsWith($root, [System.StringComparison]::OrdinalIgnoreCase)) {
            return $candidate
        }
    }
    throw "Refusing path outside AllowedRoots: $candidate"
}

function Rotate-LogFile {
    param([string]$Path, [long]$MaximumBytes, [int]$KeepFiles)
    if (-not (Test-Path -LiteralPath $Path) -or (Get-Item -LiteralPath $Path).Length -lt $MaximumBytes) { return }
    for ($i = $KeepFiles - 1; $i -ge 1; $i--) {
        $older = "$Path.$i"
        $newer = "$Path.$($i + 1)"
        if (Test-Path -LiteralPath $older) { Move-Item -LiteralPath $older -Destination $newer -Force }
    }
    Move-Item -LiteralPath $Path -Destination "$Path.1" -Force
}

function Test-ChildHealth {
    param([string]$HealthUrl, [int]$TimeoutSeconds)
    if ([string]::IsNullOrWhiteSpace($HealthUrl)) { return $true }
    try {
        $response = Invoke-WebRequest -Uri $HealthUrl -UseBasicParsing -TimeoutSec $TimeoutSeconds
        return [int]$response.StatusCode -ge 200 -and [int]$response.StatusCode -lt 300
    } catch { return $false }
}

function Get-ProcessIdentity {
    param(
        [Parameter(Mandatory = $true)][System.Diagnostics.Process]$Process,
        [string]$ExpectedExecutable
    )
    $executable = if ([string]::IsNullOrWhiteSpace($ExpectedExecutable)) {
        $Process.MainModule.FileName
    } else {
        [IO.Path]::GetFullPath($ExpectedExecutable)
    }
    return [ordered]@{
        pid = $Process.Id
        startTimeUtc = $Process.StartTime.ToUniversalTime().ToString('o')
        executable = $executable
    }
}

function Test-OwnedProcess {
    param([Parameter(Mandatory = $true)][hashtable]$Identity)
    try {
        $process = [System.Diagnostics.Process]::GetProcessById([int]$Identity.pid)
        $actualStart = $process.StartTime.ToUniversalTime().ToString('o')
        $actualExe = $process.MainModule.FileName
        return $actualStart -eq $Identity.startTimeUtc -and
            [string]::Equals($actualExe, $Identity.executable, [System.StringComparison]::OrdinalIgnoreCase)
    } catch { return $false }
}

function Start-OwnedChild {
    param([hashtable]$Spec, [hashtable]$Config)
    $allowed = [string[]]$Config.AllowedRoots
    $exe = Assert-PathUnderAllowedRoot -Path ([string]$Spec.Executable) -AllowedRoots $allowed
    $working = Assert-PathUnderAllowedRoot -Path ([string]$Spec.WorkingDirectory) -AllowedRoots $allowed
    $logDir = Assert-PathUnderAllowedRoot -Path ([string]$Config.LogDirectory) -AllowedRoots $allowed -AllowMissing
    [System.IO.Directory]::CreateDirectory($logDir) | Out-Null
    $stdout = Join-Path $logDir "$($Spec.Name).stdout.log"
    $stderr = Join-Path $logDir "$($Spec.Name).stderr.log"
    Rotate-LogFile $stdout ([long]$Config.LogRotation.MaximumBytes) ([int]$Config.LogRotation.KeepFiles)
    Rotate-LogFile $stderr ([long]$Config.LogRotation.MaximumBytes) ([int]$Config.LogRotation.KeepFiles)

    $psi = [System.Diagnostics.ProcessStartInfo]::new()
    $psi.FileName = $exe
    $psi.WorkingDirectory = $working
    $psi.UseShellExecute = $false
    $psi.CreateNoWindow = $true
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    foreach ($argument in @($Spec.Arguments)) { $psi.ArgumentList.Add([string]$argument) }
    foreach ($entry in $Spec.Environment.GetEnumerator()) { $psi.Environment[[string]$entry.Key] = [string]$entry.Value }
    if ($Spec.ContainsKey('EnvironmentFile') -and -not [string]::IsNullOrWhiteSpace([string]$Spec.EnvironmentFile)) {
        $environmentFile = Assert-PathUnderAllowedRoot -Path ([string]$Spec.EnvironmentFile) -AllowedRoots $allowed
        $environmentItem = Get-Item -LiteralPath $environmentFile -Force
        if ($environmentItem.PSIsContainer -or ($environmentItem.Attributes -band [IO.FileAttributes]::ReparsePoint)) {
            throw "Environment file for $($Spec.Name) must be a regular non-reparse file"
        }
        $requestedKeys = [string[]]@($Spec.EnvironmentFileKeys)
        if ($requestedKeys.Count -eq 0) { throw "EnvironmentFileKeys is required for $($Spec.Name)" }
        $loaded = @{}
        foreach ($line in [IO.File]::ReadAllLines($environmentFile, [Text.Encoding]::UTF8)) {
            $trimmed = $line.Trim()
            if ($trimmed.Length -eq 0 -or $trimmed.StartsWith('#')) { continue }
            $separator = $trimmed.IndexOf('=')
            if ($separator -le 0) { continue }
            $key = $trimmed.Substring(0, $separator).Trim()
            if ($requestedKeys -notcontains $key) { continue }
            $value = $trimmed.Substring($separator + 1).Trim()
            if ($value.Length -ge 2 -and (($value[0] -eq '"' -and $value[-1] -eq '"') -or ($value[0] -eq "'" -and $value[-1] -eq "'"))) {
                $value = $value.Substring(1, $value.Length - 2)
            }
            $loaded[$key] = $value
        }
        foreach ($key in $requestedKeys) {
            if (-not $loaded.ContainsKey($key) -or [string]::IsNullOrWhiteSpace([string]$loaded[$key])) {
                throw "Required environment key $key is missing for $($Spec.Name)"
            }
            $psi.Environment[$key] = [string]$loaded[$key]
        }
    }
    $process = [System.Diagnostics.Process]::new()
    $process.StartInfo = $psi
    if (-not $process.Start()) { throw "Failed to start child $($Spec.Name)" }
    $stdoutFile = [IO.File]::Open($stdout, [IO.FileMode]::Append, [IO.FileAccess]::Write, [IO.FileShare]::Read)
    $stderrFile = [IO.File]::Open($stderr, [IO.FileMode]::Append, [IO.FileAccess]::Write, [IO.FileShare]::Read)
    $stdoutTask = $process.StandardOutput.BaseStream.CopyToAsync($stdoutFile)
    $stderrTask = $process.StandardError.BaseStream.CopyToAsync($stderrFile)
    return @{
        Process = $process
        Identity = Get-ProcessIdentity $process $exe
        StartedUtc = [DateTime]::UtcNow
        Delay = [int]$Config.Restart.InitialDelaySeconds
        LogCopies = @(
            @{ Task = $stdoutTask; Stream = $stdoutFile }
            @{ Task = $stderrTask; Stream = $stderrFile }
        )
    }
}

function Complete-ChildLogs {
    param([hashtable]$Runtime)
    if ($null -eq $Runtime -or -not $Runtime.ContainsKey('LogCopies')) { return }
    foreach ($copy in @($Runtime.LogCopies)) {
        try { $null = $copy.Task.GetAwaiter().GetResult() } finally { $copy.Stream.Dispose() }
    }
    $null = $Runtime.Remove('LogCopies')
}

function Stop-OwnedChild {
    param([hashtable]$Runtime, [int]$GraceSeconds = 10)
    if ($null -eq $Runtime -or -not (Test-OwnedProcess $Runtime.Identity)) { return $false }
    $process = [System.Diagnostics.Process]::GetProcessById([int]$Runtime.Identity.pid)
    try { $null = $process.CloseMainWindow() } catch {}
    if (-not $process.WaitForExit($GraceSeconds * 1000)) {
        if (-not (Test-OwnedProcess $Runtime.Identity)) { throw 'PID identity changed during shutdown; refusing force stop.' }
        $process.Kill($true)
        $process.WaitForExit(5000) | Out-Null
    }
    Complete-ChildLogs $Runtime
    return $true
}

function Save-SupervisorState {
    param([hashtable]$Runtimes, [hashtable]$Config)
    $stateDir = Assert-PathUnderAllowedRoot ([string]$Config.StateDirectory) ([string[]]$Config.AllowedRoots) -AllowMissing
    [System.IO.Directory]::CreateDirectory($stateDir) | Out-Null
    $statePath = Join-Path $stateDir 'supervisor-state.json'
    $safe = [ordered]@{ updatedUtc = [DateTime]::UtcNow.ToString('o'); children = @{} }
    foreach ($name in $Runtimes.Keys) { $safe.children[$name] = $Runtimes[$name].Identity }
    $temp = "$statePath.tmp"
    $safe | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $temp -Encoding UTF8
    Move-Item -LiteralPath $temp -Destination $statePath -Force
}

function Invoke-SupervisorLoop {
    param([hashtable]$Config, [switch]$RunOnce)
    $runtimes = @{}
    $stopFile = $null
    if ($Config.ContainsKey('StopFilePath') -and -not [string]::IsNullOrWhiteSpace([string]$Config.StopFilePath)) {
        $stopFile = Assert-PathUnderAllowedRoot -Path ([string]$Config.StopFilePath) -AllowedRoots ([string[]]$Config.AllowedRoots) -AllowMissing
        if (Test-Path -LiteralPath $stopFile) { Remove-Item -LiteralPath $stopFile -Force }
    }
    try {
        foreach ($spec in @($Config.Children | Where-Object { $_.Enabled })) {
            $runtimes[[string]$spec.Name] = Start-OwnedChild $spec $Config
        }
        do {
            Start-Sleep -Seconds ([int]$Config.PollSeconds)
            if ($null -ne $stopFile -and (Test-Path -LiteralPath $stopFile)) {
                Remove-Item -LiteralPath $stopFile -Force
                break
            }
            foreach ($spec in @($Config.Children | Where-Object { $_.Enabled })) {
                $name = [string]$spec.Name
                $runtime = $runtimes[$name]
                $alive = Test-OwnedProcess $runtime.Identity
                $healthy = $alive -and (Test-ChildHealth ([string]$spec.HealthUrl) ([int]$Config.HealthTimeoutSeconds))
                if (-not $healthy) {
                    if ($alive) { Stop-OwnedChild $runtime | Out-Null } else { Complete-ChildLogs $runtime }
                    Start-Sleep -Seconds ([int]$runtime.Delay)
                    $nextDelay = [Math]::Min([int]$Config.Restart.MaximumDelaySeconds, [Math]::Max(1, [int]$runtime.Delay * 2))
                    $replacement = Start-OwnedChild $spec $Config
                    $replacement.Delay = $nextDelay
                    $runtimes[$name] = $replacement
                } elseif (([DateTime]::UtcNow - $runtime.StartedUtc).TotalSeconds -ge [int]$Config.Restart.StableResetSeconds) {
                    $runtime.Delay = [int]$Config.Restart.InitialDelaySeconds
                }
            }
            Save-SupervisorState $runtimes $Config
        } while (-not $RunOnce)
    } finally {
        foreach ($runtime in @($runtimes.Values)) {
            if (-not (Stop-OwnedChild $runtime)) { Complete-ChildLogs $runtime }
        }
    }
}

$resolvedConfig = Resolve-ExactPath -Path $ConfigPath
$config = Import-PowerShellDataFile -LiteralPath $resolvedConfig
if (-not $LibraryOnly) { Invoke-SupervisorLoop -Config $config -RunOnce:$Once }
