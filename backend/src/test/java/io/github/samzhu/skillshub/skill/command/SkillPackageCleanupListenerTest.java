package io.github.samzhu.skillshub.skill.command;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import io.github.samzhu.skillshub.skill.domain.SkillDeletedEvent;
import io.github.samzhu.skillshub.storage.StorageService;

class SkillPackageCleanupListenerTest {

    @Test
    @DisplayName("AC-S144-4: SkillDeletedEvent deletes every stored package path")
    @Tag("AC-S144-4")
    void skillDeleted_deletesEveryStoragePath() {
        var storage = mock(StorageService.class);
        var listener = new SkillPackageCleanupListener(storage);
        var event = new SkillDeletedEvent("skill-1", "demo", "alice", Instant.now(),
                List.of("skills/skill-1/1.0.0.zip", "skills/skill-1/1.1.0.zip"));

        listener.on(event);

        verify(storage).delete("skills/skill-1/1.0.0.zip");
        verify(storage).delete("skills/skill-1/1.1.0.zip");
    }

    @Test
    @DisplayName("AC-S144-4: storage delete failure propagates so Modulith can retry")
    @Tag("AC-S144-4")
    void storageDeleteFailure_propagates() {
        var storage = mock(StorageService.class);
        doThrow(new IllegalStateException("GCS unavailable"))
                .when(storage).delete("skills/skill-1/1.1.0.zip");
        var listener = new SkillPackageCleanupListener(storage);
        var event = new SkillDeletedEvent("skill-1", "demo", "alice", Instant.now(),
                List.of("skills/skill-1/1.0.0.zip", "skills/skill-1/1.1.0.zip"));

        assertThatThrownBy(() -> listener.on(event))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("GCS unavailable");
    }
}
