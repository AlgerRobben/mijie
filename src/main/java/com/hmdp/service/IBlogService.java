package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.dto.Result;
import com.hmdp.entity.Blog;

/**
 * 业务服务接口，继承IService提供实体通用CRUD；自定义方法由BlogServiceImpl实现。
 * 接口描述调用入口，缓存、事务或消息执行细节应继续阅读实现类。
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IBlogService extends IService<Blog> {

    /**
     * 按MySQL liked倒序分页，补作者信息与当前用户点赞状态；允许匿名热门列表，匿名时没有UserHolder身份。
     */
    Result queryHotBlog(Integer current);

    /**
     * 取单篇实体，再补作者昵称/头像及当前用户是否点赞；这些展示字段不写回笔记表。
     */
    Result queryBlogById(Long id);

    /**
     * 用用户在ZSet中是否有score判断点赞/取消。数据库数量更新成功后再修改用户ZSet。
     * ZSet以点赞时间为分数，支持时间顺序查询；当前查分与跨存储更新不是原子操作，不要理解为并发绝对幂等。
     */
    Result likeBlog(Long id);

    /**
     * ZRANGE 0..4按分数升序取最早的5个点赞用户；用FIELD保持这个时间顺序，再转换成UserDTO。
     */
    Result queryBlogLikes(Long id);

    /**
     * 将作者设为当前登录用户，保存笔记后查询粉丝并逐个把笔记ID写入其Feed ZSet，score为推送时间。
     * 这是写扩散，粉丝多时会增加发布耗时；当前推送与数据库保存没有统一事务。
     */
    Result saveBlog(Blog blog);

    /**
     * 按max时间分数倒序读当前用户收件箱，每批2条，offset用于跳过边界同分数记录。
     * 遍历计算本批最小时间及同时间数量os，再按Redis顺序查询笔记。
     * 当前返回构造仍使用入参offset而非计算出的os；注释说明真实行为，该游标细节应另行核对修正。
     */
    Result queryBlogOfFollow(Long max, Integer offset);

}
