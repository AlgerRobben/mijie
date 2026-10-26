-- 已有库增量升级：不删除商铺/订单。热点身份及数据版本持久化，不随缓存丢失而消失。
CREATE TABLE IF NOT EXISTS tb_shop_cache_policy (
    shop_id BIGINT UNSIGNED NOT NULL PRIMARY KEY,
    cache_mode VARCHAR(16) NOT NULL DEFAULT 'NORMAL',
    activation_state VARCHAR(16) NOT NULL DEFAULT 'READY',
    logical_ttl_seconds INT UNSIGNED NOT NULL DEFAULT 1800,
    revision BIGINT UNSIGNED NOT NULL DEFAULT 1,
    deleted TINYINT NOT NULL DEFAULT 0,
    update_time TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    KEY idx_hot_policy (cache_mode, activation_state, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT IGNORE INTO tb_shop_cache_policy(shop_id) SELECT id FROM tb_shop;

-- 版本必须覆盖直接SQL。策略表只存业务/策略变化，刷新完成状态不写此表，避免CDC自触发循环。
DELIMITER $$
DROP TRIGGER IF EXISTS hmdp_cache_policy_version_bu$$
CREATE TRIGGER hmdp_cache_policy_version_bu BEFORE UPDATE ON tb_shop_cache_policy
FOR EACH ROW BEGIN
    SET NEW.revision = OLD.revision + 1;
END$$

DROP TRIGGER IF EXISTS hmdp_shop_cache_ai$$
CREATE TRIGGER hmdp_shop_cache_ai AFTER INSERT ON tb_shop
FOR EACH ROW BEGIN
    INSERT INTO tb_shop_cache_policy(shop_id) VALUES(NEW.id)
    ON DUPLICATE KEY UPDATE
        activation_state = IF(cache_mode='HOT' AND deleted=1, 'WARMING', activation_state),
        deleted=0, update_time=CURRENT_TIMESTAMP(6);
END$$

DROP TRIGGER IF EXISTS hmdp_shop_cache_au$$
CREATE TRIGGER hmdp_shop_cache_au AFTER UPDATE ON tb_shop
FOR EACH ROW BEGIN
    IF OLD.id <> NEW.id THEN
        UPDATE tb_shop_cache_policy SET deleted=1 WHERE shop_id=OLD.id;
    END IF;
    INSERT INTO tb_shop_cache_policy(shop_id) VALUES(NEW.id)
    ON DUPLICATE KEY UPDATE
        activation_state = IF(cache_mode='HOT' AND deleted=1, 'WARMING', activation_state),
        deleted=0, update_time=CURRENT_TIMESTAMP(6);
END$$

DROP TRIGGER IF EXISTS hmdp_shop_cache_ad$$
CREATE TRIGGER hmdp_shop_cache_ad AFTER DELETE ON tb_shop
FOR EACH ROW BEGIN
    INSERT INTO tb_shop_cache_policy(shop_id, deleted) VALUES(OLD.id,1)
    ON DUPLICATE KEY UPDATE deleted=1, update_time=CURRENT_TIMESTAMP(6);
END$$
DELIMITER ;
