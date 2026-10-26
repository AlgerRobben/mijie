package com.hmdp.controller;


import com.hmdp.dto.Result;
import com.hmdp.service.IFollowService;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;

/**
 * 当前用户关注/取关与共同关注接口，当前用户身份从UserHolder读取。
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/follow")
public class FollowController {

    @Resource
    private IFollowService followService;

    /**
     * 路径followable决定添加或取消，目标id是被关注用户，不是当前用户。
     */
    @PutMapping("/{id}/{followable}")
    public Result followAndUnfollow(@PathVariable("id") Long followUserId, @PathVariable("followable") Boolean followable){
        return followService.followAndUnfollow(followUserId, followable);
    }

    /**
     * 查询当前用户是否关注指定用户。
     */
    @GetMapping("/or/not/{id}")
    public Result isFollow(@PathVariable("id") Long followUserId){
        return followService.isFollow(followUserId);
    }

    /**
     * 求当前用户与指定用户的关注集合交集并返回用户DTO列表。
     */
    @GetMapping("/common/{id}")
    public Result commonFollows(@PathVariable("id") Long id){
        return followService.commonFollows(id);
    }

}
