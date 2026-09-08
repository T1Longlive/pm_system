package com.pm.common;

import lombok.Getter;
import lombok.Setter;

/**
 * 统一响应结构：code + message + data
 * 约定：成功 code = 200；业务错误码由异常处理器填充
 */
@Getter
@Setter
public class Result<T> {

    private Integer code;
    private String message;
    private T data;

    private Result() {
    }

    private Result(Integer code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    /**
     * 成功（无数据）
     */
    public static <T> Result<T> success() {
        return new Result<>(200, "success", null);
    }

    /**
     * 成功（携带数据）
     */
    public static <T> Result<T> success(T data) {
        return new Result<>(200, "success", data);
    }

    /**
     * 失败（错误码 + 错误信息）
     */
    public static <T> Result<T> error(Integer code, String message) {
        return new Result<>(code, message, null);
    }
}
