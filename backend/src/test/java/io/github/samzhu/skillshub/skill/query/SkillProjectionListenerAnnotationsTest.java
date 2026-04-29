package io.github.samzhu.skillshub.skill.query;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.event.EventListener;
import org.springframework.modulith.events.ApplicationModuleListener;

import io.github.samzhu.skillshub.skill.domain.SkillAclGrantedEvent;
import io.github.samzhu.skillshub.skill.domain.SkillAclRevokedEvent;
import io.github.samzhu.skillshub.skill.domain.SkillCreatedEvent;
import io.github.samzhu.skillshub.skill.domain.SkillDownloadedEvent;
import io.github.samzhu.skillshub.skill.domain.SkillReactivatedEvent;
import io.github.samzhu.skillshub.skill.domain.SkillSuspendedEvent;
import io.github.samzhu.skillshub.skill.domain.SkillVersionPublishedEvent;

/**
 * S023-T02 — 驗證 SkillProjection 的 hybrid listener migration（per S023 spec §2.2）。
 *
 * <p>用 reflection 檢查 7 個 {@code on(*)} method 的 annotation：
 * <ul>
 *   <li>2 個 FK target row 創建者保留 {@code @EventListener}（sync, in-TX）：
 *       {@link SkillCreatedEvent}、{@link SkillVersionPublishedEvent}</li>
 *   <li>5 個非 FK-creating handlers 升級為 {@code @ApplicationModuleListener}（async,
 *       AFTER_COMMIT, REQUIRES_NEW, outbox 追蹤）：
 *       {@link SkillDownloadedEvent}、{@link SkillAclGrantedEvent}、
 *       {@link SkillAclRevokedEvent}、{@link SkillSuspendedEvent}、
 *       {@link SkillReactivatedEvent}</li>
 * </ul>
 *
 * <p>純 reflection test — 不啟動 Spring context；快速驗證 spec AC-4 partial 5 of 9 的
 * SkillProjection 部分。剩餘 4 個 listener（AnalyticsProjection.on 1 個 + SearchProjection.on 2 個
 * + ScanOrchestrator.on 1 個）由 T03 / T04 處理。
 */
class SkillProjectionListenerAnnotationsTest {

    private Method findMethod(Class<?> eventType) {
        return Arrays.stream(SkillProjection.class.getDeclaredMethods())
                .filter(m -> m.getName().equals("on"))
                .filter(m -> m.getParameterCount() == 1 && m.getParameterTypes()[0].equals(eventType))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "SkillProjection.on(" + eventType.getSimpleName() + ") not found"));
    }

    @Test
    @DisplayName("AC-4: SkillCreated handler 保留 @EventListener (FK target row 創建者)")
    @Tag("AC-4")
    void skillCreatedHandler_keepsEventListener() {
        var method = findMethod(SkillCreatedEvent.class);

        assertThat(method.isAnnotationPresent(EventListener.class)).isTrue();
        assertThat(method.isAnnotationPresent(ApplicationModuleListener.class)).isFalse();
    }

    @Test
    @DisplayName("AC-4: SkillVersionPublished handler 保留 @EventListener (FK target row 創建者)")
    @Tag("AC-4")
    void skillVersionPublishedHandler_keepsEventListener() {
        var method = findMethod(SkillVersionPublishedEvent.class);

        assertThat(method.isAnnotationPresent(EventListener.class)).isTrue();
        assertThat(method.isAnnotationPresent(ApplicationModuleListener.class)).isFalse();
    }

    @Test
    @DisplayName("AC-4: 5 個非 FK-creating handlers 升級為 @ApplicationModuleListener")
    @Tag("AC-4")
    void nonFkHandlers_migratedToApplicationModuleListener() {
        var migratedEvents = List.of(
                SkillDownloadedEvent.class,
                SkillAclGrantedEvent.class,
                SkillAclRevokedEvent.class,
                SkillSuspendedEvent.class,
                SkillReactivatedEvent.class);

        for (var eventType : migratedEvents) {
            var method = findMethod(eventType);
            assertThat(method.isAnnotationPresent(ApplicationModuleListener.class))
                    .as("%s.on(%s) should be @ApplicationModuleListener",
                            SkillProjection.class.getSimpleName(), eventType.getSimpleName())
                    .isTrue();
            assertThat(method.isAnnotationPresent(EventListener.class))
                    .as("%s.on(%s) should NOT have @EventListener",
                            SkillProjection.class.getSimpleName(), eventType.getSimpleName())
                    .isFalse();
        }
    }
}
