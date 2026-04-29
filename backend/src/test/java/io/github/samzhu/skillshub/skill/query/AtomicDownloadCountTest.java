package io.github.samzhu.skillshub.skill.query;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import io.github.samzhu.skillshub.TestcontainersConfiguration;

/**
 * S014 AC-6 — atomic download_count 並發更新驗證。
 *
 * <p>修正既有 Mongo 上的 read-modify-write race condition：
 * SkillProjection 改用 {@link SkillReadModelRepository#incrementDownloadCount}
 * （@Modifying @Query），由 PostgreSQL row-level lock 保證原子性。
 *
 * <p>100 次並發 SkillDownloadedEvent → download_count 必定為 100（不丟失任何更新）。
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AtomicDownloadCountTest {

	@Autowired
	private SkillReadModelRepository repo;

	// S023: removed @Autowired SkillProjection — test changed to call repo.incrementDownloadCount directly
	// (詳見 60 行附近註解)

	@Test
	@DisplayName("AC-6: 100 次並發 SkillDownloadedEvent 累加為 100（atomic UPDATE 防 race condition）")
	@Tag("AC-6")
	void concurrentIncrement_noLostUpdate() throws Exception {
		var skillId = UUID.randomUUID().toString();
		var now = Instant.now();
		repo.save(new SkillReadModel(
				skillId,
				"atomic-test-skill-" + skillId.substring(0, 8),
				"测试 atomic increment",
				"tester",
				"Testing",
				"1.0.0",
				"LOW",
				"PUBLISHED",
				0L, // 起始 downloadCount = 0
				now,
				now,
				List.of())); // S016: aclEntries — 本測試不關心 ACL，留空

		// S023 update：原測試呼叫 projection.on(event) 100 次驗 atomic UPDATE。
		// listener 在 S023 改 @ApplicationModuleListener（@Async）後，proxy 直接呼叫變
		// async 提交 — latch 在 async 工作完成前 release，引發 false negative。
		// 此 test 真正關注點是「PostgreSQL atomic UPDATE 不丟失更新」（SQL row-level lock），
		// 與 listener 同步/異步無關 — 改打 repo.incrementDownloadCount 直接驗 SQL atomicity。
		var now2 = Instant.now();
		var executor = Executors.newFixedThreadPool(10);
		var latch = new CountDownLatch(100);

		for (int i = 0; i < 100; i++) {
			executor.submit(() -> {
				try {
					repo.incrementDownloadCount(skillId, now2);
				} finally {
					latch.countDown();
				}
			});
		}
		boolean done = latch.await(30, TimeUnit.SECONDS);
		executor.shutdown();
		assertThat(done).as("並發任務應在 30 秒內完成").isTrue();

		var finalSkill = repo.findById(skillId).orElseThrow();
		assertThat(finalSkill.downloadCount())
				.as("100 次並發 increment，PostgreSQL atomic UPDATE 不可丟失任何更新")
				.isEqualTo(100L);
	}
}
