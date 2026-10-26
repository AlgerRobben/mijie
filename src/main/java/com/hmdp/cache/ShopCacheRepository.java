package com.hmdp.cache;

import com.hmdp.entity.Shop;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 版本表只记录数据变化，不存冷热策略。JOIN一次读取对应版本和商铺数据。
 */
@Repository
public class ShopCacheRepository {
    public record State(ShopCacheVersion version, Shop shop) {
    }

    private final JdbcTemplate jdbc;

    public ShopCacheRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<ShopCacheVersion> VERSION = (rs, row) -> new ShopCacheVersion(
            rs.getLong("shop_id"), rs.getString("revision"), rs.getBoolean("deleted"));

    public ShopCacheVersion version(long id) {
        var rows = jdbc.query("SELECT shop_id,revision,deleted FROM tb_shop_cache_version WHERE shop_id=?", VERSION, id);
        return rows.isEmpty() ? ShopCacheVersion.absent(id) : rows.getFirst();
    }

    public List<ShopCacheVersion> versions() {
        return jdbc.query("SELECT shop_id,revision,deleted FROM tb_shop_cache_version ORDER BY shop_id", VERSION);
    }

    public State load(long id) {
        List<State> rows = jdbc.query(connection -> {
            var statement = connection.prepareStatement("SELECT v.shop_id,v.revision,v.deleted,s.id AS data_id,s.name,s.type_id,s.images,"
                    + "s.area,s.address,s.x,s.y,s.avg_price,s.sold,s.comments,s.score,s.open_hours,"
                    + "s.create_time,s.update_time AS shop_update_time FROM tb_shop_cache_version v "
                    + "LEFT JOIN tb_shop s ON s.id=v.shop_id WHERE v.shop_id=?");
            statement.setLong(1, id);
            statement.setQueryTimeout(2);
            return statement;
        }, (rs, row) -> {
            ShopCacheVersion version = VERSION.mapRow(rs, row);
            Shop shop = null;
            if (rs.getObject("data_id") != null && !version.deleted()) {
                shop = new Shop().setId(rs.getLong("data_id")).setName(rs.getString("name"))
                        .setTypeId(number(rs, "type_id")).setImages(rs.getString("images"))
                        .setArea(rs.getString("area")).setAddress(rs.getString("address"))
                        .setX(decimal(rs, "x")).setY(decimal(rs, "y"))
                        .setAvgPrice(number(rs, "avg_price")).setSold(integer(rs, "sold"))
                        .setComments(integer(rs, "comments")).setScore(integer(rs, "score"))
                        .setOpenHours(rs.getString("open_hours"));
                // 与MyBatis相同的类型映射，保留业务本地时间，不经Timestamp进行时区平移。
                shop.setCreateTime(rs.getObject("create_time", LocalDateTime.class));
                shop.setUpdateTime(rs.getObject("shop_update_time", LocalDateTime.class));
            }
            return new State(version, shop);
        });
        return rows.isEmpty() ? new State(ShopCacheVersion.absent(id), null) : rows.getFirst();
    }

    private static Long number(ResultSet rs, String key) throws SQLException {
        long v = rs.getLong(key);
        return rs.wasNull() ? null : v;
    }

    private static Integer integer(ResultSet rs, String key) throws SQLException {
        int v = rs.getInt(key);
        return rs.wasNull() ? null : v;
    }

    private static Double decimal(ResultSet rs, String key) throws SQLException {
        double v = rs.getDouble(key);
        return rs.wasNull() ? null : v;
    }
}
