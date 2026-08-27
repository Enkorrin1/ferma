[CmdletBinding(SupportsShouldProcess)]
param(
    [Parameter(Mandatory=$true)][string[]]$Paths,
    [Parameter(Mandatory=$true)][string]$ServiceIdentity
)
$ErrorActionPreference = 'Stop'
$systemSid = [Security.Principal.SecurityIdentifier]::new('S-1-5-18')
$administratorsSid = [Security.Principal.SecurityIdentifier]::new('S-1-5-32-544')
$serviceAccount = [Security.Principal.NTAccount]::new($ServiceIdentity)
$serviceSid = $serviceAccount.Translate([Security.Principal.SecurityIdentifier])
$allowed = @($systemSid, $administratorsSid, $serviceSid)
foreach ($rawPath in $Paths) {
    $path = (Resolve-Path -LiteralPath $rawPath).Path
    if ($PSCmdlet.ShouldProcess($path, 'Replace ACL with service-only access')) {
        $acl = [System.Security.AccessControl.FileSecurity]::new()
        $acl.SetAccessRuleProtection($true, $false)
        foreach ($identity in $allowed | Sort-Object Value -Unique) {
            $rule = [System.Security.AccessControl.FileSystemAccessRule]::new($identity, 'FullControl', 'Allow')
            $acl.AddAccessRule($rule)
        }
        Set-Acl -LiteralPath $path -AclObject $acl
    }
    $actual = Get-Acl -LiteralPath $path
    $broad = $actual.Access | Where-Object {
        $_.AccessControlType -eq 'Allow' -and $_.IdentityReference.Value -match '(?i)(Authenticated Users|BUILTIN\\Users|Everyone)'
    }
    if ($broad) { throw "Broad secret ACL remains on $path" }
}
Write-Output 'Secret ACL validation passed.'
