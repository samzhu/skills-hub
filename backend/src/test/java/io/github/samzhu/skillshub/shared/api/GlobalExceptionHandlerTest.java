package io.github.samzhu.skillshub.shared.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotSupportedException;

/**
 * S162 — {@link GlobalExceptionHandler} unit test。
 *
 * <p>純 POJO 測試；無 Spring context；快速；驗證 handler 方法直接呼叫的回傳合約。
 * 這個範圍只 cover S162 新增的 415 handler；其他 handler 由 controller integration test 間接覆蓋。
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("S162 AC-3: 415 unsupported media type → 平台 ErrorResponse shape")
    void unsupportedMediaTypeReturnsPlatformErrorShape() {
        var ex = new HttpMediaTypeNotSupportedException(
                MediaType.TEXT_PLAIN, List.of(MediaType.APPLICATION_JSON));

        ResponseEntity<ErrorResponse> response = handler.handleUnsupportedMediaType(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        ErrorResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.error()).isEqualTo("UNSUPPORTED_MEDIA_TYPE");
        assertThat(body.message()).contains("text/plain");
        assertThat(body.timestamp()).isNotNull();
    }

    @Test
    @DisplayName("S162 AC-3: Content-Type null（client 沒帶）→ 仍 normalized message 不 NPE")
    void unsupportedMediaTypeWithNullContentType() {
        var ex = new HttpMediaTypeNotSupportedException("Content type not specified");

        ResponseEntity<ErrorResponse> response = handler.handleUnsupportedMediaType(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        ErrorResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.error()).isEqualTo("UNSUPPORTED_MEDIA_TYPE");
        assertThat(body.message()).contains("(none)");
    }

    @Test
    @DisplayName("S162 AC-5: uncaught Exception fallback → 500 INTERNAL_ERROR generic message 不洩漏 stack")
    void uncaughtExceptionReturnsGenericInternalError() {
        var sensitive = new RuntimeException("SQL error: SELECT * FROM users WHERE id = 'leak'");

        ResponseEntity<ErrorResponse> response = handler.handleUncaught(sensitive);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        ErrorResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.error()).isEqualTo("INTERNAL_ERROR");
        // 訊息固定 generic，不含 ex.getMessage() 的敏感資訊（SQL / 內部類名 / 路徑）
        assertThat(body.message()).isEqualTo("An unexpected error occurred");
        assertThat(body.message()).doesNotContain("SQL");
        assertThat(body.message()).doesNotContain("SELECT");
        assertThat(body.timestamp()).isNotNull();
    }

    @Test
    @DisplayName("S162 AC-5: 任意 Exception 子類（如 NullPointerException）也走 fallback shape")
    void uncaughtNpeAlsoReturnsGenericShape() {
        var npe = new NullPointerException("Cannot invoke \"X.foo()\" because \"X\" is null");

        ResponseEntity<ErrorResponse> response = handler.handleUncaught(npe);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().error()).isEqualTo("INTERNAL_ERROR");
        assertThat(response.getBody().message()).doesNotContain("X");
    }
}
