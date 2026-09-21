# trading-order-service

> 个人作品集项目（**非生产系统**，如实标注）。目标：能跑、能压测、能演示、能讲清，
> 把「我说做过」变成「你看代码」。当前进度：**D1–D2 完成**。

## 1. 定位

复刻简历主题（交易/订单、幂等、库存防超卖、Outbox 最终一致性、缓存治理）的 Java 后端项目，
覆盖高并发下单的核心考点，作为面试可演示、可追问的作品。

- 设计参考：《项目设计文档.md》（原稿偏 Go/gRPC，本项目用 **Java + REST** 适配）
- 进度计划：《MVP两周冲刺计划.md》

## 2. 技术栈

| 层 | 选型 |
| --- | --- |
| 语言 / 框架 | Java 17、Spring Boot 3.2.5 |
| Web | Spring MVC（REST）、Jakarta Validation |
| 持久化 | MyBatis 3（mybatis-spring-boot-starter 3.0.3）、MySQL 8 |
| 缓存 / 库存 | Redis 7（spring-data-redis） |
| 消息 | Kafka 3.7（KRaft 单节点，spring-kafka） |
| 测试 | JUnit 5、AssertJ、H2（内存库） |
| 部署 | Docker Compose |

## 3. 架构（DDD 分层）

```
api (REST Controller / DTO)
  └─> application (用例编排 / 事务边界 / 幂等前置)
        └─> domain (实体 / 领域规则 / 枚举)
              └─> infrastructure (MyBatis / MySQL / Redis / Kafka / Outbox)
common (统一响应 / 错误码 / 工具)
```

包结构：`com.fuzuyang.trading.{api, application, domain, infrastructure, common}`

## 4. 数据库设计

| 表 | 关键字段 | 关键索引 |
| --- | --- | --- |
| `t_order` | order_no、status、idempotent_key、amount | `uk_order_no`、`uk_idempotent_key`、`idx_user_created(user_id, created_at)` |
| `t_stock` | available、version | 主键 `product_id` |
| `t_outbox` | status、retry_count、next_retry_at | `idx_status_next(status, next_retry_at)` |
| `t_receipt` | order_no、upstream_no | `uk_receipt_order_no(order_no)` |

建表与种子数据见 [`sql/init.sql`](sql/init.sql)。

## 5. 启动步骤

前置：JDK 17、Maven 3.9+、Docker Desktop。

```powershell
# 1) 启动依赖（MySQL 映射到宿主机 3307，因本机 3306 已被占用）
docker compose up -d
docker compose ps

# 2) 编译与测试
mvn -Dfile.encoding=UTF-8 clean test
mvn -Dfile.encoding=UTF-8 -DskipTests package

# 3) 启动应用
mvn -Dfile.encoding=UTF-8 spring-boot:run
# 或：java -Dfile.encoding=UTF-8 -jar target/trading-order-service-0.0.1-SNAPSHOT.jar

# 4) 健康检查
curl http://localhost:8080/api/health
```

预期返回：

```json
{"code":0,"message":"ok","data":{"app":"trading-order-service","status":"UP","profiles":["local"],"timestamp":"..."}}
```

> 说明：MySQL / Redis / Kafka 未启动时应用**仍可正常启动**，`/api/health` 仍返回 200
> （数据源设置了 `initialization-fail-timeout: -1`，Redis/Kafka 为懒连接）。

### 下单接口（D3）

```bash
curl -X POST http://localhost:8080/api/orders \
  -H "Content-Type: application/json" \
  -d '{"userId":"U1","productId":"P1001","quantity":2,"idempotentKey":"idem-001"}'
```

```json
{"code":0,"message":"ok","data":{"orderNo":"20260921190500xxxxxx","status":"CREATED"}}
```

参数非法（如缺 `userId`）返回 HTTP 400：

```json
{"code":400,"message":"userId: userId 不能为空","data":null}
```

> D3 故意**未做幂等**：相同参数、不同 `idempotentKey` 的重复调用会产生多单，作为 D4 幂等实现的正反对照。

### 端口约定

| 服务 | 宿主机端口 | 说明 |
| --- | --- | --- |
| 应用 | 8080 | |
| MySQL | **3307** | 本机 3306 被服务 `MySQL267` 占用，故容器映射 3307 |
| Redis | 6379 | |
| Kafka | 9092 | |

## 6. 目录结构

```
trading-order-service/
  pom.xml
  docker-compose.yml
  sql/init.sql
  src/main/java/com/fuzuyang/trading/
    TradingOrderServiceApplication.java
    api/controller/{HealthController,OrderController}.java
    api/dto/{CreateOrderRequest,CreateOrderResponse}.java
    application/service/OrderApplicationService.java   # 下单用例编排（@Transactional）
    domain/enums/                # OrderStatus / OutboxStatus
    infrastructure/persistence/entity/   # 4 个 DO
    infrastructure/persistence/mapper/   # 4 个 Mapper 接口
    common/ApiResponse.java  common/ErrorCode.java  common/exception/GlobalExceptionHandler.java
  src/main/resources/
    application.yml  application-local.yml  mapper/*.xml
  src/test/...                   # 上下文加载 + 单元测试 + 接口集成测试
```

## 7. 进度清单

### D1–D3 已完成
- [x] Maven 工程骨架（Spring Boot 3.2.5 + Java 17，UTF-8）
- [x] 五层目录结构（api/application/domain/infrastructure/common）
- [x] `application.yml` / `application-local.yml`（MySQL 3307、Redis 6379、Kafka 9092）
- [x] `docker-compose.yml`：MySQL 8 / Redis 7 / Kafka 3.7（KRaft），含 healthcheck 与数据卷
- [x] `sql/init.sql`：4 张表 + 索引 + 2 条库存种子
- [x] 4 个 DO + 4 个 MyBatis Mapper（基础方法）
- [x] REST 健康检查 `GET /api/health`
- [x] `.gitignore`、README
- [x] **D3** 下单主链路 `POST /api/orders`：Jakarta 校验 → 落单 → 状态机 `INIT → CREATED`
- [x] 统一错误结构 `GlobalExceptionHandler`（校验失败 HTTP 400 + `ApiResponse`）
- [x] 测试：服务层单测 + H2 全链路集成测试（正常/非法/重复对照）

### D3+ 待做
- [ ] D4 幂等（Redis SETNX + DB 唯一索引兜底）
- [ ] D5 库存（Redis Lua 原子预扣 + DB 乐观锁 + 超时回补）
- [ ] D6 Outbox 落库 + 定时投递 Kafka + 指数退避重试
- [ ] D7–D9 消费者、回执比对、超时扫描
- [ ] D10 缓存治理；D11 异常路径测试
- [ ] D12 压测（QPS/P99）并写入 README
- [ ] D13 架构图与设计取舍；D14 上传 GitHub

## 8. 明确不做

分库分表、Elasticsearch、微服务拆分、Spring Cloud 全家桶（面试口述，不写代码）。
