param(
    [Parameter(Mandatory = $true)]
    [string]$LogDirectory
)

$ErrorActionPreference = "Stop"
$daemonPath = Join-Path $PSScriptRoot "wow-telemetry-daemon.exe"

if (-not (Test-Path -LiteralPath $daemonPath -PathType Leaf)) {
    throw "Daemon executable not found: $daemonPath"
}
if (-not (Test-Path -LiteralPath $LogDirectory -PathType Container)) {
    throw "Combat log directory not found: $LogDirectory. Edit LOG_DIR in run-windows.bat or set WOW_COMBAT_LOG_DIR."
}

$culture = [Globalization.CultureInfo]::InvariantCulture
$styles = [Globalization.DateTimeStyles]::None
$latest = Get-ChildItem -LiteralPath $LogDirectory -File -Filter "WoWCombatLog-*.txt" |
    ForEach-Object {
        if ($_.BaseName -match '^WoWCombatLog-(\d{6}_\d{6})$') {
            $timestamp = [DateTime]::MinValue
            if ([DateTime]::TryParseExact(
                $Matches[1],
                "MMddyy_HHmmss",
                $culture,
                $styles,
                [ref]$timestamp
            )) {
                [pscustomobject]@{
                    File = $_
                    Timestamp = $timestamp
                }
            }
        }
    } |
    Sort-Object -Property Timestamp -Descending |
    Select-Object -First 1

if ($null -eq $latest) {
    throw "No WoWCombatLog-MMDDYY_HHMMSS.txt files found in: $LogDirectory"
}

Write-Host "Using latest combat log: $($latest.File.FullName)"
Write-Host "Starting WebSocket listener on 0.0.0.0:8765"
& $daemonPath $latest.File.FullName --ws-addr "0.0.0.0:8765"
exit $LASTEXITCODE
