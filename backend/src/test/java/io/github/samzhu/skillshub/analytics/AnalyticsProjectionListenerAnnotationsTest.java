package io.github.samzhu.skillshub.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.event.EventListener;
import org.springframework.modulith.events.ApplicationModuleListener;

import io.github.samzhu.skillshub.skill.domain.SkillDownloadedEvent;

/**
 * S023-T03 — 驗證 {@link AnalyticsProjection#on(SkillDownloadedEvent)} 已升級為
 * {@link ApplicationModuleListener}（per S023 spec §2.2）。
 */
class AnalyticsProjectionListenerAnnotationsTest {

	@Test
	@DisplayName("AC-4: AnalyticsProjection.on(SkillDownloadedEvent) 為 @ApplicationModuleListener")
	@Tag("AC-4")
	void onMethod_isApplicationModuleListener() {
		Method method = Arrays.stream(AnalyticsProjection.class.getDeclaredMethods())
				.filter(m -> m.getName().equals("on"))
				.filter(m -> m.getParameterCount() == 1
						&& m.getParameterTypes()[0].equals(SkillDownloadedEvent.class))
				.findFirst()
				.orElseThrow(() -> new AssertionError(
						"AnalyticsProjection.on(SkillDownloadedEvent) not found"));

		assertThat(method.isAnnotationPresent(ApplicationModuleListener.class)).isTrue();
		assertThat(method.isAnnotationPresent(EventListener.class)).isFalse();
	}
}
