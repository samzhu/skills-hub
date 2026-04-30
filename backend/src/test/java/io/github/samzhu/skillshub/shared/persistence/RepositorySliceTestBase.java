package io.github.samzhu.skillshub.shared.persistence;

import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jdbc.test.autoconfigure.DataJdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.github.samzhu.skillshub.TestcontainersConfiguration;

/**
 * S025b — {@code @DataJdbcTest} slice 共用 base class，收斂 13 個 REPO test 為單一
 * Spring TestContext cache key（per spec §2.2 / §4.1）。
 *
 * <p><b>設計理由</b>（per spec §2.4 根因分析 + T01 POC findings）：
 * <ol>
 *   <li>Spring Boot 4.0.6 預設 {@code @AutoConfigureTestDatabase(replace = NON_TEST)} —
 *       {@code @ServiceConnection} 容器自動 detected 為 test database；不需顯式
 *       {@code replace = NONE}（SB3 才需要）。</li>
 *   <li>Flyway 在 {@code @DataJdbcTest} slice 預設啟用（via
 *       {@code spring-boot-flyway} jar 的 {@code AutoConfigureDataSourceInitialization.imports}
 *       註冊 {@code FlywayAutoConfiguration}）— V1-V6 migrations 自動 run 對 Testcontainers PostgreSQL。</li>
 *   <li>{@code AbstractJdbcConfiguration} 子類（Skills Hub 的 {@link
 *       io.github.samzhu.skillshub.shared.persistence.JdbcConfiguration}）由
 *       {@code DataJdbcTypeExcludeFilter.KNOWN_INCLUDES = Set.of(AbstractJdbcConfiguration.class)}
 *       自動 picked up — 不需 {@code @Import(JdbcConfiguration.class)}；自訂 converters
 *       （{@code MapJsonbConverter}、{@code StringListJsonbConverter} 等）自動可用。</li>
 *   <li><b>Spring Modulith AOT blocker — 雙重 fix</b>（POC 揭露 spec §2.4 hypothesis 不完整）：
 *       <ol type="a">
 *         <li>{@code @TestPropertySource("management.tracing.enabled=false")} 解第一條 path —
 *             {@code ModuleObservabilityAutoConfiguration}（由 {@code spring-modulith-observability}
 *             runtimeOnly jar 的獨立 {@code AutoConfiguration.imports} 載入）class-level
 *             {@code @ConditionalOnProperty(name = "management.tracing.enabled"
 *             havingValue = "true" matchIfMissing = true)} 設 false 後整個 class 不啟用 →
 *             不註冊 tracing bean post processor / listener → 不需要 {@code ApplicationModulesRuntime}。</li>
 *         <li>{@code @ImportAutoConfiguration} (bare) +
 *             {@code META-INF/spring/io.github.samzhu.skillshub.shared.persistence.RepositorySliceTestBase.imports}
 *             解第二條 path — {@code spring-modulith-runtime} 透過
 *             {@code META-INF/spring/aot.factories} **classpath-level** 註冊
 *             {@code ApplicationModulesFileGeneratingProcessor}（{@code BeanFactoryInitializationAotProcessor}），
 *             非 auto-config，無屬性可關；其 {@code processAheadOfTime} contribution 對
 *             {@code ApplicationModulesRuntime} bean 為 hard dep。Slice 不在 {@code @DataJdbcTest}
 *             whitelist 故預設不載 {@code SpringModulithRuntimeAutoConfiguration}（package-private
 *             無法 class-reference）。
 *
 *             <p>解法：bare {@code @ImportAutoConfiguration} 觸發 Spring TestContext 從
 *             同名 {@code .imports} resource file 讀 FQN 字串 list（package-private OK，
 *             由 {@code ImportCandidates.load()} reflection bypass class visibility），
 *             把 {@code SpringModulithRuntimeAutoConfiguration} +
 *             {@code JacksonAutoConfiguration}（{@code JdbcConfiguration} ctor inject
 *             {@code ObjectMapper}）一併帶進 slice。</li>
 *       </ol>
 *
 *       <p>取代既有 (S014-T1) Javadoc 標註的 "@DataJdbcTest slice 不可用" 過時敘述。</li>
 *   <li>所有 REPO slice test {@code extends} 此 base = 共用同一 {@code MergedContextConfiguration}
 *       customizer set = 共用同一 Spring TestContext cache entry = 共用同一 PostgreSQL container。
 *       對齊 S025a {@link io.github.samzhu.skillshub.shared.security.LabModeTestBase} pattern。</li>
 *   <li><b>{@code @Transactional(propagation = NOT_SUPPORTED)}</b> 反 {@code @DataJdbcTest}
 *       預設 meta-annotated {@code @Transactional}（auto-wrap test method 為 TX）— REPO slice
 *       test 對齊 S025a {@code @SpringBootTest} 既有語意（test 自管 TX、helper 自 commit）；
 *       理由：(a) 既有 test 使用 {@code S024CrossAggregateSaveHelper} 等 {@code @Transactional}
 *       helper 顯式 commit + outbox INSERT；(b) {@code @ApplicationModuleListener} AFTER_COMMIT
 *       async listener 必須 TX commit 才觸發；(c) {@code DataIntegrityViolationException} 後
 *       PSQL connection abort，後續 SQL 在 outer test TX 內全失敗（25P02），需各 helper 各自
 *       TX 隔離；(d) {@code @BeforeEach DELETE FROM ...} cleanup 需 commit 才對下個 test 可見。</li>
 * </ol>
 *
 * <p><b>使用範例</b>：
 * <pre>{@code
 * class SkillVersionRepositoryTest extends RepositorySliceTestBase {
 *     @Autowired private SkillVersionRepository repo;
 *
 *     @Test
 *     void saveAndFind() {
 *         // ...
 *     }
 * }
 * }</pre>
 *
 * <p><b>注意</b>：service test（注入 {@code @Service} bean）需在子類加
 * {@code @Import(TheService.class)} — {@code @DataJdbcTest} 預設只掃 {@code @Repository} +
 * Spring Data infrastructure，不掃 {@code @Service}。
 *
 * @see io.github.samzhu.skillshub.shared.security.LabModeTestBase S025a base class precedent
 * @see io.github.samzhu.skillshub.TestcontainersConfiguration 共用 Testcontainers + mock + ScenarioCustomizer
 * @see <a href="https://github.com/spring-projects/spring-modulith/blob/2.0.6/spring-modulith-observability/src/main/java/org/springframework/modulith/observability/autoconfigure/ModuleObservabilityAutoConfiguration.java">ModuleObservabilityAutoConfiguration source (Modulith 2.0.6)</a>
 * @see <a href="https://docs.spring.io/spring-boot/api/java/org/springframework/boot/data/jdbc/test/autoconfigure/DataJdbcTest.html">@DataJdbcTest Javadoc (Spring Boot 4.0.6)</a>
 * @see <a href="https://docs.spring.io/spring-boot/api/java/org/springframework/boot/autoconfigure/ImportAutoConfiguration.html">@ImportAutoConfiguration — file-based loading via META-INF/spring/&lt;class&gt;.imports</a>
 */
@DataJdbcTest
@ImportAutoConfiguration
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = "management.tracing.enabled=false")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public abstract class RepositorySliceTestBase {
}
