# Applies only the reviewed scanner-status PHP/JS files to the existing portals.
# Backups stay in the project. Catalogs, media, passwords and scanner state are untouched.
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$source = Join-Path $PSScriptRoot 'portal'
$destinations = @(
    @{ Name = 'installed'; Path = 'C:\PriceVerifier\portal' },
    @{ Name = 'source'; Path = 'C:\price verifier server - temporaryrename' }
)
$files = @{
    'index.php' = 'ED5237C28BF1F45D98D5B34A6D4760258471E7582FB9E4EB597772DC3996EBA2'
    'assets\app.js' = '0145563F96E2657B84450882AAB93C62C4BB3DD5D5F64AE852D68544443536D8'
}

# Check all existing files before changing anything, so an intervening edit
# cannot silently be replaced by this prepared update.
foreach ($destination in $destinations) {
    foreach ($file in $files.Keys) {
        $target = Join-Path $destination.Path $file
        $current = (Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash
        $updated = (Get-FileHash -LiteralPath (Join-Path $source $file) -Algorithm SHA256).Hash
        if ($current -ne $files[$file] -and $current -ne $updated) {
            throw "File changed since review: $target. No update applied."
        }
    }
}

$backup = Join-Path $projectRoot ('backups\portal-scanner-status-' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
foreach ($destination in $destinations) {
    foreach ($file in $files.Keys) {
        $saved = Join-Path (Join-Path $backup $destination.Name) $file
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $saved) | Out-Null
        Copy-Item -LiteralPath (Join-Path $destination.Path $file) -Destination $saved
    }
}

foreach ($destination in $destinations) {
    foreach ($file in $files.Keys) {
        $target = Join-Path $destination.Path $file
        $staged = Join-Path (Split-Path -Parent $target) ('.scanner-status-' + [guid]::NewGuid().ToString('N') + '.tmp')
        Copy-Item -LiteralPath (Join-Path $source $file) -Destination $staged
        try {
            # A plain $null is converted to an empty path by Windows PowerShell.
            [IO.File]::Replace($staged, $target, [NullString]::Value)
        } finally {
            if (Test-Path -LiteralPath $staged) {
                Remove-Item -LiteralPath $staged
            }
        }
        if ((Get-FileHash -LiteralPath $target).Hash -ne (Get-FileHash -LiteralPath (Join-Path $source $file)).Hash) {
            throw "Updated file verification failed: $target. Backups: $backup"
        }
        Write-Output "Updated: $target"
        # Remove matching temporary files left by an interrupted previous attempt.
        $updatedHash = (Get-FileHash -LiteralPath (Join-Path $source $file)).Hash
        $pending = Get-ChildItem -LiteralPath (Split-Path -Parent $target) -Filter '.scanner-status-*.tmp' -File
        foreach ($temporary in $pending) {
            if ((Get-FileHash -LiteralPath $temporary.FullName).Hash -eq $updatedHash) {
                Remove-Item -LiteralPath $temporary.FullName
            }
        }
    }
}
Write-Output "Backups: $backup"
