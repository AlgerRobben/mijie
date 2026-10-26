package com.hmdp.utils;

import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * 登录权限拦截器，检查前一拦截器恢复的UserHolder。未登录返回HTTP401并停止处理；自身不解析token。
 */
public class LoginInterceptor implements HandlerInterceptor {

    /**
     * 检查ThreadLocal身份，无用户则HTTP401并返回false；需先经过RefreshTokenInterceptor。
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        //根据ThreadLocal中有无用户信息来判断是否已经做过登录
        if(UserHolder.getUser() == null){
            response.setStatus(401);
            return false;
        }
        return true;
    }
}
