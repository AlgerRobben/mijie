param([ValidateSet('prepare','cleanup','cdc','retry','all')][string]$Mode='all',[string]$JavaHome='D:\JDK21\jdk-21.0.12.1+1')
$ErrorActionPreference='Stop'
$root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$verify=Join-Path $root '.local/verification';$out=Join-Path $verify 'fixed-cache'
New-Item -ItemType Directory -Force $out | Out-Null
# cdc要求两个业务实例及边缘都使用60秒逻辑期限，主动通知必须在期限前完成；不是修改生产默认5秒。
if($Mode-in @('cdc','all')) {
    $fixture=Get-Content (Join-Path $out 'fixture.json') -Raw | ConvertFrom-Json
    $expectedIds='--hmdp.cache.hot.ids=10,'+$fixture.hotId
    foreach($port in @(8081,8082)){
        $pidfile=Join-Path $root ".local/cache-sync/app-$port.pid"
        if(!(Test-Path $pidfile)){throw "Start test app :$port first"}
        $process=Get-CimInstance Win32_Process -Filter ("ProcessId="+[int](Get-Content $pidfile))
        if(!$process -or $process.CommandLine-notlike '*--hmdp.cache.hot.local-logical-ttl-seconds=60*' -or $process.CommandLine-notlike '*--spring.redis.database=15*' -or $process.CommandLine-notlike ('*'+$expectedIds+'*')){throw "App :$port must use Redis15, fixed fixture HOT IDs and HotLocalLogicalTtlSeconds=60"}
    }
}
if($Mode-in @('prepare','cleanup')) {
    $active=Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object {
        $_.CommandLine -like '*hm-dianping-0.0.1-SNAPSHOT.jar*' -and $_.CommandLine -like '*--spring.redis.database=15*'
    }
    if($active){throw 'Stop project diagnostic Java instances using Redis15 before preparing/cleaning v3 test cache'}
}
Push-Location $verify
try {& (Join-Path $JavaHome 'bin/jar.exe') xf (Join-Path $root 'target/hm-dianping-0.0.1-SNAPSHOT.jar') BOOT-INF/lib;if($LASTEXITCODE-ne 0){throw 'Extract dependencies failed'}}finally {Pop-Location}
Push-Location $root
try {
    $modes=if($Mode-eq 'all'){@('cdc','retry')}else{@($Mode)}
    foreach($one in $modes){
        & (Join-Path $JavaHome 'bin/java.exe') '-Dfile.encoding=UTF-8' ('-Duser.home='+(Join-Path $root '.local/cache-sync')) ('-Dlogback.configurationFile='+(Join-Path $PSScriptRoot 'e2e-logback.xml')) -cp ((Join-Path $root 'target/classes')+';'+(Join-Path $verify 'BOOT-INF/lib/*')) (Join-Path $PSScriptRoot 'CacheSyncE2E.java') $one > (Join-Path $out "$one.log") 2>&1
        if($LASTEXITCODE-ne 0){Get-Content (Join-Path $out "$one.log") -Tail 25;throw "$one verification failed"}
        Select-String -Path (Join-Path $out "$one.log") -Pattern '^CHECK |^PASS ' | ForEach-Object {$_.Line}
    }
    if($Mode-in @('retry','all')){
        $topic=(Get-Content (Join-Path $out 'dlq-topic.txt') -Raw).Trim()
        & (Join-Path $PSScriptRoot 'mq-admin.ps1') -JavaHome $JavaHome -Command topicStatus -MqArguments @('-n','127.0.0.1:9876','-t',$topic)
        $status=Get-Content (Join-Path $root '.local/cache-sync/mq-admin-topicStatus.log') -Raw
        if($status-notmatch '(?m)^hmdp-broker\s+0\s+0\s+1\s+'){throw 'Expected one real Broker DLQ message'}
        Copy-Item (Join-Path $root '.local/cache-sync/mq-admin-topicStatus.log') (Join-Path $out 'dlq-status.log')
        Write-Output 'PASS real Broker DLQ message count=1'
    }
}finally {Pop-Location}
