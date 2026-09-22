-- 测试数据复位（仅用于本地测试库！会清空业务数据）
-- 用法：
--   Get-Content scripts/reset-data.sql -Raw | docker exec -i trading-mysql mysql -uroot -proot123 -N -B trading
--   docker exec trading-redis redis-cli FLUSHDB
-- 说明：清空 Redis 后不需要重启应用——StockService 在 key 不存在时会自动从 DB 重新预热。

USE trading;

TRUNCATE TABLE t_order;
TRUNCATE TABLE t_outbox;
TRUNCATE TABLE t_receipt;
TRUNCATE TABLE t_reconcile_diff;

-- 库存复位：available = total，乐观锁版本归零
UPDATE t_stock SET available = total, version = 0;

-- 并发测试专用商品（100 库存）
INSERT INTO t_stock (product_id, total, available, version)
VALUES ('IT_P1', 100, 100, 0)
ON DUPLICATE KEY UPDATE total = VALUES(total), available = VALUES(available), version = 0;

SELECT product_id, total, available, version FROM t_stock ORDER BY product_id;
