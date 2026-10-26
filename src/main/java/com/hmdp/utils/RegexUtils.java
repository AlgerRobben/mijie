package com.hmdp.utils;

import cn.hutool.core.util.StrUtil;

/**
 * 格式校验工具，方法名isXInvalid返回true表示非法；业务判断不要按“校验成功”理解其布尔值。
 * @author 虎哥
 */
public class RegexUtils {
    /**
     * 返回true代表不匹配项目手机号规则，空值同样非法。
     */
    public static boolean isPhoneInvalid(String phone){
        return mismatch(phone, RegexPatterns.PHONE_REGEX);
    }
    /**
     * 返回true代表邮箱格式非法，仅校验格式而不验证实际邮箱。
     */
    public static boolean isEmailInvalid(String email){
        return mismatch(email, RegexPatterns.EMAIL_REGEX);
    }

    /**
     * 判断验证码格式是否非法；与登录时读取Redis比较真实验证码是不同步骤。
     */
    public static boolean isCodeInvalid(String code){
        return mismatch(code, RegexPatterns.VERIFY_CODE_REGEX);
    }

    // 校验是否不符合正则格式
    private static boolean mismatch(String str, String regex){
        if (StrUtil.isBlank(str)) {
            return true;
        }
        return !str.matches(regex);
    }
}
