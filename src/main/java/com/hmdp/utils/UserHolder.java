package com.hmdp.utils;

import com.hmdp.dto.UserDTO;

/**
 * 当前请求的用户上下文，基于ThreadLocal绑定Tomcat处理线程。后台消费/异步重建线程不能默认继承这里的身份。请求结束必须remove。
 */
public class UserHolder {
    private static final ThreadLocal<UserDTO> tl = new ThreadLocal<>();

    /**
     * 把本次请求身份绑定到当前线程，不会自动传到异步线程。
     */
    public static void saveUser(UserDTO user){
        tl.set(user);
    }

    /**
     * 读取当前线程身份；匿名请求或后台线程可能为null。
     */
    public static UserDTO getUser(){
        return tl.get();
    }

    /**
     * 调用ThreadLocal.remove释放引用，避免线程池复用时残留上个用户。
     */
    public static void removeUser(){
        tl.remove();
    }
}
