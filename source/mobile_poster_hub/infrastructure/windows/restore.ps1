[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$BackupDirectory,
    [Parameter(Mandatory=$true)][string]$NewTargetDirectory,
    [Parameter(Mandatory=$true)][string]$AllowedRoot,
    [string]$PythonPath = 'python.exe',
    [string]$SupervisorStatePath
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Resolve-SafeRestorePath([string]$Path, [string]$Root, [bool]$MustExist) {
    $full = [IO.Path]::GetFullPath($Path).TrimEnd('\')
    $rootFull = [IO.Path]::GetFullPath($Root).TrimEnd('\') + '\'
    if (-not ($full + '\').StartsWith($rootFull, [StringComparison]::OrdinalIgnoreCase)) { throw "Unsafe path outside allowed root: $full" }
    if ($MustExist -and -not (Test-Path -LiteralPath $full)) { throw "Missing required path: $full" }
    return $full
}

function Assert-NoReparsePointInExistingChain([string]$Path) {
    $cursor = [IO.Path]::GetFullPath($Path)
    while (-not [string]::IsNullOrWhiteSpace($cursor)) {
        if (Test-Path -LiteralPath $cursor) {
            $item = Get-Item -LiteralPath $cursor -Force
            if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
                throw "Reparse points are forbidden in restore paths: $cursor"
            }
        }
        $parent = [IO.Directory]::GetParent($cursor)
        if ($null -eq $parent -or $parent.FullName -eq $cursor) { break }
        $cursor = $parent.FullName
    }
}

function ConvertTo-SafeManifestPath([string]$RawPath) {
    if ([string]::IsNullOrWhiteSpace($RawPath)) { throw 'Manifest path is empty.' }
    if ([IO.Path]::IsPathRooted($RawPath) -or $RawPath.StartsWith('\\') -or $RawPath.StartsWith('//')) {
        throw "Rooted or UNC manifest path is forbidden: $RawPath"
    }
    $windowsPath = $RawPath.Replace('/', '\')
    $segments = $windowsPath.Split('\')
    if ($segments.Count -eq 0) { throw 'Manifest path has no segments.' }
    $invalidChars = [IO.Path]::GetInvalidFileNameChars()
    foreach ($segment in $segments) {
        if ([string]::IsNullOrWhiteSpace($segment) -or $segment -eq '.' -or $segment -eq '..') {
            throw "Dot, empty, or whitespace manifest segment is forbidden: $RawPath"
        }
        if ($segment.Contains(':') -or $segment.IndexOfAny($invalidChars) -ge 0 -or $segment.EndsWith('.') -or $segment.EndsWith(' ')) {
            throw "Invalid or ADS-capable manifest segment is forbidden: $segment"
        }
    }
    return [string]::Join('\', $segments)
}

function Resolve-ContainedManifestFile([string]$Root, [string]$RelativePath) {
    $rootFull = [IO.Path]::GetFullPath($Root).TrimEnd('\')
    $candidate = [IO.Path]::GetFullPath((Join-Path $rootFull $RelativePath))
    $prefix = $rootFull + '\'
    if (-not $candidate.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Manifest path escapes exact root containment: $RelativePath"
    }
    return $candidate
}

$backup = Resolve-SafeRestorePath $BackupDirectory $AllowedRoot $true
$target = Resolve-SafeRestorePath $NewTargetDirectory $AllowedRoot $false
Assert-NoReparsePointInExistingChain $backup
Assert-NoReparsePointInExistingChain (Split-Path -Parent $target)
if (Test-Path -LiteralPath $target) { throw 'Restore is new-target only; refusing to overwrite an existing path.' }
if ($SupervisorStatePath -and (Test-Path -LiteralPath $SupervisorStatePath)) {
    $state = Get-Content -LiteralPath $SupervisorStatePath -Raw | ConvertFrom-Json
    foreach ($child in $state.children.PSObject.Properties.Value) {
        if (Get-Process -Id ([int]$child.pid) -ErrorAction SilentlyContinue) { throw 'Supervisor child appears active; offline restore required.' }
    }
}
$manifestPath = Join-Path $backup 'manifest.json'
$manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
if ([int]$manifest.format -ne 1) { throw 'Unsupported backup manifest format.' }
$seen = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
$validatedFiles = @()
foreach ($file in @($manifest.files)) {
    $relative = ConvertTo-SafeManifestPath ([string]$file.path)
    if (-not $seen.Add($relative)) { throw "Duplicate normalized manifest path: $relative" }
    $source = Resolve-ContainedManifestFile $backup $relative
    $destination = Resolve-ContainedManifestFile $target $relative
    if (-not (Test-Path -LiteralPath $source)) { throw "Manifest file missing: $($file.path)" }
    Assert-NoReparsePointInExistingChain $source
    $item = Get-Item -LiteralPath $source
    if ($item.PSIsContainer) { throw "Manifest entry must be a regular file: $relative" }
    if ($item.Length -ne [long]$file.size) { throw "Size mismatch: $($file.path)" }
    if ((Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash -ne [string]$file.sha256) { throw "Hash mismatch: $($file.path)" }
    $validatedFiles += [pscustomobject]@{ Relative = $relative; Source = $source; Destination = $destination }
}
$databaseEntries = @($validatedFiles | Where-Object { $_.Relative -ieq 'hub.sqlite3' })
if ($databaseEntries.Count -ne 1) { throw 'Manifest must contain exactly one root hub.sqlite3.' }
[IO.Directory]::CreateDirectory($target) | Out-Null
Assert-NoReparsePointInExistingChain $target
foreach ($file in $validatedFiles) {
    $destinationParent = Split-Path -Parent $file.Destination
    [IO.Directory]::CreateDirectory($destinationParent) | Out-Null
    Assert-NoReparsePointInExistingChain $destinationParent
    Copy-Item -LiteralPath $file.Source -Destination $file.Destination
}
$db = Join-Path $target 'hub.sqlite3'
$python = "import sqlite3,sys; c=sqlite3.connect(sys.argv[1]); r=c.execute('PRAGMA integrity_check').fetchone()[0]; c.close(); print(r); raise SystemExit(0 if r=='ok' else 2)"
& $PythonPath -c $python $db
if ($LASTEXITCODE -ne 0) { throw 'Restored SQLite integrity_check failed.' }
Write-Output $target
