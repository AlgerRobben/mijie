package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
import java.util.Set;

/**
 * MySQL保存关注关系，Redis Set保存关注集合以计算共同关注。
 * 本实现按数据库操作成功后再修改Redis，跨两种存储的操作没有统一原子事务。
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IUserService userService;

    /**
     * followable=true表示发起关注，false表示取消。先修改MySQL关系，成功后更新follow:<当前用户>集合。
     * Redis集合用于共同关注查询；当前接口没有把两种存储组成同一事务，也没有在这里实现完整并发去重。
     */
    @Override
    public Result followAndUnfollow(Long followUserId, Boolean followable) {
        //1.获取当前用户id
        Long userId = UserHolder.getUser().getId();
        String key = "follow:" + userId;
        //2.判断是否可关注, 如果可关注——>关注逻辑, 如果不可关注——>取关逻辑
        if(followable){
            //向tb_follow中写入关注记录
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(followUserId);
            boolean success = save(follow);
            //在redis中记录userId的关注集合，便于使用集合操作实现共同关注等功能
            if (success){
                stringRedisTemplate.opsForSet().add(key, followUserId.toString());
            }
        }else {
            //从tb_follow中移除关注记录
            boolean success = remove(new QueryWrapper<Follow>()
                    .eq("user_id", userId)
                    .eq("follow_user_id", followUserId));
            //从redis的关注集合中移除
            if(success){
                stringRedisTemplate.opsForSet().remove(key, followUserId.toString());
            }
        }
        return Result.ok();
    }

    /**
     * 查询MySQL中当前用户到目标用户的关注记录数量，返回布尔值。
     */
    @Override
    public Result isFollow(Long followUserId) {
        //1.获取当前用户id
        Long userId = UserHolder.getUser().getId();
        //2.查询userId是否关注followUserId
        Integer count = query().eq("user_id", userId).eq("follow_user_id", followUserId).count();
        //3.返回
        return Result.ok(count > 0);
    }

    /**
     * Redis SINTER求两个用户关注集合交集，再读取交集中用户的简化资料；Set交集本身不承诺展示排序。
     */
    @Override
    public Result commonFollows(Long id) {
        //1.获取当前用户
        Long userId = UserHolder.getUser().getId();
        //2.获取两个用户的关注集合的交集
        String key1 = "follow:" + userId;
        String key2 = "follow:" + id;
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(key1, key2);
        if(intersect == null || intersect.isEmpty()){
            return Result.ok(List.of());
        }
        //3.从交集中解析出userId并根据userId查询user
        List<Long> ids = intersect.stream().map(Long::valueOf).toList();
        List<UserDTO> users = userService.listByIds(ids)
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .toList();
        return Result.ok(users);
    }


}
