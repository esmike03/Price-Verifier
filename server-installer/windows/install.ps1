#Requires -Version 5.1
<#
.SYNOPSIS
    Installs (or upgrades) the Price Verifier server on Windows 10/11 or Windows Server.

.DESCRIPTION
    - Copies portable PHP + Caddy and the web portal to the install folder.
    - Starts automatically at boot, before anyone logs in (scheduled task as SYSTEM),
      and restarts itself if it stops.
    - Reachable only from the local network: a firewall rule limited to the local
      subnet, plus the web server refusing non-private addresses.
    - Re-running it upgrades the program files and keeps all portal data.

.EXAMPLE
    Right-click install.cmd > Run as administrator
.EXAMPLE
    powershell -ExecutionPolicy Bypass -File install.ps1 -Port 8000 -ImportFrom "C:\price verifier server" -Quiet
#>
param(
    [int]$Port = 0,
    [string]$InstallDir = 'C:\PriceVerifier',
    # Existing portal folder to copy data from on a first install; '' to skip.
    # When omitted, the installer asks.
    $ImportFrom = $null,
    [string]$TimeZone = 'Asia/Manila',
    # Accept defaults instead of asking.
    [switch]$Quiet
)

$ErrorActionPreference = 'Stop'
$TaskName = 'Price Verifier Server'
$FirewallRule = 'Price Verifier Server'
$PhpPorts = @(9101, 9102, 9103, 9104)
$Payload = Join-Path $PSScriptRoot 'files'
$Version = (Get-Content -Raw (Join-Path $PSScriptRoot 'VERSION')).Trim()
# Program files replaced on every install; everything else in portal\ is data.
$PortalCodeFiles = @('index.php', '.htaccess', '.user.ini', 'README-client-upload.txt', 'assets')

function Say([string]$text, [string]$color = 'Gray') { Write-Host $text -ForegroundColor $color }
function Step([string]$text) { Write-Host ''; Write-Host "==> $text" -ForegroundColor Cyan }

function Ask([string]$question, [string]$default) {
    if ($Quiet) { return $default }
    $suffix = if ($default) { " [$default]" } else { '' }
    $answer = Read-Host "$question$suffix"
    if ([string]::IsNullOrWhiteSpace($answer)) { return $default }
    return $answer.Trim()
}

function Test-PortInUse([int]$number) {
    return [bool](Get-NetTCPConnection -LocalPort $number -State Listen -ErrorAction SilentlyContinue)
}

# True while the autostart task is switched off for an upgrade (see below).
$script:TaskDisabled = $false

function Stop-ExistingServer {
    # The task restarts itself a minute after it stops and again every 5 minutes.
    # Left enabled, it brings the old server back while the installer is still
    # asking questions or replacing files, and the files in use cannot be
    # replaced. So it is switched off first and stays off until the new version
    # is registered and started.
    if (Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue) {
        Disable-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue | Out-Null
        $script:TaskDisabled = $true
        Stop-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
    }
    $bin = Join-Path $InstallDir 'bin'
    # Keep going until nothing of the server is left running: a file still in
    # use by one of its programs cannot be replaced.
    for ($attempt = 0; $attempt -lt 15; $attempt++) {
        $running = @(Get-CimInstance Win32_Process | Where-Object {
            ($_.ExecutablePath -and $_.ExecutablePath.StartsWith($bin, [StringComparison]::OrdinalIgnoreCase)) -or
            ($_.Name -eq 'powershell.exe' -and $_.CommandLine -like "*$InstallDir\run-server.ps1*")
        })
        if ($running.Count -eq 0) { break }
        $running | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
        Start-Sleep -Seconds 1
    }
    Start-Sleep -Seconds 1
}

# Starts the server through its task, switching the task back on if an upgrade
# had turned it off.
function Start-Server {
    Enable-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue | Out-Null
    $script:TaskDisabled = $false
    Start-ScheduledTask -TaskName $TaskName
}

# If the install stops with an error after the old server was switched off, do
# not leave the PC without a server: turn the previous one back on.
trap {
    if ($script:TaskDisabled) {
        Enable-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue | Out-Null
        Start-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
        $script:TaskDisabled = $false
        Write-Host ''
        Write-Host 'The install did not finish. The server that was already installed has been started again.' -ForegroundColor Yellow
    }
    break
}

# Runs a native program and returns its exit code and output. Windows PowerShell
# turns stderr lines into errors, which 'Stop' would make fatal, so relax it here.
function Invoke-Native([string]$exe, [string[]]$arguments) {
    $saved = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $output = & $exe @arguments 2>&1 | ForEach-Object { "$_" }
        return @{ Code = $LASTEXITCODE; Output = ($output -join "`n") }
    } catch {
        return @{ Code = -1; Output = "$_" }
    } finally {
        $ErrorActionPreference = $saved
    }
}

function Fill-Template([string]$source, [string]$target, [hashtable]$values) {
    $text = Get-Content -Raw -Path $source
    foreach ($key in $values.Keys) { $text = $text.Replace("{{$key}}", $values[$key]) }
    [IO.File]::WriteAllText($target, $text, (New-Object Text.UTF8Encoding($false)))
}

# Removes a file or folder, trying again for a few seconds: antivirus, or a
# program that is still closing, can hold a file for a moment.
function Remove-Old([string]$path) {
    for ($attempt = 1; ; $attempt++) {
        try {
            if (Test-Path $path) { Remove-Item -Recurse -Force $path }
            return
        } catch {
            if ($attempt -ge 10) { throw }
            Start-Sleep -Seconds 1
        }
    }
}

function Test-VcRuntime {
    # PHP needs the Visual C++ 2015-2022 runtime. With an older one PHP can still
    # start, yet its workers may be unstable, so the PC's runtime has to be at
    # least as new as the one bundled with this installer.
    $key = Get-ItemProperty 'HKLM:\SOFTWARE\Microsoft\VisualStudio\14.0\VC\Runtimes\x64' -ErrorAction SilentlyContinue
    if (-not $key -or $key.Installed -ne 1) { return $false }
    $needed = New-Object Version 14, 29, 0
    try {
        $bundled = [Version](Get-Item (Join-Path $Payload 'vc_redist.x64.exe')).VersionInfo.ProductVersion
        $needed = New-Object Version $bundled.Major, $bundled.Minor, ([Math]::Max(0, $bundled.Build))
    } catch { }
    return (New-Object Version ([int]$key.Major), ([int]$key.Minor), ([int]$key.Bld)) -ge $needed
}

# Keep Windows from showing a blocking "DLL not found" dialog if PHP can't load.
Add-Type -Namespace PriceVerifier -Name Native -MemberDefinition '[DllImport("kernel32.dll")] public static extern uint SetErrorMode(uint mode);'
[PriceVerifier.Native]::SetErrorMode(0x8003) | Out-Null

function Test-Php {
    # No quotes in the PHP code: Windows PowerShell strips embedded double quotes
    # from native arguments.
    $result = Invoke-Native (Join-Path $InstallDir 'bin\php\php.exe') @('-n', '-r', 'echo 6*7;')
    return $result.Code -eq 0 -and $result.Output.Trim() -eq '42'
}

# --- Preconditions ------------------------------------------------------------
$identity = [Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()
if (-not $identity.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw 'Run the installer as administrator (right-click install.cmd > Run as administrator).'
}
if (-not [Environment]::Is64BitOperatingSystem) {
    throw 'The Price Verifier server needs 64-bit Windows.'
}

Say "Price Verifier server $Version" 'White'
$configFile = Join-Path $InstallDir 'config\server.json'
$upgrade = Test-Path $configFile
$existing = if ($upgrade) { Get-Content -Raw $configFile | ConvertFrom-Json } else { $null }
if ($upgrade) {
    Say "Existing installation found in $InstallDir (version $($existing.version)). It will be upgraded; data is kept."
}

# --- Stop the old version (so its port counts as free) -----------------------
# Also when an earlier install got as far as setting up the task but did not finish.
if ($upgrade -or (Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue)) {
    Step 'Stopping the running server'
    Stop-ExistingServer
}

# --- Port ---------------------------------------------------------------------
Step 'Choosing the port'
if ($Port -le 0) {
    $default = if ($existing) { [string]$existing.port } else { '8000' }
    $Port = [int](Ask 'Port for scanners and the web portal' $default)
}
while (Test-PortInUse $Port) {
    Say "Port $Port is already used by another program on this PC." 'Yellow'
    if ($Quiet) { throw "Port $Port is in use. Re-run with -Port <free port>." }
    $Port = [int](Ask 'Choose another port' ([string]($Port + 1)))
}
Say "Using port $Port."

# --- Program files --------------------------------------------------------------
Step "Installing program files to $InstallDir"
foreach ($dir in 'bin', 'config', 'logs', 'sessions', 'portal') {
    New-Item -ItemType Directory -Force -Path (Join-Path $InstallDir $dir) | Out-Null
}
$binDir = Join-Path $InstallDir 'bin'
Remove-Old (Join-Path $binDir 'php')
Copy-Item -Recurse -Force (Join-Path $Payload 'bin\*') $binDir
foreach ($script in 'run-server.ps1', 'uninstall.ps1', 'uninstall.cmd') {
    Copy-Item -Force (Join-Path $Payload $script) (Join-Path $InstallDir $script)
}
Copy-Item -Force (Join-Path $PSScriptRoot 'VERSION') (Join-Path $InstallDir 'VERSION')

$restartAdvised = $false
if (-not (Test-VcRuntime) -or -not (Test-Php)) {
    Step 'Installing the Microsoft Visual C++ runtime (needed by PHP)'
    $proc = Start-Process -FilePath (Join-Path $Payload 'vc_redist.x64.exe') `
        -ArgumentList '/install', '/quiet', '/norestart' -Wait -PassThru
    # 0 = installed, 1638 = a newer one is already there, 3010 = installed, restart to finish.
    if ($proc.ExitCode -eq 3010) { $restartAdvised = $true }
    if ($proc.ExitCode -notin 0, 1638, 3010) {
        # Not fatal while PHP itself runs: carry on with the runtime the PC has.
        if (-not (Test-Php)) { throw "The Visual C++ runtime failed to install (code $($proc.ExitCode)), and PHP cannot start without it." }
        Say "The Visual C++ runtime could not be updated (code $($proc.ExitCode)); continuing with the one already on this PC." 'Yellow'
    }
    if (-not (Test-Php)) { throw 'PHP still cannot start after installing the Visual C++ runtime. Restart Windows and run the installer again.' }
}

# --- Portal data ------------------------------------------------------------------
$portal = Join-Path $InstallDir 'portal'
$hasData = @(Get-ChildItem -Force $portal | Where-Object { $_.Name -notin $PortalCodeFiles }).Count -gt 0
if (-not $hasData) {
    Step 'Portal data'
    if (-not $PSBoundParameters.ContainsKey('ImportFrom')) {
        $suggest = if (Test-Path 'C:\price verifier server\index.php') { 'C:\price verifier server' } else { '' }
        $ImportFrom = Ask 'Import data from an existing portal folder? Enter its path, or leave blank to start empty' $suggest
    }
    if ($ImportFrom) {
        if (-not (Test-Path (Join-Path $ImportFrom 'index.php'))) { throw "No portal found in '$ImportFrom'." }
        Say "Copying catalog, media, promos, design and the portal password from $ImportFrom ..."
        Get-ChildItem -Force $ImportFrom | Where-Object {
            $_.Name -notin $PortalCodeFiles -and $_.Name -notin 'device-serials.txt', 'assets.zip' -and
            $_.Name -notlike '*.tmp' -and $_.Name -notlike '.catalog-*' -and $_.Name -notlike '.promos-*' -and $_.Name -notlike '.design-*'
        } | ForEach-Object { Copy-Item -Recurse -Force $_.FullName (Join-Path $portal $_.Name) }
    } else {
        Say 'Starting with an empty portal. Open it in a browser to create the portal password.'
    }
}
foreach ($item in $PortalCodeFiles) {
    $target = Join-Path $portal $item
    Remove-Old $target
    Copy-Item -Recurse -Force (Join-Path $Payload "portal\$item") $target
}

# --- Configuration ----------------------------------------------------------------
Step 'Writing configuration'
$slash = { param($path) $path.Replace('\', '/') }
Fill-Template (Join-Path $Payload 'Caddyfile.template') (Join-Path $InstallDir 'config\Caddyfile') @{
    PORT      = [string]$Port
    PORTAL    = & $slash $portal
    LOGS      = & $slash (Join-Path $InstallDir 'logs')
    UPSTREAMS = ($PhpPorts | ForEach-Object { "127.0.0.1:$_" }) -join ' '
}
# Firebird and Oracle connect natively only when their client libraries are on this
# PC; each is enabled when PHP can load it (both can also be reached through ODBC).
$optionalExtensions = @()
foreach ($extension in 'pdo_firebird', 'pdo_oci') {
    $probe = Invoke-Native (Join-Path $binDir 'php\php.exe') @('-n', '-d', "extension_dir=$(Join-Path $binDir 'php\ext')",
        '-d', "extension=$extension", '-r', 'echo 6*7;')
    if ($probe.Code -eq 0 -and $probe.Output.Trim() -eq '42') { $optionalExtensions += "extension = $extension" }
}
Fill-Template (Join-Path $Payload 'php.ini.template') (Join-Path $InstallDir 'config\php.ini') @{
    OPTIONAL_EXTENSIONS = $optionalExtensions -join "`r`n"
    PHP      = Join-Path $InstallDir 'bin\php'
    LOGS     = Join-Path $InstallDir 'logs'
    SESSIONS = Join-Path $InstallDir 'sessions'
    TIMEZONE = $TimeZone
}
[IO.File]::WriteAllText($configFile, (@{ version = $Version; port = $Port; phpPorts = $PhpPorts } | ConvertTo-Json),
    (New-Object Text.UTF8Encoding($false)))

$check = Invoke-Native (Join-Path $binDir 'caddy.exe') @('validate', '--config', (Join-Path $InstallDir 'config\Caddyfile'), '--adapter', 'caddyfile')
if ($check.Code -ne 0) { throw "Web server configuration is invalid:`n$($check.Output)" }

# The service account needs to write catalog uploads, media, promos and logs.
foreach ($dir in 'portal', 'logs', 'sessions') {
    Invoke-Native 'icacls.exe' @((Join-Path $InstallDir $dir), '/grant', '*S-1-5-18:(OI)(CI)F', '/T', '/Q') | Out-Null
}

# --- Firewall: local network only ---------------------------------------------------
Step 'Allowing scanners on the local network (firewall)'
Get-NetFirewallRule -DisplayName $FirewallRule -ErrorAction SilentlyContinue | Remove-NetFirewallRule
New-NetFirewallRule -DisplayName $FirewallRule -Description 'Price Verifier server: local network only.' `
    -Direction Inbound -Action Allow -Protocol TCP -LocalPort $Port -RemoteAddress LocalSubnet `
    -Program (Join-Path $binDir 'caddy.exe') -Profile Any | Out-Null

# --- Autostart ----------------------------------------------------------------------
Step 'Setting up automatic start at boot'
$action = New-ScheduledTaskAction -Execute 'powershell.exe' `
    -Argument "-NoProfile -NonInteractive -ExecutionPolicy Bypass -WindowStyle Hidden -File `"$(Join-Path $InstallDir 'run-server.ps1')`""
$atBoot = New-ScheduledTaskTrigger -AtStartup
# Watchdog: every 5 minutes the task is started again; the supervisor exits at
# once if it is already running, so this only matters after a crash.
$watchdog = New-ScheduledTaskTrigger -Once -At (Get-Date).AddMinutes(1) -RepetitionInterval (New-TimeSpan -Minutes 5)
$principal = New-ScheduledTaskPrincipal -UserId 'S-1-5-18' -LogonType ServiceAccount -RunLevel Highest
$settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -StartWhenAvailable `
    -ExecutionTimeLimit ([TimeSpan]::Zero) -MultipleInstances IgnoreNew -RestartCount 999 -RestartInterval (New-TimeSpan -Minutes 1)
Register-ScheduledTask -TaskName $TaskName -Action $action -Trigger @($atBoot, $watchdog) -Principal $principal `
    -Settings $settings -Description 'Runs the Price Verifier server (PHP + Caddy) for scanners on the local network.' -Force | Out-Null
# Switches the task back on if the upgrade had turned it off, and starts it.
Start-Server
$registered = Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
if (-not $registered -or $registered.State -eq 'Disabled') {
    throw "The automatic-start task '$TaskName' could not be set up (Task Scheduler reports: $(if ($registered) { $registered.State } else { 'not found' }))."
}

# --- Health check -------------------------------------------------------------------
# Ask this PC directly, even when Windows is set to use a proxy server.
[System.Net.WebRequest]::DefaultWebProxy = New-Object System.Net.WebProxy

# The HTTP status of the portal page, or 0 when nothing answers. Any status
# below 500 means a PHP worker produced the reply.
function Get-PortalStatus([string]$method) {
    try {
        $request = @{ UseBasicParsing = $true; TimeoutSec = 10; Uri = "http://127.0.0.1:$Port/index.php"; Method = $method }
        # No session, so the portal just rejects it; it only has to be answered.
        if ($method -eq 'POST') { $request.Body = 'action=installer-check' }
        return [int](Invoke-WebRequest @request).StatusCode
    } catch {
        if ($_.Exception.Response) { return [int]$_.Exception.Response.StatusCode }
        return 0
    }
}

# Waits up to a minute and a half for the portal page. A healthy server answers
# within seconds; one whose PHP workers cannot start keeps every request waiting
# for 10 seconds, so the wait is limited by the clock, not by a number of tries.
function Wait-Portal {
    $deadline = (Get-Date).AddSeconds(90)
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Seconds 1
        if ((Get-PortalStatus 'GET') -eq 200) { return $true }
    }
    return $false
}

# Shows on screen what the server recorded, so the cause can be read (or
# photographed) without hunting through the log folder.
function Show-Diagnostics {
    Say ''
    Say '--- What the server recorded ---' 'Yellow'
    $info = Get-ScheduledTaskInfo -TaskName $TaskName -ErrorAction SilentlyContinue
    $task = Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
    Say ("Task '{0}': state {1}, last run {2}, last result 0x{3:X}" -f $TaskName, $task.State, $info.LastRunTime, [int64]$info.LastTaskResult)
    $bin = Join-Path $InstallDir 'bin'
    $running = @(Get-CimInstance Win32_Process | Where-Object {
        $_.ExecutablePath -and $_.ExecutablePath.StartsWith($bin, [StringComparison]::OrdinalIgnoreCase) } | ForEach-Object { $_.Name })
    Say "Running server programs: $(if ($running.Count) { ($running | Group-Object | ForEach-Object { "$($_.Count) x $($_.Name)" }) -join ', ' } else { 'none' })"
    if ($running -notcontains 'php-cgi.exe') {
        Say 'PHP is not running: its workers stop as soon as they are started (see supervisor.log below).' 'Red'
    }
    $logs = @('supervisor.log', 'caddy-console.log', 'php-errors.log') + @($PhpPorts | ForEach-Object { "php-worker-$_.log" })
    foreach ($name in $logs) {
        $file = Join-Path $InstallDir "logs\$name"
        $lines = @(Get-Content $file -Tail 8 -ErrorAction SilentlyContinue | Where-Object { $_.Trim() } | ForEach-Object {
            # Caddy writes very long JSON lines; the message is the part worth reading.
            $line = if ($_ -match '"msg":"((?:[^"\\]|\\.)*)"') { $Matches[1] } else { $_ }
            if ($line.Length -gt 220) { $line.Substring(0, 220) + '...' } else { $line }
        } | Select-Object -Unique)
        if ($lines.Count) {
            Say "[$name]" 'Yellow'
            foreach ($line in $lines) { Say "  $line" }
        }
    }
    Say '--- end ---' 'Yellow'
    Say "Full logs: $InstallDir\logs"
}

Step 'Checking that the server answers'
$ok = Wait-Portal
if (-not $ok) {
    # A first start can lose a race with security software scanning the new files.
    Say 'No answer yet. Starting the server once more...' 'Yellow'
    Stop-ExistingServer
    Start-Server
    $ok = Wait-Portal
}
if (-not $ok) {
    Say "The server did not answer on port $Port." 'Red'
    Show-Diagnostics
    exit 1
}

# A page can load while signing in or uploading fails, when only some of the
# PHP workers run: page views are retried on another worker, form submissions
# are not. Send a few harmless submissions so every worker gets a turn.
$failedForms = 0
for ($i = 0; $i -lt 12; $i++) {
    $status = Get-PortalStatus 'POST'
    if ($status -eq 0 -or $status -ge 500) { $failedForms++ }
}
if ($failedForms -gt 0) {
    Say "Warning: $failedForms of 12 test form submissions failed, so signing in or uploading may show an error (HTTP 502)." 'Red'
    Say 'Some PHP workers are not running properly on this PC:' 'Red'
    Show-Diagnostics
}

$addresses = Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
    Where-Object { $_.IPAddress -notlike '127.*' -and $_.IPAddress -notlike '169.254.*' } |
    Select-Object -ExpandProperty IPAddress
Write-Host ''
if ($failedForms -gt 0) {
    Say 'Price Verifier server is installed and answering, but see the warning above.' 'Yellow'
} else {
    Say 'Price Verifier server is installed and running.' 'Green'
}
Say "  Starts automatically when this PC boots (task '$TaskName')."
Say '  Scanners and staff on the local network can use:'
foreach ($ip in $addresses) { Say "      http://${ip}:$Port" 'White' }
Say "  On this PC: http://localhost:$Port"
Say "  Enter that address as the Server URL in each scanner's Admin > Sync."
Say "  Uninstall: $InstallDir\uninstall.cmd (as administrator)."
if ($restartAdvised) {
    Say '  Restart this PC when convenient: Windows needs it to finish updating the Visual C++ runtime.' 'Yellow'
}
