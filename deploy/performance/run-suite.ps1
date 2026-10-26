param(
    [string]$RunId=('run-'+(Get-Date -Format 'yyyyMMdd-HHmmss')),
    [ValidateSet('Setup','ResumeSetup','PrepareMeasurements','Cache','WarmCache','WarmCacheClean','Orders','Sync','Availability','Cleanup','All')][string]$Phase='All',
    [string]$JavaHome='D:\JDK21\jdk-21.0.12.1+1'
)
$ErrorActionPreference='Stop'
$project=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
# 53轮历史编排的清空/日期夹具使用旧缓存协议。禁止在当前V4版本上产出名义上可比、实际错误的成绩。
if(Test-Path (Join-Path $project 'src/main/resources/db/migration/V3__shop_cache_policy.sql')) {
    throw '此编排对应2026-10-08旧缓存协议；请使用对应归档工程复现历史压测。当前性能工具为deploy/performance/run-current.py，功能验收入口为deploy/canal/verify-cache-sync.ps1，详见docs/测试/性能与可用性测试手册.md。'
}
$run=Join-Path $project ".local/performance/$RunId"
$java=Join-Path $JavaHome 'bin/java.exe'
$jar=Join-Path $project 'target/hm-dianping-0.0.1-SNAPSHOT.jar'
$k6=(Get-ChildItem -LiteralPath (Join-Path $project '.local/performance/tools/k6') -Filter k6.exe -Recurse | Select-Object -First 1).FullName
$nginx=Join-Path $project '.local/openresty-1.27.1.2-win64/nginx.exe'
$pidsFile=Join-Path $run 'pids.json'
$fixtureFile=Join-Path $run 'fixture.json'
New-Item -ItemType Directory -Force -Path $run | Out-Null
Set-Location -LiteralPath $project
function Control([string]$Path,[int]$Timeout=180){Invoke-RestMethod -Uri ("http://127.0.0.1:19080"+$Path) -TimeoutSec $Timeout}
function Save([string]$Name,$Value){$Value | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath (Join-Path $run $Name) -Encoding utf8}
function Mark([string]$Name){$Name | Set-Content -LiteralPath (Join-Path $run 'phase.txt');Write-Host ((Get-Date -Format 'HH:mm:ss')+' '+$Name)}
function Read-Pids {if(Test-Path -LiteralPath $pidsFile){Get-Content -LiteralPath $pidsFile -Raw | ConvertFrom-Json -AsHashtable}else{@{}}}
function Record-Pid([string]$Name,[int]$ProcessId){$p=Read-Pids;$p[$Name]=$ProcessId;Save 'pids.json' $p}
function Stop-Owned([string]$Name){
    $p=Read-Pids;if(!$p.ContainsKey($Name)){return}
    $id=[int]$p[$Name];$process=Get-CimInstance Win32_Process -Filter "ProcessId=$id"
    if($process){if(!$process.CommandLine.Contains($run)){throw "PID ownership mismatch for $Name"};Stop-Process -Id $id -Force;Start-Sleep -Milliseconds 500}
    $p.Remove($Name);Save 'pids.json' $p
}
function Start-App([int]$Port,[bool]$Consume){
    $name="app-$Port";$appRuntimeRoot=Join-Path $run $name;New-Item -ItemType Directory -Force -Path $appRuntimeRoot | Out-Null
    foreach($log in @('out.log','err.log')){$old=Join-Path $appRuntimeRoot $log;if(Test-Path -LiteralPath $old){Move-Item -LiteralPath $old -Destination (Join-Path $appRuntimeRoot ($log+'.'+(Get-Date -Format 'yyyyMMdd-HHmmssfff')+'.archived'))}}
    $args=@('-Xms128m','-Xmx512m','-Dfile.encoding=UTF-8',('-Duser.home='+$appRuntimeRoot),("-Xlog:gc*:file=.local/performance/$RunId/app-$Port/gc.log:time,uptime,level,tags"),
        '-jar',$jar,"--server.port=$Port",'--spring.redis.host=127.0.0.1','--spring.redis.port=16379','--spring.redis.database=15',
        '--spring.datasource.url=jdbc:mysql://127.0.0.1:13306/hmdp?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC',
        ('--hmdp.canal.enabled='+$Consume.ToString().ToLowerInvariant()),"--hmdp.canal.consumer-group=hmdp-perf-$RunId",'--hmdp.canal.max-reconsume-times=8',
        '--hmdp.cache.local-ttl-seconds=5','--hmdp.cache.edge-invalidate-urls=http://127.0.0.1:19080/edge',
        ('--hmdp.seckill.stream-consumer.enabled='+$Consume.ToString().ToLowerInvariant()),"--hmdp.seckill.stream=perf:$RunId`:orders",
        "--hmdp.seckill.dead-letter-stream=perf:$RunId`:orders:dlq","--hmdp.seckill.compensation-archive=perf:$RunId`:orders:archive")
    $process=Start-Process -FilePath $java -ArgumentList $args -WorkingDirectory $project -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $appRuntimeRoot 'out.log') -RedirectStandardError (Join-Path $appRuntimeRoot 'err.log')
    Record-Pid $name $process.Id;Save "$name-arguments.json" $args
    $deadline=[DateTime]::UtcNow.AddSeconds(60)
    do{try{$r=Invoke-WebRequest "http://127.0.0.1:$Port/shop/1" -TimeoutSec 3;if($r.StatusCode-eq 200){return}}catch{};if($process.HasExited){throw "$name exited; inspect logs"};Start-Sleep -Milliseconds 500}while([DateTime]::UtcNow-lt $deadline)
    throw "$name failed readiness"
}
function Warm {
    $fixture=Get-Content -LiteralPath $fixtureFile -Raw | ConvertFrom-Json
    foreach($id in @($fixture.hotShops)+@($fixture.otherShops)){Invoke-WebRequest "http://127.0.0.1:8084/api/shop/$id" -TimeoutSec 5 | Out-Null}
    Start-Sleep -Seconds 5
}
function Check-Memory {
    $last=Get-Content -LiteralPath (Join-Path $run 'resources.jsonl') -Tail 1 -ErrorAction SilentlyContinue
    if($last){$m=$last|ConvertFrom-Json;if($m.availableMemoryBytes-lt 1GB){throw 'Available memory below 1 GiB; stopping escalation'}}
}
function Probe([string]$Name){
    $cp=(Join-Path $project '.local/performance/classes')+';'+(Join-Path $project '.local/verification/BOOT-INF/lib/*')
    & $java '-Xmx96m' ('-Dlogback.configurationFile='+(Join-Path $project 'deploy/canal/e2e-logback.xml')) -cp $cp RedisProbe (Join-Path $run "$Name-clock.json") > (Join-Path $run "$Name-clock.log") 2>&1
    if($LASTEXITCODE-ne 0){throw 'Redis clock probe failed'}
}
function Load([string]$Name,[string]$Mode,[int]$Rate,[string]$Base,[string]$Duration='30s',[string]$VoucherId='', [string[]]$Extra=@()) {
    Check-Memory;Mark $Name
    if($Mode-eq 'order'-or $Mode-eq 'duplicate'){Probe "$Name-before"}
    $args=@('run','--no-color','--quiet','--log-format=json','-e',"FIXTURE=$fixtureFile",'-e',"MODE=$Mode",'-e',"RATE=$Rate",'-e',"BASE=$Base",'-e',"DURATION=$Duration",'-e',"SUMMARY=$(Join-Path $run ($Name+'.json'))",'-e',"VOUCHER_ID=$VoucherId",'-e',('EDGE='+($Base.EndsWith(':8084')).ToString().ToLowerInvariant()))
    foreach($entry in $Extra){$args+=@('-e',$entry)};$args+=Join-Path $PSScriptRoot 'load.js'
    Save "$Name-arguments.json" $args
    $start=[DateTime]::UtcNow
    $p=Start-Process -FilePath $k6 -ArgumentList $args -WorkingDirectory $project -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $run "$Name.out.log") -RedirectStandardError (Join-Path $run "$Name.err.log")
    $p.WaitForExit();$end=[DateTime]::UtcNow
    if($Mode-eq 'order'-or $Mode-eq 'duplicate'){Probe "$Name-after"}
    if(!(Test-Path -LiteralPath (Join-Path $run "$Name.json"))){throw "k6 failed without summary: $Name (exit $($p.ExitCode))"}
    Save "$Name-execution.json" @{start=$start.ToString('o');end=$end.ToString('o');elapsedSeconds=($end-$start).TotalSeconds;exitCode=$p.ExitCode}
    $s=Get-Content -LiteralPath (Join-Path $run "$Name.json") -Raw | ConvertFrom-Json
    Write-Host ("  completed: reqs={0}, p95={1:N2}ms, failed={2}, exit={3}" -f $s.metrics.http_reqs.values.count,$s.metrics.http_req_duration.values.'p(95)',$s.metrics.http_req_failed.values.rate,$p.ExitCode)
    return $s
}
function Wait-Orders([string]$Id,[int]$Expected,[string]$Name,[bool]$Failed=$false,[int]$Timeout=180){
    $start=[DateTime]::UtcNow;$deadline=$start.AddSeconds($Timeout)
    do {$s=Control "/snapshot?id=$Id";Save "$Name-snapshot.json" $s
        $finished=$s.terminalObservation.n
        if($finished-eq $Expected -and $s.pending-eq 0 -and $s.dlqLength-eq 0){
            if(!$Failed -and ($s.orders-ne $Expected -or $s.distinctUsers-ne $Expected)){throw "Order invariant failed: $Name"}
            Save "$Name-drain.json" @{seconds=([DateTime]::UtcNow-$start).TotalSeconds;expected=$Expected;finished=$finished};return $s
        };Start-Sleep -Milliseconds 500
    }while([DateTime]::UtcNow-lt $deadline)
    Save "$Name-drain.json" @{timedOut=$true;seconds=([DateTime]::UtcNow-$start).TotalSeconds;expected=$Expected;finished=$s.terminalObservation.n};Write-Output "  WARNING: $Name did not drain within $Timeout seconds";return $s
}
function Start-Sync([string]$Name){
    Mark $Name;Control '/sync-start?count=30' | Out-Null
    $deadline=[DateTime]::UtcNow.AddMinutes(9)
    do{Start-Sleep -Seconds 1;$s=Control '/sync-result';if($s.state-ne 'running'){Save "$Name.json" $s;Write-Output ("  sync: state={0}, success={1}, p95={2}ms" -f $s.state,$s.result.successful,$s.result.delay.p95Ms);return}}while([DateTime]::UtcNow-lt $deadline)
    throw 'CDC sampling exceeded deadline'
}
function Setup {
    Mark 'setup';if(Test-Path -LiteralPath (Join-Path $run 'ready.json')){throw 'Use a new run ID for setup'}
    $cp=(Join-Path $project '.local/performance/classes')+';'+(Join-Path $project '.local/verification/BOOT-INF/lib/*')
    $p=Start-Process -FilePath $java -ArgumentList @('-Xms64m','-Xmx256m','-Dfile.encoding=UTF-8',('-Dlogback.configurationFile='+(Join-Path $project 'deploy/canal/e2e-logback.xml')),'-cp',$cp,'PerfHarness',$run) -WorkingDirectory $project -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $run 'helper.out.log') -RedirectStandardError (Join-Path $run 'helper.err.log')
    Record-Pid 'helper' $p.Id
    $deadline=[DateTime]::UtcNow.AddSeconds(30);do{try{Control '/health' 2 | Out-Null;break}catch{};if($p.HasExited){throw 'Helper exited'};Start-Sleep -Milliseconds 500}while([DateTime]::UtcNow-lt $deadline)
    Save 'dependencies.json' (Control '/environment')
    $infra=Join-Path $project '.local/cache-sync';foreach($name in @('broker','namesrv','canal')){Record-Pid $name ([int](Get-Content -LiteralPath (Join-Path $infra "$name.pid")))}
    foreach($process in @(Get-Process -Name mysqld -ErrorAction SilentlyContinue)){Record-Pid ("mysql-"+$process.Id) $process.Id}
    $os=Get-CimInstance Win32_OperatingSystem;$cpu=Get-CimInstance Win32_Processor
    $fingerprints=@();foreach($f in @($jar)+@(Get-ChildItem -LiteralPath (Join-Path $project 'src/main') -File -Recurse | ForEach-Object {$_.FullName})){$fingerprints+=@{path=$f.Replace($project,'');sha256=(Get-FileHash -Algorithm SHA256 -LiteralPath $f).Hash}}
    Save 'environment.json' @{runId=$RunId;start=[DateTime]::UtcNow.ToString('o');os=$os.Caption;osVersion=$os.Version;totalMemoryKiB=$os.TotalVisibleMemorySize;cpu=$cpu.Name;cores=$cpu.NumberOfCores;logicalProcessors=$cpu.NumberOfLogicalProcessors;javaVersion=(& $java -version 2>&1|Out-String);k6Version=(& $k6 version|Out-String);hashes=$fingerprints;redisDatabase=15;topology='co-located load generator / apps / MySQL / single RocketMQ / Canal; VMware Redis; dedicated TCP fault proxies'}
    Save 'prepare.json' (Control '/prepare')
    $prefix=Join-Path $run 'openresty';New-Item -ItemType Directory -Force -Path (Join-Path $prefix 'conf/lua'),(Join-Path $prefix 'logs') | Out-Null
    Copy-Item -LiteralPath (Join-Path $project 'deploy/openresty/lua/shop.lua') -Destination (Join-Path $prefix 'conf/lua/shop.lua')
    Copy-Item -LiteralPath (Join-Path $project 'deploy/openresty/lua/invalidate_shop.lua') -Destination (Join-Path $prefix 'conf/lua/invalidate_shop.lua')
    $conf=Get-Content -LiteralPath (Join-Path $project 'deploy/openresty/nginx.conf') -Raw
    $conf=$conf.Replace('listen 8080;','listen 8084;').Replace('127.0.0.1:8081','127.0.0.1:8085').Replace('include mime.types;','include '+(Join-Path $project '.local/openresty-1.27.1.2-win64/conf/mime.types').Replace('\','/')+';')
    $conf | Set-Content -LiteralPath (Join-Path $prefix 'conf/nginx.conf') -Encoding utf8
    $yaml=Get-Content -LiteralPath (Join-Path $project 'src/main/resources/application.yaml') -Raw
    $ry=$yaml.Substring($yaml.IndexOf('  redis:'),$yaml.IndexOf('  jackson:')-$yaml.IndexOf('  redis:'))
    $env:HMDP_REDIS_HOST='127.0.0.1';$env:HMDP_REDIS_PORT='16379';$env:HMDP_REDIS_DB='15';$env:HMDP_REDIS_PASSWORD=[regex]::Match($ry,'(?m)^    password: (.+)$').Groups[1].Value.Trim()
    & $nginx -t -p ($prefix.Replace('\','/')+'/') -c conf/nginx.conf
    if($LASTEXITCODE-ne 0){throw 'OpenResty config invalid'}
    $p=Start-Process -FilePath $nginx -ArgumentList @('-p',($prefix.Replace('\','/')+'/'),'-c','conf/nginx.conf') -WorkingDirectory $prefix -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $prefix 'out.log') -RedirectStandardError (Join-Path $prefix 'err.log')
    Record-Pid 'openresty' $p.Id;Remove-Item Env:HMDP_REDIS_PASSWORD
    Finish-Setup
}
function Finish-Setup {
    Start-App 8085 $false;Start-App 8086 $true
    'running' | Set-Content -LiteralPath (Join-Path $run 'monitor.alive')
    $p=Start-Process -FilePath (Get-Process -Id $PID).Path -ArgumentList @('-NoProfile','-File',(Join-Path $PSScriptRoot 'monitor.ps1'),'-RunDirectory',$run) -WorkingDirectory $project -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $run 'monitor.out.log') -RedirectStandardError (Join-Path $run 'monitor.err.log')
    Record-Pid 'monitor' $p.Id
    Start-Sleep -Seconds 10;Warm
    $smoke=Load 'smoke-shop' 'shop' 10 'http://127.0.0.1:8084' '5s'
    if($smoke.metrics.content_valid.values.rate-ne 1){throw 'Shop smoke failed'}
    $v=Control '/voucher?stock=20';$smoke=Load 'smoke-order' 'order' 2 'http://127.0.0.1:8085' '5s' $v.id
    $accepted=[int]$smoke.metrics.business_accepted.values.count
    if($accepted-lt 9){throw 'Order smoke failed'}
    Wait-Orders $v.id $accepted 'smoke-order' | Out-Null
    Save 'setup-complete.json' @{complete=$true;time=[DateTime]::UtcNow.ToString('o')};Mark 'setup-complete'
}
function Prepare-Measurements {
    Mark 'prepare-measurements'
    $prefix=Join-Path $run 'openresty';$confPath=Join-Path $prefix 'conf/nginx.conf'
    $conf=Get-Content -LiteralPath $confPath -Raw;$conf=$conf.Replace('    access_log off;','');$conf | Set-Content -LiteralPath $confPath -Encoding utf8
    & $nginx -p ($prefix.Replace('\','/')+'/') -c conf/nginx.conf -s reload
    Start-Sleep -Seconds 2
    $p=Read-Pids;$master=[int]$p['openresty'];foreach($child in @(Get-CimInstance Win32_Process -Filter "ParentProcessId=$master")){if($child.Name-eq 'nginx.exe'){Record-Pid 'openresty-worker' $child.ProcessId}}
    Probe 'baseline'
    Mark 'measurement-ready'
}
function Cache {
    foreach($kind in @('edge','java')){foreach($rate in @(100,300,600)){foreach($repeat in 1..3){Warm;$base=if($kind-eq 'edge'){'http://127.0.0.1:8084'}else{'http://127.0.0.1:8085'};Load "cache-$kind-$rate-r$repeat" 'shop' $rate $base | Out-Null}}}
    foreach($repeat in 1..3){Warm;Load "cache-mixed-300-r$repeat" 'mixed' 300 'http://127.0.0.1:8084' | Out-Null}
    foreach($repeat in 1..3){Load "cache-cold-r$repeat" 'cold' 0 'http://127.0.0.1:8084' '30s' '' @("COLD_OFFSET=$(($repeat-1)*100)",'VUS=10','ITERATIONS=100') | Out-Null}
    $fixture=Get-Content -LiteralPath $fixtureFile -Raw|ConvertFrom-Json;$id=$fixture.hotShops[1]
    Save 'burst-before-digest.json' (Control '/digest');Control "/clear?id=$id"|Out-Null
    Load 'cache-cold-same-key' 'burst' 0 'http://127.0.0.1:8084' '30s' '' @("SHOP_ID=$id",'VUS=100','ITERATIONS=100')|Out-Null
    Save 'burst-after-digest.json' (Control '/digest')
    Save 'logical-before-digest.json' (Control '/digest');Control "/logical?id=$id"|Out-Null
    Load 'cache-logical-expired' 'logical' 0 'http://127.0.0.1:8085' '30s' '' @("SHOP_ID=$id",'VUS=100','ITERATIONS=100')|Out-Null
    Start-Sleep -Seconds 6;Save 'logical-after-read.json' (Control "/read?id=$id");Save 'logical-after-digest.json' (Control '/digest')
    Control "/clear?id=$id"|Out-Null;Save 'logical-restored-fixture.json' (Control "/read?id=$id")
}
function Orders {
    $v=Control '/voucher?stock=500';$s=Load 'order-warmup' 'order' 10 'http://127.0.0.1:8085' '20s' $v.id;Wait-Orders $v.id ([int]$s.metrics.business_accepted.values.count) 'order-warmup'|Out-Null
    foreach($rate in @(10,30,60)){foreach($repeat in 1..3){$v=Control '/voucher?stock=2500';$name="order-$rate-r$repeat";$s=Load $name 'order' $rate 'http://127.0.0.1:8085' '30s' $v.id;$accepted=[int]$s.metrics.business_accepted.values.count;Wait-Orders $v.id $accepted $name|Out-Null}}
    $v=Control '/voucher?stock=300';$s=Load 'order-stock-competition' 'order' 200 'http://127.0.0.1:8085' '7.5s' $v.id;$a=[int]$s.metrics.business_accepted.values.count;$final=Wait-Orders $v.id $a 'order-stock-competition'
    Save 'stock-competition-check.json' @{accepted=$a;exactly300=($a-eq 300-and $final.orders-eq 300);nonnegative=($final.mysqlStock-ge 0-and [int]$final.redisStock-ge 0);stockEqual=($final.mysqlStock-eq [int]$final.redisStock)}
    $v=Control '/voucher?stock=20';$s=Load 'order-duplicate-user' 'duplicate' 50 'http://127.0.0.1:8085' '4s' $v.id;$a=[int]$s.metrics.business_accepted.values.count;Wait-Orders $v.id $a 'order-duplicate-user'|Out-Null
    Save 'duplicate-before.json' (Control "/snapshot?id=$($v.id)");Mark 'order-duplicate-message';Save 'duplicate-enqueue.json' (Control "/replay?id=$($v.id)&count=100");Start-Sleep -Seconds 2
    $deadline=[DateTime]::UtcNow.AddSeconds(60);do{$after=Control "/snapshot?id=$($v.id)";$group=$after.groups[0];$fields=@{};for($i=0;$i-lt $group.Count;$i+=2){$fields[$group[$i]]=$group[$i+1]};if($after.pending-eq 0-and $fields['last-delivered-id']-eq $after.lastStreamId){break};Start-Sleep -Milliseconds 500}while([DateTime]::UtcNow-lt $deadline)
    Save 'duplicate-after.json' $after
}
function Warm-Cache {
    Load 'cache-java-warmup' 'shop' 300 'http://127.0.0.1:8085' '45s'|Out-Null
    foreach($repeat in 1..3){Warm;Load "cache-java-warm-100-r$repeat" 'shop' 100 'http://127.0.0.1:8085'|Out-Null}
}
function Warm-CacheClean {
    $fixture=Get-Content -LiteralPath $fixtureFile -Raw|ConvertFrom-Json;$id=$fixture.hotShops[1]
    Control "/clear?id=$id"|Out-Null;Save 'logical-restored-fixture.json' (Control "/read?id=$id")
    Load 'cache-java-clean-warmup' 'shop' 300 'http://127.0.0.1:8085' '45s'|Out-Null
    foreach($repeat in 1..3){Warm;Load "cache-java-warm-clean-100-r$repeat" 'shop' 100 'http://127.0.0.1:8085'|Out-Null}
}
function Collect-Mq([string]$Name){
    try{& (Join-Path $project 'deploy/canal/mq-admin.ps1') -Command consumerProgress -MqArguments @('-n','127.0.0.1:9876','-g',"hmdp-perf-$RunId") | Out-Null
        Copy-Item -LiteralPath (Join-Path $project '.local/cache-sync/mq-admin-consumerProgress.log') -Destination (Join-Path $run "$Name-mq-progress.log")
    }catch{Save "$Name-mq-error.json" @{error=$_.Exception.Message}}
}
function Restart-Dependency([string]$Service,[string]$Version,[long]$Shop){
    Mark "fault-$Service-restart";Control "/read?id=$Shop"|Out-Null
    $known=@((Read-Pids).Values|ForEach-Object{[int]$_});$unknown=@(Get-Process -Name java -ErrorAction SilentlyContinue|Where-Object {$_.Id-notin $known})
    if($unknown.Count-gt 0){Save "fault-$Service-skipped.json" @{reason='Other Java processes are running; dependency restart skipped';count=$unknown.Count};return}
    & (Join-Path $project 'deploy/canal/stop-local.ps1') -Services @($Service)
    Save "fault-$Service-update.json" (Control "/update?id=$Shop&version=$Version")
    Start-Sleep -Seconds 15;Save "fault-$Service-during.json" (Control "/read?id=$Shop")
    $begin=[DateTime]::UtcNow
    $startup=if($Service-eq 'broker'){'start-mq.ps1'}else{'start-canal.ps1'}
    & (Join-Path $project ("deploy/canal/"+$startup)) -JavaHome $JavaHome
    if($Service-eq 'broker'){Record-Pid 'broker' ([int](Get-Content -LiteralPath (Join-Path $project '.local/cache-sync/broker.pid')))}else{Record-Pid 'canal' ([int](Get-Content -LiteralPath (Join-Path $project '.local/cache-sync/canal.pid')))}
    $deadline=[DateTime]::UtcNow.AddSeconds(60);$fresh=$false
    do{$reads=Control "/read?id=$Shop";$names=@($reads.PSObject.Properties|ForEach-Object {$_.Value.name});$fresh=($names.Count-eq 3-and ($names|Where-Object {$_-notlike "*$Version"}).Count-eq 0);if($fresh){break};Start-Sleep -Milliseconds 200}while([DateTime]::UtcNow-lt $deadline)
    Save "fault-$Service-recovery.json" @{restartStarted=$begin.ToString('o');observed=[DateTime]::UtcNow.ToString('o');elapsedSeconds=([DateTime]::UtcNow-$begin).TotalSeconds;freshAll=$fresh;reads=$reads}
    Collect-Mq "after-$Service-restart"
}
function Sync-Phase {
    Collect-Mq 'before-cdc'
    Start-Sync 'cdc-idle'
    $args=@('run','--quiet','--no-color','-e',"FIXTURE=$fixtureFile",'-e','MODE=mixed','-e','RATE=300','-e','BASE=http://127.0.0.1:8084','-e','EDGE=true','-e','DURATION=90s','-e',"SUMMARY=$(Join-Path $run 'cdc-background-load.json')",(Join-Path $PSScriptRoot 'load.js'))
    $p=Start-Process -FilePath $k6 -ArgumentList $args -WorkingDirectory $project -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $run 'cdc-background-load.out.log') -RedirectStandardError (Join-Path $run 'cdc-background-load.err.log')
    try{Start-Sync 'cdc-under-load';$p.WaitForExit()}finally{if(!$p.HasExited){Stop-Process -Id $p.Id -Force}}
    Collect-Mq 'after-cdc'
}
function Availability {
    $fixture=Get-Content -LiteralPath $fixtureFile -Raw|ConvertFrom-Json;$shop=$fixture.hotShops[2]
    Mark 'fault-mysql';Control '/fault?target=mysql&healthy=false'|Out-Null
    $restore=Start-Process -FilePath (Get-Process -Id $PID).Path -ArgumentList @('-NoProfile','-File',(Join-Path $PSScriptRoot 'restore-fault.ps1'),'-RunDirectory',$run,'-Target','mysql','-DelaySeconds','15') -WorkingDirectory $project -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $run 'restore-mysql.out.log') -RedirectStandardError (Join-Path $run 'restore-mysql.err.log')
    $v=Control '/voucher?stock=100';$s=Load 'fault-mysql-ingress' 'order' 10 'http://127.0.0.1:8085' '5s' $v.id;$a=[int]$s.metrics.business_accepted.values.count;Save 'fault-mysql-during.json' (Control "/snapshot?id=$($v.id)")
    $entry=Get-Content -LiteralPath (Join-Path $run 'fault-mysql-ingress.err.log')|ForEach-Object {try{($_|ConvertFrom-Json).msg|ConvertFrom-Json}catch{}}|Where-Object kind -eq 'accepted-order'|Select-Object -First 1
    if($entry){$token=$fixture.users[$entry.userIndex].token;$started=[DateTime]::UtcNow;try{$status=Invoke-RestMethod -Uri "http://127.0.0.1:8085/voucher-order/status/$($entry.orderId)" -Headers @{authorization=$token} -TimeoutSec 3;Save 'fault-mysql-status.json' @{elapsedMs=([DateTime]::UtcNow-$started).TotalMilliseconds;response=$status}}catch{Save 'fault-mysql-status.json' @{elapsedMs=([DateTime]::UtcNow-$started).TotalMilliseconds;error=$_.Exception.GetType().Name}}}
    $restore.WaitForExit();Save 'fault-mysql-restored.json' (Control '/fault?target=mysql&healthy=true');Wait-Orders $v.id $a 'fault-mysql-recovery'|Out-Null
    Mark 'fault-worker-crash';Control '/fault?target=mysql&healthy=false'|Out-Null;$v=Control '/voucher?stock=100';$s=Load 'fault-worker-ingress' 'order' 10 'http://127.0.0.1:8085' '5s' $v.id;$a=[int]$s.metrics.business_accepted.values.count;Save 'fault-worker-before-crash.json' (Control "/snapshot?id=$($v.id)");Stop-Owned 'app-8086';Save 'fault-worker-connection-restored.json' (Control '/fault?target=mysql&healthy=true');$restart=[DateTime]::UtcNow;Start-App 8086 $true;Save 'fault-worker-restart.json' @{start=$restart.ToString('o');ready=[DateTime]::UtcNow.ToString('o')};Wait-Orders $v.id $a 'fault-worker-recovery'|Out-Null
    Mark 'fault-redis';$v=Control '/voucher?stock=100';Control "/read?id=$shop"|Out-Null;Control '/fault?target=redis&healthy=false'|Out-Null
    $restore=Start-Process -FilePath (Get-Process -Id $PID).Path -ArgumentList @('-NoProfile','-File',(Join-Path $PSScriptRoot 'restore-fault.ps1'),'-RunDirectory',$run,'-Target','redis','-DelaySeconds','8') -WorkingDirectory $project -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $run 'restore-fault.out.log') -RedirectStandardError (Join-Path $run 'restore-fault.err.log')
    $faultArgs=@('run','--no-color','--quiet','--log-format=json','-e',"FIXTURE=$fixtureFile",'-e','MODE=order','-e','RATE=2','-e','BASE=http://127.0.0.1:8085','-e','DURATION=8s','-e','TIMEOUT=4s','-e',"VOUCHER_ID=$($v.id)",'-e',"SUMMARY=$(Join-Path $run 'fault-redis-ingress.json')",(Join-Path $PSScriptRoot 'load.js'))
    $faultLoad=Start-Process -FilePath $k6 -ArgumentList $faultArgs -WorkingDirectory $project -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $run 'fault-redis-ingress.out.log') -RedirectStandardError (Join-Path $run 'fault-redis-ingress.err.log')
    $samples=@();for($i=0;$i-lt 12;$i++){$start=[DateTime]::UtcNow;try{$r=Invoke-WebRequest "http://127.0.0.1:8084/api/shop/$shop" -TimeoutSec 3;$samples+=@{time=$start.ToString('o');status=$r.StatusCode;cache=$r.Headers['X-HMDP-Cache'];latencyMs=([DateTime]::UtcNow-$start).TotalMilliseconds}}catch{$samples+=@{time=$start.ToString('o');error=$_.Exception.GetType().Name;latencyMs=([DateTime]::UtcNow-$start).TotalMilliseconds}};Start-Sleep -Milliseconds 700}
    $restore.WaitForExit();$faultLoad.WaitForExit()
    Save 'fault-redis-samples.json' $samples;Save 'fault-redis-restored.json' (Control '/fault?target=redis&healthy=true');Start-Sleep -Seconds 8;Save 'fault-redis-recovered-read.json' (Control "/read?id=$shop");Save 'fault-redis-final-orders.json' (Control "/snapshot?id=$($v.id)")
    Mark 'fault-cdc-worker';Control "/read?id=$shop"|Out-Null;Stop-Owned 'app-8086';Save 'fault-cdc-update.json' (Control "/update?id=$shop&version=consumer-stopped");Start-Sleep -Seconds 20;Save 'fault-cdc-during.json' (Control "/read?id=$shop");$restart=[DateTime]::UtcNow;Start-App 8086 $true
    $deadline=[DateTime]::UtcNow.AddSeconds(60);do{$reads=Control "/read?id=$shop";$names=@($reads.PSObject.Properties|ForEach-Object {$_.Value.name});if(($names|Where-Object {$_-notlike '*consumer-stopped'}).Count-eq 0){break};Start-Sleep -Milliseconds 200}while([DateTime]::UtcNow-lt $deadline)
    Save 'fault-cdc-recovery.json' @{restartStarted=$restart.ToString('o');observed=[DateTime]::UtcNow.ToString('o');elapsedSeconds=([DateTime]::UtcNow-$restart).TotalSeconds;reads=$reads}
    Mark 'fault-edge-retry';$before=Control '/health';Control '/fault?target=edge&healthy=false'|Out-Null;Save 'fault-edge-update.json' (Control "/update?id=$shop&version=edge-retry")
    $deadline=[DateTime]::UtcNow.AddSeconds(30);do{Start-Sleep -Milliseconds 200;$h=Control '/health'}while($h.edgeFailure-le $before.edgeFailure-and [DateTime]::UtcNow-lt $deadline)
    Save 'fault-edge-first-failure.json' $h;Save 'fault-edge-restored.json' (Control '/fault?target=edge&healthy=true');$start=[DateTime]::UtcNow;$deadline=$start.AddSeconds(60)
    do{Start-Sleep -Milliseconds 200;$h=Control '/health'}while($h.edgeSuccess-le $before.edgeSuccess-and [DateTime]::UtcNow-lt $deadline)
    Save 'fault-edge-recovery.json' @{elapsedSeconds=([DateTime]::UtcNow-$start).TotalSeconds;edgeSuccess=$h.edgeSuccess;edgeFailure=$h.edgeFailure;reads=(Control "/read?id=$shop")}
    Restart-Dependency 'broker' 'broker-restored' $shop
    Restart-Dependency 'canal' 'canal-restored' $shop
    Mark 'fault-permanent-order';$v=Control '/voucher?stock=10&dbStock=0';$s=Load 'fault-permanent-ingress' 'order' 2 'http://127.0.0.1:8085' '5s' $v.id;$a=[int]$s.metrics.business_accepted.values.count;Wait-Orders $v.id $a 'fault-permanent-compensation' $true 180|Out-Null
    Collect-Mq 'final'
}
function Cleanup {
    Mark 'cleanup';try{Control '/fault?target=mysql&healthy=true'|Out-Null;Control '/fault?target=redis&healthy=true'|Out-Null;Control '/fault?target=edge&healthy=true'|Out-Null;Save 'cleanup.json' (Control '/cleanup');Start-Sleep -Seconds 5}catch{Save 'cleanup-error.json' @{error=$_.Exception.Message};throw}
    foreach($name in @('app-8086','app-8085')){Stop-Owned $name}
    $prefix=Join-Path $run 'openresty';& $nginx -p ($prefix.Replace('\','/')+'/') -c conf/nginx.conf -s quit
    Start-Sleep -Seconds 1
    Remove-Item -LiteralPath (Join-Path $run 'monitor.alive') -ErrorAction SilentlyContinue
    Start-Sleep -Seconds 2;Stop-Owned 'monitor';Stop-Owned 'helper'
    Mark 'complete'
}
try {
    switch($Phase){'Setup'{Setup};'ResumeSetup'{Finish-Setup};'PrepareMeasurements'{Prepare-Measurements};'Cache'{Cache};'WarmCache'{Warm-Cache};'WarmCacheClean'{Warm-CacheClean};'Orders'{Orders};'Sync'{Sync-Phase};'Availability'{Availability};'Cleanup'{Cleanup};'All'{Setup;Prepare-Measurements;Cache;Warm-Cache;Orders;Sync-Phase;Availability;Cleanup}}
}catch{Save "failure-$Phase.json" @{time=[DateTime]::UtcNow.ToString('o');phase=$Phase;error=$_.Exception.Message;stack=$_.ScriptStackTrace};throw}
