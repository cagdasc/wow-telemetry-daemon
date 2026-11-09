param(
    [Parameter(Mandatory = $true)]
    [string]$LogDirectory
)

$ErrorActionPreference = "Stop"
$daemonPath = Join-Path $PSScriptRoot "wow-telemetry-daemon.exe"

if (-not (Test-Path -LiteralPath $daemonPath -PathType Leaf)) {
    throw "Daemon executable not found: $daemonPath"
}
$daemonHelp = (& $daemonPath --help 2>&1 | Out-String)
$daemonExitCode = $LASTEXITCODE
if ($daemonExitCode -ne 0 -or $daemonHelp -notmatch '--from-start') {
    throw "Daemon executable does not support --from-start. Rebuild it from the current source and replace: $daemonPath"
}
if (-not (Test-Path -LiteralPath $LogDirectory -PathType Container)) {
    throw "Combat log directory not found: $LogDirectory. Edit LOG_DIR in run-windows.bat or set WOW_COMBAT_LOG_DIR."
}

$existingLogs = @(Get-ChildItem -LiteralPath $LogDirectory -File -Filter "WoWCombatLog-*.txt")
$existingNames = @{}
foreach ($file in $existingLogs) {
    $existingNames[$file.Name] = $true
}

$backupDirectory = Join-Path $LogDirectory "Backups\$(Get-Date -Format 'yyyyMMdd-HHmmss')-$PID"
New-Item -ItemType Directory -Path $backupDirectory -Force | Out-Null
foreach ($file in $existingLogs) {
    Copy-Item -LiteralPath $file.FullName -Destination $backupDirectory
}
foreach ($file in $existingLogs) {
    Remove-Item -LiteralPath $file.FullName
}
Write-Host "Backed up and removed $($existingLogs.Count) existing combat log(s): $backupDirectory"
Write-Host "Waiting for a new WoWCombatLog-MMDDYY_HHMMSS.txt file..."

while ($true) {
    $newFile = Get-ChildItem -LiteralPath $LogDirectory -File -Filter "WoWCombatLog-*.txt" |
        Where-Object {
            $_.Name -match '^WoWCombatLog-\d{6}_\d{6}\.txt$' -and
                -not $existingNames.ContainsKey($_.Name)
        } |
        Sort-Object -Property CreationTimeUtc |
        Select-Object -First 1

    if ($null -ne $newFile) {
        break
    }
    Start-Sleep -Milliseconds 200
}

Write-Host "Starting daemon on new combat log: $($newFile.FullName)"
Write-Host "Starting WebSocket listener on 0.0.0.0:8765"
& $daemonPath $newFile.FullName --from-start --ws-addr "0.0.0.0:8765"
exit $LASTEXITCODE
