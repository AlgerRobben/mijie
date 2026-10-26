package com.hmdp.utils;


import cn.hutool.core.util.RandomUtil;
import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;

/**
 * 带随机盐的密码摘要辅助工具。当前验证码登录主链路不调用密码校验，不能据此认为已实现密码登录。
 */
public class PasswordEncoder {

    /**
     * 将随机盐和UTF-8的MD5摘要拼成salt@digest；重载复用同一盐以便比较。它是摘要示例，当前验证码登录未调用。
     */
    public static String encode(String password) {
        // 生成盐
        String salt = RandomUtil.randomString(20);
        // 加密
        return encode(password,salt);
    }
    /**
     * 将随机盐和UTF-8的MD5摘要拼成salt@digest；重载复用同一盐以便比较。它是摘要示例，当前验证码登录未调用。
     */
    private static String encode(String password, String salt) {
        // 加密
        return salt + "@" + DigestUtils.md5DigestAsHex((password + salt).getBytes(StandardCharsets.UTF_8));
    }
    /**
     * 从已保存字符串提取盐，用相同规则处理输入并比较；null返回false，缺少分隔符会抛格式错误。
     */
    public static Boolean matches(String encodedPassword, String rawPassword) {
        if (encodedPassword == null || rawPassword == null) {
            return false;
        }
        if(!encodedPassword.contains("@")){
            throw new RuntimeException("密码格式不正确！");
        }
        String[] arr = encodedPassword.split("@");
        // 获取盐
        String salt = arr[0];
        // 比较
        return encodedPassword.equals(encode(rawPassword, salt));
    }
}
