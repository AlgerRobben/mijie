package com.hmdp.dto;

import java.util.List;

/**
 * 统一HTTP业务返回结构。success描述业务结果，data/total按需要提供；Spring Jackson默认省略null字段。HTTP状态与success需要同时查看。
 */
public record Result(Boolean success, String errorMsg, Object data, Long total) {

    /**
     * 构造业务成功结果；各重载按需要提供data与分页total，未提供的字段为null。
     */
    public static Result ok(){
        return new Result(true, null, null, null);
    }
    /**
     * 构造业务成功结果；各重载按需要提供data与分页total，未提供的字段为null。
     */
    public static Result ok(Object data){
        return new Result(true, null, data, null);
    }
    /**
     * 构造业务成功结果；各重载按需要提供data与分页total，未提供的字段为null。
     */
    public static Result ok(List<?> data, Long total){
        return new Result(true, null, data, total);
    }
    /**
     * 构造业务失败结果及提示；自身不设置HTTP状态码。
     */
    public static Result fail(String errorMsg){
        return new Result(false, errorMsg, null, null);
    }
}
