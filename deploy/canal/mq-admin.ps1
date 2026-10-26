param(
    [Parameter(Mandatory=$true)][string]$Command,
    [string[]]$MqArguments = @(),
    [string]$RocketMqHome = 'D:\rocketmq-all-5.3.3-bin-release',
    [string]$JavaHome = 'D:\JDK21\jdk-21.0.12.1+1'
)
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$runtimeRoot = Join-Path $projectRoot '.local/cache-sync'
New-Item -ItemType Directory -Force -Path $runtimeRoot | Out-Null
$logFile = Join-Path $runtimeRoot ('mq-admin-' + $Command + '.log')
& (Join-Path $JavaHome 'bin/java.exe') ('-Duser.home=' + $runtimeRoot) ('-Drocketmq.home.dir=' + $RocketMqHome) `
    -cp ((Join-Path $RocketMqHome 'conf') + ';' + (Join-Path $RocketMqHome 'lib/*')) `
    org.apache.rocketmq.tools.command.MQAdminStartup $Command @MqArguments > $logFile 2>&1
if ($LASTEXITCODE -ne 0) { throw "mqadmin $Command failed; inspect $logFile" }
Get-Content $logFile | Where-Object { $_ -notmatch '^\d{2}:\d{2}:\d{2},\d{3} \|-' } | Select-Object -Last 10
