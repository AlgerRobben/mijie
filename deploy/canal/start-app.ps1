param(
    [int]$Port = 8081,
    [bool]$ConsumeCanal = $true,
    [bool]$ConsumeOrders = $true,
    [Alias('LocalTtlSeconds')][int]$HotLocalLogicalTtlSeconds = 5,
    [int]$RedisDatabase = 0,
    [string]$HotShopIds = '10',
    [string]$CanalConsumerGroup = 'hmdp-shop-cache',
    [string]$JavaHome = 'D:\JDK21\jdk-21.0.12.1+1'
)
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$runtimeRoot = Join-Path $projectRoot '.local/cache-sync'
New-Item -ItemType Directory -Force -Path $runtimeRoot | Out-Null
$pidFile = Join-Path $runtimeRoot ("app-$Port.pid")
if (Test-Path $pidFile) {
    $existing=Get-CimInstance Win32_Process -Filter ("ProcessId="+[int](Get-Content $pidFile))
    if($existing -and $existing.CommandLine -like '*hm-dianping-0.0.1-SNAPSHOT.jar*' -and $existing.CommandLine -like "*--server.port=$Port*") {
        Write-Output ("App :$Port is already running");return
    }
}
$arguments = @('-Xms128m', '-Xmx512m', '-Dfile.encoding=UTF-8', ('-Duser.home=' + $runtimeRoot),
    '-jar', (Join-Path $projectRoot 'target/hm-dianping-0.0.1-SNAPSHOT.jar'),
    "--server.port=$Port", "--spring.redis.database=$RedisDatabase",
    "--hmdp.cache.hot.ids=$HotShopIds", "--hmdp.canal.consumer-group=$CanalConsumerGroup", ('--hmdp.canal.enabled=' + $ConsumeCanal.ToString().ToLowerInvariant()),
    "--hmdp.cache.hot.local-logical-ttl-seconds=$HotLocalLogicalTtlSeconds", '--hmdp.cache.edge-invalidate-urls=http://127.0.0.1:8080/internal/cache/shop',
    ('--hmdp.seckill.stream-consumer.enabled=' + $ConsumeOrders.ToString().ToLowerInvariant()))
$process = Start-Process -FilePath (Join-Path $JavaHome 'bin/java.exe') -ArgumentList $arguments `
    -WorkingDirectory $projectRoot -WindowStyle Hidden -PassThru `
    -RedirectStandardOutput (Join-Path $runtimeRoot ("app-$Port.out.log")) `
    -RedirectStandardError (Join-Path $runtimeRoot ("app-$Port.err.log"))
$process.Id | Set-Content $pidFile
Write-Output ("Started app :$Port, PID " + $process.Id)
