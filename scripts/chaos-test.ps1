# 故障注入测试（交互式）：Redis / Kafka / MySQL 宕机与恢复 + 进程崩溃后的补偿投递
#
# 关于“应用进程”：
#   * 指正在运行 trading-order-service、监听 8080 端口的 java 进程。
#   * 若用 `java -jar target/trading-order-service-0.0.1-SNAPSHOT.jar` 启动：杀的就是这个 java 进程。
#   * 若用 `mvn spring-boot:run` 启动：spring-boot-maven-plugin 会 fork 一个子 java 进程跑应用，
#     要杀的是这个子进程（不是 mvn.cmd / PowerShell 外壳），否则 8080 仍会被占用。
#   * 本脚本按端口 8080 定位进程（Get-NetTCPConnection），不靠进程名模糊匹配，
#     避免误杀 VS Code 的 Java 语言服务等其它 java 进程。
#
# 用法：
#   pwsh -File scripts/chaos-test.ps1                 # 全程手动确认（默认）
#   pwsh -File scripts/chaos-test.ps1 -AutoKill       # 崩溃场景由脚本自动杀应用进程

param(
    [string]$BaseUrl = 'http://localhost:8080',
    [string]$MysqlContainer = 'trading-mysql',
    [int]$WaitSeconds = 60,
    [switch]$AutoKill
)

$ErrorActionPreference = 'Stop'

function Pause-For([string]$instruction) {
    Write-Host "`n>>> 请手动执行：$instruction" -ForegroundColor Yellow
    Read-Host "执行完成后按回车继续" | Out-Null
}

function Invoke-Sql([string]$sql) {
    return (& docker exec $MysqlContainer mysql -uroot -proot123 -N -B trading -e $sql 2>$null | Select-Object -First 1)
}

function New-Order([string]$key, [string]$product = 'P1001', [int]$qty = 1) {
    $body = @{ userId = 'U-chaos'; productId = $product; quantity = $qty } | ConvertTo-Json -Compress
    try {
        $resp = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/orders" -Headers @{ 'x-idempotency-key' = $key } `
            -ContentType 'application/json; charset=utf-8' -Body $body
        return $resp.data.orderNo
    } catch { return $null }
}

function Find-AppPid {
    <#
      按端口定位应用进程：
      返回监听 8080 的进程 PID；没有则返回 $null（应用未启动）。
    #>
    $conn = Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if (-not $conn) { return $null }
    return $conn.OwningProcess
}

function Stop-AppProcess {
    <#
      杀掉应用进程（-Force 模拟崩溃，不给优雅关闭机会），并确认 8080 已释放。
    #>
    $appPid = Find-AppPid
    if (-not $appPid) {
        Write-Host "未发现监听 8080 的进程：应用可能已经停止（无需再杀）。" -ForegroundColor Yellow
        return
    }
    $proc = Get-Process -Id $appPid -ErrorAction SilentlyContinue
    if ($proc) {
        Write-Host ("应用进程：PID={0} Name={1} Path={2} Start={3}" -f $appPid, $proc.ProcessName, $proc.Path, $proc.StartTime) -ForegroundColor Cyan
    }
    Stop-Process -Id $appPid -Force
    for ($i = 0; $i -lt 20; $i++) {
        Start-Sleep -Milliseconds 500
        if (-not (Find-AppPid)) {
            Write-Host "已确认 8080 端口释放，应用进程已被杀掉。" -ForegroundColor Green
            return
        }
    }
    Write-Host "警告：杀进程后 8080 仍被占用，请手动检查是否还有其它 java 进程。" -ForegroundColor Red
}

Write-Host "=== 故障注入测试 ===" -ForegroundColor Cyan

# 0) 基线
$order0 = New-Order ("chaos-base-" + [guid]::NewGuid().ToString('N').Substring(0,8))
Write-Host ("基线：下单返回 orderNo = {0}" -f $order0)

# 1) Redis 宕机：下单仍成功，幂等靠 DB 唯一索引兜底
Pause-For "docker stop trading-redis"
$key = "chaos-redis-" + [guid]::NewGuid().ToString('N').Substring(0,8)
$o1 = New-Order $key
$o2 = New-Order $key
$cnt = Invoke-Sql ("SELECT COUNT(*) FROM t_order WHERE idempotent_key='{0}'" -f $key)
Write-Host ("[Redis 宕机] 第一次={0} 第二次={1} 库中行数={2}（期望：同单号且 1 行）" -f $o1, $o2, $cnt)
Pause-For "docker start trading-redis"

# 2) Kafka 宕机：下单成功，outbox 保持 PENDING 且重试递增；恢复后自动投递
Pause-For "docker stop trading-kafka"
$keyK = "chaos-kafka-" + [guid]::NewGuid().ToString('N').Substring(0,8)
$oK = New-Order $keyK
Write-Host ("[Kafka 宕机] 下单 orderNo={0}（期望：下单仍成功）" -f $oK)
Start-Sleep -Seconds 20
$st = Invoke-Sql ("SELECT CONCAT(status,'/',retry_count,'/',next_retry_at) FROM t_outbox WHERE aggregate_id='{0}'" -f $oK)
Write-Host ("[Kafka 宕机] outbox status/retry/next = {0}（期望：status=0 且 retry_count 增长）" -f $st)
Pause-For "docker start trading-kafka"
$deadline = (Get-Date).AddSeconds($WaitSeconds)
do {
    Start-Sleep -Seconds 5
    $st2 = Invoke-Sql ("SELECT status FROM t_outbox WHERE aggregate_id='{0}'" -f $oK)
} while ($st2 -ne '1' -and (Get-Date) -lt $deadline)
Write-Host ("[Kafka 恢复] outbox status = {0}（期望：1 = SENT）" -f $st2)

# 3) MySQL 宕机：下单失败，但健康检查仍 200
Pause-For "docker stop trading-mysql"
$oM = New-Order ("chaos-mysql-" + [guid]::NewGuid().ToString('N').Substring(0,8))
$health = try { (Invoke-RestMethod -Uri "$BaseUrl/api/health").code } catch { 'ERR' }
Write-Host ("[MySQL 宕机] 下单返回={0}（期望：失败/异常），/api/health code={1}（期望：0，仍 200）" -f $oM, $health)
Pause-For "docker start trading-mysql"

# 4) 进程崩溃：停 Kafka -> 下单（消息留在 outbox）-> 杀应用 -> 重启 + 启 Kafka -> 验证补偿投递
Write-Host "`n=== 场景 4：进程崩溃后的 Outbox 补偿投递 ===" -ForegroundColor Cyan
Pause-For "docker stop trading-kafka（先停 Kafka，让消息只能留在 outbox）"

$oC = New-Order ("chaos-crash-" + [guid]::NewGuid().ToString('N').Substring(0,8))
$before = Invoke-Sql ("SELECT CONCAT(status,'/',retry_count) FROM t_outbox WHERE aggregate_id='{0}'" -f $oC)
Write-Host ("[崩溃前] 下单 orderNo={0}，outbox status/retry = {1}（期望：0/0 或 0/n，即尚未投递成功）" -f $oC, $before)

if ($AutoKill) {
    Write-Host "AutoKill 已开启：脚本将自动杀掉应用进程。" -ForegroundColor Yellow
    Stop-AppProcess
} else {
    Write-Host "`n手动杀进程可用以下命令（复制到另一个终端执行）：" -ForegroundColor Yellow
    Write-Host '  $appPid = (Get-NetTCPConnection -LocalPort 8080 -State Listen).OwningProcess'
    Write-Host '  Get-Process -Id $appPid | Select-Object Id, ProcessName, Path, StartTime'
    Write-Host '  Stop-Process -Id $appPid -Force'
    Write-Host '  # 验证端口已释放：'
    Write-Host '  Test-NetConnection localhost -Port 8080 -InformationLevel Quiet   # 期望 False'
    Pause-For "杀掉应用进程（用上面 3 行命令，或直接调用 Stop-AppProcess）"
}

Pause-For "重启应用（mvn spring-boot:run），然后执行 docker start trading-kafka"
$deadline = (Get-Date).AddSeconds($WaitSeconds)
do {
    Start-Sleep -Seconds 5
    $st3 = Invoke-Sql ("SELECT status FROM t_outbox WHERE aggregate_id='{0}'" -f $oC)
} while ($st3 -ne '1' -and (Get-Date) -lt $deadline)
Write-Host ("[恢复后] outbox status = {0}（期望：1 = SENT，说明崩溃后补偿投递成功）" -f $st3)

Write-Host "`n=== 故障注入结束：请把上面每步的实际输出抄进 docs/测试报告模板.md ===" -ForegroundColor Cyan
