param()
$ErrorActionPreference='Stop'
$root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$backup=Join-Path $root ('.local/verification/fixed-cache/schema-'+(Get-Date -Format 'yyyyMMdd-HHmmss')+'.sql')
New-Item -ItemType Directory -Force (Split-Path $backup) | Out-Null
$cfg=Get-Content (Join-Path $root 'src/main/resources/application.yaml') -Raw
$env:MYSQL_PWD=[regex]::Match($cfg,'(?m)^    password: (.+)$').Groups[1].Value.Trim()
try {
    # 先备份结构/现有触发器。迁移只改本项目命名的4个触发器，不清空业务数据。
    & mysqldump --host=127.0.0.1 --user=root --no-data --triggers --set-gtid-purged=OFF --single-transaction "--result-file=$backup" hmdp
    if($LASTEXITCODE-ne 0){throw 'Schema backup failed'}
    $policyBackup=$backup.Replace('schema-','policy-data-')
    & mysqldump --host=127.0.0.1 --user=root --no-create-info --skip-triggers --set-gtid-purged=OFF --single-transaction "--result-file=$policyBackup" hmdp tb_shop_cache_policy
    if($LASTEXITCODE-ne 0){throw 'Old policy data backup failed (V4 requires the V3 database)'}
    $migration=(Join-Path $root 'src/main/resources/db/migration/V4__fixed_shop_cache.sql').Replace('\','/')
    Get-Content -LiteralPath $migration -Raw | & mysql --host=127.0.0.1 --user=root --default-character-set=utf8mb4 hmdp
    if($LASTEXITCODE-ne 0){throw 'Fixed shop cache migration failed'}
    Write-Output ('Fixed shop cache migration completed; schema backup: '+$backup)
} finally {Remove-Item Env:MYSQL_PWD}
