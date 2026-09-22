-- 一致性不变量校验：每个检查项返回 violations = 0 表示通过
-- 用法：
--   Get-Content scripts/consistency-check.sql -Raw | docker exec -i trading-mysql mysql -uroot -proot123 -N -B trading

USE trading;

SELECT '1_oversell_available_lt_0' AS check_name, COUNT(*) AS violations
FROM t_stock
WHERE available < 0;

SELECT '2_stock_conservation' AS check_name, COUNT(*) AS violations
FROM (
    SELECT s.product_id
    FROM t_stock s
    LEFT JOIN (
        SELECT product_id, SUM(quantity) AS sold
        FROM t_order
        WHERE status IN (2, 3, 4)   -- CREATED / REPORTED / CONFIRMED
        GROUP BY product_id
    ) o ON o.product_id = s.product_id
    WHERE s.available <> s.total - COALESCE(o.sold, 0)
) x;

SELECT '3_duplicate_order_no' AS check_name, COUNT(*) AS violations
FROM (SELECT order_no FROM t_order GROUP BY order_no HAVING COUNT(*) > 1) x;

SELECT '4_duplicate_idempotent_key' AS check_name, COUNT(*) AS violations
FROM (SELECT idempotent_key FROM t_order GROUP BY idempotent_key HAVING COUNT(*) > 1) x;

SELECT '5_order_without_outbox' AS check_name, COUNT(*) AS violations
FROM t_order o
LEFT JOIN t_outbox x ON x.aggregate_id = o.order_no
WHERE x.id IS NULL;

SELECT '6_orphan_receipt' AS check_name, COUNT(*) AS violations
FROM t_receipt r
LEFT JOIN t_order o ON o.order_no = r.order_no
WHERE o.id IS NULL;

SELECT '7_stuck_order_gt_30min' AS check_name, COUNT(*) AS violations
FROM t_order
WHERE status IN (1, 2, 3)
  AND updated_at < NOW() - INTERVAL 30 MINUTE;

-- 附加：明细（排查用，不参与断言）
SELECT 'detail:negative_stock' AS detail, product_id, available FROM t_stock WHERE available < 0;
SELECT 'detail:duplicate_keys' AS detail, idempotent_key, COUNT(*) AS cnt FROM t_order GROUP BY idempotent_key HAVING COUNT(*) > 1;
