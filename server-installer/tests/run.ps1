# Runs one harness scenario in a child PowerShell and reports what happened.
param(
    [Parameter(Mandatory)] [string]$Package,      # folder under it\ that holds install.ps1
    [Parameter(Mandatory)] [string]$Name,         # install folder name under it\
    [Parameter(Mandatory)] [int]$Port,
    [Parameter(Mandatory)] [int]$FirstPhpPort,
    [switch]$Watchdog,
    [int]$AnswerDelaySeconds = 0,
    [string]$ExtraArgs = '',                      # e.g. "@{ PosSyncPort = 8791 }"
    [int]$Tail = 40
)

$it = $PSScriptRoot
$dir = Join-Path $it $Name
$log = "$dir.task.json.watchdog.log"
if (Test-Path $log) { Clear-Content $log }
$ports = ($FirstPhpPort..($FirstPhpPort + 3)) -join ','
$command = "& '$it\harness.ps1' -PackageDir '$it\$Package' -InstallDir '$dir' -Port $Port -PhpPorts $ports"
if ($Watchdog) { $command += ' -Watchdog' }
if ($AnswerDelaySeconds -gt 0) { $command += " -AnswerDelaySeconds $AnswerDelaySeconds" }
if ($ExtraArgs) { $command += " -InstallerArgs $ExtraArgs" }
$command += '; exit $LASTEXITCODE'

$started = Get-Date
$out = & powershell.exe -NoProfile -ExecutionPolicy Bypass -Command $command 2>&1
$code = $LASTEXITCODE
$out | ForEach-Object { "$_" } | Select-Object -Last $Tail
"=== exit code: $code   (took $([int]((Get-Date) - $started).TotalSeconds) s)"
"=== watchdog log:"
if (Test-Path $log) { Get-Content $log } else { '(none)' }
"=== php files in bin\php: " + @(Get-ChildItem "$dir\bin\php" -Recurse -File -ErrorAction SilentlyContinue).Count
"=== task state: " + ((Get-Content -Raw "$dir.task.json" -ErrorAction SilentlyContinue) -replace '\s+', ' ')
foreach ($path in '/', '/banner-does-not-exist.jpg') {
    try {
        $r = Invoke-WebRequest "http://127.0.0.1:$Port$path" -UseBasicParsing -TimeoutSec 8
        "=== GET $path -> $($r.StatusCode)"
    } catch {
        $status = if ($_.Exception.Response) { [int]$_.Exception.Response.StatusCode } else { $_.Exception.Message }
        "=== GET $path -> $status"
    }
}
if (Test-Path "$dir\logs\server.log") { "=== server.log (last 6):"; Get-Content "$dir\logs\server.log" -Tail 6 }
