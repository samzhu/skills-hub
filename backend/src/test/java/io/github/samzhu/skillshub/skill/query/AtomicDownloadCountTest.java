package io.github.samzhu.skillshub.skill.query;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
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
import io.github.samzhu.skillshub.skill.domain.SkillDownloadedEvent;

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

	@Autowired
	private SkillProjection projection;

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
				now));

		var executor = Executors.newFixedThreadPool(10);
		var latch = new CountDownLatch(100);

		for (int i = 0; i < 100; i++) {
			executor.submit(() -> {
				try {
					projection.on(new SkillDownloadedEvent(skillId, "1.0.0"));
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
