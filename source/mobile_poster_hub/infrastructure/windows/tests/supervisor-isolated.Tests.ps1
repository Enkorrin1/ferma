[CmdletBinding()]
param()
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$root = Split-Path -Parent $here
$testRoot = Join-Path ([IO.Path]::GetTempPath()) ("farm-hub-supervisor-test-" + [Guid]::NewGuid().ToString('N'))
$passed = 0

function Assert-True([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw "ASSERT FAILED: $Message" }
    $script:passed++
}
function Assert-Throws([scriptblock]$Action, [string]$Message) {
    try { & $Action; throw "ASSERT FAILED: $Message" } catch {
        if ($_.Exception.Message -like 'ASSERT FAILED:*') { throw }
        $script:passed++
    }
}
function Copy-BackupFixture([string]$Source, [string]$Name, [string]$Parent) {
    $destination = Join-Path $Parent $Name
    Copy-Item -LiteralPath $Source -Destination $destination -Recurse
    return $destination
}
function Read-FixtureManifest([string]$Fixture) {
    return Get-Content -LiteralPath (Join-Path $Fixture 'manifest.json') -Raw | ConvertFrom-Json
}
function Write-FixtureManifest([string]$Fixture, $Manifest) {
    $Manifest | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $Fixture 'manifest.json') -Encoding UTF8
}

try {
    [IO.Directory]::CreateDirectory($testRoot) | Out-Null
    $configPath = Join-Path $testRoot 'test-config.psd1'
    $escapedRoot = $testRoot.Replace("'", "''")
    @"
@{
 AllowedRoots=@('$escapedRoot'); StateDirectory='$escapedRoot\state'; LogDirectory='$escapedRoot\logs'; PollSeconds=1; HealthTimeoutSeconds=1
 Restart=@{InitialDelaySeconds=1;MaximumDelaySeconds=2;StableResetSeconds=2}; LogRotation=@{MaximumBytes=8;KeepFiles=2}; Children=@()
}
"@ | Set-Content -LiteralPath $configPath -Encoding UTF8
    . (Join-Path $root 'supervisor.ps1') -ConfigPath $configPath -LibraryOnly

    $safe = Assert-PathUnderAllowedRoot -Path (Join-Path $testRoot 'new') -AllowedRoots @($testRoot) -AllowMissing
    Assert-True ($safe.StartsWith($testRoot, [StringComparison]::OrdinalIgnoreCase)) 'safe child path accepted'
    Assert-Throws { Assert-PathUnderAllowedRoot -Path ([IO.Path]::GetPathRoot($testRoot)) -AllowedRoots @($testRoot) -AllowMissing } 'broad/outside path rejected'

    $log = Join-Path $testRoot 'rotate.log'
    '0123456789' | Set-Content -LiteralPath $log
    Rotate-LogFile -Path $log -MaximumBytes 8 -KeepFiles 2
    Assert-True (Test-Path -LiteralPath "$log.1") 'oversized log rotated'
    Assert-True (-not (Test-Path -LiteralPath $log)) 'active file moved atomically'

    $self = [Diagnostics.Process]::GetCurrentProcess()
    $identity = Get-ProcessIdentity $self
    Assert-True (Test-OwnedProcess $identity) 'exact PID/start/executable identity recognized'
    $bad = @{} + $identity
    $bad.startTimeUtc = [DateTime]::UtcNow.AddDays(-1).ToString('o')
    Assert-True (-not (Test-OwnedProcess $bad)) 'recycled or mismatched PID refused'

    $runtime = Join-Path $testRoot 'runtime'
    $evidence = Join-Path $runtime 'evidence'
    $backups = Join-Path $runtime 'backups'
    [IO.Directory]::CreateDirectory($evidence) | Out-Null
    [IO.File]::WriteAllBytes((Join-Path $evidence 'proof.png'), [byte[]](1,2,3,4,5))
    [IO.File]::WriteAllBytes((Join-Path $evidence 'proof-two.png'), [byte[]](5,4,3,2,1))
    $db = Join-Path $runtime 'hub.sqlite3'
    $pythonExe = (Get-Command python.exe -ErrorAction SilentlyContinue).Source
    if (-not $pythonExe) { $pythonExe = (Get-Command python -ErrorAction Stop).Source }

    $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
    $listener.Start(); $port = ([Net.IPEndPoint]$listener.LocalEndpoint).Port; $listener.Stop()
    $childConfig = @{
        AllowedRoots = @($testRoot, (Split-Path -Parent $pythonExe))
        LogDirectory = (Join-Path $testRoot 'child-logs')
        LogRotation = @{ MaximumBytes = 1048576; KeepFiles = 2 }
        Restart = @{ InitialDelaySeconds = 1; MaximumDelaySeconds = 2; StableResetSeconds = 2 }
    }
    $childSpec = @{
        Name = 'isolated-health'
        Executable = $pythonExe
        Arguments = @('-m','http.server',[string]$port,'--bind','127.0.0.1','--directory',$testRoot)
        WorkingDirectory = $testRoot
        HealthUrl = "http://127.0.0.1:$port/"
        Environment = @{}
    }
    $child = Start-OwnedChild $childSpec $childConfig
    $healthy = $false
    foreach ($probe in 1..20) {
        if (Test-ChildHealth $childSpec.HealthUrl 1) { $healthy = $true; break }
        Start-Sleep -Milliseconds 100
    }
    Assert-True $healthy 'isolated child reaches local health endpoint'
    Assert-True (Test-OwnedProcess $child.Identity) 'started child identity remains owned'
    Assert-True (Stop-OwnedChild $child 1) 'owned isolated child stopped'
    Assert-True (-not (Test-OwnedProcess $child.Identity)) 'stopped child is no longer live'
    $wrongChild = @{ Identity = (@{} + $child.Identity) }
    $wrongChild.Identity.startTimeUtc = [DateTime]::UtcNow.AddDays(-2).ToString('o')
    Assert-True (-not (Stop-OwnedChild $wrongChild 0)) 'unowned/recycled PID is never stopped'

    & $pythonExe -c "import sqlite3,sys; c=sqlite3.connect(sys.argv[1]); c.execute('create table jobs(id text primary key,status text)'); c.execute('insert into jobs values (?,?)',('isolated','pending')); c.commit(); c.close()" $db
    Assert-True ($LASTEXITCODE -eq 0) 'isolated SQLite fixture created'

    $backup = & (Join-Path $root 'backup.ps1') -DatabasePath $db -EvidenceDirectory $evidence -DestinationRoot $backups -AllowedRoot $runtime -PythonPath $pythonExe -RetentionCount 2
    $backup = [string]($backup | Select-Object -Last 1)
    Assert-True (Test-Path -LiteralPath (Join-Path $backup 'manifest.json')) 'manifest created'
    Assert-True (Test-Path -LiteralPath (Join-Path $backup 'evidence\proof.png')) 'evidence copied'
    $restore = Join-Path $runtime 'restored'
    & (Join-Path $root 'restore.ps1') -BackupDirectory $backup -NewTargetDirectory $restore -AllowedRoot $runtime -PythonPath $pythonExe | Out-Null
    Assert-True (Test-Path -LiteralPath (Join-Path $restore 'hub.sqlite3')) 'database restored to new target'
    Assert-True ((Get-FileHash (Join-Path $restore 'evidence\proof.png')).Hash -eq (Get-FileHash (Join-Path $evidence 'proof.png')).Hash) 'evidence hash preserved'
    Assert-Throws { & (Join-Path $root 'restore.ps1') -BackupDirectory $backup -NewTargetDirectory $restore -AllowedRoot $runtime -PythonPath $pythonExe } 'existing restore target refused'

    $traversalFixture = Copy-BackupFixture $backup 'malicious-traversal' $runtime
    $traversalManifest = Read-FixtureManifest $traversalFixture
    $traversalManifest.files[0].path = '../escape.sqlite3'
    Write-FixtureManifest $traversalFixture $traversalManifest
    Assert-Throws { & (Join-Path $root 'restore.ps1') -BackupDirectory $traversalFixture -NewTargetDirectory (Join-Path $runtime 'restore-traversal') -AllowedRoot $runtime -PythonPath $pythonExe } '../ traversal rejected'

    $absoluteFixture = Copy-BackupFixture $backup 'malicious-absolute' $runtime
    $absoluteManifest = Read-FixtureManifest $absoluteFixture
    $absoluteManifest.files[0].path = 'C:\Windows\win.ini'
    Write-FixtureManifest $absoluteFixture $absoluteManifest
    Assert-Throws { & (Join-Path $root 'restore.ps1') -BackupDirectory $absoluteFixture -NewTargetDirectory (Join-Path $runtime 'restore-absolute') -AllowedRoot $runtime -PythonPath $pythonExe } 'absolute manifest path rejected'

    $uncFixture = Copy-BackupFixture $backup 'malicious-unc' $runtime
    $uncManifest = Read-FixtureManifest $uncFixture
    $uncManifest.files[0].path = '\\server\share\hub.sqlite3'
    Write-FixtureManifest $uncFixture $uncManifest
    Assert-Throws { & (Join-Path $root 'restore.ps1') -BackupDirectory $uncFixture -NewTargetDirectory (Join-Path $runtime 'restore-unc') -AllowedRoot $runtime -PythonPath $pythonExe } 'UNC manifest path rejected'

    $adsFixture = Copy-BackupFixture $backup 'malicious-ads' $runtime
    $adsManifest = Read-FixtureManifest $adsFixture
    $adsManifest.files[0].path = 'hub.sqlite3:payload'
    Write-FixtureManifest $adsFixture $adsManifest
    Assert-Throws { & (Join-Path $root 'restore.ps1') -BackupDirectory $adsFixture -NewTargetDirectory (Join-Path $runtime 'restore-ads') -AllowedRoot $runtime -PythonPath $pythonExe } 'ADS manifest path rejected'

    $duplicateFixture = Copy-BackupFixture $backup 'malicious-duplicate' $runtime
    $duplicateManifest = Read-FixtureManifest $duplicateFixture
    $databaseRecord = $duplicateManifest.files | Where-Object { $_.path -eq 'hub.sqlite3' } | Select-Object -First 1
    $duplicateRecord = [pscustomobject]@{ path='HUB.SQLITE3'; size=$databaseRecord.size; sha256=$databaseRecord.sha256 }
    $duplicateManifest.files = @($duplicateManifest.files) + @($duplicateRecord)
    Write-FixtureManifest $duplicateFixture $duplicateManifest
    Assert-Throws { & (Join-Path $root 'restore.ps1') -BackupDirectory $duplicateFixture -NewTargetDirectory (Join-Path $runtime 'restore-duplicate') -AllowedRoot $runtime -PythonPath $pythonExe } 'case-insensitive duplicate normalized path rejected'

    $missingDbFixture = Copy-BackupFixture $backup 'malicious-missing-root-db' $runtime
    $missingDbManifest = Read-FixtureManifest $missingDbFixture
    $missingDbManifest.files = @($missingDbManifest.files | Where-Object { $_.path -ne 'hub.sqlite3' })
    Write-FixtureManifest $missingDbFixture $missingDbManifest
    Assert-Throws { & (Join-Path $root 'restore.ps1') -BackupDirectory $missingDbFixture -NewTargetDirectory (Join-Path $runtime 'restore-missing-db') -AllowedRoot $runtime -PythonPath $pythonExe } 'exactly one root hub.sqlite3 required'

    Assert-Throws { & (Join-Path $root 'restore.ps1') -BackupDirectory $backup -NewTargetDirectory "$runtime-evil\restore" -AllowedRoot $runtime -PythonPath $pythonExe } 'prefix-collision target rejected by exact containment'

    $junctionFixture = Copy-BackupFixture $backup 'malicious-junction' $runtime
    $junctionOutside = Join-Path $runtime 'junction-outside'
    [IO.Directory]::CreateDirectory($junctionOutside) | Out-Null
    [IO.File]::WriteAllBytes((Join-Path $junctionOutside 'outside.bin'), [byte[]](9,8,7))
    $junctionPath = Join-Path $junctionFixture 'evidence-link'
    try {
        New-Item -ItemType Junction -Path $junctionPath -Target $junctionOutside -ErrorAction Stop | Out-Null
        $junctionManifest = Read-FixtureManifest $junctionFixture
        $outsideFile = Join-Path $junctionPath 'outside.bin'
        $junctionManifest.files = @($junctionManifest.files) + @([pscustomobject]@{ path='evidence-link/outside.bin'; size=(Get-Item $outsideFile).Length; sha256=(Get-FileHash $outsideFile).Hash })
        Write-FixtureManifest $junctionFixture $junctionManifest
        Assert-Throws { & (Join-Path $root 'restore.ps1') -BackupDirectory $junctionFixture -NewTargetDirectory (Join-Path $runtime 'restore-junction') -AllowedRoot $runtime -PythonPath $pythonExe } 'junction/reparse manifest path rejected'
    } catch {
        if (Test-Path -LiteralPath $junctionPath) { throw }
        Write-Output "SKIP: junction creation unavailable: $($_.Exception.Message)"
    }

    $outsideDbRoot = Join-Path $testRoot 'outside-database'
    [IO.Directory]::CreateDirectory($outsideDbRoot) | Out-Null
    $outsideDb = Join-Path $outsideDbRoot 'hub.sqlite3'
    Copy-Item -LiteralPath $db -Destination $outsideDb
    $databaseLink = Join-Path $runtime 'database-link'
    New-Item -ItemType Junction -Path $databaseLink -Target $outsideDbRoot -ErrorAction Stop | Out-Null
    Assert-Throws { & (Join-Path $root 'backup.ps1') -DatabasePath (Join-Path $databaseLink 'hub.sqlite3') -EvidenceDirectory $evidence -DestinationRoot (Join-Path $runtime 'db-link-backups') -AllowedRoot $runtime -PythonPath $pythonExe } 'database ancestor junction rejected'
    Assert-True (Test-Path -LiteralPath $outsideDb) 'database junction target survives refusal'

    $outsideEvidence = Join-Path $testRoot 'outside-evidence'
    [IO.Directory]::CreateDirectory($outsideEvidence) | Out-Null
    $outsideEvidenceSentinel = Join-Path $outsideEvidence 'sentinel.bin'
    [IO.File]::WriteAllBytes($outsideEvidenceSentinel, [byte[]](4,3,2,1))
    $evidenceLink = Join-Path $runtime 'evidence-link'
    New-Item -ItemType Junction -Path $evidenceLink -Target $outsideEvidence -ErrorAction Stop | Out-Null
    Assert-Throws { & (Join-Path $root 'backup.ps1') -DatabasePath $db -EvidenceDirectory $evidenceLink -DestinationRoot (Join-Path $runtime 'evidence-link-backups') -AllowedRoot $runtime -PythonPath $pythonExe } 'evidence ancestor junction rejected'
    Assert-True (Test-Path -LiteralPath $outsideEvidenceSentinel) 'evidence junction sentinel survives refusal'

    $outsideDestination = Join-Path $testRoot 'outside-destination'
    [IO.Directory]::CreateDirectory($outsideDestination) | Out-Null
    $outsideDestinationSentinel = Join-Path $outsideDestination 'sentinel.keep'
    'keep' | Set-Content -LiteralPath $outsideDestinationSentinel
    $destinationLink = Join-Path $runtime 'destination-link'
    New-Item -ItemType Junction -Path $destinationLink -Target $outsideDestination -ErrorAction Stop | Out-Null
    Assert-Throws { & (Join-Path $root 'backup.ps1') -DatabasePath $db -EvidenceDirectory $evidence -DestinationRoot $destinationLink -AllowedRoot $runtime -PythonPath $pythonExe } 'DestinationRoot junction rejected before create'
    Assert-True ((Get-Content -LiteralPath $outsideDestinationSentinel -Raw).Trim() -eq 'keep') 'destination sentinel survives refusal'

    $injectedOutside = Join-Path $testRoot 'outside-injected-target'
    [IO.Directory]::CreateDirectory($injectedOutside) | Out-Null
    $injectedSentinel = Join-Path $injectedOutside 'sentinel.keep'
    'keep' | Set-Content -LiteralPath $injectedSentinel
    $injectionHook = {
        param($CreatedTarget)
        Remove-Item -LiteralPath $CreatedTarget -Recurse -Force
        New-Item -ItemType Junction -Path $CreatedTarget -Target $injectedOutside -ErrorAction Stop | Out-Null
    }.GetNewClosure()
    Assert-Throws { & (Join-Path $root 'backup.ps1') -DatabasePath $db -EvidenceDirectory $evidence -DestinationRoot (Join-Path $runtime 'injection-backups') -AllowedRoot $runtime -PythonPath $pythonExe -BeforeEvidenceCopyTestHook $injectionHook } 'target reparse injection rejected immediately before evidence copy'
    Assert-True ((Get-Content -LiteralPath $injectedSentinel -Raw).Trim() -eq 'keep') 'injected target sentinel survives refusal'
    Assert-True (-not (Test-Path -LiteralPath (Join-Path $injectedOutside 'evidence\proof.png'))) 'no evidence copied through injected target junction'

    $childInjectionOutside = Join-Path $testRoot 'outside-injected-evidence-child'
    [IO.Directory]::CreateDirectory($childInjectionOutside) | Out-Null
    $childInjectionSentinel = Join-Path $childInjectionOutside 'sentinel.keep'
    'keep' | Set-Content -LiteralPath $childInjectionSentinel
    $childInjectionHook = {
        param($CreatedTarget)
        $child = Join-Path $CreatedTarget 'evidence'
        Remove-Item -LiteralPath $child -Recurse -Force
        New-Item -ItemType Junction -Path $child -Target $childInjectionOutside -ErrorAction Stop | Out-Null
    }.GetNewClosure()
    Assert-Throws { & (Join-Path $root 'backup.ps1') -DatabasePath $db -EvidenceDirectory $evidence -DestinationRoot (Join-Path $runtime 'child-injection-backups') -AllowedRoot $runtime -PythonPath $pythonExe -BeforeEvidenceCopyTestHook $childInjectionHook } 'target evidence child junction rejected immediately before copy'
    Assert-True ((Get-Content -LiteralPath $childInjectionSentinel -Raw).Trim() -eq 'keep') 'pre-copy child junction sentinel survives refusal'
    Assert-True (-not (Test-Path -LiteralPath (Join-Path $childInjectionOutside 'proof.png'))) 'pre-copy child junction receives no evidence'

    $midCopyOutside = Join-Path $testRoot 'outside-mid-copy-evidence-child'
    [IO.Directory]::CreateDirectory($midCopyOutside) | Out-Null
    $midCopySentinel = Join-Path $midCopyOutside 'sentinel.keep'
    'keep' | Set-Content -LiteralPath $midCopySentinel
    $midCopyHook = {
        param($CreatedTarget, $Source, $Destination, $Index)
        if ($Index -eq 1) {
            $child = Join-Path $CreatedTarget 'evidence'
            Remove-Item -LiteralPath $child -Recurse -Force
            New-Item -ItemType Junction -Path $child -Target $midCopyOutside -ErrorAction Stop | Out-Null
        }
    }.GetNewClosure()
    Assert-Throws { & (Join-Path $root 'backup.ps1') -DatabasePath $db -EvidenceDirectory $evidence -DestinationRoot (Join-Path $runtime 'mid-copy-injection-backups') -AllowedRoot $runtime -PythonPath $pythonExe -AfterEvidenceEntryCopyTestHook $midCopyHook } 'evidence child injection after first file rejected before second copy'
    Assert-True ((Get-Content -LiteralPath $midCopySentinel -Raw).Trim() -eq 'keep') 'mid-copy junction sentinel survives refusal'
    Assert-True (@(Get-ChildItem -LiteralPath $midCopyOutside -File).Count -eq 1) 'no copied evidence escapes through mid-copy junction'

    $retentionRoot = Join-Path $runtime 'retention-backups'
    [IO.Directory]::CreateDirectory($retentionRoot) | Out-Null
    $retentionOutside = Join-Path $testRoot 'outside-retention'
    [IO.Directory]::CreateDirectory($retentionOutside) | Out-Null
    $retentionSentinel = Join-Path $retentionOutside 'sentinel.keep'
    'keep' | Set-Content -LiteralPath $retentionSentinel
    $retentionLink = Join-Path $retentionRoot 'backup-20000101T000000000Z'
    New-Item -ItemType Junction -Path $retentionLink -Target $retentionOutside -ErrorAction Stop | Out-Null
    Assert-Throws { & (Join-Path $root 'backup.ps1') -DatabasePath $db -EvidenceDirectory $evidence -DestinationRoot $retentionRoot -AllowedRoot $runtime -PythonPath $pythonExe -RetentionCount 1 } 'retention backup junction rejected immediately before recursive delete'
    Assert-True ((Get-Content -LiteralPath $retentionSentinel -Raw).Trim() -eq 'keep') 'retention outside sentinel survives and is not deleted'

    $tampered = Join-Path $backup 'evidence\proof.png'
    [IO.File]::AppendAllText($tampered, 'tamper')
    Assert-Throws { & (Join-Path $root 'restore.ps1') -BackupDirectory $backup -NewTargetDirectory (Join-Path $runtime 'tampered-restore') -AllowedRoot $runtime -PythonPath $pythonExe } 'manifest tamper rejected'

    $secret = Join-Path $testRoot 'secret.env'
    'placeholder=value' | Set-Content -LiteralPath $secret
    $acl = Get-Acl -LiteralPath $secret
    $broadBefore = @($acl.Access | Where-Object { $_.IdentityReference.Value -match '(?i)(Authenticated Users|BUILTIN\\Users|Everyone)' }).Count
    Assert-True ($broadBefore -ge 0) 'ACL can be inspected without reading secret value'
    $identityName = [Security.Principal.WindowsIdentity]::GetCurrent().Name
    & (Join-Path $root 'protect-secrets.ps1') -Paths @($secret) -ServiceIdentity $identityName | Out-Null
    $protectedAcl = Get-Acl -LiteralPath $secret
    $broadAfter = @($protectedAcl.Access | Where-Object { $_.AccessControlType -eq 'Allow' -and $_.IdentityReference.Value -match '(?i)(Authenticated Users|BUILTIN\\Users|Everyone)' }).Count
    Assert-True ($protectedAcl.AreAccessRulesProtected) 'secret ACL inheritance disabled'
    Assert-True ($broadAfter -eq 0) 'secret ACL has no broad allow entries'

    Write-Output "PASS: $passed isolated assertions"
} finally {
    if (Test-Path -LiteralPath $testRoot) { Remove-Item -LiteralPath $testRoot -Recurse -Force }
}
