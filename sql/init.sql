-- trading-order-service 数据库初始化脚本（D1–D2）
-- 字符集统一 utf8mb4；金额使用 DECIMAL，禁止 float。
-- 本脚本会被 docker-compose 挂载到 MySQL 容器 /docker-entrypoint-initdb.d/ 首次启动执行。

CREATE DATABASE IF NOT EXISTS trading
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_general_ci;

USE trading;

-- ---------------------------------------------------------------------------
-- 订单表
-- uk_order_no：业务单号唯一，保证订单不重复。
-- uk_idempotent_key：幂等键唯一，是幂等正确性的最终兜底（Redis 只是性能优化）。
-- idx_user_created：支撑「按用户查订单并按时间排序」的列表页。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_order
(
    id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    order_no       VARCHAR(32)     NOT NULL COMMENT '业务单号',
    user_id        VARCHAR(32)     NOT NULL COMMENT '用户 ID',
    product_id     VARCHAR(32)     NOT NULL COMMENT '商品 ID',
    quantity       INT             NOT NULL COMMENT '购买数量',
    amount         DECIMAL(18, 2)  NOT NULL COMMENT '金额（元）',
    status         TINYINT         NOT NULL DEFAULT 0 COMMENT '状态：0 INIT / 1 STOCK_LOCKED / 2 CREATED / 3 REPORTED / 4 CONFIRMED / 9 FAILED',
    idempotent_key VARCHAR(64)     NOT NULL COMMENT '幂等键',
    created_at     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_no (order_no),
    UNIQUE KEY uk_idempotent_key (idempotent_key),
    KEY idx_user_created (user_id, created_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='订单表';

-- ---------------------------------------------------------------------------
-- 库存表
-- version 乐观锁：UPDATE ... WHERE version = ? AND available >= ? 防超卖兜底。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_stock
(
    product_id VARCHAR(32) NOT NULL COMMENT '商品 ID',
    total      INT         NOT NULL DEFAULT 0 COMMENT '总库存',
    available  INT         NOT NULL DEFAULT 0 COMMENT '可用库存',
    version    INT         NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    PRIMARY KEY (product_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='库存表';

-- ---------------------------------------------------------------------------
-- Outbox 消息表
-- idx_status_next：后台任务按 status + next_retry_at 扫描待投递消息。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_outbox
(
    id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    aggregate_type VARCHAR(32)     NOT NULL COMMENT '聚合类型，如 ORDER',
    aggregate_id   VARCHAR(32)     NOT NULL COMMENT '聚合根 ID',
    payload        JSON            NULL COMMENT '消息体',
    status         TINYINT         NOT NULL DEFAULT 0 COMMENT '0 待发送 / 1 已发送 / 2 失败',
    retry_count    INT             NOT NULL DEFAULT 0 COMMENT '已重试次数',
    next_retry_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '下次重试时间',
    created_at     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_status_next (status, next_retry_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='Outbox 消息表';

-- ---------------------------------------------------------------------------
-- 上游回执表
-- uk_receipt_order_no：同一订单只接受一条回执，消费/回执幂等。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_receipt
(
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    order_no    VARCHAR(32)     NOT NULL COMMENT '订单号',
    upstream_no VARCHAR(64)     NOT NULL COMMENT '上游流水号',
    status      TINYINT         NOT NULL DEFAULT 0 COMMENT '上游处理结果',
    amount      DECIMAL(18, 2)  NULL COMMENT '上游金额（对账用）',
    payload     JSON            NULL COMMENT '原始回执',
    created_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_receipt_order_no (order_no)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='上游回执表';

-- ---------------------------------------------------------------------------
-- 对账差异表
-- idx_reconcile_order_no：按订单查询差异。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_reconcile_diff
(
    id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    order_no   VARCHAR(32)     NOT NULL COMMENT '订单号',
    diff_type  VARCHAR(32)     NOT NULL COMMENT 'AMOUNT_MISMATCH / UPSTREAM_FAILED / RECEIPT_TIMEOUT / LATE_RECEIPT',
    detail     VARCHAR(512)    NULL COMMENT '差异详情',
    created_at DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    KEY idx_reconcile_order_no (order_no)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='对账差异表';

-- ---------------------------------------------------------------------------
-- 库存种子数据（2 条）
-- ---------------------------------------------------------------------------
INSERT INTO t_stock (product_id, total, available, version)
VALUES ('P1001', 100, 100, 0),
       ('P1002', 50, 50, 0)
ON DUPLICATE KEY UPDATE product_id = product_id;
