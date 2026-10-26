param([string]$JavaHome='D:\JDK21\jdk-21.0.12.1+1')
$ErrorActionPreference='Stop'
$projectRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$runtime=Join-Path $projectRoot '.local/cache-sync'
$verify=Join-Path $projectRoot '.local/verification'
$suffix=[Guid]::NewGuid().ToString('N')
$stream="test:order-e2e:$suffix"
$arguments=@('-Xms128m','-Xmx512m','-Dfile.encoding=UTF-8',('-Duser.home='+$runtime),'-jar',
    (Join-Path $projectRoot 'target/hm-dianping-0.0.1-SNAPSHOT.jar'), '--server.port=8083','--spring.redis.database=15',
    '--hmdp.canal.enabled=false','--hmdp.cache.edge-invalidate-urls=', '--hmdp.seckill.stream-consumer.enabled=true',
    "--hmdp.seckill.stream=$stream", "--hmdp.seckill.dead-letter-stream=$stream`:dlq", "--hmdp.seckill.compensation-archive=$stream`:archive",
    '--hmdp.seckill.stream-consumer.claim-idle=200ms','--hmdp.seckill.stream-consumer.recovery-interval=200ms','--hmdp.seckill.stream-consumer.max-deliveries=2')
$process=Start-Process -FilePath (Join-Path $JavaHome 'bin/java.exe') -ArgumentList $arguments -WorkingDirectory $projectRoot -WindowStyle Hidden -PassThru `
    -RedirectStandardOutput (Join-Path $runtime 'order-e2e-app.out.log') -RedirectStandardError (Join-Path $runtime 'order-e2e-app.err.log')
try {
    $deadline=[DateTime]::UtcNow.AddSeconds(45)
    do {try{$health=Invoke-RestMethod http://127.0.0.1:8083/actuator/health -TimeoutSec 2;break}catch{Start-Sleep -Milliseconds 500}} while([DateTime]::UtcNow-lt $deadline)
    if(!$health){throw 'Isolated order test app failed to start'}
    New-Item -ItemType Directory -Force $verify | Out-Null
    Push-Location $verify
    try{& (Join-Path $JavaHome 'bin/jar.exe') xf (Join-Path $projectRoot 'target/hm-dianping-0.0.1-SNAPSHOT.jar') BOOT-INF/lib}finally{Pop-Location}
    Push-Location $projectRoot
    try {
        & (Join-Path $JavaHome 'bin/java.exe') '-Dfile.encoding=UTF-8' ('-Dlogback.configurationFile='+ (Join-Path $projectRoot 'deploy/canal/e2e-logback.xml')) `
            -cp (Join-Path $verify 'BOOT-INF/lib/*') (Join-Path $PSScriptRoot 'OrderLifecycleE2E.java') $stream "$stream`:dlq" "$stream`:archive" > (Join-Path $runtime 'order-http-e2e.log') 2>&1
        if($LASTEXITCODE -ne 0){throw 'Order HTTP E2E failed; inspect .local/cache-sync/order-http-e2e.log'}
        Select-String -Path (Join-Path $runtime 'order-http-e2e.log') -Pattern '^CHECK |^PASS ' | ForEach-Object {$_.Line}
    }finally{Pop-Location}
}finally{
    if(!$process.HasExited){& taskkill.exe /PID $process.Id /F | Out-Null}
}
