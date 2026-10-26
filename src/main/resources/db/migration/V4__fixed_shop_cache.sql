-- 已执行V3的现有库升级：保留版本和删除记录，取消数据库冷热策略。
-- 请先停止旧业务/Canal实例并备份结构、tb_shop_cache_policy数据；MySQL DDL不能事务回滚。
CREATE TABLE IF NOT EXISTS tb_shop_cache_version (
    shop_id BIGINT UNSIGNED NOT NULL PRIMARY KEY,
    revision BIGINT UNSIGNED NOT NULL DEFAULT 1,
    deleted TINYINT NOT NULL DEFAULT 0,
    update_time TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 版本只前进；重跑迁移也不降低已有版本。复制删除记录避免同ID重用时版本归零。
INSERT INTO tb_shop_cache_version(shop_id,revision,deleted)
SELECT shop_id,revision,deleted FROM tb_shop_cache_policy
ON DUPLICATE KEY UPDATE
    deleted=IF(VALUES(revision)>tb_shop_cache_version.revision,VALUES(deleted),tb_shop_cache_version.deleted),
    revision=GREATEST(tb_shop_cache_version.revision,VALUES(revision));
INSERT IGNORE INTO tb_shop_cache_version(shop_id) SELECT id FROM tb_shop;

DELIMITER $$
DROP TRIGGER IF EXISTS hmdp_cache_policy_version_bu$$
DROP TRIGGER IF EXISTS hmdp_shop_cache_ai$$
DROP TRIGGER IF EXISTS hmdp_shop_cache_au$$
DROP TRIGGER IF EXISTS hmdp_shop_cache_ad$$
DROP TRIGGER IF EXISTS hmdp_cache_version_bu$$
CREATE TRIGGER hmdp_cache_version_bu BEFORE UPDATE ON tb_shop_cache_version
FOR EACH ROW BEGIN
    SET NEW.revision=OLD.revision+1;
END$$
CREATE TRIGGER hmdp_shop_cache_ai AFTER INSERT ON tb_shop
FOR EACH ROW BEGIN
    INSERT INTO tb_shop_cache_version(shop_id) VALUES(NEW.id)
    ON DUPLICATE KEY UPDATE deleted=0,update_time=CURRENT_TIMESTAMP(6);
END$$
CREATE TRIGGER hmdp_shop_cache_au AFTER UPDATE ON tb_shop
FOR EACH ROW BEGIN
    IF OLD.id<>NEW.id THEN
        UPDATE tb_shop_cache_version SET deleted=1 WHERE shop_id=OLD.id;
    END IF;
    INSERT INTO tb_shop_cache_version(shop_id) VALUES(NEW.id)
    ON DUPLICATE KEY UPDATE deleted=0,update_time=CURRENT_TIMESTAMP(6);
END$$
CREATE TRIGGER hmdp_shop_cache_ad AFTER DELETE ON tb_shop
FOR EACH ROW BEGIN
    INSERT INTO tb_shop_cache_version(shop_id,deleted) VALUES(OLD.id,1)
    ON DUPLICATE KEY UPDATE deleted=1,update_time=CURRENT_TIMESTAMP(6);
END$$
DELIMITER ;
DROP TABLE tb_shop_cache_policy;
