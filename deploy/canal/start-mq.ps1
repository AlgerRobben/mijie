param(
    [string]$RocketMqHome = 'D:\rocketmq-all-5.3.3-bin-release',
    [string]$JavaHome = 'D:\JDK21\jdk-21.0.12.1+1'
)
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$runtimeRoot = Join-Path $projectRoot '.local/cache-sync'
New-Item -ItemType Directory -Force -Path $runtimeRoot | Out-Null
$java = Join-Path $JavaHome 'bin/java.exe'
if (!(Test-Path $java) -or !(Test-Path (Join-Path $RocketMqHome 'lib/rocketmq-broker-5.3.3.jar'))) {
    throw 'Check JavaHome and RocketMqHome before starting the local services.'
}
$common = @('-Xms128m', '-Xmx512m', '-XX:MaxDirectMemorySize=256m',
    '--add-opens=java.base/java.lang=ALL-UNNAMED', '--add-opens=java.base/java.nio=ALL-UNNAMED',
    '--add-opens=java.base/sun.nio.ch=ALL-UNNAMED', '--add-opens=java.base/java.util=ALL-UNNAMED',
    ('-Duser.home=' + $runtimeRoot), ('-Drocketmq.home.dir=' + $RocketMqHome),
    '-cp', ((Join-Path $RocketMqHome 'conf') + ';' + (Join-Path $RocketMqHome 'lib/*')))
$env:ROCKETMQ_HOME = $RocketMqHome
@"
listenPort=9876
bindAddress=127.0.0.1
"@ | Set-Content -Encoding ascii (Join-Path $runtimeRoot 'namesrv.conf')
$storeRoot = (Join-Path $runtimeRoot 'store').Replace('\', '/')
@"
brokerClusterName=hmdp-local
brokerName=hmdp-broker
brokerId=0
brokerIP1=127.0.0.1
bindAddress=127.0.0.1
listenPort=10911
namesrvAddr=127.0.0.1:9876
autoCreateTopicEnable=false
autoCreateSubscriptionGroup=true
brokerRole=ASYNC_MASTER
flushDiskType=SYNC_FLUSH
storePathRootDir=$storeRoot
storePathCommitLog=$storeRoot/commitlog
mappedFileSizeCommitLog=67108864
fileReservedTime=24
"@ | Set-Content -Encoding ascii (Join-Path $runtimeRoot 'broker.conf')
foreach ($service in @(
    @{Name='namesrv'; Main='org.apache.rocketmq.namesrv.NamesrvStartup'; Config='namesrv.conf'},
    @{Name='broker'; Main='org.apache.rocketmq.broker.BrokerStartup'; Config='broker.conf'}
)) {
    $pidFile = Join-Path $runtimeRoot ($service.Name + '.pid')
    if (Test-Path $pidFile) {
        $existing=Get-CimInstance Win32_Process -Filter ("ProcessId="+[int](Get-Content $pidFile))
        if($existing -and $existing.CommandLine.Contains($service.Main) -and $existing.CommandLine.Contains($runtimeRoot)){
            Write-Output ($service.Name + ' is already running');continue
        }
    }
    $arguments = $common + @($service.Main, '-c', (Join-Path $runtimeRoot $service.Config))
    $process = Start-Process -FilePath $java -ArgumentList $arguments -WorkingDirectory $runtimeRoot -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput (Join-Path $runtimeRoot ($service.Name + '.out.log')) `
        -RedirectStandardError (Join-Path $runtimeRoot ($service.Name + '.err.log'))
    $process.Id | Set-Content $pidFile
    Write-Output ('Started {0}, PID {1}' -f $service.Name, $process.Id)
    Start-Sleep -Seconds 2
    if ($process.HasExited) { throw ($service.Name + ' exited; inspect its logs in ' + $runtimeRoot) }
}
