package io.github.samzhu.skillshub.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.text.ParseException;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

/**
 * T2 / AC-1, AC-2, AC-3 — End-to-end 整合測試：以 Testcontainers 啟動真實的
 * navikt/mock-oauth2-server 容器，掛載與 docker-compose 相同的 {@code config/oauth-mock-config.json}，
 * 真打 OIDC discovery 與 token endpoint，並用 Nimbus JOSE+JWT 解碼 access_token 驗證 claims。
 *
 * <p>本測試**不啟動 Spring 應用**（無 {@code @SpringBootTest}）— 只驗 mock-oauth2-server 容器
 * 本身的行為（well-known 可達、JSON_CONFIG 三組 client_id mapping 正確）。Spring Security 端
 * 的整合（{@code JwtAuthenticationConverter} 把 {@code roles} claim 映射為 GrantedAuthority、
 * SecurityFilterChain 規則）由 T1 的 MockMvc 單元測試（AC-4~AC-8）涵蓋。
 *
 * <p>JWT 簽章驗證刻意 NOT 進行——本測試只驗 claim 內容；簽章在生產路徑由 Spring Security 的
 * {@code SupplierJwtDecoder} + {@code NimbusJwtDecoder} 處理。
 */
@Testcontainers
class OAuthMockE2ETest {

    @Container
    @SuppressWarnings("resource") // Testcontainers manages container lifecycle
    private static final GenericContainer<?> mockOauth =
        new GenericContainer<>(DockerImageName.parse("ghcr.io/navikt/mock-oauth2-server:3.0.1"))
            .withExposedPorts(8080)
            // 掛入與 docker-compose 完全相同的設定檔；Gradle 工作目錄是 backend/
            // 所以相對路徑 config/oauth-mock-config.json 直接命中
            .withCopyFileToContainer(
                MountableFile.forHostPath(Path.of("config/oauth-mock-config.json")),
                "/app/config.json")
            .withEnv("JSON_CONFIG_PATH", "/app/config.json")
            .withEnv("LOG_LEVEL", "INFO")
            .waitingFor(Wait.forHttp("/skills-hub-dev/.well-known/openid-configuration")
                .forStatusCode(200));

    private static String baseUrl() {
        return "http://localhost:" + mockOauth.getMappedPort(8080) + "/skills-hub-dev";
    }

    @Test
    @Tag("AC-1")
    @DisplayName("AC-1: bootRun 帶起的 mock-oauth2-server 提供合法 OIDC discovery")
    @SuppressWarnings("unchecked")
    void wellKnownEndpoint_returnsOidcDiscovery() {
        var discovery = RestClient.create()
            .get()
            .uri(baseUrl() + "/.well-known/openid-configuration")
            .retrieve()
            .body(Map.class);

        assertThat(discovery).isNotNull();
        // issuer 必須等於 base URL（mock 自動以收到的 host:port 計算）
        assertThat(discovery.get("issuer")).isEqualTo(baseUrl());
        // 三個必要端點：jwks_uri 用於簽章驗證、token_endpoint 給 client、authorization_endpoint 給瀏覽器流程
        assertThat(discovery.get("jwks_uri")).isNotNull();
        assertThat(discovery.get("token_endpoint")).isNotNull();
        assertThat(discovery.get("authorization_endpoint")).isNotNull();
    }

    @Test
    @Tag("AC-2")
    @DisplayName("AC-2: admin-client client_credentials 取得帶完整 claims 的 JWT")
    void clientCredentials_admin_yieldsAdminClaims() throws ParseException {
        var claims = fetchTokenClaims("admin-client");

        assertThat(claims.getSubject()).isEqualTo("admin-001");
        assertThat(claims.getStringListClaim("roles")).containsExactly("admin");
        assertThat(claims.getStringListClaim("groups"))
            .containsExactlyInAnyOrder("platform-admins", "skills-curators");
        assertThat(claims.getStringClaim("company_id")).isEqualTo("skills-hub-corp");
        assertThat(claims.getStringClaim("dept_id")).isEqualTo("engineering");
        assertThat(claims.getStringClaim("scope"))
            .isEqualTo("skills:admin skills:read skills:write");
        // iss 必須與 well-known 的 issuer 一致（讓 Spring Security 驗 iss claim 通過）
        assertThat(claims.getIssuer()).isEqualTo(baseUrl());
    }

    @ParameterizedTest(name = "AC-3 [{index}]: {0} → roles={1}, dept={2}, group={3}")
    @CsvSource({
        "developer-client, developer, engineering, skill-authors",
        "viewer-client,    viewer,    marketing,   readers"
    })
    @Tag("AC-3")
    @DisplayName("AC-3: developer / viewer client_credentials 取得各自身分的 JWT")
    void clientCredentials_otherIdentities_yieldExpectedClaims(
            String clientId, String expectedRole, String expectedDept, String expectedGroup)
            throws ParseException {
        var claims = fetchTokenClaims(clientId);

        assertThat(claims.getStringListClaim("roles")).containsExactly(expectedRole);
        assertThat(claims.getStringClaim("dept_id")).isEqualTo(expectedDept);
        assertThat(claims.getStringListClaim("groups")).containsExactly(expectedGroup);
    }

    /**
     * Helper — 用 client_credentials grant 換 token，回傳 Nimbus 解析後的 claims。
     * 不驗簽章（簽章驗證是 Spring Security 在 prod 路徑做的，本測試只驗 claim 內容）。
     */
    @SuppressWarnings("unchecked")
    private static JWTClaimsSet fetchTokenClaims(String clientId) throws ParseException {
        // RFC 6749 client_credentials grant — client_secret 在 mock 不驗，但欄位需出現
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", clientId);
        form.add("client_secret", "secret");
        form.add("scope", "skills:read");

        var response = RestClient.create()
            .post()
            .uri(baseUrl() + "/token")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(form)
            .retrieve()
            .body(Map.class);

        assertThat(response).as("token response").isNotNull();
        var accessToken = (String) response.get("access_token");
        assertThat(accessToken).as("access_token in response").isNotBlank();

        return SignedJWT.parse(accessToken).getJWTClaimsSet();
    }
}
