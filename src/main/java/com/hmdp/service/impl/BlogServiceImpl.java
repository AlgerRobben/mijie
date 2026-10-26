package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 探店笔记查询、点赞与粉丝收件箱推送。MySQL保存正文/点赞数，Redis ZSet保存点赞用户时间顺序和Feed索引。
 * 发布后按粉丝逐个推送，属于写扩散；当前数据库与Redis步骤未组成同一个事务，阅读时区分数据事实与辅助索引。
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private IUserService userService;

    @Resource
    private IFollowService followService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 按MySQL liked倒序分页，补作者信息与当前用户点赞状态；允许匿名热门列表，匿名时没有UserHolder身份。
     */
    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog ->{
            this.queryBlogUser(blog);
            //3.判断该blog是否被当前用户点赞过并修改like字段
            this.isBlogLiked(blog);
        });
        return Result.ok(records);
    }

    /**
     * 取单篇实体，再补作者昵称/头像及当前用户是否点赞；这些展示字段不写回笔记表。
     */
    @Override
    public Result queryBlogById(Long id) {
        //1.查询blog
        Blog blog = getById(id);
        if(blog == null){
            return Result.fail("blog不存在");
        }
        //2.为blog增加相关用户信息用于展示
        queryBlogUser(blog);
        //3.判断该blog是否被当前用户点赞过并修改isLike字段
        isBlogLiked(blog);
        return Result.ok(blog);
    }

    /**
     * 用用户在ZSet中是否有score判断点赞/取消。数据库数量更新成功后再修改用户ZSet。
     * ZSet以点赞时间为分数，支持时间顺序查询；当前查分与跨存储更新不是原子操作，不要理解为并发绝对幂等。
     */
    @Override
    public Result likeBlog(Long id) {
        //1.获取当前用户id
        Long userId = UserHolder.getUser().getId();
        //2.查看当前用户是否点赞过该blog
        String key = RedisConstants.BLOG_LIKED_KEY + id;
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        //3.如果未点赞，则数据库中点赞数+1并且将userId加入sortedset
        if(score == null){
            boolean success = update().setSql("liked = liked + 1").eq("id", id).update();
            if(success) {
                stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
            }
        }else {
            //4.如果点赞过，则数据库中点赞数-1并且将userId移出sortedset
            boolean success = update().setSql("liked = liked - 1").eq("id", id).update();
            if(success) {
                stringRedisTemplate.opsForZSet().remove(key, userId.toString());
            }
        }
        return Result.ok();
    }

    /**
     * ZRANGE 0..4按分数升序取最早的5个点赞用户；用FIELD保持这个时间顺序，再转换成UserDTO。
     */
    @Override
    public Result queryBlogLikes(Long id) {
        //1.在sortedset中查询top5用户id
        String key = RedisConstants.BLOG_LIKED_KEY + id;
        Set<String> top5User = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if(top5User == null || top5User.isEmpty()){
            return Result.ok(List.of());
        }
        List<Long> ids = top5User.stream().map(Long::valueOf).toList();
        String idStr = StrUtil.join(",", ids);
        //2.根据用户id查询用户
        List<User> users = userService.query()
                .in("id", ids)
                .last("ORDER BY FIELD(id," + idStr + ")").list();
        //3.转成UserDTO返回用户列表
        List<UserDTO> userDTOs = users.stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .toList();
        return Result.ok(userDTOs);
    }

    /**
     * 将作者设为当前登录用户，保存笔记后查询粉丝并逐个把笔记ID写入其Feed ZSet，score为推送时间。
     * 这是写扩散，粉丝多时会增加发布耗时；当前推送与数据库保存没有统一事务。
     */
    @Override
    public Result saveBlog(Blog blog) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        // 保存探店博文
        boolean success = save(blog);
        if(!success){
            return Result.fail("发布笔记失败");
        }
        //查询当前用户的粉丝
        List<Follow> follows = followService.query().eq("follow_user_id", user.getId()).list();
        // 将blog推送给粉丝收件箱（redis的sortedlist）
        for (Follow follow: follows){
            Long fanId = follow.getUserId();
            stringRedisTemplate.opsForZSet().add(RedisConstants.FEED_KEY + fanId, blog.getId().toString(), System.currentTimeMillis());
        }
        // 返回id
        return Result.ok(blog.getId());
    }

    /**
     * 按max时间分数倒序读当前用户收件箱，每批2条，offset用于跳过边界同分数记录。
     * 遍历计算本批最小时间及同时间数量os，再按Redis顺序查询笔记。
     * 当前返回构造仍使用入参offset而非计算出的os；注释说明真实行为，该游标细节应另行核对修正。
     */
    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        //1.获取当前用户
        Long userId = UserHolder.getUser().getId();
        //2.根据max和offset从当前用户的收件箱中读取数据，获取blogId和时间戳
        String key = RedisConstants.FEED_KEY + userId;
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key, 0, max, offset, 2);
        //3.非空判断
        if(typedTuples == null || typedTuples.isEmpty()){
            return Result.ok();
        }
        //4.解析收件箱中的数据，获取List<BlogId>，minTime和offset
        List<Long> ids = new ArrayList<>(typedTuples.size());
        long minTime = 0;
        int os = 1;
        for (ZSetOperations.TypedTuple<String> tuple: typedTuples){
            //获取blogid
            ids.add(Long.valueOf(tuple.getValue()));
            //获取最小时间戳
            long time = tuple.getScore().longValue();
            if(time == minTime){
                os++;
            }else {
                minTime = time;
                os = 1;
            }
        }
        //5.根据blogIds查询blogs
        String idStr = StrUtil.join(",", ids);
        List<Blog> blogs = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();
        for(Blog blog: blogs){
            queryBlogUser(blog);
            isBlogLiked(blog);
        }
        //6.封装并返回
        // 当前确实返回入参offset，前面计算的os没有用于这个返回值；同分数翻页细节需另行修正。
        ScrollResult result = new ScrollResult(blogs, minTime, offset);
        return Result.ok(result);
    }

    /**
     * 按笔记作者ID查User，把昵称/头像填到非表展示字段。
     */
    private void queryBlogUser(Blog blog){
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }

    /**
     * 有登录身份才查询该用户在点赞ZSet中的score并设置isLike，供前端按钮显示。
     */
    private void isBlogLiked(Blog blog){
        UserDTO user = UserHolder.getUser();
        if(user == null){
            return;
        }
        Long userId = user.getId();
        String key = RedisConstants.BLOG_LIKED_KEY + blog.getId();
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        blog.setIsLike(score != null);
    }
}
