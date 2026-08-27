[CmdletBinding(SupportsShouldProcess)]
param([string]$TaskName = 'FarmHubSupervisor')
$ErrorActionPreference = 'Stop'
$task = Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
if ($null -ne $task -and $PSCmdlet.ShouldProcess($TaskName, 'Unregister supervisor task')) {
    Stop-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
    Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false
}
