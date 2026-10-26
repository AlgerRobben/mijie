-- 一人一券的数据库唯一约束：不同订单ID也不能拥有相同(user_id, voucher_id)。
-- 部署已有库前先核对重复记录，迁移不会自动执行；以下两行为检查SQL示例：
-- SELECT user_id, voucher_id, COUNT(*) FROM tb_voucher_order
-- GROUP BY user_id, voucher_id HAVING COUNT(*) > 1;
ALTER TABLE tb_voucher_order
    ADD UNIQUE KEY uk_user_voucher (user_id, voucher_id);
