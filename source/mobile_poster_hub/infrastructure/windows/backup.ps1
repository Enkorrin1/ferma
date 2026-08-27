[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$DatabasePath,
    [Parameter(Mandatory=$true)][string]$EvidenceDirectory,
    [Parameter(Mandatory=$true)][string]$DestinationRoot,
    [Parameter(Mandatory=$true)][string]$AllowedRoot,
    [string]$PythonPath = 'python.exe',
    [ValidateRange(1,365)][int]$RetentionCount = 14,
    [Parameter(DontShow=$true)][scriptblock]$BeforeEvidenceCopyTestHook,
    [Parameter(DontShow=$true)][scriptblock]$AfterEvidenceEntryCopyTestHook
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Resolve-SafeBackupPath([string]$Path, [string]$Root, [bool]$MustExist) {
    $full = [IO.Path]::GetFullPath($Path).TrimEnd('\')
    $rootFull = [IO.Path]::GetFullPath($Root).TrimEnd('\') + '\'
    if (-not ($full + '\').StartsWith($rootFull, [StringComparison]::OrdinalIgnoreCase)) { throw "Unsafe path outside allowed root: $full" }
    if ($MustExist -and -not (Test-Path -LiteralPath $full)) { throw "Missing required path: $full" }
    return $full
}

function Assert-NoReparseInExistingChain([string]$Path, [string]$Root) {
    $rootFull = [IO.Path]::GetFullPath($Root).TrimEnd('\')
    $pathFull = [IO.Path]::GetFullPath($Path).TrimEnd('\')
    if (-not (($pathFull + '\').StartsWith($rootFull + '\', [StringComparison]::OrdinalIgnoreCase))) {
        throw "Path escapes exact allowed root containment: $pathFull"
    }
    $relative = [IO.Path]::GetRelativePath($rootFull, $pathFull)
    $cursor = $rootFull
    foreach ($segment in @('.') + @($relative.Split('\'))) {
        if ($segment -ne '.' -and -not [string]::IsNullOrWhiteSpace($segment)) { $cursor = Join-Path $cursor $segment }
        if (Test-Path -LiteralPath $cursor) {
            $item = Get-Item -LiteralPath $cursor -Force
            if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
                throw "Reparse point is forbidden in backup path chain: $cursor"
            }
        }
    }
}

function Assert-NoBackupTreeReparsePoint([string]$Path) {
    foreach ($item in @(Get-Item -LiteralPath $Path -Force) + @(Get-ChildItem -LiteralPath $Path -Force -Recurse)) {
        if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw "Backup refuses reparse point: $($item.FullName)"
        }
    }
}

function Assert-RealBackupDirectory([string]$Path, [string]$Root) {
    Assert-NoReparseInExistingChain $Path $Root
    if (-not (Test-Path -LiteralPath $Path -PathType Container)) { throw "Expected real backup directory: $Path" }
    $item = Get-Item -LiteralPath $Path -Force
    if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { throw "Backup directory is a reparse point: $Path" }
}

function ConvertTo-CanonicalBackupRelativePath([string]$Root, [string]$FullName) {
    $relative = [IO.Path]::GetRelativePath($Root, $FullName).Replace('\','/')
    if ([string]::IsNullOrWhiteSpace($relative) -or $relative.StartsWith('../') -or $relative -eq '..' -or [IO.Path]::IsPathRooted($relative) -or $relative.Contains(':')) {
        throw "Unsafe backup manifest path: $relative"
    }
    foreach ($segment in $relative.Split('/')) {
        if ([string]::IsNullOrWhiteSpace($segment) -or $segment -eq '.' -or $segment -eq '..') { throw "Unsafe backup manifest segment: $relative" }
    }
    return $relative
}

$database = Resolve-SafeBackupPath $DatabasePath $AllowedRoot $true
$evidence = Resolve-SafeBackupPath $EvidenceDirectory $AllowedRoot $true
$backupRoot = Resolve-SafeBackupPath $DestinationRoot $AllowedRoot $false
Assert-NoReparseInExistingChain $database $AllowedRoot
Assert-NoReparseInExistingChain $evidence $AllowedRoot
Assert-NoReparseInExistingChain $backupRoot $AllowedRoot
Assert-NoBackupTreeReparsePoint $database
Assert-NoBackupTreeReparsePoint $evidence
[IO.Directory]::CreateDirectory($backupRoot) | Out-Null
Assert-NoReparseInExistingChain $backupRoot $AllowedRoot
$stamp = [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssfffZ')
$target = Join-Path $backupRoot "backup-$stamp"
[IO.Directory]::CreateDirectory($target) | Out-Null
Assert-NoReparseInExistingChain $target $AllowedRoot

$databaseCopy = Join-Path $target 'hub.sqlite3'
$python = @'
import sqlite3, sys
source, destination = sys.argv[1], sys.argv[2]
src = sqlite3.connect('file:' + source.replace('\\','/') + '?mode=ro', uri=True)
dst = sqlite3.connect(destination)
try:
    src.backup(dst)
    result = dst.execute('PRAGMA integrity_check').fetchone()[0]
    if result != 'ok':
        raise RuntimeError('integrity_check=' + str(result))
finally:
    dst.close(); src.close()
'@
& $PythonPath -c $python $database $databaseCopy
if ($LASTEXITCODE -ne 0) { throw "SQLite online backup failed with exit code $LASTEXITCODE" }

$evidenceCopy = Join-Path $target 'evidence'
[IO.Directory]::CreateDirectory($evidenceCopy) | Out-Null
if ($null -ne $BeforeEvidenceCopyTestHook) { & $BeforeEvidenceCopyTestHook $target }
Assert-NoReparseInExistingChain $evidence $AllowedRoot
Assert-NoReparseInExistingChain $target $AllowedRoot
Assert-NoBackupTreeReparsePoint $evidence
Assert-NoBackupTreeReparsePoint $target
Assert-RealBackupDirectory $evidenceCopy $AllowedRoot

$evidenceEntries = @(Get-ChildItem -LiteralPath $evidence -Force -Recurse)
$sourceDirectories = @($evidenceEntries | Where-Object { $_.PSIsContainer } | Sort-Object { $_.FullName.Length })
foreach ($directory in $sourceDirectories) {
    Assert-NoReparseInExistingChain $directory.FullName $AllowedRoot
    if (($directory.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { throw "Evidence directory became a reparse point: $($directory.FullName)" }
    $relative = ConvertTo-CanonicalBackupRelativePath $evidence $directory.FullName
    $destinationDirectory = Resolve-SafeBackupPath (Join-Path $evidenceCopy $relative.Replace('/','\')) $target $false
    Assert-NoBackupTreeReparsePoint $target
    Assert-RealBackupDirectory $evidenceCopy $AllowedRoot
    [IO.Directory]::CreateDirectory($destinationDirectory) | Out-Null
    Assert-RealBackupDirectory $destinationDirectory $AllowedRoot
}

$copiedEvidenceFiles = 0
foreach ($entry in @($evidenceEntries | Where-Object { -not $_.PSIsContainer } | Sort-Object FullName)) {
    Assert-NoReparseInExistingChain $entry.FullName $AllowedRoot
    $sourceItem = Get-Item -LiteralPath $entry.FullName -Force
    if ($sourceItem.PSIsContainer -or ($sourceItem.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw "Evidence source must remain a regular non-reparse file: $($entry.FullName)"
    }
    $relative = ConvertTo-CanonicalBackupRelativePath $evidence $entry.FullName
    $destination = Resolve-SafeBackupPath (Join-Path $evidenceCopy $relative.Replace('/','\')) $target $false
    $destinationParent = Split-Path -Parent $destination
    Assert-NoBackupTreeReparsePoint $target
    Assert-RealBackupDirectory $evidenceCopy $AllowedRoot
    Assert-RealBackupDirectory $destinationParent $AllowedRoot
    Copy-Item -LiteralPath $entry.FullName -Destination $destination -Force
    Assert-NoReparseInExistingChain $destination $AllowedRoot
    $destinationItem = Get-Item -LiteralPath $destination -Force
    if ($destinationItem.PSIsContainer -or ($destinationItem.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw "Evidence destination must be a regular non-reparse file: $destination"
    }
    $copiedEvidenceFiles++
    if ($null -ne $AfterEvidenceEntryCopyTestHook) { & $AfterEvidenceEntryCopyTestHook $target $entry.FullName $destination $copiedEvidenceFiles }
}

Assert-NoBackupTreeReparsePoint $target
$files = Get-ChildItem -LiteralPath $target -File -Recurse | Sort-Object FullName | ForEach-Object {
    [ordered]@{
        path = ConvertTo-CanonicalBackupRelativePath $target $_.FullName
        size = $_.Length
        sha256 = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash
    }
}
$manifest = [ordered]@{
    format = 1
    createdUtc = [DateTime]::UtcNow.ToString('o')
    databaseSource = [IO.Path]::GetFileName($database)
    files = @($files)
}
$manifest | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $target 'manifest.json') -Encoding UTF8

$old = Get-ChildItem -LiteralPath $backupRoot -Directory -Filter 'backup-*' | Sort-Object Name -Descending | Select-Object -Skip $RetentionCount
foreach ($item in $old) {
    if ($item.Name -notmatch '^backup-\d{8}T\d{9}Z$') { throw "Refusing unexpected retention candidate name: $($item.Name)" }
    $safeOld = Resolve-SafeBackupPath $item.FullName $backupRoot $true
    Assert-NoReparseInExistingChain $safeOld $backupRoot
    Assert-NoBackupTreeReparsePoint $safeOld
    Remove-Item -LiteralPath $safeOld -Recurse -Force
}
Write-Output $target
