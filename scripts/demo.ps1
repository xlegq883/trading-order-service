# D13 一键演示脚本（PowerShell / Windows）
# 按序自动执行：健康检查 -> 正常下单 -> 幂等 -> 库存不足 -> Outbox/回执/对账 -> 缓存治理
# 用法：
#   pwsh -File scripts/demo.ps1                 # 依赖与应用已启动
#   pwsh -File scripts/demo.ps1 -StartDeps      # 顺带 docker compose up -d
#   pwsh -File scripts/demo.ps1 -Reset          # 先复位数据
# 说明：SQL/Redis 断言需要 docker；不可用时相关用例标记 SKIP，不影响前置 HTTP 用例。

param(
    [string]$BaseUrl = 'http://localhost:8080',
    [string]$MysqlContainer = 'trading-mysql',
    [string]$RedisContainer = 'trading-redis',
    [switch]$StartDeps,
    [switch]$Reset,
    [int]$WaitSeconds = 40
)

$ErrorActionPreference = 'Stop'
$script:Pass = 0
$script:Fail = 0
$script:Skip = 0
$script:DockerExe = $null
$script:SqlOk = $false

function Get-DockerExe {
    $cmd = Get-Command docker -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    $candidates = @('D:\Docker\resources\bin\docker.exe', 'C:\Program Files\Docker\Docker\resources\bin\docker.exe')
    foreach ($c in $candidates) { if (Test-Path $c) { return $c } }
    return $null
}

function Use-Docker {
    if (-not $script:DockerExe) { return $false }
    try {
        & $script:DockerExe ps 2>$null | Out-Null
        return ($LASTEXITCODE -eq 0)
    } catch { return $false }
}

function Write-Result([string]$name, [string]$expected, [string]$actual, [bool]$ok) {
    if ($ok) { $script:Pass++; $tag = 'PASS' } else { $script:Fail++; $tag = 'FAIL' }
    $color = if ($ok) { 'Green' } else { 'Red' }
    Write-Host ("[{0}] {1}" -f $tag, $name) -ForegroundColor $color
    Write-Host ("        期望: {0}" -f $expected)
    Write-Host ("        实际: {0}" -f $actual)
}

function Write-Info([string]$text) { Write-Host $text -ForegroundColor Yellow }

function Invoke-Sql([string]$sql) {
    if (-not $script:SqlOk) { return $null }
    try {
        $out = & $script:DockerExe exec $MysqlContainer mysql -uroot -proot123 -N -B trading -e $sql 2>$null
        if ($LASTEXITCODE -ne 0) { return $null }
        return $out
    } catch { return $null }
}

function Get-SqlScalar([string]$sql) {
    $out = Invoke-Sql $sql
    if ($null -eq $out) { return $null }
    return ($out | Select-Object -First 1)
}

function Redis-Cli([string]$command) {
    if (-not $script:DockerExe) { return $null }
    $parts = $command -split '\s+'
    return (& $script:DockerExe exec $RedisContainer redis-cli @parts 2>$null | Select-Object -First 1)
}

function New-Order([string]$key, [string]$user, [string]$product, [int]$qty) {
    $body = @{ userId = $user; productId = $product; quantity = $qty } | ConvertTo-Json -Compress
    try {
        $resp = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/orders" -Headers @{ 'x-idempotency-key' = $key } `
            -ContentType 'application/json; charset=utf-8' -Body $body
        return @{ http = 200; code = $resp.code; orderNo = $resp.data.orderNo; status = $resp.data.status }
    } catch {
        $status = $_.Exception.Response.StatusCode.value__
        return @{ http = $status; code = $null; orderNo = $null; status = $null }
    }
}

# ---------------------------------------------------------------- 准备
$script:DockerExe = Get-DockerExe
Write-Host "=== trading-order-service 一键演示 ===" -ForegroundColor Cyan
Write-Host ("BaseUrl   = {0}" -f $BaseUrl)
Write-Host ("DockerExe = {0}" -f $(if ($script:DockerExe) { $script:DockerExe } else { '未找到' }))

if ($StartDeps) {
    if (-not $script:DockerExe) { Write-Info "未找到 docker，跳过 -StartDeps" }
    else {
        $root = Split-Path $PSScriptRoot -Parent
        Write-Info ("启动依赖：{0}" -f (Join-Path $root 'docker-compose.yml'))
        & $script:DockerExe compose -f (Join-Path $root 'docker-compose.yml') up -d 2>$null | Out-Null
        Start-Sleep -Seconds 5
    }
}

$dockerUp = Use-Docker
$script:SqlOk = $dockerUp
if ($script:SqlOk) { Write-Host "SQL 断言：可用" -ForegroundColor Green }
else { Write-Host "SQL 断言：不可用（docker 未就绪），相关用例标记 SKIP" -ForegroundColor Yellow }

if ($Reset -and $script:SqlOk) {
    $resetSql = Join-Path $PSScriptRoot 'reset-data.sql'
    if (Test-Path $resetSql) {
        Get-Content $resetSql -Raw | & $script:DockerExe exec -i $MysqlContainer mysql -uroot -proot123 -N -B trading 2>$null | Out-Null
    }
    & $script:DockerExe exec $RedisContainer redis-cli FLUSHDB 2>$null | Out-Null
    Write-Host "数据已复位（MySQL + Redis）" -ForegroundColor Green
}

# ---------------------------------------------------------------- A 健康
try {
    $health = Invoke-RestMethod -Uri "$BaseUrl/api/health"
    Write-Result 'A1 健康检查' 'HTTP 200 且 code=0' ("code={0}, status={1}" -f $health.code, $health.data.status) ($health.code -eq 0)
} catch {
    Write-Result 'A1 健康检查' 'HTTP 200 且 code=0' $_.Exception.Message $false
    Write-Host "`n应用不可用，后续用例无法执行。请先 mvn spring-boot:run。" -ForegroundColor Red
    Write-Host ("`n=== 汇总 PASS={0} FAIL={1} SKIP={2} ===" -f $script:Pass, $script:Fail, $script:Skip)
    exit 1
}

# ---------------------------------------------------------------- B 下单 + 幂等 + 库存不足
$key = "demo-" + [guid]::NewGuid().ToString('N').Substring(0, 8)
$r1 = New-Order $key 'U-demo' 'P1001' 1
Write-Result 'B1 正常下单' 'HTTP 200, status=CREATED' ("HTTP {0}, status={1}, orderNo={2}" -f $r1.http, $r1.status, $r1.orderNo) ($r1.http -eq 200 -and $r1.status -eq 'CREATED')

$r2 = New-Order $key 'U-demo' 'P1001' 1
$same = ($r1.orderNo -and $r1.orderNo -eq $r2.orderNo)
Write-Result 'B2 幂等：同 key 同单' '两次返回同一 orderNo' ("{0} / {1}" -f $r1.orderNo, $r2.orderNo) ([bool]$same)

if ($script:SqlOk) {
    $cnt = Get-SqlScalar ("SELECT COUNT(*) FROM t_order WHERE idempotent_key='{0}'" -f $key)
    Write-Result 'B3 幂等键仅落 1 单' '库中 1 行' ("库中 {0} 行" -f $cnt) ($cnt -eq '1')
} else { Write-Info "[SKIP] B3 幂等键仅落 1 单（SQL 不可用）"; $script:Skip++ }

$keyD = "demo-" + [guid]::NewGuid().ToString('N').Substring(0, 8)
$rD = New-Order $keyD 'U-demo' 'P1001' 1000000
Write-Result 'B4 库存不足' 'HTTP 422' ("HTTP {0}" -f $rD.http) ($rD.http -eq 422)

# ---------------------------------------------------------------- C Outbox / 回执 / 对账
if ($script:SqlOk) {
    $deadline = (Get-Date).AddSeconds($WaitSeconds)
    $ob = $null
    while ((Get-Date) -lt $deadline) {
        $ob = Get-SqlScalar ("SELECT status FROM t_outbox WHERE aggregate_id='{0}'" -f $r1.orderNo)
        if ($ob -eq '1') { break }
        Start-Sleep -Seconds 2
    }
    Write-Result 'C1 Outbox 投递 Kafka' 'status=1(SENT)' ("status={0}" -f $ob) ($ob -eq '1')

    # 回执与对账（消费者会自动回执；等待订单到 CONFIRMED）
    $deadline = (Get-Date).AddSeconds($WaitSeconds)
    $st = $null
    while ((Get-Date) -lt $deadline) {
        $st = Get-SqlScalar ("SELECT status FROM t_order WHERE order_no='{0}'" -f $r1.orderNo)
        if ($st -eq '4') { break }
        Start-Sleep -Seconds 2
    }
    Write-Result 'C2 消费+回执+对账' '订单最终 4(CONFIRMED)' ("status={0}" -f $st) ($st -eq '4')
} else {
    Write-Info "[SKIP] C1/C2 Outbox/回执/对账（SQL 不可用）"; $script:Skip += 2
}

# ---------------------------------------------------------------- D 缓存治理
try {
    $p1 = Invoke-RestMethod -Uri "$BaseUrl/api/products/P1001"
    Write-Result 'D1 商品查询' 'code=0 且 productId=P1001' ("code={0}, productId={1}, name={2}" -f $p1.code, $p1.data.productId, $p1.data.name) ($p1.code -eq 0 -and $p1.data.productId -eq 'P1001')
} catch {
    Write-Result 'D1 商品查询' 'code=0' $_.Exception.Message $false
}

try {
    Invoke-RestMethod -Uri "$BaseUrl/api/products/NOPE" | Out-Null
    Write-Result 'D2 穿透防护' 'HTTP 404' 'HTTP 200（未按预期拒绝）' $false
} catch {
    $code = $_.Exception.Response.StatusCode.value__
    Write-Result 'D2 穿透防护' 'HTTP 404' ("HTTP {0}" -f $code) ($code -eq 404)
}

if ($script:SqlOk -and $script:DockerExe) {
    $nullMark = Redis-Cli "GET product:NOPE"
    Write-Result 'D3 空值缓存' '__NULL__' ("{0}" -f $nullMark) ($nullMark -eq '__NULL__')
} else { Write-Info "[SKIP] D3 空值缓存（docker 不可用）"; $script:Skip++ }

# ---------------------------------------------------------------- 汇总
Write-Host "`n=== 汇总 ===" -ForegroundColor Cyan
Write-Host ("PASS={0}  FAIL={1}  SKIP={2}" -f $script:Pass, $script:Fail, $script:Skip)
if ($script:Fail -gt 0) { exit 1 }
