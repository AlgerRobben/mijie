param([int]$RedisDatabase=0,[int]$JavaPort=8081,[int]$HotLogicalTtlSeconds=5,[switch]$Restart)
$ErrorActionPreference='Stop'
$root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$runtime=Join-Path $root '.local/openresty-1.27.1.2-win64'
$exe=Join-Path $runtime 'nginx.exe'
if(!(Test-Path $exe)){throw 'Extract official Windows OpenResty 1.27.1.2 into .local/openresty-1.27.1.2-win64'}
$processes=@(Get-CimInstance Win32_Process -Filter "Name='nginx.exe'" | Where-Object {$_.ExecutablePath-eq $exe})
if($processes.Count-gt 0){
    if(!$Restart){Write-Output 'Project OpenResty is already running';return}
    # 只停止这个解压目录的进程，不误停其他Nginx。完整重启才能继承新环境变量。
    foreach($process in $processes){Stop-Process -Id $process.ProcessId -ErrorAction SilentlyContinue}
}
$archive=Join-Path $root ('.local/archive/openresty-conf-'+(Get-Date -Format 'yyyyMMdd-HHmmss'))
Copy-Item -LiteralPath (Join-Path $runtime 'conf') -Destination $archive -Recurse
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'nginx.conf') -Destination (Join-Path $runtime 'conf/nginx.conf')
New-Item -ItemType Directory -Force (Join-Path $runtime 'conf/lua') | Out-Null
Copy-Item (Join-Path $PSScriptRoot 'lua/*.lua') (Join-Path $runtime 'conf/lua') -Force
$cfg=Get-Content (Join-Path $root 'src/main/resources/application.yaml') -Raw
$redis=$cfg.Substring($cfg.IndexOf('  redis:'),$cfg.IndexOf('  jackson:')-$cfg.IndexOf('  redis:'))
$env:HMDP_REDIS_HOST=[regex]::Match($redis,'(?m)^    host: (.+)$').Groups[1].Value.Trim()
$env:HMDP_REDIS_PORT=[regex]::Match($redis,'(?m)^    port: (.+)$').Groups[1].Value.Trim()
$env:HMDP_REDIS_PASSWORD=[regex]::Match($redis,'(?m)^    password: (.+)$').Groups[1].Value.Trim()
$keyfile=Join-Path $root '.local/cache-sync/internal-cache-key.txt'
if(!(Test-Path $keyfile)){throw 'Start Java first to create the shared internal credential file'}
$env:HMDP_CACHE_INTERNAL_KEY=(Get-Content $keyfile -Raw).Trim()
$env:HMDP_REDIS_DB="$RedisDatabase";$env:HMDP_JAVA_HOST='127.0.0.1';$env:HMDP_JAVA_PORT="$JavaPort"
$env:HMDP_EDGE_LOGICAL_TTL_SECONDS="$HotLogicalTtlSeconds";$env:HMDP_HOT_MAX_SHOPS='500';$env:HMDP_HOT_MAX_ENTRY_BYTES='8192'
try {
    Push-Location $runtime
    try {& $exe -t;if($LASTEXITCODE-ne 0){throw 'OpenResty configuration check failed'}
        $process=Start-Process -FilePath $exe -WorkingDirectory $runtime -WindowStyle Hidden -PassThru
        $process.Id | Set-Content (Join-Path $root '.local/cache-sync/openresty.pid')
        Write-Output ('Started project OpenResty :8080, PID '+$process.Id)
    } finally {Pop-Location}
} finally {Remove-Item Env:HMDP_REDIS_PASSWORD,Env:HMDP_CACHE_INTERNAL_KEY -ErrorAction SilentlyContinue}
