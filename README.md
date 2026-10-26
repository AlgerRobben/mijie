# 觅街

本地商铺查询与优惠券秒杀项目，基于 JDK 21 和 Spring Boot。

- OpenResty、Redis、Caffeine 多级缓存处理热点商铺；普通商铺只使用 Redis。
- Canal 采集 MySQL Binlog 并投递 RocketMQ，协调 Redis、Java 本地缓存与边缘缓存。
- Redis Lua 预占秒杀库存并写入 Stream；消费者异步落库，通过 Pending、死信流和失败补偿恢复订单状态。

## 文档

- [核心业务设计与技术实现](docs/业务/核心业务设计与技术实现.md)
- [本机部署与联调手册](docs/部署/本机部署与联调手册.md)
- [性能与可用性测试手册](docs/测试/性能与可用性测试手册.md)

## 运行

导入 src/main/resources/db/hmdp.sql。设置 HMDP_DB_URL、HMDP_DB_USERNAME、HMDP_DB_PASSWORD、HMDP_REDIS_HOST、HMDP_REDIS_PORT、HMDP_REDIS_PASSWORD 环境变量后，用 mvn -DskipTests package 构建。完整缓存同步链路还需要 Canal、RocketMQ 和 OpenResty，具体步骤见部署文档。

公开版本中的数据库和 Redis 密码由环境变量提供，SQL 示例用户已经匿名化。本机运行状态、凭据及依赖文件位于不发布的 .local/。
