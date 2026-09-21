-- 库存原子扣减脚本
-- KEYS[1] = stock:{productId}
-- ARGV[1] = quantity
-- 返回：1 扣减成功 / 0 库存不足 / -1 未预热（key 不存在）
local available = redis.call('GET', KEYS[1])
if not available then
    return -1
end
if tonumber(available) < tonumber(ARGV[1]) then
    return 0
end
redis.call('DECRBY', KEYS[1], ARGV[1])
return 1
