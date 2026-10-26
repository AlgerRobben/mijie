package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.lang.Nullable;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 请求身份恢复拦截器，读取authorization中的token，从Redis Hash恢复UserDTO并续期。
 * 它允许无token请求继续走后续链路；登录强制要求由LoginInterceptor控制。请求结束移除ThreadLocal，避免线程复用串用户。
 */
public class RefreshTokenInterceptor implements HandlerInterceptor {

    private StringRedisTemplate stringRedisTemplate;

    public RefreshTokenInterceptor(StringRedisTemplate stringRedisTemplate){
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 尝试恢复身份。无token或不存在的Hash先放行，后续登录拦截器再决定是否允许访问该接口。
     * 有效Hash复制为UserDTO写入ThreadLocal并续期；这里的Redis连接错误没有被吞掉。
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        //1.尝试从请求头携带的token中获取key，没有key就放行
        String token = request.getHeader("authorization");
        if(StrUtil.isBlank(token)){
            return true;
        }
        //2.根据key查询用户信息，没有用户信息就放行
        String tokenKey = RedisConstants.LOGIN_USER_KEY + token;
        Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(tokenKey);
        if(userMap.isEmpty()){
            return true;
        }
        //3.成功获取到用户信息则保存到ThreadLocal中并放行
        UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);
        UserHolder.saveUser(userDTO);
        //4.刷新redis中用户信息有效期
        stringRedisTemplate.expire(tokenKey, RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);
        return true;
    }

    /**
     * 请求完成后清除用户上下文，即使业务发生异常也避免Tomcat线程下次被其他用户复用时串身份。
     */
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, @Nullable Exception ex) throws Exception {
        //移除用户信息防止内存泄漏
        UserHolder.removeUser();
    }
}
