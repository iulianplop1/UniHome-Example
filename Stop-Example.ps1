[CmdletBinding()]
param([ValidateSet('demo','verification')][string]$Instance='demo')
$ErrorActionPreference='Stop'
. "$PSScriptRoot\Example.Runtime.ps1"
$c=Get-ExampleConfig $Instance
foreach ($name in @('web','application','data')){
    $f=Join-Path $c.Dir "$name.process.json"
    if(!(Test-Path -LiteralPath $f)){continue}
    $record=Get-Content -LiteralPath $f -Raw|ConvertFrom-Json
    $p=Get-Process -Id $record.id -ErrorAction SilentlyContinue
    if($p -and $p.StartTime.ToUniversalTime().Ticks.ToString() -eq $record.stamp){
        Stop-Process -InputObject $p
        try { $p.WaitForExit(10000) | Out-Null } catch {}
    }
    Remove-Item -LiteralPath $f
}
$ctl=Join-Path $c.PgBin 'pg_ctl.exe'
if(Test-Path -LiteralPath (Join-Path $c.Pg 'PG_VERSION')){
    & $ctl status -D $c.Pg *> $null
    if($LASTEXITCODE -eq 0){Invoke-Checked $ctl @('stop','-D',$c.Pg,'-m','fast','-w')}
}
Write-Output "Stopped example instance: $Instance"
