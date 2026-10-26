param([string]$JavaHome='D:\JDK21\jdk-21.0.12.1+1')
$ErrorActionPreference='Stop'
$projectRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
Push-Location $projectRoot
try {
    & (Join-Path $JavaHome 'bin/java.exe') '-Dlogback.configurationFile=deploy/canal/e2e-logback.xml' `
        -cp '.local/verification/BOOT-INF/lib/*' (Join-Path $PSScriptRoot 'ConfigureOrderRedis.java')
    if($LASTEXITCODE -ne 0){throw 'Redis durability configuration failed'}
} finally {Pop-Location}
