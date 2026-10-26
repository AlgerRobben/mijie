package com.hmdp.config;

import com.hmdp.dto.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import com.hmdp.cache.CacheBusyException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

/**
 * 捕获MVC路径的RuntimeException，记录日志并返回Result.fail。当前方法没有@ResponseStatus设置，通常仍为HTTP200；客户端必须检查success字段。
 */
@Slf4j
@RestControllerAdvice
public class WebExceptionAdvice {

    /** 无旧值且受控等待失败时返回真实503，不把失败当不存在或放开所有请求查库。 */
    @ExceptionHandler(CacheBusyException.class)
    public ResponseEntity<Result> handleCacheBusy(CacheBusyException e) {
        log.warn("SHOP_CACHE_BUSY {}",e.getMessage());
        return ResponseEntity.status(503).header("Retry-After","1").body(Result.fail("服务繁忙，请稍后再试"));
    }
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Result> handleStatus(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatus()).body(Result.fail(e.getStatus().getReasonPhrase()));
    }

    /**
     * 异常转成“服务器异常”业务载荷并保留服务端错误日志；本方法本身不设置HTTP500状态。
     */
    @ExceptionHandler(RuntimeException.class)
    public Result handleRuntimeException(RuntimeException e) {
        log.error(e.toString(), e);
        return Result.fail("服务器异常");
    }
}
