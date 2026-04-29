package io.github.samzhu.skillshub.security.scan;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.event.EventListener;
import org.springframework.modulith.events.ApplicationModuleListener;

import io.github.samzhu.skillshub.skill.domain.SkillVersionPublishedEvent;

/**
 * S023-T04 — 驗證 {@link ScanOrchestrator#on(SkillVersionPublishedEvent)} 已升級為
 * {@link ApplicationModuleListener}（per S023 spec §2.2 hybrid migration last 1/9）。
 */
class ScanOrchestratorListenerAnnotationsTest {

	@Test
	@DisplayName("AC-4: ScanOrchestrator.on(SkillVersionPublishedEvent) 為 @ApplicationModuleListener")
	@Tag("AC-4")
	void onMethod_isApplicationModuleListener() {
		Method method = Arrays.stream(ScanOrchestrator.class.getDeclaredMethods())
				.filter(m -> m.getName().equals("on"))
				.filter(m -> m.getParameterCount() == 1
						&& m.getParameterTypes()[0].equals(SkillVersionPublishedEvent.class))
				.findFirst()
				.orElseThrow(() -> new AssertionError(
						"ScanOrchestrator.on(SkillVersionPublishedEvent) not found"));

		assertThat(method.isAnnotationPresent(ApplicationModuleListener.class)).isTrue();
		assertThat(method.isAnnotationPresent(EventListener.class)).isFalse();
	}
}
