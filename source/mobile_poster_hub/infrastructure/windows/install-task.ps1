[CmdletBinding(SupportsShouldProcess)]
param(
    [Parameter(Mandatory=$true)][string]$SupervisorPath,
    [Parameter(Mandatory=$true)][string]$ConfigPath,
    [string]$TaskName = 'FarmHubSupervisor',
    [string]$ServiceIdentity = 'SYSTEM',
    [PSCredential]$Credential
)
$ErrorActionPreference = 'Stop'
$supervisor = (Resolve-Path -LiteralPath $SupervisorPath).Path
$config = (Resolve-Path -LiteralPath $ConfigPath).Path
$pwsh = (Get-Command pwsh.exe -ErrorAction Stop).Source
$quotedArgs = "-NoLogo -NoProfile -NonInteractive -ExecutionPolicy RemoteSigned -File `"$supervisor`" -ConfigPath `"$config`""
$action = New-ScheduledTaskAction -Execute $pwsh -Argument $quotedArgs
$trigger = New-ScheduledTaskTrigger -AtStartup
$settings = New-ScheduledTaskSettingsSet -RestartCount 999 -RestartInterval (New-TimeSpan -Minutes 1) -ExecutionTimeLimit ([TimeSpan]::Zero) -StartWhenAvailable
if ($PSCmdlet.ShouldProcess($TaskName, 'Register startup supervisor task')) {
    if ($ServiceIdentity -eq 'SYSTEM') {
        $principal = New-ScheduledTaskPrincipal -UserId 'SYSTEM' -LogonType ServiceAccount -RunLevel Highest
        Register-ScheduledTask -TaskName $TaskName -Action $action -Trigger $trigger -Settings $settings -Principal $principal -Force | Out-Null
    } else {
        if ($null -eq $Credential -or $Credential.UserName -ne $ServiceIdentity) {
            throw 'A matching PSCredential is required for a dedicated service identity. It is used in memory and is never written by this script.'
        }
        Register-ScheduledTask -TaskName $TaskName -Action $action -Trigger $trigger -Settings $settings -User $ServiceIdentity -Password $Credential.GetNetworkCredential().Password -RunLevel Highest -Force | Out-Null
    }
}
