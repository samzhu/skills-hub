package io.github.samzhu.skillshub.shared.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.zip.ZipException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * S162 / V03 ratchet — {@link GlobalExceptionHandler} unit test。
 *
 * <p>純 POJO 測試；無 Spring context；快速；驗證 handler 方法直接呼叫的回傳合約 +
 * status code mapping + error code 命名一致性。每 handler 一支 test；增 ~30 supports
 * 把 GlobalExceptionHandler coverage 從 35% 拉高（jacoco V03 baseline ratchet 的核心 target）。
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

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

    @Test
    @DisplayName("S162 anonymous + AccessDenied → 401 UNAUTHORIZED（@PreAuthorize 拒絕 anonymous 對齊 Spring Security 標準）")
    void accessDeniedFromAnonymousReturns401() {
        // Anonymous SecurityContext — Spring 對未驗證 user 的代表
        var anon = new AnonymousAuthenticationToken("key", "anonymous",
                AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"));
        SecurityContextHolder.getContext().setAuthentication(anon);

        ResponseEntity<ErrorResponse> response = handler.handleAccessDenied(
                new AccessDeniedException("denied"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        ErrorResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.error()).isEqualTo("UNAUTHORIZED");
        assertThat(body.message()).isEqualTo("Authentication required");
    }

    @Test
    @DisplayName("S162 unauthenticated context（無 Authentication）+ AccessDenied → 401 UNAUTHORIZED")
    void accessDeniedFromNullAuthReturns401() {
        // SecurityContext 沒設 authentication（@AfterEach clear 後預設狀態）
        ResponseEntity<ErrorResponse> response = handler.handleAccessDenied(
                new AccessDeniedException("denied"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().error()).isEqualTo("UNAUTHORIZED");
    }

    @Test
    @DisplayName("S162 authenticated user 缺 perm + AccessDenied → 403 ACCESS_DENIED")
    void accessDeniedFromAuthenticatedReturns403() {
        var auth = new UsernamePasswordAuthenticationToken("alice", "password",
                AuthorityUtils.createAuthorityList("ROLE_USER"));
        SecurityContextHolder.getContext().setAuthentication(auth);

        ResponseEntity<ErrorResponse> response = handler.handleAccessDenied(
                new AccessDeniedException("not allowed"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        ErrorResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.error()).isEqualTo("ACCESS_DENIED");
        assertThat(body.message()).isEqualTo("Access denied");
    }

    @Test
    @DisplayName("S162 AuthenticationException → 401 UNAUTHORIZED")
    void authenticationFailureReturns401() {
        ResponseEntity<ErrorResponse> response = handler.handleAuthentication(
                new BadCredentialsException("bad creds"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        ErrorResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.error()).isEqualTo("UNAUTHORIZED");
        assertThat(body.message()).isEqualTo("Authentication required");
    }

    // ─────────────────────────────────────────────────────────────
    // V03 ratchet — 補 high-impact handler unit tests（covered ratio 拉回）
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("SkillValidationException → 400 VALIDATION_ERROR + findings list")
    void skillValidationReturns400WithFindings() {
        var ex = new SkillValidationException("bad",
                List.of(new ValidationFinding("error", "rule-x", "scripts/x.sh:3", "msg")));
        ResponseEntity<ValidationErrorResponse> response = handler.handleSkillValidation(ex);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error()).isEqualTo("VALIDATION_ERROR");
        assertThat(response.getBody().findings()).hasSize(1);
    }

    @Test
    @DisplayName("IllegalArgumentException → 400 VALIDATION_ERROR")
    void illegalArgumentReturns400() {
        ResponseEntity<ErrorResponse> response = handler.handleValidationError(
                new IllegalArgumentException("bad input"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().error()).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    @DisplayName("NoSuchElementException → 404 NOT_FOUND")
    void noSuchElementReturns404() {
        ResponseEntity<ErrorResponse> response = handler.handleNotFound(
                new NoSuchElementException("not found"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().error()).isEqualTo("NOT_FOUND");
    }

    @Test
    @DisplayName("S029 SkillSuspendedException → 403 SKILL_SUSPENDED")
    void skillSuspendedReturns403() {
        ResponseEntity<ErrorResponse> response = handler.handleSuspended(
                new SkillSuspendedException("skill-1"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().error()).isEqualTo("SKILL_SUSPENDED");
    }

    @Test
    @DisplayName("S098e2 ReviewForbiddenException → 403 (error code = ex.getMessage())")
    void reviewForbiddenReturns403() {
        ResponseEntity<ErrorResponse> response = handler.handleReviewForbidden(
                new ReviewForbiddenException("not_review_author"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().error()).isEqualTo("not_review_author");
    }

    @Test
    @DisplayName("S098e3 InvalidStatusTransitionException → 400 INVALID_STATUS_TRANSITION")
    void invalidStatusTransitionReturns400() {
        ResponseEntity<ErrorResponse> response = handler.handleInvalidStatusTransition(
                new InvalidStatusTransitionException("bad transition"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().error()).isEqualTo("INVALID_STATUS_TRANSITION");
    }

    @Test
    @DisplayName("S098e3 FlagNotFoundException → 404 FLAG_NOT_FOUND")
    void flagNotFoundReturns404() {
        ResponseEntity<ErrorResponse> response = handler.handleFlagNotFound(
                new FlagNotFoundException("flag-1"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().error()).isEqualTo("FLAG_NOT_FOUND");
    }

    @Test
    @DisplayName("S096g2 RequestNotFoundException → 404 REQUEST_NOT_FOUND")
    void requestNotFoundReturns404() {
        ResponseEntity<ErrorResponse> response = handler.handleRequestNotFound(
                new RequestNotFoundException("req-1"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().error()).isEqualTo("REQUEST_NOT_FOUND");
    }

    @Test
    @DisplayName("S096g2 NotRequestClaimerException → 403 NOT_REQUEST_CLAIMER")
    void notRequestClaimerReturns403() {
        ResponseEntity<ErrorResponse> response = handler.handleNotClaimer(
                new NotRequestClaimerException());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().error()).isEqualTo("NOT_REQUEST_CLAIMER");
    }

    @Test
    @DisplayName("S096f2 CollectionNotFoundException → 404 COLLECTION_NOT_FOUND")
    void collectionNotFoundReturns404() {
        ResponseEntity<ErrorResponse> response = handler.handleCollectionNotFound(
                new CollectionNotFoundException("col-1"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().error()).isEqualTo("COLLECTION_NOT_FOUND");
    }

    @Test
    @DisplayName("S096f2 SkillNotPublishableException → 400 SKILL_NOT_PUBLISHABLE")
    void skillNotPublishableReturns400() {
        ResponseEntity<ErrorResponse> response = handler.handleSkillNotPublishable(
                new SkillNotPublishableException(List.of("skill-1")));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().error()).isEqualTo("SKILL_NOT_PUBLISHABLE");
    }

    @Test
    @DisplayName("S096h2 NotificationNotFoundException → 404 NOTIFICATION_NOT_FOUND")
    void notificationNotFoundReturns404() {
        ResponseEntity<ErrorResponse> response = handler.handleNotificationNotFound(
                new NotificationNotFoundException("notif-1"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().error()).isEqualTo("NOTIFICATION_NOT_FOUND");
    }

    @Test
    @DisplayName("S096h2 NotNotificationRecipientException → 403 NOT_NOTIFICATION_RECIPIENT")
    void notNotificationRecipientReturns403() {
        ResponseEntity<ErrorResponse> response = handler.handleNotNotificationRecipient(
                new NotNotificationRecipientException());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().error()).isEqualTo("NOT_NOTIFICATION_RECIPIENT");
    }

    @Test
    @DisplayName("S114a NotSkillOwnerException → 403 NOT_SKILL_OWNER")
    void notSkillOwnerReturns403() {
        ResponseEntity<ErrorResponse> response = handler.handleNotSkillOwner(
                new NotSkillOwnerException());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().error()).isEqualTo("NOT_SKILL_OWNER");
    }

    @Test
    @DisplayName("S114a OwnerAlreadyExistsException → 409 OWNER_ALREADY_EXISTS")
    void ownerAlreadyExistsReturns409() {
        ResponseEntity<ErrorResponse> response = handler.handleOwnerAlreadyExists(
                new OwnerAlreadyExistsException());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().error()).isEqualTo("OWNER_ALREADY_EXISTS");
    }

    @Test
    @DisplayName("S114a GrantNotFoundException → 404 GRANT_NOT_FOUND")
    void grantNotFoundReturns404() {
        ResponseEntity<ErrorResponse> response = handler.handleGrantNotFound(
                new GrantNotFoundException());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().error()).isEqualTo("GRANT_NOT_FOUND");
    }

    @Test
    @DisplayName("S114a CannotRevokeOwnOwnerException → 403 CANNOT_REVOKE_OWN_OWNER")
    void cannotRevokeOwnOwnerReturns403() {
        ResponseEntity<ErrorResponse> response = handler.handleCannotRevokeOwnOwner(
                new CannotRevokeOwnOwnerException());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().error()).isEqualTo("CANNOT_REVOKE_OWN_OWNER");
    }

    @Test
    @DisplayName("S142b SecurityNotScannedException → 404 SECURITY_NOT_SCANNED")
    void securityNotScannedReturns404() {
        ResponseEntity<ErrorResponse> response = handler.handleSecurityNotScanned(
                new SecurityNotScannedException("not scanned"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().error()).isEqualTo("SECURITY_NOT_SCANNED");
    }

    @Test
    @DisplayName("S135a QualityNotEvaluatedException → 404 QUALITY_NOT_EVALUATED")
    void qualityNotEvaluatedReturns404() {
        ResponseEntity<ErrorResponse> response = handler.handleNotEvaluated(
                new QualityNotEvaluatedException("pending"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().error()).isEqualTo("QUALITY_NOT_EVALUATED");
    }

    @Test
    @DisplayName("S098a3-2 BundleNotPublishedException → 404 BUNDLE_NOT_PUBLISHED")
    void bundleNotPublishedReturns404() {
        ResponseEntity<ErrorResponse> response = handler.handleBundleNotPublished(
                new BundleNotPublishedException("no published"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().error()).isEqualTo("BUNDLE_NOT_PUBLISHED");
    }

    @Test
    @DisplayName("S115 MissingJwtSubException → 401 invalid_token + WWW-Authenticate header")
    void missingJwtSubReturns401WithHeader() {
        ResponseEntity<ErrorResponse> response = handler.handleMissingJwtSub(
                new MissingJwtSubException());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().error()).isEqualTo("invalid_token");
        assertThat(response.getHeaders().getFirst("WWW-Authenticate"))
                .contains("Bearer error=\"invalid_token\"");
    }

    @Test
    @DisplayName("S037 MaxUploadSizeExceededException → 413 PAYLOAD_TOO_LARGE")
    void maxUploadSizeReturns413() {
        ResponseEntity<ErrorResponse> response = handler.handlePayloadTooLarge(
                new MaxUploadSizeExceededException(10 * 1024 * 1024));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(response.getBody().error()).isEqualTo("PAYLOAD_TOO_LARGE");
        assertThat(response.getBody().message()).contains("MB");
    }

    @Test
    @DisplayName("S080 MissingServletRequestParameterException → 400 VALIDATION_ERROR")
    void missingParamReturns400() {
        ResponseEntity<ErrorResponse> response = handler.handleMissingParam(
                new MissingServletRequestParameterException("foo", "String"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().error()).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    @DisplayName("S126 MethodArgumentTypeMismatchException → 400 VALIDATION_ERROR + 含 param name")
    void typeMismatchReturns400() {
        var ex = new MethodArgumentTypeMismatchException(
                "not-a-uuid", java.util.UUID.class, "id", null, new RuntimeException());
        ResponseEntity<ErrorResponse> response = handler.handleTypeMismatch(ex);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().error()).isEqualTo("VALIDATION_ERROR");
        assertThat(response.getBody().message()).contains("id");
    }

    @Test
    @DisplayName("S127 NoResourceFoundException → 404 NOT_FOUND")
    void noResourceFoundReturns404() {
        var ex = new NoResourceFoundException(
                org.springframework.http.HttpMethod.GET, "/some/path", "/some/path");
        ResponseEntity<ErrorResponse> response = handler.handleNoResourceFound(ex);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().error()).isEqualTo("NOT_FOUND");
    }

    @Test
    @DisplayName("S074 FileTooLargeException → 413 PAYLOAD_TOO_LARGE")
    void fileTooLargeReturns413() {
        ResponseEntity<ErrorResponse> response = handler.handleFileTooLarge(
                new FileTooLargeException("file.txt", 2_000_000L, 1_000_000L));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(response.getBody().error()).isEqualTo("PAYLOAD_TOO_LARGE");
    }

    @Test
    @DisplayName("S037 MultipartException → 400 MULTIPART_ERROR")
    void multipartParseErrorReturns400() {
        ResponseEntity<ErrorResponse> response = handler.handleMultipartParseError(
                new MultipartException("missing boundary"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().error()).isEqualTo("MULTIPART_ERROR");
    }

    @Test
    @DisplayName("S030 IllegalStateException → 409 STATE_CONFLICT")
    void illegalStateReturns409() {
        ResponseEntity<ErrorResponse> response = handler.handleStateConflict(
                new IllegalStateException("bad state"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().error()).isEqualTo("STATE_CONFLICT");
    }

    @Test
    @DisplayName("S052 HttpMessageNotReadableException → 400 INVALID_REQUEST_BODY (normalized message)")
    void unreadableBodyReturns400Normalized() {
        var input = new org.springframework.http.HttpInputMessage() {
            @Override public org.springframework.http.HttpHeaders getHeaders() {
                return new org.springframework.http.HttpHeaders();
            }
            @Override public java.io.InputStream getBody() {
                return new java.io.ByteArrayInputStream(new byte[0]);
            }
        };
        var ex = new HttpMessageNotReadableException(
                "JSON parse error: Unexpected character", input);
        ResponseEntity<ErrorResponse> response = handler.handleUnreadableBody(ex);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().error()).isEqualTo("INVALID_REQUEST_BODY");
        // Normalized: 不含 raw JSON parser detail
        assertThat(response.getBody().message()).isEqualTo("Request body is missing or malformed");
    }

    @Test
    @DisplayName("S057 DataIntegrityViolationException → 400 CONSTRAINT_VIOLATION (hides SQL detail)")
    void dataIntegrityReturns400Normalized() {
        ResponseEntity<ErrorResponse> response = handler.handleDataIntegrity(
                new DataIntegrityViolationException("ERROR: value too long for type varchar(255)"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().error()).isEqualTo("CONSTRAINT_VIOLATION");
        assertThat(response.getBody().message()).doesNotContain("varchar");
    }

    @Test
    @DisplayName("S051 DuplicateKeyException → 409 DUPLICATE_RESOURCE (hides SQL detail)")
    void duplicateKeyReturns409Normalized() {
        ResponseEntity<ErrorResponse> response = handler.handleDuplicateKey(
                new DuplicateKeyException("ERROR: duplicate key value violates unique constraint"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().error()).isEqualTo("DUPLICATE_RESOURCE");
        assertThat(response.getBody().message()).doesNotContain("unique constraint");
    }

    @Test
    @DisplayName("S049 ZipException → 400 VALIDATION_ERROR (hides internal detail)")
    void zipExceptionReturns400() {
        ResponseEntity<ErrorResponse> response = handler.handleInvalidZip(
                new ZipException("invalid stored block lengths"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().error()).isEqualTo("VALIDATION_ERROR");
        assertThat(response.getBody().message()).doesNotContain("stored block");
    }

    @Test
    @DisplayName("S045 HttpRequestMethodNotSupportedException → 405 METHOD_NOT_ALLOWED")
    void methodNotAllowedReturns405() {
        ResponseEntity<ErrorResponse> response = handler.handleMethodNotAllowed(
                new HttpRequestMethodNotSupportedException("DELETE"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(response.getBody().error()).isEqualTo("METHOD_NOT_ALLOWED");
        assertThat(response.getBody().message()).contains("DELETE");
    }

    @Test
    @DisplayName("S030 OptimisticLockingFailureException → 409 CONCURRENT_MODIFICATION")
    void optimisticLockReturns409() {
        ResponseEntity<ErrorResponse> response = handler.handleConcurrentModification(
                new OptimisticLockingFailureException("version conflict"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().error()).isEqualTo("CONCURRENT_MODIFICATION");
    }

    @Test
    @DisplayName("S159a UnknownQueryParamException → 400 VALIDATION_ERROR + 列出參數名")
    void unknownQueryParamReturns400WithParamNames() {
        var ex = new UnknownQueryParamException(java.util.Set.of("categroy", "fooBar"));

        ResponseEntity<ErrorResponse> response = handler.handleUnknownQueryParam(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        ErrorResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.error()).isEqualTo("VALIDATION_ERROR");
        assertThat(body.message())
                .contains("Unknown query parameter")
                .contains("categroy")
                .contains("fooBar");
    }

    @Test
    @DisplayName("S159d InvalidPageableException → 400 INVALID_PAGEABLE + 原訊息")
    void invalidPageableReturns400WithCode() {
        var ex = new InvalidPageableException("size must be <= 100");

        ResponseEntity<ErrorResponse> response = handler.handleInvalidPageable(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        ErrorResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.error()).isEqualTo("INVALID_PAGEABLE");
        assertThat(body.message()).isEqualTo("size must be <= 100");
    }
}
