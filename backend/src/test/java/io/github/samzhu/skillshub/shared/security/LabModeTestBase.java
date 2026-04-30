package io.github.samzhu.skillshub.shared.security;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import io.github.samzhu.skillshub.TestcontainersConfiguration;

/**
 * S025a-T04 — LabMode 測試共用 base class（cache key 收斂）。
 *
 * <p>3 個 LabMode test（{@code LabModeMeControllerTest} / {@code LabModeAdminControllerTest}
 * / {@code JwtDecoderConditionalTest}）各自宣告相同 {@code @TestPropertySource(properties =
 * "skillshub.security.oauth.enabled=false")} 造成 Spring TestContext cache 中 3 個 customizer
 * 變異 → 3 個 distinct context entry。
 *
 * <p><b>收斂機制</b>（per S025a §2.5 research — Spring Framework 7.x TestContext caching
 * docs）：當多個 test class extends 相同 base class，且 base class 持有共同 annotations
 * （{@code @SpringBootTest} / {@code @Import} / {@code @TestPropertySource} / profile），
 * 子類的 {@code MergedContextConfiguration} 從 base 繼承這些屬性 → equals/hashCode 一致 →
 * **共用同一 cache entry = 同一 Spring context = 同一 PostgreSQL container**。
 *
 * <p>{@code @AutoConfigureMockMvc} 對 {@code JwtDecoderConditionalTest}（不用 MockMvc）為冗餘，
 * 但加入 base 後讓 3 個 test 共用同一 cache key 的收益遠大於該 customizer 成本。
 *
 * <p><b>新增 LabMode test 規則</b>：直接 extends 此 base class，不要重複宣告
 * {@code @TestPropertySource} / {@code @Import(TestcontainersConfiguration.class)}。
 * 若需額外 property，於子類加 {@code @TestPropertySource} 並用 {@code mergeMode = MERGE_WITH_DEFAULTS}。
 *
 * @see <a href="https://docs.spring.io/spring-framework/reference/testing/testcontext-framework/ctx-management/caching.html">Spring TestContext Caching</a>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = "skillshub.security.oauth.enabled=false")
public abstract class LabModeTestBase {
}
