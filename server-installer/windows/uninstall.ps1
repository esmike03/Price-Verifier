#Requires -Version 5.1
<#
.SYNOPSIS
    Removes the Price Verifier server: stops it, removes autostart and the firewall
    rule, and deletes the program files. Portal data is kept unless -RemoveData.
#>
param(
    [switch]$RemoveData,
    [switch]$Quiet
)

$ErrorActionPreference = 'Stop'
$InstallDir = $PSScriptRoot
$TaskName = 'Price Verifier Server'
$FirewallRule = 'Price Verifier Server'

$identity = [Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()
if (-not $identity.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw 'Run the uninstaller as administrator.'
}

if (-not $Quiet -and -not $RemoveData) {
    $answer = Read-Host "Also delete the portal data (catalog, media, promos, design, password) in $InstallDir\portal? [y/N]"
    $RemoveData = $answer -match '^(y|yes)$'
}

Write-Host 'Stopping the server...'
if (Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue) {
    Stop-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
    Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false
}
$bin = Join-Path $InstallDir 'bin'
Get-CimInstance Win32_Process | Where-Object {
    ($_.ExecutablePath -and $_.ExecutablePath.StartsWith($bin, [StringComparison]::OrdinalIgnoreCase)) -or
    ($_.Name -eq 'powershell.exe' -and $_.CommandLine -like "*$InstallDir\run-server.ps1*")
} | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
Start-Sleep -Seconds 2

Write-Host 'Removing the firewall rule...'
Get-NetFirewallRule -DisplayName $FirewallRule -ErrorAction SilentlyContinue | Remove-NetFirewallRule

Write-Host 'Removing program files...'
foreach ($item in 'bin', 'config', 'logs', 'sessions', 'run-server.ps1', 'VERSION', 'uninstall.cmd') {
    $path = Join-Path $InstallDir $item
    if (Test-Path $path) { Remove-Item -Recurse -Force $path }
}

if ($RemoveData) {
    Remove-Item -Recurse -Force (Join-Path $InstallDir 'portal') -ErrorAction SilentlyContinue
    # This script is inside the folder; remove the rest once it has exited.
    Start-Process -WindowStyle Hidden cmd.exe -ArgumentList "/c timeout /t 2 >nul & rmdir /s /q `"$InstallDir`""
    Write-Host 'The Price Verifier server and its data were removed.' -ForegroundColor Green
} else {
    Remove-Item -Force (Join-Path $InstallDir 'uninstall.ps1') -ErrorAction SilentlyContinue
    Write-Host "The Price Verifier server was removed. Portal data was kept in $InstallDir\portal." -ForegroundColor Green
}
