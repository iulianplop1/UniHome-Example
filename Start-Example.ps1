[CmdletBinding()]
param(
    [ValidateSet('demo','verification')][string]$Instance='demo',
    [switch]$SkipBuild,
    [switch]$RunTests,
    [switch]$ApiOnly,
    [ValidatePattern('^\d{4}-\d{2}-\d{2}$')][string]$DemoToday
)
$ErrorActionPreference='Stop'
. "$PSScriptRoot\Example.Runtime.ps1"
$c=Get-ExampleConfig $Instance
if($DemoToday -and $Instance -ne 'verification'){throw 'The test clock is only supported for -Instance verification.'}
if($DemoToday){[void][DateOnly]::ParseExact($DemoToday,'yyyy-MM-dd')}
if($RunTests -and $Instance -ne 'verification'){throw 'Use -Instance verification for tests that write test records.'}
if(Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue | Where-Object LocalPort -In $c.DbPort,$c.DataPort,$c.AppPort,$c.WebPort){
    throw 'One or more expected ports are in use. Stop the example instance or free the ports first.'
}
New-Item -ItemType Directory -Force -Path $c.Dir | Out-Null
$java=(Get-Command java.exe -ErrorAction Stop).Source
$dotnet=(Get-Command dotnet.exe -ErrorAction Stop).Source
$initdb=Join-Path $c.PgBin 'initdb.exe'
$ctl=Join-Path $c.PgBin 'pg_ctl.exe'
$psql=Join-Path $c.PgBin 'psql.exe'
$project=Join-Path $PSScriptRoot 'Presentation-tier\Presentation'
$maven=Get-ExampleMaven
function Invoke-ExampleMaven([string]$tier,[string[]]$goals) {
    $dir=Join-Path $PSScriptRoot $tier
    Push-Location -LiteralPath $dir
    try {
        if($maven){
            $argsList=@('-B','-ntp')+$goals
            if(Test-Path -LiteralPath (Join-Path $HOME '.m2\repository')){
                & $maven -o @argsList
                if($LASTEXITCODE -eq 0){return}
                if($goals -contains 'test'){throw "Maven tests in $tier failed (exit $LASTEXITCODE)"}
            }
            Invoke-Checked $maven $argsList
        }else{
            Invoke-Checked (Join-Path $dir 'mvnw.cmd') (@('-B','-ntp')+$goals)
        }
    }finally{Pop-Location}
}
if(!$SkipBuild){
    foreach($tier in @('application-tier','data-tier')){
        $kind=$tier.Split('-')[0]; $package=$tier.Replace('-','')
        $name=if($kind -eq 'data'){'DataTierApplication'}else{'ApplicationTierApplication'}
        $source=Join-Path $PSScriptRoot 'scripts\ExampleClasspath.java'
        $arguments=Join-Path $PSScriptRoot "$tier\target\example-launch.args"
        Invoke-ExampleMaven $tier @('compile','org.codehaus.mojo:exec-maven-plugin:3.1.0:exec','-Dexec.executable=java','-Dexec.classpathScope=runtime',('-Dexec.args=-cp %classpath "{0}" "{1}" dk.via.unihome.{2}.{3}' -f $source,$arguments,$package,$name))
    }
    if(!$ApiOnly){Invoke-Checked $dotnet @('build',(Join-Path $project 'Presentation.csproj'),'-nologo','-p:NuGetAudit=false')}
}
$passFile=Join-Path $c.Dir 'db-password.txt'
if(!(Test-Path -LiteralPath $passFile)){
    $p=[Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(22))
    Set-Content -LiteralPath $passFile -Value $p -Encoding ascii
}
$password=(Get-Content -LiteralPath $passFile -Raw).Trim()
$pgStarted=$false
try {
    if(!(Test-Path -LiteralPath (Join-Path $c.Pg 'PG_VERSION'))){
        Invoke-Checked $initdb @('-D',$c.Pg,'--username=unihome_example',"--pwfile=$passFile",'--auth=scram-sha-256','--encoding=UTF8','--locale=C')
    }
    Invoke-Checked $ctl @('start','-D',$c.Pg,'-l',(Join-Path $c.Dir 'postgres.log'),'-o',"-h 127.0.0.1 -p $($c.DbPort)",'-w')
    $pgStarted=$true
    $env:PGPASSWORD=$password
    $common=@('-X','-h','127.0.0.1','-p',"$($c.DbPort)",'-U',$c.User,'-v','ON_ERROR_STOP=1')
    $exists=& $psql @common -d postgres -Atc "SELECT 1 FROM pg_database WHERE datname='unihome_example'"
    if($LASTEXITCODE){throw 'Could not inspect the isolated database.'}
    if($exists -ne '1'){
        Invoke-Checked $psql ($common+@('-d','postgres','-c','CREATE DATABASE unihome_example'))
    }
    foreach($sql in @('create-database.sql','create-demo.sql')){
        Invoke-Checked $psql ($common+@('-d',$c.Db,'-f',(Join-Path $PSScriptRoot "database\$sql")))
    }
    Remove-Item Env:\PGPASSWORD -ErrorAction SilentlyContinue
    $env:SPRING_DATASOURCE_URL="jdbc:postgresql://127.0.0.1:$($c.DbPort)/$($c.Db)"
    $env:SPRING_DATASOURCE_USERNAME=$c.User
    $env:SPRING_DATASOURCE_PASSWORD=$password
    $env:SPRING_GRPC_SERVER_ADDRESS='127.0.0.1'
    $env:SPRING_GRPC_SERVER_PORT="$($c.DataPort)"
    $env:DEMO_TODAY=$DemoToday
    if($RunTests){
        Invoke-ExampleMaven 'data-tier' @('test')
        Invoke-ExampleMaven 'application-tier' @('test')
    }
    $jar=Join-Path $PSScriptRoot 'data-tier\target\example-launch.args'
    Start-ExampleProcess $c 'data' $java @(('"@{0}"' -f $jar)) $PSScriptRoot
    foreach($n in @('SPRING_DATASOURCE_URL','SPRING_DATASOURCE_USERNAME','SPRING_DATASOURCE_PASSWORD','SPRING_GRPC_SERVER_ADDRESS','SPRING_GRPC_SERVER_PORT','DEMO_TODAY','DEMO_CLOCK_FILE')){Remove-Item "Env:\$n" -ErrorAction SilentlyContinue}
    $env:SERVER_ADDRESS='127.0.0.1'
    $env:SERVER_PORT="$($c.AppPort)"
    $jar=Join-Path $PSScriptRoot 'application-tier\target\example-launch.args'
    Start-ExampleProcess $c 'application' $java @(('"@{0}"' -f $jar),"--spring.grpc.client.channel.datatier.target=static://127.0.0.1:$($c.DataPort)") $PSScriptRoot
    Remove-Item Env:\SERVER_ADDRESS,Env:\SERVER_PORT -ErrorAction SilentlyContinue
    Wait-ExampleEndpoint {$r=Invoke-RestMethod "http://127.0.0.1:$($c.AppPort)/api/demo/ping" -Method Post -Body '{}' -ContentType 'application/json' -TimeoutSec 2; $null -ne $r.today} 'REST and gRPC' $c.Dir
    if($ApiOnly){
        Write-Output "UniHome example API ($Instance): http://127.0.0.1:$($c.AppPort)/api/demo/ping"
        return
    }

    $env:ApplicationTier__BaseUrl="http://127.0.0.1:$($c.AppPort)/"
    $env:ASPNETCORE_URLS="http://127.0.0.1:$($c.WebPort)"
    $env:ASPNETCORE_ENVIRONMENT='Development'
    $dll=Join-Path $project 'bin\Debug\net10.0\Presentation.dll'
    Start-ExampleProcess $c 'web' $dotnet @(('"{0}"' -f $dll)) $project
    foreach($n in @('ApplicationTier__BaseUrl','ASPNETCORE_URLS','ASPNETCORE_ENVIRONMENT')){Remove-Item "Env:\$n" -ErrorAction SilentlyContinue}
    Wait-ExampleEndpoint {(Invoke-WebRequest "http://127.0.0.1:$($c.WebPort)/" -TimeoutSec 2).StatusCode -eq 200} 'Blazor' $c.Dir
    Write-Output "UniHome example ($Instance): http://127.0.0.1:$($c.WebPort)/"
    Write-Output "Runtime logs: $($c.Dir)"
} catch {
    if($pgStarted){& "$PSScriptRoot\Stop-Example.ps1" -Instance $Instance}
    throw
} finally {
    Remove-Item Env:\PGPASSWORD,Env:\SPRING_DATASOURCE_PASSWORD -ErrorAction SilentlyContinue
}


