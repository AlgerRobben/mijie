package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.entity.User;

import javax.servlet.http.HttpSession;

/**
 * 业务服务接口，继承IService提供实体通用CRUD；自定义方法由UserServiceImpl实现。
 * 接口描述调用入口，缓存、事务或消息执行细节应继续阅读实现类。
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IUserService extends IService<User> {

    /**
     * 校验手机号，生成6位数字验证码并以分钟TTL写入Redis。HttpSession在当前方案中未使用。
     * 日志仅模拟发送；返回成功不代表第三方短信已经送达。
     */
    Result sendCode(String phone, HttpSession session);

    /**
     * 从Redis校验验证码，查询/创建用户，再生成token并把UserDTO以字符串Hash字段保存。
     * 返回token给前端；后续请求通过authorization携带。当前成功登录后未删除验证码，仍受原TTL限制。
     */
    Result login(LoginFormDTO loginFormDTO, HttpSession session);

    /**
     * 按用户和yyyyMM组织Bitmap，本月第day天映射到从0开始的day-1位；重复签到仍是把同一位设为true。
     */
    Result sign();

    /**
     * BITFIELD一次取得本月1号至今日的无符号位段，最低位对应今日。
     * 从最低位开始连续统计1，遇到0即停止，因此今日未签返回0，且只统计当前月份内的连续天数。
     */
    Result signCount();
}
