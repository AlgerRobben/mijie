package com.hmdp.dto;

import lombok.Data;

//用户隐私信息隐藏

/**
 * 对接口与Redis登录Hash暴露的简化用户信息，只保留ID、昵称、头像；与完整User实体分开，避免直接返回账号字段。
 */
@Data
public class UserDTO {
    private Long id;
    private String nickName;
    private String icon;
}
