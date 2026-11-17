package com.pm.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 全局异常处理单测：业务异常、上传超限与兜底 500 三条分支。
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("业务异常：原样返回业务码与文案（HTTP 200）")
    void bizException() {
        Result<Void> result = handler.handleBizException(new BizException(403, "无权限"));

        assertThat(result.getCode()).isEqualTo(403);
        assertThat(result.getMessage()).isEqualTo("无权限");
    }

    @Test
    @DisplayName("上传超限：转业务 400，避免落到兜底 500")
    void maxUploadSize() {
        Result<Void> result = handler.handleMaxUploadSize(new MaxUploadSizeExceededException(1024));

        assertThat(result.getCode()).isEqualTo(400);
        assertThat(result.getMessage()).isEqualTo("上传文件过大");
    }

    @Test
    @DisplayName("未知异常：HTTP 500，不向前端泄露内部细节")
    void unexpectedException() {
        ResponseEntity<Result<Void>> response = handler.handleException(new IllegalStateException("boom"));

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo(500);
        assertThat(response.getBody().getMessage()).isEqualTo("系统异常，请稍后重试");
    }
}
