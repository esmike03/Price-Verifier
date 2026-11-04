# Price Verifier server supervisor (Windows).
# Started by the "Price Verifier Server" scheduled task at boot (as SYSTEM).
# Runs the PHP FastCGI workers and Caddy, and restarts any that stop.
$ErrorActionPreference = 'Continue'

$root = $PSScriptRoot
$configFile = Join-Path $root 'config\server.json'
$phpCgi = Join-Path $root 'bin\php\php-cgi.exe'
$phpCli = Join-Path $root 'bin\php\php.exe'
$phpIni = Join-Path $root 'config\php.ini'
$caddy = Join-Path $root 'bin\caddy.exe'
$caddyfile = Join-Path $root 'config\Caddyfile'
$logDir = Join-Path $root 'logs'
$supervisorLog = Join-Path $logDir 'supervisor.log'
New-Item -ItemType Directory -Force -Path $logDir | Out-Null

function Write-Log([string]$message) {
    try {
        if ((Test-Path $supervisorLog) -and (Get-Item $supervisorLog).Length -gt 5MB) {
            Move-Item -Force $supervisorLog "$supervisorLog.1"
        }
        Add-Content -Path $supervisorLog -Value ("{0:yyyy-MM-dd HH:mm:ss}  {1}" -f (Get-Date), $message) -ErrorAction Stop
    } catch { }
}

# Nothing may fail silently: an unexpected error is recorded, and the non-zero
# exit makes Task Scheduler start the supervisor again a minute later.
trap {
    Write-Log "Supervisor stopped by an error: $($_.Exception.Message) (line $($_.InvocationInfo.ScriptLineNumber))."
    exit 1
}

# Only one supervisor at a time: the task's repeating trigger starts this
# script again as a watchdog, and a second copy simply exits.
$mutex = New-Object System.Threading.Mutex($false, 'Global\PriceVerifierServerSupervisor')
try {
    $owned = $mutex.WaitOne(0)
} catch [System.Threading.AbandonedMutexException] {
    $owned = $true    # the previous supervisor was killed; the mutex is ours now
}
if (-not $owned) { exit 0 }

# The settings file can be briefly unreadable right after an install or at boot
# (for example while security software scans it). Wait for it instead of
# starting a server that has no PHP workers.
function Read-ServerConfig {
    $problem = ''
    for ($attempt = 1; $attempt -le 30; $attempt++) {
        try {
            $config = [IO.File]::ReadAllText($configFile) | ConvertFrom-Json
            $phpPorts = @($config.phpPorts | ForEach-Object { [int]$_ } | Where-Object { $_ -gt 0 })
            if ([int]$config.port -gt 0 -and $phpPorts.Count -gt 0) {
                return @{ Port = [int]$config.port; PhpPorts = $phpPorts }
            }
            $problem = 'it does not list the port and the PHP worker ports'
        } catch {
            $problem = $_.Exception.Message
        }
        if ($attempt -eq 1) {
            Write-Log "Cannot use config\server.json yet ($problem). Retrying for up to a minute."
        }
        Start-Sleep -Seconds 2
    }
    Write-Log "Giving up: config\server.json is still unusable ($problem). Windows starts the server again in a minute; if this repeats, run the installer again."
    return $null
}

$config = Read-ServerConfig
if (-not $config) { exit 1 }

# Clear out workers left behind by a crash, identified by their install path.
Get-CimInstance Win32_Process | Where-Object {
    $_.ExecutablePath -and $_.ExecutablePath.StartsWith((Join-Path $root 'bin'), [StringComparison]::OrdinalIgnoreCase)
} | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }

# php-cgi exits after this many requests; the loop below restarts it, which
# keeps long-running workers from growing without bound.
$env:PHP_FCGI_MAX_REQUESTS = '5000'

function Test-Listening([int]$port) {
    foreach ($endpoint in [Net.NetworkInformation.IPGlobalProperties]::GetIPGlobalProperties().GetActiveTcpListeners()) {
        if ($endpoint.Port -eq $port) { return $true }
    }
    return $false
}

$script:startError = ''

function Start-PhpWorker([int]$port) {
    $script:startError = ''
    try {
        $process = Start-Process -FilePath $phpCgi -ArgumentList @('-b', "127.0.0.1:$port", '-c', "`"$phpIni`"") `
            -WorkingDirectory (Join-Path $root 'portal') -WindowStyle Hidden -PassThru `
            -RedirectStandardError (Join-Path $logDir "php-worker-$port.log") -ErrorAction Stop
        $null = $process.Handle    # keeps the exit code readable after the process ends
        return $process
    } catch {
        $script:startError = $_.Exception.Message
        return $null
    }
}

# Why a worker is not running: its exit code and whatever it wrote before stopping.
function Get-WorkerFailure([int]$port, $process) {
    if (-not $process) { return "could not be started: $script:startError" }
    $text = try { "exit code $($process.ExitCode)" } catch { 'stopped' }
    $output = Join-Path $logDir "php-worker-$port.log"
    $detail = if (Test-Path $output) { ((Get-Content $output -Tail 3 -ErrorAction SilentlyContinue) -join ' ').Trim() } else { '' }
    if ($detail.Length -gt 300) { $detail = $detail.Substring(0, 300) + '...' }
    if ($detail) { $text += ": $detail" }
    return $text
}

function Start-Caddy {
    Start-Process -FilePath $caddy -ArgumentList @('run', '--config', "`"$caddyfile`"", '--adapter', 'caddyfile') `
        -WorkingDirectory $root -WindowStyle Hidden -PassThru `
        -RedirectStandardError (Join-Path $logDir 'caddy-console.log') `
        -RedirectStandardOutput (Join-Path $logDir 'caddy-stdout.log')
}

# Workers are started one at a time and given a moment to open their port, so
# a worker that cannot run is noticed here instead of by the first visitor.
$workers = @{}
$ready = 0
foreach ($port in $config.PhpPorts) {
    $process = Start-PhpWorker $port
    $listening = $false
    for ($wait = 0; $wait -lt 50 -and $process -and -not $process.HasExited -and -not $listening; $wait++) {
        Start-Sleep -Milliseconds 200
        $listening = Test-Listening $port
    }
    if ($listening) {
        $ready++
    } else {
        $reason = if ($process -and -not $process.HasExited) { 'did not open its port within 10 seconds' } else { Get-WorkerFailure $port $process }
        Write-Log "PHP worker on port $port is not ready ($reason)."
    }
    $workers[$port] = @{ Process = $process; StartedAt = Get-Date; Failures = 0; NextStart = Get-Date }
}
$caddyProcess = Start-Caddy
$caddyFailures = 0

# Data source (portal tab): every 30 seconds PHP checks whether a sync with the
# store's POS database is due and runs it; it exits at once when nothing is due
# or the data source is not set up. One at a time, and never longer than 20 minutes.
$sourceProcess = $null
$sourceCheckAt = (Get-Date).AddSeconds(20)
function Start-SourceCheck {
    try {
        $process = Start-Process -FilePath $phpCli -ArgumentList @('-c', "`"$phpIni`"", "`"$(Join-Path $root 'portal\index.php')`"", '--catalog-source-sync') `
            -WorkingDirectory (Join-Path $root 'portal') -WindowStyle Hidden -PassThru `
            -RedirectStandardOutput (Join-Path $logDir 'catalog-source.log') `
            -RedirectStandardError (Join-Path $logDir 'catalog-source-error.log') -ErrorAction Stop
        $null = $process.Handle
        return $process
    } catch {
        Write-Log "Could not start the data source check: $($_.Exception.Message)"
        return $null
    }
}
Write-Log "Started on port $($config.Port): $ready of $($workers.Count) PHP workers ready."

while ($true) {
    Start-Sleep -Seconds 2
    $now = Get-Date
    if ($sourceProcess -and -not $sourceProcess.HasExited -and ($now - $sourceProcess.StartTime).TotalMinutes -gt 20) {
        Write-Log 'A data source sync ran for more than 20 minutes and was stopped.'
        Stop-Process -Id $sourceProcess.Id -Force -ErrorAction SilentlyContinue
    }
    if ($now -ge $sourceCheckAt -and (-not $sourceProcess -or $sourceProcess.HasExited)) {
        $sourceCheckAt = $now.AddSeconds(30)
        $sourceProcess = Start-SourceCheck
    }
    foreach ($port in @($workers.Keys)) {
        $worker = $workers[$port]
        $process = $worker.Process
        if ($process -and -not $process.HasExited) {
            if ($worker.Failures -gt 0 -and ($now - $worker.StartedAt).TotalSeconds -ge 120) {
                Write-Log "PHP worker on port $port is running normally again."
                $worker.Failures = 0
            }
            continue
        }
        if ($worker.StartedAt) {
            # It was running (or was tried) and is not any more.
            $lived = [int]($now - $worker.StartedAt).TotalSeconds
            $recycled = $process -and $lived -ge 30 -and $process.ExitCode -eq 0    # reached PHP_FCGI_MAX_REQUESTS
            if ($recycled) {
                $worker.Failures = 0
                $worker.NextStart = $now
            } else {
                $worker.Failures++
                # Log the first few and then only now and again, so a worker that
                # cannot run does not flood the log.
                if ($worker.Failures -le 3 -or $worker.Failures % 20 -eq 0) {
                    Write-Log "PHP worker on port $port stopped after $lived s ($(Get-WorkerFailure $port $process)); restarting (failure $($worker.Failures))."
                }
                $worker.NextStart = $now.AddSeconds([Math]::Min(30, 2 * $worker.Failures))
            }
            $worker.Process = $null
            $worker.StartedAt = $null
        }
        if ($now -ge $worker.NextStart) {
            $worker.Process = Start-PhpWorker $port
            $worker.StartedAt = Get-Date
        }
    }
    if ($caddyProcess.HasExited) {
        $caddyFailures++
        if ($caddyFailures -le 3 -or $caddyFailures % 20 -eq 0) {
            $detail = ((Get-Content (Join-Path $logDir 'caddy-console.log') -Tail 1 -ErrorAction SilentlyContinue) -join ' ').Trim()
            if ($detail.Length -gt 300) { $detail = $detail.Substring(0, 300) + '...' }
            Write-Log "Web server stopped; restarting (failure $caddyFailures). Last message: $detail"
        }
        Start-Sleep -Seconds ([Math]::Min(30, 3 * $caddyFailures))
        $caddyProcess = Start-Caddy
    }
}
