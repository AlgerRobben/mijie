package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Follow;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * 业务服务接口，继承IService提供实体通用CRUD；自定义方法由FollowServiceImpl实现。
 * 接口描述调用入口，缓存、事务或消息执行细节应继续阅读实现类。
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IFollowService extends IService<Follow> {

    /**
     * followable=true表示发起关注，false表示取消。先修改MySQL关系，成功后更新follow:<当前用户>集合。
     * Redis集合用于共同关注查询；当前接口没有把两种存储组成同一事务，也没有在这里实现完整并发去重。
     */
    Result followAndUnfollow(Long id, Boolean followable);

    /**
     * 查询MySQL中当前用户到目标用户的关注记录数量，返回布尔值。
     */
    Result isFollow(Long id);

    /**
     * Redis SINTER求两个用户关注集合交集，再读取交集中用户的简化资料；Set交集本身不承诺展示排序。
     */
    Result commonFollows(Long id);

}
