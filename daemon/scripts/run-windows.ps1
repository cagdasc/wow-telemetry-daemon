param(
    [string]$LogDirectory,

    # Interactive (default), NewLog, Latest, Pick, File
    [ValidateSet("Interactive", "NewLog", "Latest", "Pick", "File")]
    [string]$Mode = "Interactive",

    [string]$File,

    [string]$WebSocketAddress = "0.0.0.0:8765",

    # protobuf is the default binary stream; json sends JSON text frames.
    [ValidateSet("protobuf", "json")]
    [string]$Format = "protobuf"
)

$ErrorActionPreference = "Stop"
$daemonDir = (Get-Item $PSScriptRoot).Parent.FullName

# Determine combat log directory
if (-not $LogDirectory) {
    if ($env:WOW_COMBAT_LOG_DIR -and (Test-Path -LiteralPath $env:WOW_COMBAT_LOG_DIR -PathType Container)) {
        $LogDirectory = $env:WOW_COMBAT_LOG_DIR
    }
    elseif (Test-Path "C:\Program Files (x86)\World of Warcraft\_retail_\Logs" -PathType Container) {
        $LogDirectory = "C:\Program Files (x86)\World of Warcraft\_retail_\Logs"
    }
    else {
        $sampleDir = Join-Path $daemonDir "sample_log"
        if (Test-Path $sampleDir -PathType Container) {
            $LogDirectory = $sampleDir
        }
        else {
            $LogDirectory = "C:\Program Files (x86)\World of Warcraft\_retail_\Logs"
        }
    }
}

# Locate daemon executable strictly in the same directory as this script
function Find-DaemonExecutable {
    $daemonPath = Join-Path $PSScriptRoot "wow-telemetry-daemon.exe"
    if (-not (Test-Path -LiteralPath $daemonPath -PathType Leaf)) {
        throw "Daemon executable not found in script directory: $daemonPath. Please build wow-telemetry-daemon.exe and place it beside this script."
    }
    return $daemonPath
}

function Invoke-Daemon {
    param(
        [string[]]$DaemonArgs
    )
    $daemonPath = Find-DaemonExecutable
    & $daemonPath $DaemonArgs
    exit $LASTEXITCODE
}

function Get-CombatLogs {
    param([string]$Dir)
    if (Test-Path -LiteralPath $Dir -PathType Container) {
        @(
            Get-ChildItem -LiteralPath $Dir -File |
                Where-Object { $_.Name -like "WoWCombatLog*.txt" -or $_.Extension -eq ".txt" } |
                Sort-Object -Property LastWriteTime -Descending
        )
    }
    else {
        @()
    }
}

function Format-Size {
    param([long]$Bytes)
    if ($Bytes -ge 1MB) {
        return "{0:N1} MB" -f ($Bytes / 1MB)
    }
    elseif ($Bytes -ge 1KB) {
        return "{0:N1} KB" -f ($Bytes / 1KB)
    }
    return "$Bytes B"
}

function Start-NewLog {
    if (-not (Test-Path -LiteralPath $LogDirectory -PathType Container)) {
        Write-Warning "Combat log directory not found: $LogDirectory"
        $custom = Read-Host "Enter valid combat log directory"
        if (Test-Path -LiteralPath $custom -PathType Container) {
            $script:LogDirectory = $custom
        }
        else {
            throw "Invalid directory: $custom"
        }
    }

    $existingLogs = Get-CombatLogs -Dir $LogDirectory
    if ($existingLogs.Count -gt 0) {
        $backupDirectory = Join-Path $LogDirectory "Backups\$(Get-Date -Format 'yyyyMMdd-HHmmss')-$PID"
        New-Item -ItemType Directory -Path $backupDirectory -Force | Out-Null
        foreach ($f in $existingLogs) {
            Copy-Item -LiteralPath $f.FullName -Destination $backupDirectory -ErrorAction Stop
            Remove-Item -LiteralPath $f.FullName -Force
        }
        Write-Host "Backed up and removed $($existingLogs.Count) log(s) into: $backupDirectory" -ForegroundColor Cyan
    }

    Write-Host "Waiting for WoW to create a new combat log in $LogDirectory..." -ForegroundColor Cyan
    $newLog = $null
    while ($true) {
        $newLog = Get-CombatLogs -Dir $LogDirectory | Select-Object -First 1
        if ($null -ne $newLog) {
            break
        }
        Start-Sleep -Milliseconds 200
    }

    Write-Host "Found new combat log: $($newLog.FullName)" -ForegroundColor Green
    Write-Host "Starting WebSocket stream on ws://$WebSocketAddress ($Format)..." -ForegroundColor Cyan
    Invoke-Daemon -DaemonArgs @($newLog.FullName, "--from-start", "--quiet", "--format", $Format, "--ws-addr", $WebSocketAddress)
}

function Start-LatestLog {
    $latest = Get-CombatLogs -Dir $LogDirectory | Select-Object -First 1
    if ($null -eq $latest) {
        throw "No combat logs found in $LogDirectory. Use NewLog or Pick."
    }

    Write-Host "Using latest combat log: $($latest.FullName)" -ForegroundColor Green
    Write-Host "Starting WebSocket stream on ws://$WebSocketAddress ($Format)..." -ForegroundColor Cyan
    Invoke-Daemon -DaemonArgs @($latest.FullName, "--from-start", "--quiet", "--format", $Format, "--ws-addr", $WebSocketAddress)
}

function Start-PickLog {
    $sampleDir = Join-Path $daemonDir "sample_log"
    $allLogs = @(
        (Get-CombatLogs -Dir $LogDirectory) +
        (Get-CombatLogs -Dir $sampleDir)
    ) | Sort-Object -Property FullName -Unique

    if ($allLogs.Count -eq 0) {
        Write-Warning "No log files found in $LogDirectory or $sampleDir."
        $manual = Read-Host "Enter full path to combat log file"
        if (Test-Path -LiteralPath $manual -PathType Leaf) {
            Start-FileLog -TargetFile $manual
        }
        return
    }

    Write-Host "`nAvailable Combat Logs:" -ForegroundColor White
    for ($i = 0; $i -lt $allLogs.Count; $i++) {
        $f = $allLogs[$i]
        $sz = Format-Size -Bytes $f.Length
        $parent = $f.Directory.Name
        Write-Host ("  [{0,2}] {1,-36} ({2,8}) [{3}]" -f ($i + 1), $f.Name, $sz, $parent) -ForegroundColor Cyan
    }

    Write-Host ""
    $pick = Read-Host ("Select a log file [1-{0}] (or 'q' to cancel)" -f $allLogs.Count)
    if ($pick -match "^\d+$") {
        $idx = [int]$pick - 1
        if ($idx -ge 0 -and $idx -lt $allLogs.Count) {
            Start-FileLog -TargetFile $allLogs[$idx].FullName
        }
    }
}

function Start-FileLog {
    param([string]$TargetFile)
    if (-not (Test-Path -LiteralPath $TargetFile -PathType Leaf)) {
        throw "File not found: $TargetFile"
    }

    Write-Host "Streaming log file: $TargetFile" -ForegroundColor Green
    Write-Host "Waiting for WebSocket client to connect on ws://$WebSocketAddress ($Format)..." -ForegroundColor Cyan
    Write-Host "Once the log is streamed, the daemon will automatically terminate." -ForegroundColor Yellow
    Invoke-Daemon -DaemonArgs @("--file", $TargetFile, "--quiet", "--format", $Format, "--ws-addr", $WebSocketAddress)
}

function Show-Settings {
    Write-Host "`nCurrent Settings:" -ForegroundColor White
    Write-Host "  Log Directory:     $LogDirectory" -ForegroundColor Cyan
    Write-Host "  WebSocket Address: $WebSocketAddress" -ForegroundColor Cyan
    Write-Host "  Stream Format:     $Format" -ForegroundColor Cyan
    Write-Host ""

    $newDir = Read-Host "Change log directory? (press Enter to keep current)"
    if ($newDir -and (Test-Path $newDir -PathType Container)) {
        $script:LogDirectory = $newDir
    }

    $newAddr = Read-Host "Change WebSocket address? (press Enter to keep current)"
    if ($newAddr) {
        $script:WebSocketAddress = $newAddr
    }

    $newFmt = Read-Host "Change stream format (protobuf/json)? (press Enter to keep current)"
    if ($newFmt -eq "protobuf" -or $newFmt -eq "json") {
        $script:Format = $newFmt
    }
    Write-Host "Settings updated.`n" -ForegroundColor Green
}

# Direct mode dispatch
if ($Mode -eq "NewLog") {
    Start-NewLog
}
elseif ($Mode -eq "Latest") {
    Start-LatestLog
}
elseif ($Mode -eq "Pick") {
    Start-PickLog
}
elseif ($Mode -eq "File") {
    if (-not $File) {
        throw "Parameter -File is required when -Mode is File."
    }
    Start-FileLog -TargetFile $File
}
else {
    # Interactive menu loop
    while ($true) {
        Write-Host "`n==================================================" -ForegroundColor White
        Write-Host "         WoW Telemetry Launcher (Windows)         " -ForegroundColor Cyan
        Write-Host "==================================================" -ForegroundColor White
        Write-Host "  Log Folder:  $LogDirectory" -ForegroundColor Yellow
        Write-Host "  WebSocket:   ws://$WebSocketAddress ($Format)" -ForegroundColor Yellow
        Write-Host "--------------------------------------------------" -ForegroundColor White
        Write-Host "  [1] New Live Log     - Back up old logs, wait for WoW to create a new one" -ForegroundColor Green
        Write-Host "  [2] Latest Live Log  - Tail the latest combat log from start" -ForegroundColor Green
        Write-Host "  [3] Pick Existing    - Choose a completed log file to stream & exit" -ForegroundColor Cyan
        Write-Host "  [4] Custom Log File  - Enter path to an existing log file to stream & exit" -ForegroundColor Cyan
        Write-Host "  [5] Settings        - Change directory, WebSocket address, or format" -ForegroundColor White
        Write-Host "  [q] Quit" -ForegroundColor White
        Write-Host "--------------------------------------------------" -ForegroundColor White

        $choice = Read-Host "Choose an option [1-5, q]"
        switch ($choice) {
            "1" { Start-NewLog }
            "2" { Start-LatestLog }
            "3" { Start-PickLog }
            "4" {
                $customPath = Read-Host "Enter path to combat log file"
                if ($customPath) { Start-FileLog -TargetFile $customPath }
            }
            "5" { Show-Settings }
            "q" { exit 0 }
            default { Write-Warning "Invalid choice." }
        }
    }
}
