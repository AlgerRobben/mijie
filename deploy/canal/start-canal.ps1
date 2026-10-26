param([string]$JavaHome = 'D:\JDK21\jdk-21.0.12.1+1')
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$canalRoot = Join-Path $projectRoot '.local/canal-1.1.8'
$runtimeRoot = Join-Path $projectRoot '.local/cache-sync'
New-Item -ItemType Directory -Force -Path $runtimeRoot | Out-Null
if (!(Test-Path (Join-Path $canalRoot 'lib'))) { throw 'Extract the official Canal 1.1.8 deployer into .local/canal-1.1.8 first.' }
$pidFile = Join-Path $runtimeRoot 'canal.pid'
if (Test-Path $pidFile) {
    $existing=Get-CimInstance Win32_Process -Filter ("ProcessId="+[int](Get-Content $pidFile))
    if($existing -and $existing.CommandLine -like '*com.alibaba.otter.canal.deployer.CanalLauncher*' -and $existing.CommandLine.Contains($canalRoot)){
        Write-Output 'Canal is already running';return
    }
}
$credentialsFile = Join-Path $runtimeRoot 'canal-account.json'
if (Test-Path $credentialsFile) {
    $credentials = Get-Content $credentialsFile -Raw | ConvertFrom-Json
} else {
    $credentials = @{user='hmdp_canal_local'; password=[Guid]::NewGuid().ToString('N')}
    $credentials | ConvertTo-Json | Set-Content -Encoding ascii $credentialsFile
}
$appConfig = Get-Content (Join-Path $projectRoot 'src/main/resources/application.yaml') -Raw
if (-not $env:HMDP_DB_PASSWORD) { throw 'Set HMDP_DB_PASSWORD.' }
$env:MYSQL_PWD = $env:HMDP_DB_PASSWORD
try {
    # Canal's TSDB scans table metadata across schemas; the account remains local and read-only.
    $sql = "CREATE USER IF NOT EXISTS '$($credentials.user)'@'127.0.0.1' IDENTIFIED BY '$($credentials.password)'; GRANT REPLICATION SLAVE, REPLICATION CLIENT, SELECT ON *.* TO '$($credentials.user)'@'127.0.0.1';"
    $sql | & mysql --host=127.0.0.1 --user=root --batch
    if ($LASTEXITCODE -ne 0) { throw 'Creating the dedicated Canal replication account failed.' }
} finally { Remove-Item Env:MYSQL_PWD }
function Set-CanalProperty([string]$file, [string]$key, [string]$value) {
    $content = [IO.File]::ReadAllText($file)
    $pattern = '(?m)^' + [regex]::Escape($key) + '\s*=.*$'
    $line = $key + '=' + $value
    if ([regex]::IsMatch($content, $pattern)) {
        $content = [regex]::Replace($content, $pattern, [System.Text.RegularExpressions.MatchEvaluator]{param($match) $line})
    } else { $content += "`n$line`n" }
    [IO.File]::WriteAllText($file, $content, [Text.UTF8Encoding]::new($false))
}
$serverConfig = Join-Path $canalRoot 'conf/canal.properties'
$instanceConfig = Join-Path $canalRoot 'conf/example/instance.properties'
Set-CanalProperty $serverConfig 'canal.ip' '127.0.0.1'
Set-CanalProperty $serverConfig 'canal.admin.port' '18110'
Set-CanalProperty $serverConfig 'canal.metrics.pull.port' '18112'
Set-CanalProperty $serverConfig 'canal.serverMode' 'rocketMQ'
Set-CanalProperty $serverConfig 'canal.mq.flatMessage' 'true'
Set-CanalProperty $serverConfig 'canal.instance.parser.parallelThreadSize' '2'
Set-CanalProperty $serverConfig 'canal.mq.send.thread.size' '2'
Set-CanalProperty $serverConfig 'rocketmq.namesrv.addr' '127.0.0.1:9876'
Set-CanalProperty $serverConfig 'rocketmq.producer.group' 'hmdp-canal-producer'
Set-CanalProperty $serverConfig 'rocketmq.retry.times.when.send.failed' '3'
Set-CanalProperty $instanceConfig 'canal.instance.mysql.slaveId' '11001'
Set-CanalProperty $instanceConfig 'canal.instance.dbUsername' $credentials.user
Set-CanalProperty $instanceConfig 'canal.instance.dbPassword' $credentials.password
Set-CanalProperty $instanceConfig 'canal.instance.master.address' '127.0.0.1:3306'
Set-CanalProperty $instanceConfig 'canal.instance.filter.regex' 'hmdp\\.(tb_shop|tb_shop_cache_version)'
Set-CanalProperty $instanceConfig 'canal.mq.topic' 'hmdp-shop-change'
$arguments = @('-Xms128m','-Xmx512m','-Dfile.encoding=UTF-8','-Djava.net.preferIPv4Stack=true',
    '--add-opens=java.base/java.lang=ALL-UNNAMED', '--add-opens=java.base/java.util=ALL-UNNAMED',
    ('-Dcanal.conf=' + $serverConfig), ('-Dlogback.configurationFile=' + (Join-Path $canalRoot 'conf/logback.xml')),
    '-cp', ((Join-Path $canalRoot 'conf') + ';' + (Join-Path $canalRoot 'lib/*')), 'com.alibaba.otter.canal.deployer.CanalLauncher')
$process = Start-Process -FilePath (Join-Path $JavaHome 'bin/java.exe') -ArgumentList $arguments `
    -WorkingDirectory (Join-Path $canalRoot 'bin') -WindowStyle Hidden -PassThru `
    -RedirectStandardOutput (Join-Path $runtimeRoot 'canal.out.log') -RedirectStandardError (Join-Path $runtimeRoot 'canal.err.log')
$process.Id | Set-Content $pidFile
Write-Output ('Started Canal, PID ' + $process.Id)
