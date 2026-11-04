# Keeps one file open for a while, the way antivirus or a slow-closing program
# does, so that deleting it fails until the file is let go.
param(
    [Parameter(Mandatory)] [string]$File,
    [Parameter(Mandatory)] [int]$Seconds,
    [Parameter(Mandatory)] [string]$Log
)
$stream = [IO.File]::Open($File, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::Read)
Add-Content $Log ("{0:HH:mm:ss.fff} holding {1}" -f (Get-Date), (Split-Path $File -Leaf))
Start-Sleep -Seconds $Seconds
$stream.Dispose()
Add-Content $Log ("{0:HH:mm:ss.fff} released" -f (Get-Date))
