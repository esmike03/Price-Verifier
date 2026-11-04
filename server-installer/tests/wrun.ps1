# Runs harness2.ps1 in a child PowerShell and prints the tail of the output and
# the exit code.   wrun.ps1 -Script <ps1> -ArgsText "@{ ... }" [-PhpFirst 9151] [-LegacyDir dir] [-Tail 12]
param(
    [Parameter(Mandatory)] [string]$Script,
    [string]$ArgsText = '@{}',
    [int]$PhpFirst = 0,
    [string]$LegacyDir = '',
    [int]$Tail = 12
)
$here = $PSScriptRoot
$command = "& '$here\harness2.ps1' -Script '$Script' -StateDir '$here\wt\state' -ScriptArgs $ArgsText"
if ($PhpFirst -gt 0) { $command += ' -PhpPorts ' + (($PhpFirst..($PhpFirst + 3)) -join ',') }
if ($LegacyDir) { $command += " -LegacyDir '$LegacyDir'" }
$command += '; exit $LASTEXITCODE'
$started = Get-Date
$out = & powershell.exe -NoProfile -ExecutionPolicy Bypass -Command $command 2>&1
$code = $LASTEXITCODE
$out | ForEach-Object { "$_" } | Where-Object { $_.Trim() } | Select-Object -Last $Tail
"   -> exit code $code ($([int]((Get-Date) - $started).TotalSeconds) s)"
