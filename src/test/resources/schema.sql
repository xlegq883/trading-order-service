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
