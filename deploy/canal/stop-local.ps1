param([string[]]$Services = @('app-8082', 'app-8081', 'canal', 'broker', 'namesrv'))
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$runtimeRoot = Join-Path $projectRoot '.local/cache-sync'
foreach ($service in $Services) {
    if ($service -notin @('app-8082','app-8081','canal','broker','namesrv')) { throw 'Unknown local service' }
    $pidFile = Join-Path $runtimeRoot ($service + '.pid')
    if (!(Test-Path -LiteralPath $pidFile)) { continue }
    $servicePid = [int](Get-Content -LiteralPath $pidFile)
    $process = Get-CimInstance Win32_Process -Filter "ProcessId=$servicePid"
    if ($process) {
        $expected = switch ($service) {
            'broker' { 'org.apache.rocketmq.broker.BrokerStartup' }
            'namesrv' { 'org.apache.rocketmq.namesrv.NamesrvStartup' }
            'canal' { (Join-Path $projectRoot '.local/canal-1.1.8') }
            default { (Join-Path $projectRoot 'target/hm-dianping-0.0.1-SNAPSHOT.jar') }
        }
        if (!$process.CommandLine -or !$process.CommandLine.Contains($expected)) { throw "PID $servicePid no longer belongs to $service" }
        if($service.StartsWith('app-') -and $process.CommandLine -notlike ('*--server.port='+$service.Substring(4)+'*')){throw "PID $servicePid app port mismatch"}
        & taskkill.exe /PID $servicePid /F | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "Cannot stop $service" }
        Write-Output "Stopped $service, PID $servicePid"
    }
    Remove-Item -LiteralPath $pidFile
}
