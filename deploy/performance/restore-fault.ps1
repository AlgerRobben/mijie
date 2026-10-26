param([Parameter(Mandatory)][string]$RunDirectory,[ValidateSet('redis','mysql')][string]$Target='redis',[int]$DelaySeconds=8)
$ErrorActionPreference='Stop'
Start-Sleep -Seconds $DelaySeconds
Invoke-RestMethod -Uri "http://127.0.0.1:19080/fault?target=$Target&healthy=true" -TimeoutSec 10 | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $RunDirectory "fault-$Target-auto-restored.json") -Encoding utf8
