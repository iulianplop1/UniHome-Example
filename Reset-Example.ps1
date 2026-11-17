[CmdletBinding()]
param([ValidateSet('demo','verification')][string]$Instance='demo',[switch]$SkipBuild)
$ErrorActionPreference='Stop'
. "$PSScriptRoot\Example.Runtime.ps1"
$c=Get-ExampleConfig $Instance
& "$PSScriptRoot\Stop-Example.ps1" -Instance $Instance
if(Test-Path -LiteralPath $c.Dir -PathType Container){
    $path=(Resolve-Path -LiteralPath $c.Dir).Path
    $base=(Resolve-Path -LiteralPath (Split-Path $c.Dir -Parent)).Path
    if($path -ne (Join-Path $base $Instance) -or (Get-Item -LiteralPath $path).Attributes -band [IO.FileAttributes]::ReparsePoint){
        throw 'Refusing to archive an unexpected runtime path.'
    }
    $archive="$Instance-archive-"+(Get-Date -Format 'yyyyMMdd-HHmmss-fff')
    Rename-Item -LiteralPath $path -NewName $archive
    Write-Output "Previous database and logs preserved at $(Join-Path $base $archive)"
}
& "$PSScriptRoot\Start-Example.ps1" -Instance $Instance -SkipBuild:$SkipBuild
