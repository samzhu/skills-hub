package io.github.samzhu.skillshub.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.event.EventListener;
import org.springframework.modulith.events.ApplicationModuleListener;

import io.github.samzhu.skillshub.skill.domain.SkillCreatedEvent;
import io.github.samzhu.skillshub.skill.domain.SkillVersionPublishedEvent;

/**
 * S023-T03 — 驗證 {@link SearchProjection} 的兩個 listener 已升級為
 * {@link ApplicationModuleListener}（per S023 spec §2.2）。
 */
class SearchProjectionListenerAnnotationsTest {

	@Test
	@DisplayName("AC-4: SearchProjection.onSkillCreated + onVersionPublished 升 @ApplicationModuleListener")
	@Tag("AC-4")
	void bothListenerMethods_areApplicationModuleListener() {
		var pairs = List.of(
				new Object[] { "onSkillCreated", SkillCreatedEvent.class },
				new Object[] { "onVersionPublished", SkillVersionPublishedEvent.class });

		for (var pair : pairs) {
			String name = (String) pair[0];
			Class<?> eventType = (Class<?>) pair[1];

			Method method = Arrays.stream(SearchProjection.class.getDeclaredMethods())
					.filter(m -> m.getName().equals(name))
					.filter(m -> m.getParameterCount() == 1
							&& m.getParameterTypes()[0].equals(eventType))
					.findFirst()
					.orElseThrow(() -> new AssertionError(
							"SearchProjection." + name + "(" + eventType.getSimpleName() + ") not found"));

			assertThat(method.isAnnotationPresent(ApplicationModuleListener.class))
					.as("%s should be @ApplicationModuleListener", name).isTrue();
			assertThat(method.isAnnotationPresent(EventListener.class))
					.as("%s should not have @EventListener", name).isFalse();
		}
	}
}
