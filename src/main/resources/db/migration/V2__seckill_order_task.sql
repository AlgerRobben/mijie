-- 订单创建事实与失败围栏。正常创建/补偿按order_id锁同一行；FAILED不能按短缓存TTL删除。
-- 这是任务结果，不是支付订单表；外部FAILED状态还要求compensated已完成。
CREATE TABLE IF NOT EXISTS tb_seckill_order_task (
    order_id BIGINT UNSIGNED NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    voucher_id BIGINT UNSIGNED NOT NULL,
    state VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    reason VARCHAR(64) DEFAULT NULL,
    -- 同用户同券已存在另一已提交订单时，补偿只释放额外预占并保留其购买资格。
    existing_order_id BIGINT UNSIGNED DEFAULT NULL,
    compensated TINYINT NOT NULL DEFAULT 0,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (order_id),
    KEY idx_task_user_voucher (user_id, voucher_id),
    KEY idx_task_compensation (state, compensated)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
