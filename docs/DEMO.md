# 10 分钟演示讲稿（DEMO）

> 面向面试/答辩：**照着念即可**。每步给出「命令 + 一句话讲稿 + 预期输出」。
> 一键版：`pwsh -File scripts/demo.ps1`（自动按序执行，现场只需运行它并对照本文讲解）。
> 依赖：`docker compose up -d` 起 MySQL(3307)/Redis(6379)/Kafka(9092)；应用启动在 8080。

---

## 0. 开场（30s）· 讲定位

> “这是一个个人作品集项目 trading-order-service，用 Java 17 + Spring Boot 3 复刻交易下单链路，
> 覆盖高并发下单、幂等、防超卖、Outbox 最终一致性、缓存治理。它是可运行、可压测、可演示的，不是 PPT。”

---

## 1. 起依赖 + 健康检查（30s）

```powershell
docker compose up -d
docker compose ps
curl http://localhost:8080/api/health
```

**讲**：“MySQL/Redis/Kafka 一键起；`/api/health` 不依赖任何中间件，依赖未起也能 200（数据源 `initialization-fail-timeout=-1`、Redis/Kafka 懒连接）。”

**预期**：`{"code":0,"message":"ok","data":{"status":"UP",...}}`

---

## 2. 正常下单 + 状态机（1min）

```powershell
curl -X POST http://localhost:8080/api/orders `
  -H "Content-Type: application/json" -H "x-idempotency-key: demo-1" `
  -d '{"userId":"U1","productId":"P1001","quantity":1}'
```

**讲**：“校验 → Redis SETNX 幂等抢占 → Redis Lua 原子预扣 → 一个本地事务里落 `t_order` + 扣库存 + 写 `t_outbox`，状态 `INIT→STOCK_LOCKED→CREATED`。”

**预期**：`{"code":0,...,"data":{"orderNo":"...","status":"CREATED"}}`

---

## 3. 幂等：同 key 两次返回同一单（1min）

```powershell
# 再发一次完全相同的请求（同 x-idempotency-key: demo-1）
```

**讲**：“Redis 只是性能优化，`uk_idempotent_key` 唯一索引才是正确性保证；即使 Redis 挂了，第二次也会命中唯一索引返回首单。”

**预期**：两次 `orderNo` 相同；DB `t_order` 中该 key 仅 1 行。

---

## 4. 库存不足 → 422（1min）

```powershell
curl -X POST http://localhost:8080/api/orders `
  -H "Content-Type: application/json" -H "x-idempotency-key: demo-2" `
  -d '{"userId":"U1","productId":"P1001","quantity":1000000}'
```

**讲**：“Redis Lua 预扣是并发闸门，DB `UPDATE ... WHERE available>=?` 是持久真相；不足返回 422，且库存不会被扣成负数。”

**预期**：`HTTP 422 {"code":422,"message":"库存不足: productId=P1001"}`

---

## 5. Outbox → Kafka → 消费 → 回执 → 对账（2min）

```powershell
# 查看 Outbox 是否投递成功
docker exec trading-mysql mysql -uroot -proot123 -N -e "USE trading; SELECT aggregate_id,status FROM t_outbox ORDER BY id DESC LIMIT 3;"
# 查看订单与回执状态
docker exec trading-mysql mysql -uroot -proot123 -N -e "USE trading; SELECT order_no,status FROM t_order ORDER BY id DESC LIMIT 3;"
docker exec trading-mysql mysql -uroot -proot123 -N -e "USE trading; SELECT order_no,upstream_no,status FROM t_receipt ORDER BY id DESC LIMIT 3;"
```

**讲**：“下单同事务写 Outbox；后台任务扫表投 Kafka（指数退避重试）；消费者模拟上游，按 `orderNo` 幂等落 `t_receipt`；对账任务比对后把订单推进到 `CONFIRMED`——这就是本地事务 + Outbox 的最终一致。”

**预期**：outbox `status=1`；`t_receipt` 1 条；订单最终 `status=4(CONFIRMED)`。

---

## 6. 对账差异：金额不一致 → FAILED + 差异（1min）

```powershell
# 用错误金额调回执接口（orderNo 用上一步某个真实单号）
$body = '{"orderNo":"<orderNo>","upstreamNo":"UP-X","status":1,"amount":99.99}'
curl -X POST http://localhost:8080/api/receipts -H "Content-Type: application/json" -d $body
# 等待对账任务（约 10s）后查询差异
docker exec trading-mysql mysql -uroot -proot123 -N -e "USE trading; SELECT order_no,diff_type FROM t_reconcile_diff ORDER BY id DESC LIMIT 3;"
```

**讲**：“对账以本地 DB 为唯一事实来源；金额不一致会被判定 `AMOUNT_MISMATCH`、订单置 `FAILED`，并回补库存，差异落 `t_reconcile_diff`。”

**预期**：订单 `status=9(FAILED)`，差异含 `AMOUNT_MISMATCH`。

---

## 7. 缓存治理：命中 / 穿透 / 失效（1.5min）

```powershell
curl http://localhost:8080/api/products/P1001          # miss -> 回源 DB -> 写缓存
curl http://localhost:8080/api/products/P1001          # hit
curl http://localhost:8080/api/products/NOPE           # 404，写空值缓存防穿透
docker exec trading-redis redis-cli TTL product:P1001  # base ± jitter 随机
docker exec trading-redis redis-cli GET product:NOPE   # __NULL__
curl -X PUT http://localhost:8080/api/products/P1001 -H "Content-Type: application/json" -d '{"name":"商品A改","price":88.00}'
docker exec trading-redis redis-cli EXISTS product:P1001   # 0 = 缓存已删
```

**讲**：“Cache-Aside + 随机 TTL 防雪崩 + 空值缓存防穿透 + 先更库再删缓存；Redis 不可用自动降级直读 DB。”

---

## 8. 故障注入（1.5min）

```powershell
pwsh -File scripts/chaos-test.ps1        # 交互式，按提示 stop/start 容器
# 或非交互回归：
pwsh -File scripts/e2e-test.ps1 -Reset
```

**讲**：“Redis 宕机 → 靠 DB 唯一索引仍只落 1 单；Kafka 宕机 → 下单照常、消息留在 Outbox、恢复后自动补投；MySQL 宕机 → 下单失败但健康检查仍 200。”

---

## 9. 压测数据（30s）

- 打开 `README.md` §11「压测数据」：100 并发 × 50 = 5000 请求，**0 错误**、P99 809ms；不超卖场景 100 库存 vs 300 请求 = 100 成功 + 200×(422)。
- 讲：“单机压测、客户端与服务端共享 CPU；`QPS ≈ 并发/平均延迟`。压测还暴露并修复了一个订单号碰撞 bug——详见 `docs/D12压测复盘.md`。”

---

## 10. 收尾（30s）· 讲取舍与边界

> “明确不做：分库分表、ES、微服务拆分；这些留在口述。项目的价值在**每个取舍都能对应到代码**。”

---

## 一键演示

```powershell
pwsh -File scripts/demo.ps1                 # 依赖与应用已启动
pwsh -File scripts/demo.ps1 -StartDeps      # 顺带 docker compose up -d
pwsh -File scripts/demo.ps1 -Reset          # 先复位数据
```
