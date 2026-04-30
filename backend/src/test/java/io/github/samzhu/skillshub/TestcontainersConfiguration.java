package io.github.samzhu.skillshub;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Random;

import org.mockito.Mockito;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.modulith.test.ScenarioCustomizer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import io.github.samzhu.skillshub.storage.StorageService;

/**
 * 共用 test infrastructure — Testcontainers + 共用 mock + Modulith Scenario default。
 *
 * <p>所有 integration test 透過 {@code @Import(TestcontainersConfiguration.class)} 引用：
 * <ul>
 *   <li>{@link PostgreSQLContainer} — pgvector/pg16；{@link ServiceConnection} 自動注入 datasource</li>
 *   <li>{@link InMemoryStorageService} — {@link Primary} 覆蓋 {@code @Profile("local") FileSystemStorageService}</li>
 *   <li>{@code mockEmbeddingModel}（S025a-T01）— {@link Primary} {@link EmbeddingModel} mock；
 *       消除 8 處 {@code @MockitoBean EmbeddingModel} 散佈造成的 TestContext cache key 變異</li>
 *   <li>{@code scenarioTimeout}（S025a-T01）— {@link ScenarioCustomizer} global default 5s；
 *       取代 S023-T07 30s {@code Awaitility} band-aid（per S023 spec §7.7）</li>
 * </ul>
 *
 * <p><b>Cache key 收斂原理</b>（per S025a §2.3）：{@code @MockitoBean} 透過
 * {@code BeanOverrideContextCustomizer.handlers} Set 進入 cache key；不同 test class 的同名 mock
 * field 因 reflection {@code Field} 物件不等 → customizer 不等 → cache key 不等。lift 至此處
 * 為共用 {@link Bean} 後，customizer Set 各少一個 handler → 多個 file 收斂同 cache entry，
 * 共用同一 Spring context = 同一 container，避免 LRU evict 與 container churn。
 *
 * @see io.github.samzhu.skillshub.audit.AuditEventListenerTest S025a-T01 POC pilot 範例
 * @see <a href="https://raw.githubusercontent.com/spring-projects/spring-framework/v7.0.6/spring-test/src/main/java/org/springframework/test/context/bean/override/BeanOverrideContextCustomizer.java">Spring TestContext BeanOverrideContextCustomizer source</a>
 * @see <a href="https://raw.githubusercontent.com/spring-projects/spring-modulith/2.0.6/spring-modulith-test/src/main/java/org/springframework/modulith/test/ScenarioCustomizer.java">Spring Modulith ScenarioCustomizer source</a>
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	private static final int EMBEDDING_DIMENSIONS = 768;

	// S014: PostgreSQL 16 + pgvector — 與本機 compose pgvector/pgvector:pg16 對齊；
	// asCompatibleSubstituteFor("postgres") 告訴 Testcontainers 此 image 為 postgres-compatible。
	// @ServiceConnection 自動注入連線到 spring.datasource.{url,username,password}。
	// 官方 pattern：container lifecycle 綁定 Spring context；跨 test class 共用靠 context cache
	// 命中（同 cache key 的 test 自然共用同一個 context = 同一個 container）。
	// S025a-T01 已落地 mockEmbeddingModel + scenarioTimeout 兩個共用 bean，cache key 收斂中；
	// build.gradle.kts 的 maxHeapSize=3g + cache.maxSize=8 workaround 將於 S025b 移除。
	@Bean
	@ServiceConnection
	PostgreSQLContainer<?> pgvectorContainer() {
		return new PostgreSQLContainer<>(
				DockerImageName.parse("pgvector/pgvector:pg16")
						.asCompatibleSubstituteFor("postgres"))
				.withDatabaseName("test")
				.withUsername("test")
				.withPassword("test");
	}

	// @Bean
	// @ServiceConnection
	// LgtmStackContainer grafanaLgtmContainer() {
	// 	return new LgtmStackContainer(DockerImageName.parse("grafana/otel-lgtm:latest"));
	// }

	// @Primary 確保測試環境中 InMemoryStorageService 優先於 @Profile("local") 的 FileSystemStorageService
	@Bean
	@Primary
	StorageService storageService() {
		return new InMemoryStorageService();
	}

	/**
	 * S025a-T01 — Lift {@link EmbeddingModel} mock 為共用 {@link Primary} bean。
	 *
	 * <p>取代散佈在 8 個 file 的 {@code @MockitoBean EmbeddingModel}（per S025a inventory：
	 * SemanticSearchAclTest / SemanticSearchIntegrationTest / PgVectorStoreOwnerWriteTest /
	 * SkillshubPgVectorStoreAclTest / SkillshubPgVectorStoreAclSearchTest /
	 * S016EndToEndSmokeTest / SearchProjectionAclWriteTest / SearchProjectionTest）。
	 * 8 處 stub 邏輯**完全相同** — 三個 overload 皆回 {@code randomVector(768)}。
	 *
	 * <p>同 query/document 不保證同向量（每次呼叫獨立 random）— 對 cosine similarity 測試
	 * 場景需要「同向量 ≈ 1.0 > threshold」的測試，{@link SemanticSearchAclTest} 等已透過
	 * vector 寫入 {@code vector_store} 後 search 走相同 query embedding 路徑（同次 mock 呼叫
	 * 序列），此 stub 行為與原各 file 既有 stub 完全等價。
	 *
	 * <p><b>注意</b>：{@code @Primary} 在 {@code @MockitoBean} 下會被 Mockito bean override
	 * 機制覆蓋（{@code @MockitoBean} 直接替換 bean instance）；T03 將移除全部 8 處
	 * {@code @MockitoBean} 後此 bean 才真正被各 test 使用。T01 階段並存無衝突。
	 */
	@Bean
	@Primary
	EmbeddingModel mockEmbeddingModel() {
		EmbeddingModel mock = Mockito.mock(EmbeddingModel.class);
		when(mock.embed(any(Document.class))).thenAnswer(inv -> randomVector(EMBEDDING_DIMENSIONS));
		when(mock.embed(anyString())).thenAnswer(inv -> randomVector(EMBEDDING_DIMENSIONS));
		when(mock.embed(any(List.class), any(), any())).thenAnswer(inv -> {
			List<?> docs = inv.getArgument(0);
			return docs.stream().map(d -> randomVector(EMBEDDING_DIMENSIONS)).toList();
		});
		return mock;
	}

	/**
	 * S025a-T01 — Modulith {@link ScenarioCustomizer} global default Awaitility timeout = 5s。
	 *
	 * <p>取代 S023-T07 落地的 30s timeout band-aid（per S023 §7.7）。30s 為 cache key 爆炸 +
	 * container churn 時的 timing race 補丁，非 listener 本質慢；S025a 收斂 cache key 後
	 * async listener latency 自然回到亞秒級。Per Modulith
	 * {@code ScenarioParameterResolver}（line 56-70）：此 bean 自動 pickup 為 Scenario default。
	 *
	 * <p>個別 test 仍可用 {@code .andWaitAtMost(Duration.ofSeconds(N))} 顯式 override —
	 * 範例見 {@link io.github.samzhu.skillshub.security.RiskAssessmentIntegrationTest}（T02）：
	 * ScanOrchestrator 完整 SARIF pipeline 估 > 5s，需 override 至 15s。
	 */
	@Bean
	ScenarioCustomizer scenarioTimeout() {
		return (method, ctx) -> factory -> factory.atMost(Duration.ofSeconds(5));
	}

	/**
	 * 固定 seed 確保每次 {@code randomVector(768)} 呼叫回傳**相同**向量 — 必要性：
	 * SemanticSearch tests 依賴 doc/query 共用同一向量 → cosine similarity = 1.0 > 0.3 threshold
	 * → 任意 query 必命中 seed 的 doc。若用 {@code new Random()}（無 seed）則 doc/query 不同向量
	 * → cosine ≈ 0（768 維 uniform random）→ search 回空 → test 失敗。
	 *
	 * <p>各 file 既有 stub 多用 {@code new Random(42)}（per SearchProjectionAclWriteTest L95、
	 * SemanticSearchAclTest 等）— lift 後在此處集中設 seed 42 對齊原行為。
	 */
	private static float[] randomVector(int dim) {
		var rnd = new Random(42);
		float[] v = new float[dim];
		for (int i = 0; i < dim; i++) {
			v[i] = rnd.nextFloat();
		}
		return v;
	}

	static class InMemoryStorageService implements StorageService {
		private final java.util.Map<String, byte[]> store = new java.util.concurrent.ConcurrentHashMap<>();

		@Override
		public void upload(String path, byte[] data) {
			store.put(path, data);
		}

		@Override
		public byte[] download(String path) {
			var data = store.get(path);
			if (data == null) throw new RuntimeException("Not found: " + path);
			return data;
		}

		@Override
		public void delete(String path) {
			store.remove(path);
		}
	}

}
