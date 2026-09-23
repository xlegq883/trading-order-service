-- 测试用 H2 建表脚本（仅 D3 需要的 t_order）。
-- 与 sql/init.sql 保持字段/索引一致，去掉 MySQL 专有语法（ENGINE、ON UPDATE、JSON）。
DROP TABLE IF EXISTS t_order;
CREATE TABLE t_order
(
    id             BIGINT         NOT NULL AUTO_INCREMENT,
    order_no       VARCHAR(32)    NOT NULL,
    user_id        VARCHAR(32)    NOT NULL,
    product_id     VARCHAR(32)    NOT NULL,
    quantity       INT            NOT NULL,
    amount         DECIMAL(18, 2) NOT NULL,
    status         TINYINT        NOT NULL DEFAULT 0,
    idempotent_key VARCHAR(64)    NOT NULL,
    created_at     DATETIME,
    updated_at     DATETIME,
    PRIMARY KEY (id),
    CONSTRAINT uk_order_no UNIQUE (order_no),
    CONSTRAINT uk_idempotent_key UNIQUE (idempotent_key)
);

DROP TABLE IF EXISTS t_stock;
CREATE TABLE t_stock
(
    product_id VARCHAR(32) NOT NULL,
    total      INT         NOT NULL DEFAULT 0,
    available  INT         NOT NULL DEFAULT 0,
    version    INT         NOT NULL DEFAULT 0,
    PRIMARY KEY (product_id)
);

INSERT INTO t_stock (product_id, total, available, version)
VALUES ('P1001', 100, 100, 0),
       ('P1002', 50, 50, 0);

DROP TABLE IF EXISTS t_outbox;
CREATE TABLE t_outbox
(
    id             BIGINT      NOT NULL AUTO_INCREMENT,
    aggregate_type VARCHAR(32) NOT NULL,
    aggregate_id   VARCHAR(32) NOT NULL,
    payload        CLOB,
    status         TINYINT     NOT NULL DEFAULT 0,
    retry_count    INT         NOT NULL DEFAULT 0,
    next_retry_at  DATETIME,
    created_at     DATETIME,
    updated_at     DATETIME,
    PRIMARY KEY (id)
);

DROP TABLE IF EXISTS t_receipt;
CREATE TABLE t_receipt
(
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    order_no    VARCHAR(32) NOT NULL,
    upstream_no VARCHAR(64) NOT NULL,
    status      TINYINT     NOT NULL DEFAULT 0,
    amount      DECIMAL(18, 2),
    payload     CLOB,
    created_at  DATETIME,
    PRIMARY KEY (id),
    CONSTRAINT uk_receipt_order_no UNIQUE (order_no)
);

DROP TABLE IF EXISTS t_reconcile_diff;
CREATE TABLE t_reconcile_diff
(
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    order_no   VARCHAR(32)  NOT NULL,
    diff_type  VARCHAR(32)  NOT NULL,
    detail     VARCHAR(512),
    created_at DATETIME,
    PRIMARY KEY (id)
);

DROP TABLE IF EXISTS t_product;
CREATE TABLE t_product
(
    product_id VARCHAR(32)    NOT NULL,
    name       VARCHAR(64)    NOT NULL,
    price      DECIMAL(18, 2) NOT NULL,
    PRIMARY KEY (product_id)
);

INSERT INTO t_product (product_id, name, price)
VALUES ('P1001', '商品A', 100.00),
       ('P1002', '商品B', 50.00);
