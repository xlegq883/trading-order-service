# D1-D9 端到端测试脚本（PowerShell / Windows）
# 前置：依赖容器已启动（MySQL 3307 / Redis 6379 / Kafka 9092），应用已启动在 8080
# 用法：
#   pwsh -File scripts/e2e-test.ps1                 # 只跑 HTTP 场景（SQL 断言需要 docker）
#   pwsh -File scripts/e2e-test.ps1 -Reset          # 先复位数据再跑
#   pwsh -File scripts/e2e-test.ps1 -BaseUrl http://localhost:8080

param(
    [string]$BaseUrl = 'http://localhost:8080',
    [string]$MysqlContainer = 'trading-mysql',
    [switch]$Reset,
    [int]$WaitSeconds = 40
)

$ErrorActionPreference = 'Stop'
$script:Pass = 0
$script:Fail = 0
$script:Skip = 0
$script:SqlOk = $false

function Write-Result([string]$name, [string]$expected, [string]$actual, [bool]$ok) {
    if ($ok) { $script:Pass++; $tag = 'PASS' } else { $script:Fail++; $tag = 'FAIL' }
    Write-Host ("[{0}] {1}" -f $tag, $name) -ForegroundColor ($(if ($ok) { 'Green' } else { 'Red' }))
    Write-Host ("        期望: {0}" -f $expected)
    Write-Host ("        实际: {0}" -f $actual)
}

function Write-Skip([string]$name, [string]$reason) {
    $script:Skip++
    Write-Host ("[SKIP] {0} —— {1}" -f $name, $reason) -ForegroundColor Yellow
}

function Invoke-Sql([string]$sql) {
    if (-not $script:SqlOk) { return $null }
    try {
        $out = & docker exec $MysqlContainer mysql -uroot -proot123 -N -B trading -e $sql 2>$null
        if ($LASTEXITCODE -ne 0) { return $null }
        return $out
    } catch { return $null }
}

function Get-SqlScalar([string]$sql) {
    $out = Invoke-Sql $sql
    if ($null -eq $out) { return $null }
    return ($out | Select-Object -First 1)
}

function New-Order([string]$key, [string]$user = 'U1', [string]$product = 'P1001', [int]$qty = 1) {
    $body = @{ userId = $user; productId = $product; quantity = $qty } | ConvertTo-Json -Compress
    try {
        $resp = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/orders" -Headers @{ 'x-idempotency-key' = $key } `
            -ContentType 'application/json; charset=utf-8' -Body $body
        return @{ http = 200; code = $resp.code; orderNo = $resp.data.orderNo; status = $resp.data.status; raw = $resp }
    } catch {
        $status = $_.Exception.Response.StatusCode.value__
        $content = $null
        try { $content = (New-Object System.IO.StreamReader($_.Exception.Response.GetResponseStream())).ReadToEnd() } catch { }
        return @{ http = $status; code = $null; orderNo = $null; status = $null; raw = $content }
    }
}

Write-Host "=== D1-D9 端到端测试 ===" -ForegroundColor Cyan
Write-Host ("BaseUrl = {0}" -f $BaseUrl)

# 探测 SQL 可用性
$probe = & docker exec $MysqlContainer mysql -uroot -proot123 -N -B trading -e "SELECT 1" 2>$null
$script:SqlOk = ($LASTEXITCODE -eq 0)
if ($script:SqlOk) { Write-Host "SQL 断言：可用（docker exec $MysqlContainer）" -ForegroundColor Green }
else { Write-Host "SQL 断言：不可用（docker 不可访问），相关用例将标记 SKIP" -ForegroundColor Yellow }

if ($Reset) {
    if ($script:SqlOk) {
        Get-Content (Join-Path $PSScriptRoot 'reset-data.sql') -Raw | & docker exec -i $MysqlContainer mysql -uroot -proot123 -N -B trading
        & docker exec trading-redis redis-cli FLUSHDB | Out-Null
        Write-Host "数据已复位（MySQL + Redis）" -ForegroundColor Green
    } else {
        Write-Host "跳过复位（SQL 不可用）" -ForegroundColor Yellow
    }
}

# ---------- A 基础 ----------
try {
    $health = Invoke-RestMethod -Uri "$BaseUrl/api/health"
    Write-Result 'A1 健康检查' 'HTTP 200 且 code=0' ("HTTP 200, code={0}, status={1}" -f $health.code, $health.data.status) ($health.code -eq 0)
} catch {
    Write-Result 'A1 健康检查' 'HTTP 200 且 code=0' $_.Exception.Message $false
}

# ---------- B 下单与校验 ----------
$keyB1 = "e2e-b1-" + [guid]::NewGuid().ToString('N').Substring(0, 12)
$r1 = New-Order $keyB1 'U-e2e' 'P1001' 1
Write-Result 'B1 正常下单' 'HTTP 200, status=CREATED' ("HTTP {0}, status={1}, orderNo={2}" -f $r1.http, $r1.status, $r1.orderNo) ($r1.http -eq 200 -and $r1.status -eq 'CREATED')

try {
    Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/orders" -ContentType 'application/json; charset=utf-8' -Body '{"userId":"U1","productId":"P1001","quantity":1}' | Out-Null
    Write-Result 'B2 缺幂等请求头' 'HTTP 400' 'HTTP 200（未按预期拒绝）' $false
} catch {
    $code = $_.Exception.Response.StatusCode.value__
    Write-Result 'B2 缺幂等请求头' 'HTTP 400' ("HTTP {0}" -f $code) ($code -eq 400)
}

$keyB3 = "e2e-b3-" + [guid]::NewGuid().ToString('N').Substring(0, 12)
$r3 = New-Order $keyB3 'U-e2e' 'P1001' 0
Write-Result 'B3 quantity=0' 'HTTP 400' ("HTTP {0}" -f $r3.http) ($r3.http -eq 400)

$keyB4 = "e2e-b4-" + [guid]::NewGuid().ToString('N').Substring(0, 12)
$body4 = '{"productId":"P1001","quantity":1}'
try {
    Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/orders" -Headers @{ 'x-idempotency-key' = $keyB4 } -ContentType 'application/json; charset=utf-8' -Body $body4 | Out-Null
    Write-Result 'B4 缺 userId' 'HTTP 400' 'HTTP 200（未按预期拒绝）' $false
} catch {
    $code = $_.Exception.Response.StatusCode.value__
    Write-Result 'B4 缺 userId' 'HTTP 400' ("HTTP {0}" -f $code) ($code -eq 400)
}

# ---------- C 幂等 ----------
$keyC1 = "e2e-c1-" + [guid]::NewGuid().ToString('N').Substring(0, 12)
$c1a = New-Order $keyC1 'U-e2e-c1' 'P1001' 1
$c1b = New-Order $keyC1 'U-e2e-c1' 'P1001' 1
$sameOrderNo = ($c1a.orderNo -eq $c1b.orderNo -and $c1a.orderNo)
Write-Result 'C1 同 key 重复下单' '两次返回同一 orderNo' ("{0} / {1}" -f $c1a.orderNo, $c1b.orderNo) ([bool]$sameOrderNo)

if ($script:SqlOk) {
    $cnt = Get-SqlScalar ("SELECT COUNT(*) FROM t_order WHERE idempotent_key='{0}'" -f $keyC1)
    Write-Result 'C2 幂等键只落 1 单' '库中 1 行' ("库中 {0} 行" -f $cnt) ($cnt -eq '1')
} else { Write-Skip 'C2 幂等键只落 1 单' 'docker/SQL 不可用' }

# ---------- D 库存 ----------
$keyD1 = "e2e-d1-" + [guid]::NewGuid().ToString('N').Substring(0, 12)
$d1 = New-Order $keyD1 'U-e2e-d1' 'P1001' 1000000
Write-Result 'D1 库存不足' 'HTTP 422' ("HTTP {0}" -f $d1.http) ($d1.http -eq 422)

# ---------- E Outbox ----------
if ($script:SqlOk) {
    $pending = Get-SqlScalar ("SELECT COUNT(*) FROM t_outbox WHERE aggregate_id='{0}'" -f $r1.orderNo)
    Write-Result 'E1 下单写入 Outbox' '订单对应 1 条 Outbox 记录' ("{0} 条" -f $pending) ($pending -eq '1')

    $deadline = (Get-Date).AddSeconds($WaitSeconds)
    $status = $null
    while ((Get-Date) -lt $deadline) {
        $status = Get-SqlScalar ("SELECT status FROM t_outbox WHERE aggregate_id='{0}'" -f $r1.orderNo)
        if ($status -eq '1') { break }
        Start-Sleep -Seconds 2
    }
    Write-Result 'E2 Outbox 投递 Kafka' 'status=1(SENT)' ("status={0}" -f $status) ($status -eq '1')
} else {
    Write-Skip 'E1/E2 Outbox 落库与投递' 'docker/SQL 不可用'
}

# ---------- F 回执与对账 ----------
if ($script:SqlOk) {
    # F1 金额一致 -> CONFIRMED（注意：D9 之前订单 amount 恒为 0.00）
    $keyF1 = "e2e-f1-" + [guid]::NewGuid().ToString('N').Substring(0, 12)
    $f1 = New-Order $keyF1 'U-e2e-f1' 'P1001' 1
    $amount = Get-SqlScalar ("SELECT amount FROM t_order WHERE order_no='{0}'" -f $f1.orderNo)
    if ($null -eq $amount) { $amount = '0.00' }

    $receiptBody = @{ orderNo = $f1.orderNo; upstreamNo = 'UP-' + $f1.orderNo; status = 1; amount = [decimal]$amount } | ConvertTo-Json -Compress
    Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/receipts" -ContentType 'application/json; charset=utf-8' -Body $receiptBody | Out-Null
    Start-Sleep -Seconds 12
    $st = Get-SqlScalar ("SELECT status FROM t_order WHERE order_no='{0}'" -f $f1.orderNo)
    Write-Result 'F1 金额一致 -> CONFIRMED' '订单状态 4(CONFIRMED)' ("状态 {0}" -f $st) ($st -eq '4')

    # F2 重复回执幂等
    Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/receipts" -ContentType 'application/json; charset=utf-8' -Body $receiptBody | Out-Null
    $rc = Get-SqlScalar ("SELECT COUNT(*) FROM t_receipt WHERE order_no='{0}'" -f $f1.orderNo)
    Write-Result 'F2 重复回执幂等' 't_receipt 仍 1 行' ("{0} 行" -f $rc) ($rc -eq '1')

    # F3 金额不一致 -> FAILED + AMOUNT_MISMATCH
    $keyF3 = "e2e-f3-" + [guid]::NewGuid().ToString('N').Substring(0, 12)
    $f3 = New-Order $keyF3 'U-e2e-f3' 'P1001' 1
    $bad = @{ orderNo = $f3.orderNo; upstreamNo = 'UP-' + $f3.orderNo; status = 1; amount = 99.99 } | ConvertTo-Json -Compress
    Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/receipts" -ContentType 'application/json; charset=utf-8' -Body $bad | Out-Null
    Start-Sleep -Seconds 12
    $st3 = Get-SqlScalar ("SELECT status FROM t_order WHERE order_no='{0}'" -f $f3.orderNo)
    $diff3 = Get-SqlScalar ("SELECT COUNT(*) FROM t_reconcile_diff WHERE order_no='{0}' AND diff_type='AMOUNT_MISMATCH'" -f $f3.orderNo)
    Write-Result 'F3 金额不一致 -> FAILED' '状态 9 且存在 AMOUNT_MISMATCH' ("状态 {0}, 差异 {1} 条" -f $st3, $diff3) ($st3 -eq '9' -and $diff3 -ge '1')

    # F4 上游失败 -> FAILED + UPSTREAM_FAILED
    $keyF4 = "e2e-f4-" + [guid]::NewGuid().ToString('N').Substring(0, 12)
    $f4 = New-Order $keyF4 'U-e2e-f4' 'P1001' 1
    $failBody = @{ orderNo = $f4.orderNo; upstreamNo = 'UP-' + $f4.orderNo; status = 0; amount = 0 } | ConvertTo-Json -Compress
    Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/receipts" -ContentType 'application/json; charset=utf-8' -Body $failBody | Out-Null
    Start-Sleep -Seconds 12
    $st4 = Get-SqlScalar ("SELECT status FROM t_order WHERE order_no='{0}'" -f $f4.orderNo)
    $diff4 = Get-SqlScalar ("SELECT COUNT(*) FROM t_reconcile_diff WHERE order_no='{0}' AND diff_type='UPSTREAM_FAILED'" -f $f4.orderNo)
    Write-Result 'F4 上游失败 -> FAILED' '状态 9 且存在 UPSTREAM_FAILED' ("状态 {0}, 差异 {1} 条" -f $st4, $diff4) ($st4 -eq '9' -and $diff4 -ge '1')

    # G 一致性不变量
    Write-Host "`n=== 一致性不变量校验 ===" -ForegroundColor Cyan
    $checkOut = Get-Content (Join-Path $PSScriptRoot 'consistency-check.sql') -Raw | & docker exec -i $MysqlContainer mysql -uroot -proot123 -N -B trading 2>$null
    $bad = @()
    foreach ($line in $checkOut) {
        if ($line -match '^\d+_' -or $line -match '^\d+_|check_name') {
            $parts = $line -split "`t"
            if ($parts.Count -ge 2 -and $parts[1] -match '^\d+$' -and [int]$parts[1] -ne 0) { $bad += $line }
        }
    }
    Write-Result 'G1 7 条不变量全部为 0' '无违反项' ("违反 {0} 项" -f $bad.Count) ($bad.Count -eq 0)
    if ($bad.Count -gt 0) { $bad | ForEach-Object { Write-Host ("        " + $_) -ForegroundColor Red } }
} else {
    Write-Skip 'F1-F4 回执与对账' 'docker/SQL 不可用'
    Write-Skip 'G1 一致性不变量' 'docker/SQL 不可用'
}

Write-Host "`n=== 汇总 ===" -ForegroundColor Cyan
Write-Host ("PASS={0}  FAIL={1}  SKIP={2}" -f $script:Pass, $script:Fail, $script:Skip)
if ($script:Fail -gt 0) { exit 1 }
