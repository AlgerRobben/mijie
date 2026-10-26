package com.hmdp.dto;

import lombok.Data;

/**
 * 登录请求载荷，包含手机号与验证码等字段；当前UserServiceImpl实际执行验证码分支。
 */
@Data
public class LoginFormDTO {
    private String phone;
    private String code;
    private String password;
}
