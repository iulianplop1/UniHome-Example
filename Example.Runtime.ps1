$ExampleRoot = $PSScriptRoot
function Get-ExampleConfig([string]$Instance) {
    if($Instance -notin @('demo','verification')){throw "Unknown instance: $Instance"}
    $parent=Split-Path $ExampleRoot -Parent
    $workspace=Split-Path $parent -Parent
    $base=if((Split-Path $parent -Leaf) -eq 'outputs' -and (Test-Path -LiteralPath (Join-Path $workspace 'work') -PathType Container)){Join-Path $workspace 'work\UniHome-Example-runtime'}else{Join-Path $ExampleRoot '.runtime'}
    $shift=if($Instance -eq 'verification'){10000}else{0}
    $pgBin=if($env:UNHOME_PG_BIN){$env:UNHOME_PG_BIN}else{'C:\Program Files\PostgreSQL\18\bin'}
    $dir=Join-Path $base $Instance
    @{Dir=$dir; Pg=Join-Path $dir 'postgres'; PgBin=$pgBin; Db='unihome_example'; User='unihome_example'; DbPort=15432+$shift; DataPort=19090+$shift; AppPort=18080+$shift; WebPort=15283+$shift}
}
function Invoke-Checked([string]$Exe,[string[]]$Arguments) {
    & $Exe @Arguments
    if($LASTEXITCODE -ne 0){throw "$(Split-Path $Exe -Leaf) exited with $LASTEXITCODE"}
}
function Get-ExampleMaven {
    $installed=Get-Command mvn.cmd -ErrorAction SilentlyContinue
    if($installed){return $installed.Source}
    $cache=Join-Path $HOME '.m2\wrapper\dists'
    if(Test-Path -LiteralPath $cache){
        $cached=Get-ChildItem -LiteralPath $cache -Filter mvn.cmd -Recurse -File -ErrorAction SilentlyContinue |
            Where-Object FullName -Match 'apache-maven-3\.9\.16' | Select-Object -First 1
        if($cached){return $cached.FullName}
    }
    return $null
}
function Start-ExampleProcess($c,[string]$Name,[string]$Exe,[string[]]$Arguments,[string]$Directory) {
    $params=@{FilePath=$Exe; ArgumentList=$Arguments; WorkingDirectory=$Directory; WindowStyle='Hidden'; PassThru=$true; RedirectStandardOutput=(Join-Path $c.Dir "$Name.stdout.log"); RedirectStandardError=(Join-Path $c.Dir "$Name.stderr.log")}
    $p = Start-Process @params
    @{id=$p.Id; stamp=$p.StartTime.ToUniversalTime().Ticks.ToString()} | ConvertTo-Json -Compress | Set-Content -LiteralPath (Join-Path $c.Dir "$Name.process.json") -Encoding utf8
}
function Wait-ExampleEndpoint([scriptblock]$Check,[string]$Name,[string]$LogDir,[int]$Seconds=55) {
    $limit=[DateTime]::UtcNow.AddSeconds($Seconds)
    do {
        try {if (& $Check) {return}} catch {}
        Start-Sleep -Milliseconds 600
    } while([DateTime]::UtcNow -lt $limit)
    throw "$Name did not become ready. Logs: $LogDir"
}
