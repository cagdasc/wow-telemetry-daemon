param(
    [Parameter(Mandatory = $true)]
    [string]$LogDirectory,

    # NewLog: back up and remove existing logs, wait for WoW to create a new one.
    # Latest: read the most recently written existing log from its beginning.
    [ValidateSet("NewLog", "Latest")]
    [string]$Mode = "NewLog",

    [string]$WebSocketAddress = "0.0.0.0:8765"
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

function Get-CombatLogs {
    @(
        Get-ChildItem -LiteralPath $LogDirectory -File |
            Where-Object { $_.Name -like "WoWCombatLog-*.txt" }
    )
}

if ($Mode -eq "Latest") {
    $logFile = Get-CombatLogs |
        Sort-Object -Property LastWriteTimeUtc -Descending |
        Select-Object -First 1
    if ($null -eq $logFile) {
        throw "No WoWCombatLog-*.txt file found in $LogDirectory. Use -Mode NewLog to wait for one."
    }
    Write-Host "Reading latest combat log: $($logFile.FullName)"
}
else {
    $existingLogs = Get-CombatLogs
    $backupDirectory = Join-Path $LogDirectory "Backups\$(Get-Date -Format 'yyyyMMdd-HHmmss')-$PID"
    New-Item -ItemType Directory -Path $backupDirectory -Force | Out-Null
    foreach ($file in $existingLogs) {
        Copy-Item -LiteralPath $file.FullName -Destination $backupDirectory -ErrorAction Stop
    }
    foreach ($file in $existingLogs) {
        Remove-Item -LiteralPath $file.FullName
    }
    Write-Host "Backed up and removed $($existingLogs.Count) existing combat log(s): $backupDirectory"
    Write-Host "Waiting for a new WoWCombatLog-*.txt file..."

    while ($true) {
        $logFile = Get-CombatLogs |
            Sort-Object -Property CreationTimeUtc -Descending |
            Select-Object -First 1
        if ($null -ne $logFile) {
            break
        }
        Start-Sleep -Milliseconds 200
    }
    Write-Host "Found new combat log: $($logFile.FullName)"
}

Write-Host "Starting WebSocket listener on $WebSocketAddress"
& $daemonPath $logFile.FullName --from-start --ws-addr $WebSocketAddress
exit $LASTEXITCODE
