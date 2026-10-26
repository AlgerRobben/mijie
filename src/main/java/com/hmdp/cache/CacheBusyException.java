package com.hmdp.cache;

/** 无可用值时受控失败，不通过放开所有请求查库制造缓存击穿。 */
public class CacheBusyException extends RuntimeException {
    public CacheBusyException(String message) { super(message); }
    public CacheBusyException(String message, Throwable cause) { super(message, cause); }
}
