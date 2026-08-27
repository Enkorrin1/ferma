[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$ConfigPath,
    [int]$TimeoutSeconds = 30
)
$ErrorActionPreference = 'Stop'
$config = Import-PowerShellDataFile -LiteralPath (Resolve-Path -LiteralPath $ConfigPath).Path
if (-not $config.ContainsKey('StopFilePath') -or [string]::IsNullOrWhiteSpace([string]$config.StopFilePath)) {
    throw 'StopFilePath is required in supervisor config.'
}
$stopFile = [IO.Path]::GetFullPath([string]$config.StopFilePath)
$allowed = $false
foreach ($rootValue in [string[]]$config.AllowedRoots) {
    $root = [IO.Path]::GetFullPath($rootValue).TrimEnd('\') + '\'
    if (($stopFile + '\').StartsWith($root, [StringComparison]::OrdinalIgnoreCase)) { $allowed = $true; break }
}
if (-not $allowed) { throw 'StopFilePath is outside AllowedRoots.' }
[IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($stopFile)) | Out-Null
[IO.File]::WriteAllText($stopFile, [DateTime]::UtcNow.ToString('o'), [Text.Encoding]::UTF8)
$deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
while ([DateTime]::UtcNow -lt $deadline) {
    if (-not (Test-Path -LiteralPath $stopFile)) { return }
    Start-Sleep -Milliseconds 250
}
throw 'Supervisor did not consume the stop request within the timeout.'
