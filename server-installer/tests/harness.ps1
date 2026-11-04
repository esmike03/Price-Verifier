# Runs a real install.ps1 end to end without administrator rights.
#
# Only two things in the script under test are changed, both test-only:
#   - the "must be administrator" check is skipped
#   - the PHP worker ports are moved (the normal ones may be in use on this PC)
# The Task Scheduler and firewall cmdlets are replaced by stand-ins below, which
# keep the task's state in a file and start/stop the real supervisor script.
# Everything else - copying files, writing configuration, caddy validate, the
# supervisor, PHP, the health checks, diagnostics - is the real thing.
param(
    [Parameter(Mandatory)] [string]$PackageDir,
    [Parameter(Mandatory)] [string]$InstallDir,
    [int]$Port = 8098,
    [int[]]$PhpPorts = @(9121, 9122, 9123, 9124),
    [hashtable]$InstallerArgs = @{},
    [switch]$Watchdog,
    # Run interactively: each question is answered with Enter (the default)
    # after this many seconds, the way a person at the keyboard does.
    [int]$AnswerDelaySeconds = 0
)

if ($AnswerDelaySeconds -gt 0) {
    function Read-Host { param($Prompt, [switch]$AsSecureString)
        Write-Host "$Prompt  <-- (waits $AnswerDelaySeconds s, then Enter)"
        Start-Sleep -Seconds $AnswerDelaySeconds
        return ''
    }
}

$StateFile = Join-Path (Split-Path $InstallDir -Parent) ((Split-Path $InstallDir -Leaf) + '.task.json')
$MutexName = 'Local\PVInstallTest-' + (Split-Path $InstallDir -Leaf)

function Get-MockState {
    if (Test-Path $StateFile) { return Get-Content -Raw $StateFile | ConvertFrom-Json }
    return [pscustomobject]@{ Registered = $false; State = 'Ready' }
}
function Set-MockState([bool]$registered, [string]$state) {
    [IO.File]::WriteAllText($StateFile, (@{ Registered = $registered; State = $state } | ConvertTo-Json))
}
function Get-Supervisors {
    @(Get-CimInstance Win32_Process -Filter "Name='powershell.exe'" |
        Where-Object { $_.CommandLine -like "*$InstallDir\run-server.ps1*" -and $_.CommandLine -notlike '*watchdog.ps1*' })
}
function Start-Supervisor {
    $script = Join-Path $InstallDir 'run-server.ps1'
    $text = [IO.File]::ReadAllText($script)
    if ($text.Contains('Global\PriceVerifierServerSupervisor')) {
        [IO.File]::WriteAllText($script, $text.Replace('Global\PriceVerifierServerSupervisor', $MutexName))
    }
    Start-Process powershell.exe -WindowStyle Hidden -ArgumentList @('-NoProfile', '-NonInteractive',
        '-ExecutionPolicy', 'Bypass', '-WindowStyle', 'Hidden', '-File', "`"$script`"")
}

# --- Stand-ins for the cmdlets that need administrator rights ----------------------
function Get-ScheduledTask { param($TaskName, $ErrorAction)
    $s = Get-MockState
    if ($s.Registered) { [pscustomobject]@{ TaskName = $TaskName; State = $s.State } }
}
function Get-ScheduledTaskInfo { param($TaskName, $ErrorAction)
    [pscustomobject]@{ LastRunTime = Get-Date; LastTaskResult = 0 }
}
function Stop-ScheduledTask { param($TaskName, $ErrorAction)
    Get-Supervisors | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
}
function Disable-ScheduledTask { param($TaskName, $ErrorAction)
    Set-MockState $true 'Disabled'; [pscustomobject]@{ TaskName = $TaskName; State = 'Disabled' }
}
function Enable-ScheduledTask { param($TaskName, $ErrorAction)
    Set-MockState $true 'Ready'; [pscustomobject]@{ TaskName = $TaskName; State = 'Ready' }
}
function Register-ScheduledTask { param($TaskName, $Action, $Trigger, $Principal, $Settings, $Description, [switch]$Force)
    # Like the real cmdlet with -Force: the new definition replaces the old one, enabled.
    if (-not $Action -or -not $Trigger -or -not $Principal -or -not $Settings) { throw 'Register-ScheduledTask: incomplete task definition.' }
    Set-MockState $true 'Ready'; [pscustomobject]@{ TaskName = $TaskName; State = 'Ready' }
}
function Start-ScheduledTask { param($TaskName, $ErrorAction)
    $s = Get-MockState
    if (-not $s.Registered) { throw "Start-ScheduledTask: the task '$TaskName' does not exist." }
    if ($s.State -eq 'Disabled') { throw "Start-ScheduledTask: the task '$TaskName' is disabled." }
    Start-Supervisor
}
function Get-NetFirewallRule { param($DisplayName, $ErrorAction) }
function Remove-NetFirewallRule { param([Parameter(ValueFromPipeline)] $Rule) process { } }
function New-NetFirewallRule { }
# The Ororama installer locks its pos-sync settings file to SYSTEM and
# administrators. Without administrator rights that would lock this test out of
# the file, so the call is only recorded.
function icacls.exe { [IO.File]::AppendAllText("$StateFile.icacls.log", ($args -join ' ') + "`r`n"); $global:LASTEXITCODE = 0 }

# --- The script under test ---------------------------------------------------------
$real = Join-Path $PackageDir 'install.ps1'
$text = [IO.File]::ReadAllText($real)
$adminCheck = [regex]'(?s)\$identity = \[Security\.Principal\.WindowsPrincipal\].*?\r?\n\}\r?\n'
if (-not $adminCheck.IsMatch($text)) { throw 'harness: administrator check not found in install.ps1' }
$text = $adminCheck.Replace($text, "# (administrator check skipped by the test harness)`r`n", 1)
$portsLine = '$PhpPorts = @(9101, 9102, 9103, 9104)'
if (-not $text.Contains($portsLine)) { throw 'harness: PHP ports line not found in install.ps1' }
$text = $text.Replace($portsLine, '$PhpPorts = @(' + ($PhpPorts -join ', ') + ')')
$underTest = Join-Path $PackageDir 'install-undertest.ps1'
[IO.File]::WriteAllText($underTest, $text)

# The task's own triggers: every 2 seconds here (5 minutes for real), a task
# that is registered and enabled starts the server again if it is not running.
$watchdogProcess = $null
if ($Watchdog) {
    $watchdogScript = Join-Path $PSScriptRoot 'watchdog.ps1'
    $supervisor = Join-Path $InstallDir 'run-server.ps1'
    $watchdogProcess = Start-Process powershell.exe -WindowStyle Hidden -PassThru -ArgumentList @('-NoProfile', '-ExecutionPolicy', 'Bypass',
        '-File', "`"$watchdogScript`"", '-Name', (Split-Path $InstallDir -Leaf), '-MutexName', "`"$MutexName`"")
    Start-Sleep -Seconds 3
    if ($watchdogProcess.HasExited) { throw "harness: the watchdog stopped at once (exit code $($watchdogProcess.ExitCode))." }
    if (-not (Test-Path "$StateFile.watchdog.log")) { throw 'harness: the watchdog is not writing its log.' }
}

try {
    if ($AnswerDelaySeconds -gt 0) {
        & $underTest -InstallDir $InstallDir @InstallerArgs
    } else {
        & $underTest -Port $Port -InstallDir $InstallDir -Quiet @InstallerArgs
    }
    Write-Host "HARNESS: installer finished without an exit code."
} finally {
    if ($watchdogProcess) { Stop-Process -Id $watchdogProcess.Id -Force -ErrorAction SilentlyContinue }
}
