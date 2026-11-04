# Runs one of the Windows installer or uninstaller scripts without administrator
# rights. Unlike harness.ps1 it keeps several scheduled tasks and the firewall
# rules apart by name, so the web portal and pos-sync installers can be tested
# side by side.
#
# Changed in the script under test (test-only):
#   - the "must be administrator" check is skipped
#   - the PHP worker ports are moved, when the script has them
#   - C:\PriceVerifier (where the pos-sync installer looks for a combined
#     install) is pointed at -LegacyDir
# Stand-ins: Task Scheduler cmdlets (state in <StateDir>\task-*.json; "starting"
# a task runs its real supervisor script), firewall cmdlets (rules listed in
# <StateDir>\firewall.json) and icacls.
param(
    [Parameter(Mandatory)] [string]$Script,
    [Parameter(Mandatory)] [string]$StateDir,
    [hashtable]$ScriptArgs = @{},
    [int[]]$PhpPorts = @(),
    [string]$LegacyDir = ''
)

New-Item -ItemType Directory -Force $StateDir | Out-Null
$FirewallFile = Join-Path $StateDir 'firewall.json'

function Get-TaskFile([string]$name) { Join-Path $StateDir ('task-' + ($name -replace '[^A-Za-z0-9]', '_') + '.json') }
function Get-TaskState([string]$name) {
    $file = Get-TaskFile $name
    if (Test-Path $file) { return Get-Content -Raw $file | ConvertFrom-Json }
    return $null
}
function Set-TaskState([string]$name, [string]$state, [string]$script) {
    [IO.File]::WriteAllText((Get-TaskFile $name), (@{ State = $state; Script = $script } | ConvertTo-Json))
}
function Get-Supervisors([string]$script) {
    @(Get-CimInstance Win32_Process -Filter "Name='powershell.exe'" | Where-Object { $_.CommandLine -and $_.CommandLine.Contains($script) })
}

function Get-ScheduledTask { param($TaskName, $ErrorAction)
    $s = Get-TaskState $TaskName
    if ($s) { [pscustomobject]@{ TaskName = $TaskName; State = $s.State } }
}
function Get-ScheduledTaskInfo { param($TaskName, $ErrorAction)
    [pscustomobject]@{ LastRunTime = Get-Date; LastTaskResult = 0 }
}
function Stop-ScheduledTask { param($TaskName, $ErrorAction)
    $s = Get-TaskState $TaskName
    if ($s) { Get-Supervisors $s.Script | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue } }
}
function Disable-ScheduledTask { param($TaskName, $ErrorAction)
    $s = Get-TaskState $TaskName
    if ($s) { Set-TaskState $TaskName 'Disabled' $s.Script; [pscustomobject]@{ TaskName = $TaskName; State = 'Disabled' } }
}
function Enable-ScheduledTask { param($TaskName, $ErrorAction)
    $s = Get-TaskState $TaskName
    if ($s) { Set-TaskState $TaskName 'Ready' $s.Script; [pscustomobject]@{ TaskName = $TaskName; State = 'Ready' } }
}
function Register-ScheduledTask { param($TaskName, $Action, $Trigger, $Principal, $Settings, $Description, [switch]$Force)
    if (-not $Action -or -not $Trigger -or -not $Principal -or -not $Settings) { throw 'Register-ScheduledTask: incomplete task definition.' }
    if ($Action.Arguments -notmatch '-File "([^"]+)"') { throw 'Register-ScheduledTask: no script in the task action.' }
    Set-TaskState $TaskName 'Ready' $Matches[1]
    [pscustomobject]@{ TaskName = $TaskName; State = 'Ready' }
}
function Unregister-ScheduledTask { param($TaskName, $Confirm)
    Remove-Item (Get-TaskFile $TaskName) -Force -ErrorAction SilentlyContinue
}
function Start-ScheduledTask { param($TaskName, $ErrorAction)
    $s = Get-TaskState $TaskName
    if (-not $s) { throw "Start-ScheduledTask: the task '$TaskName' does not exist." }
    if ($s.State -eq 'Disabled') { throw "Start-ScheduledTask: the task '$TaskName' is disabled." }
    # A machine-wide mutex needs rights this test does not have, and must not
    # collide with a really installed server; use a per-test one instead.
    $text = [IO.File]::ReadAllText($s.Script)
    $local = 'Local\PVTest-' + ((Split-Path (Split-Path $s.Script -Parent) -Leaf) -replace '[^A-Za-z0-9]', '') + '-'
    if ($text.Contains("'Global\")) { [IO.File]::WriteAllText($s.Script, $text.Replace("'Global\", "'$local")) }
    Start-Process powershell.exe -WindowStyle Hidden -ArgumentList @('-NoProfile', '-NonInteractive',
        '-ExecutionPolicy', 'Bypass', '-WindowStyle', 'Hidden', '-File', "`"$($s.Script)`"")
}

function Read-Firewall {
    if (-not (Test-Path $FirewallFile)) { return @() }
    # ConvertFrom-Json hands back an array as one object; unroll it.
    $parsed = Get-Content -Raw $FirewallFile | ConvertFrom-Json
    return @($parsed | ForEach-Object { $_ } | Where-Object { $_ -and $_.DisplayName })
}
function Save-Firewall($rules) { [IO.File]::WriteAllText($FirewallFile, (ConvertTo-Json @($rules) -Depth 3)) }
function Get-NetFirewallRule { param($DisplayName, $ErrorAction)
    Read-Firewall | Where-Object { $_.DisplayName -eq $DisplayName }
}
function Remove-NetFirewallRule { param([Parameter(ValueFromPipeline)] $Rule)
    begin { $gone = @() } process { $gone += $Rule.DisplayName }
    end { if ($gone.Count) { Save-Firewall (Read-Firewall | Where-Object { $_.DisplayName -notin $gone }) } }
}
function New-NetFirewallRule { param($DisplayName, $Description, $Direction, $Action, $Protocol, $LocalPort, $RemoteAddress, $Program, $Profile)
    Save-Firewall (@(Read-Firewall) + [pscustomobject]@{ DisplayName = $DisplayName; LocalPort = $LocalPort; RemoteAddress = "$RemoteAddress"; Program = $Program })
}
function icacls.exe { $global:LASTEXITCODE = 0 }

# --- The script under test ---------------------------------------------------------
$text = [IO.File]::ReadAllText($Script)
$adminCheck = [regex]'(?s)\$identity = \[Security\.Principal\.WindowsPrincipal\].*?\r?\n\}\r?\n'
if (-not $adminCheck.IsMatch($text)) { throw 'harness: administrator check not found' }
$text = $adminCheck.Replace($text, "# (administrator check skipped by the test harness)`r`n", 1)
$portsLine = '$PhpPorts = @(9101, 9102, 9103, 9104)'
if ($PhpPorts.Count -and $text.Contains($portsLine)) { $text = $text.Replace($portsLine, '$PhpPorts = @(' + ($PhpPorts -join ', ') + ')') }
if ($LegacyDir) {
    if (-not $text.Contains("`$LegacyDir = 'C:\PriceVerifier'")) { throw 'harness: LegacyDir line not found' }
    $text = $text.Replace("`$LegacyDir = 'C:\PriceVerifier'", "`$LegacyDir = '$LegacyDir'")
}
$underTest = Join-Path (Split-Path $Script -Parent) ((Split-Path $Script -Leaf) -replace '\.ps1$', '-undertest.ps1')
[IO.File]::WriteAllText($underTest, $text)

& $underTest @ScriptArgs
