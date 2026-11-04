#Requires -Version 5.1
<#
.SYNOPSIS
    Builds the offline installer packages:
      dist\PriceVerifierServer-Windows-<version>.zip
      dist\PriceVerifierServer-Linux-<version>.tar.gz

.DESCRIPTION
    Takes the portal program files from -PortalSource (only code, never data),
    the installer scripts from windows\ and linux\, and the runtimes in vendor\
    (portable PHP + Caddy for Windows, FrankenPHP for Linux).
#>
param(
    [string]$PortalSource = (Join-Path $PSScriptRoot 'portal')
)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$version = (Get-Content -Raw (Join-Path $root 'VERSION')).Trim()
$dist = Join-Path $root 'dist'
$stage = Join-Path $dist 'stage'
$portalFiles = @('index.php', '.htaccess', '.user.ini', 'README-client-upload.txt', 'assets\app.css', 'assets\app.js')

function Set-LineEndings([string]$path, [string]$eol) {
    $text = [IO.File]::ReadAllText($path) -replace "`r`n", "`n"
    if ($eol -eq "`r`n") { $text = $text -replace "`n", "`r`n" }
    [IO.File]::WriteAllText($path, $text, (New-Object Text.UTF8Encoding($false)))
}

function Copy-Portal([string]$target) {
    foreach ($file in $portalFiles) {
        $source = Join-Path $PortalSource $file
        if (-not (Test-Path $source)) { throw "Portal file missing: $source" }
        $dest = Join-Path $target $file
        New-Item -ItemType Directory -Force -Path (Split-Path $dest) | Out-Null
        Copy-Item -Force $source $dest
    }
}

foreach ($required in 'vendor\windows\php\php-cgi.exe', 'vendor\windows\caddy.exe', 'vendor\windows\vc_redist.x64.exe', 'vendor\linux\frankenphp') {
    if (-not (Test-Path (Join-Path $root $required))) { throw "Missing runtime: $required" }
}
if (Test-Path $stage) { Remove-Item -Recurse -Force $stage }

# --- Windows ------------------------------------------------------------------
$name = "PriceVerifierServer-Windows-$version"
$w = Join-Path $stage $name
$wf = Join-Path $w 'files'
New-Item -ItemType Directory -Force -Path (Join-Path $wf 'bin'), (Join-Path $wf 'portal') | Out-Null
Copy-Item (Join-Path $root 'windows\install.cmd'), (Join-Path $root 'windows\install.ps1'), (Join-Path $root 'VERSION') $w
Copy-Item (Join-Path $root 'README-install.txt') (Join-Path $w 'README.txt')
foreach ($file in 'run-server.ps1', 'uninstall.ps1', 'uninstall.cmd', 'Caddyfile.template', 'php.ini.template') {
    Copy-Item (Join-Path $root "windows\$file") $wf
}
Copy-Item (Join-Path $root 'vendor\windows\vc_redist.x64.exe') $wf
Copy-Item -Recurse (Join-Path $root 'vendor\windows\php') (Join-Path $wf 'bin\php')
Copy-Item (Join-Path $root 'vendor\windows\caddy.exe') (Join-Path $wf 'bin\caddy.exe')
Copy-Portal (Join-Path $wf 'portal')
Get-ChildItem -Recurse $w -Include '*.cmd', '*.ps1', '*.txt', '*.template' | ForEach-Object { Set-LineEndings $_.FullName "`r`n" }
$zip = Join-Path $dist "$name.zip"
if (Test-Path $zip) { Remove-Item -Force $zip }
# Windows' bsdtar writes standard '/' paths; Compress-Archive (PowerShell 5.1)
# writes '\' paths that 7-Zip and non-Windows tools extract as flat file names.
& tar.exe -a -cf $zip -C $stage $name
if ($LASTEXITCODE -ne 0) { throw 'zip creation failed' }
Write-Host "Built $zip ($([math]::Round((Get-Item $zip).Length / 1MB, 1)) MB)"

# --- Linux --------------------------------------------------------------------
$name = "PriceVerifierServer-Linux-$version"
$l = Join-Path $stage $name
$lf = Join-Path $l 'files'
New-Item -ItemType Directory -Force -Path (Join-Path $lf 'bin'), (Join-Path $lf 'portal') | Out-Null
Copy-Item (Join-Path $root 'linux\install.sh'), (Join-Path $root 'linux\uninstall.sh'), (Join-Path $root 'VERSION') $l
Copy-Item (Join-Path $root 'README-install.txt') (Join-Path $l 'README.txt')
foreach ($file in 'Caddyfile.template', 'php.ini.template', 'price-verifier.service.template',
        'price-verifier-sync.service.template', 'price-verifier-sync.timer.template') {
    Copy-Item (Join-Path $root "linux\$file") $lf
}
Copy-Item (Join-Path $root 'vendor\linux\frankenphp') (Join-Path $lf 'bin\frankenphp')
Copy-Portal (Join-Path $lf 'portal')
Get-ChildItem -Recurse $l -Include '*.sh', '*.txt', '*.template', 'VERSION' | ForEach-Object { Set-LineEndings $_.FullName "`n" }
$tarball = Join-Path $dist "$name.tar.gz"
if (Test-Path $tarball) { Remove-Item -Force $tarball }
& tar.exe -czf $tarball -C $stage $name
if ($LASTEXITCODE -ne 0) { throw 'tar failed' }
Write-Host "Built $tarball ($([math]::Round((Get-Item $tarball).Length / 1MB, 1)) MB)"
