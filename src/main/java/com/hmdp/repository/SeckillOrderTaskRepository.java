package com.hmdp.repository;

import com.hmdp.dto.SeckillOrderTask;
import com.hmdp.entity.VoucherOrder;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * tb_seckill_order_task的JDBC访问层。由创建/补偿事务共享同一DataSource连接和任务行锁。
 * INSERT IGNORE保证同一订单ID只创建一行，SELECT FOR UPDATE把后续检查与结果更新串行化；锁持有到调用方事务结束。
 * FAILED记录是旧消息不能再落库的持久化依据，不能像短期缓存一样随意删除。
 */
@Repository
public class SeckillOrderTaskRepository {
    private final JdbcTemplate jdbc;
    public SeckillOrderTaskRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /**
     * 必须在调用方创建/围栏事务内执行。验证消息身份，INSERT IGNORE创建任务，再FOR UPDATE取得并锁住那一行。
     * 重复ID不会新建任务；校验原行用户/券，锁一直保持到调用方提交或回滚。
     */
    public SeckillOrderTask lock(VoucherOrder order) {
        // 订单对象字段校验
        if (order.getId() == null || order.getUserId() == null || order.getVoucherId() == null
                || order.getId() <= 0 || order.getUserId() <= 0 || order.getVoucherId() <= 0) {
            throw new IllegalArgumentException("Invalid order message identity");
        }
        // 先保证任务行存在再加行锁；这不是Java synchronized，也不是Redis锁，锁由MySQL事务管理。
        // insert ignore into相同订单任务主键插入幂等
        jdbc.update("INSERT IGNORE INTO tb_seckill_order_task(order_id,user_id,voucher_id) VALUES (?,?,?)",
                order.getId(), order.getUserId(), order.getVoucherId());
        // read方法执行sql上行锁，该行锁属于外部调用lock方法的persist方法开启的事务，一直持有到外层事务提交或者回滚:
        // SELECT order_id, user_id, voucher_id, state, reason, existing_order_id, compensated
        // FROM tb_seckill_order_task
        // WHERE order_id = ?
        // FOR UPDATE;
        SeckillOrderTask task = read(order.getId(), true);
        if (task.userId() != order.getUserId() || task.voucherId() != order.getVoucherId()) {
            throw new IllegalStateException("Order ID belongs to another reservation");
        }
        // 返回的task为record类，是任务行的Java快照对象
        return task;
    }

    /**
     * 普通查询任务，不加FOR UPDATE，供状态查询和事务内更新后读取结果使用。不存在返回null。
     */
    public SeckillOrderTask find(long id) {
        return read(id, false);
    }

    /**
     * 按lock参数选择普通SELECT或SELECT FOR UPDATE，把数据库列映射成不可变任务快照。
     * 只有查无行转null，数据库连接/SQL错误保留异常给上层处理。
     */
    private SeckillOrderTask read(long id, boolean lock) {
        try {
            return jdbc.queryForObject(
                    "SELECT order_id,user_id,voucher_id,state,reason,existing_order_id,compensated " +
                            "FROM tb_seckill_order_task WHERE order_id=?" + (lock ? " FOR UPDATE" : ""),
                    (rs, row) -> new SeckillOrderTask(
                            rs.getLong("order_id"),
                            rs.getLong("user_id"),
                            rs.getLong("voucher_id"),
                            rs.getString("state"),
                            rs.getString("reason"),
                            rs.getObject("existing_order_id", Long.class),
                            rs.getBoolean("compensated")), id);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /**
     * 在创建事务中标记SUCCEEDED并设compensated=1；成功任务无需退还预占，这个标记不代表退款。
     */
    public SeckillOrderTask succeed(long id) {
        jdbc.update("UPDATE tb_seckill_order_task " +
                "SET state='SUCCEEDED'," +
                "reason=NULL," +
                "compensated=1 " + // compensated = 1表示无需再释放预占
                "WHERE order_id=?", id);
        return find(id);
    }

    /**
     * 在核对事务中写FAILED及原因/已有订单ID，形成迟到消息不能再落库的围栏；Redis释放尚在事务之外。
     */
    public SeckillOrderTask fail(long id, String reason, Long existingOrderId) {
        jdbc.update("UPDATE tb_seckill_order_task SET state='FAILED',reason=?,existing_order_id=? WHERE order_id=?",
                reason, existingOrderId, id);
        return find(id);
    }

    /**
     * Redis释放脚本已确认后才把FAILED任务标记完成；SQL失败会留待后续幂等重试。
     */
    public void markCompensated(long id) {
        jdbc.update("UPDATE tb_seckill_order_task SET compensated=1 WHERE order_id=? AND state='FAILED'", id);
    }
}
