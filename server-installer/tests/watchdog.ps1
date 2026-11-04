# Stand-in for the scheduled task's own triggers: every 2 seconds (5 minutes for
# real), a task that is registered and enabled starts the server again when no
# supervisor is running.
param(
    # Only the folder NAME is passed: the installer stops every PowerShell whose
    # command line mentions the supervisor script path, and this must survive that.
    [Parameter(Mandatory)] [string]$Name,
    [Parameter(Mandatory)] [string]$MutexName,
    [int]$Seconds = 300
)

$StateFile = Join-Path $PSScriptRoot ($Name + '.task.json')
$Supervisor = Join-Path (Join-Path $PSScriptRoot $Name) 'run-server.ps1'
$log = "$StateFile.watchdog.log"
function Write-Line([string]$text) {
    try { Add-Content -Path $log -Value ("{0:HH:mm:ss.fff} {1}" -f (Get-Date), $text) } catch { }
}

Write-Line 'watchdog running'
$until = (Get-Date).AddSeconds($Seconds)
while ((Get-Date) -lt $until) {
    Start-Sleep -Seconds 2
    try {
        if (-not (Test-Path -LiteralPath $StateFile)) { continue }
        $s = Get-Content -Raw -LiteralPath $StateFile | ConvertFrom-Json
        if ($s.State -ne $lastState) { Write-Line "task is $($s.State)"; $lastState = $s.State }
        if (-not $s.Registered -or $s.State -ne 'Ready') { continue }
        if (-not (Test-Path -LiteralPath $Supervisor)) { continue }
        $running = @(Get-CimInstance Win32_Process -Filter "Name='powershell.exe'" |
            Where-Object { $_.ProcessId -ne $PID -and $_.CommandLine -and $_.CommandLine.Contains($Supervisor) -and -not $_.CommandLine.Contains("watchdog.ps1") })
        if ($running.Count -gt 0) { continue }

        $t = [IO.File]::ReadAllText($Supervisor)
        if ($t.Contains('Global\PriceVerifierServerSupervisor')) {
            [IO.File]::WriteAllText($Supervisor, $t.Replace('Global\PriceVerifierServerSupervisor', $MutexName))
        }
        Start-Process powershell.exe -WindowStyle Hidden -ArgumentList @('-NoProfile', '-NonInteractive',
            '-ExecutionPolicy', 'Bypass', '-WindowStyle', 'Hidden', '-File', "`"$Supervisor`"")
        Write-Line 'watchdog started the server'
    } catch {
        Write-Line "watchdog error: $($_.Exception.Message)"
    }
}
Write-Line 'watchdog finished'
