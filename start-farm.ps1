$ErrorActionPreference = 'Stop'
$supervisor = Join-Path $PSScriptRoot 'source\mobile_poster_hub\infrastructure\windows\supervisor.ps1'
$config = Join-Path $PSScriptRoot 'artifacts\hub-windows-production\config.psd1'
& $supervisor -ConfigPath $config
