param(
    [string]$RocketMqHome = 'D:\rocketmq-all-5.3.3-bin-release',
    [string]$JavaHome = 'D:\JDK21\jdk-21.0.12.1+1'
)
$ErrorActionPreference = 'Stop'
& (Join-Path $PSScriptRoot 'start-mq.ps1') -RocketMqHome $RocketMqHome -JavaHome $JavaHome
& (Join-Path $PSScriptRoot 'mq-admin.ps1') -RocketMqHome $RocketMqHome -JavaHome $JavaHome -Command updateTopic `
    -MqArguments @('-n','127.0.0.1:9876','-b','127.0.0.1:10911','-t','hmdp-shop-change','-r','4','-w','4')
& (Join-Path $PSScriptRoot 'start-canal.ps1') -JavaHome $JavaHome
& (Join-Path $PSScriptRoot 'start-app.ps1') -JavaHome $JavaHome
