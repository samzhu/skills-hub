package io.github.samzhu.skillshub.skill.command;

import java.lang.invoke.MethodHandles;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import io.github.samzhu.skillshub.skill.domain.SkillDeletedEvent;
import io.github.samzhu.skillshub.storage.StorageService;

/**
 * S144 — SkillDeletedEvent commit 後刪除已發佈 package blob。
 */
@Component
class SkillPackageCleanupListener {

    private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    private final StorageService storageService;

    SkillPackageCleanupListener(StorageService storageService) {
        this.storageService = storageService;
    }

    @ApplicationModuleListener
    void on(SkillDeletedEvent event) {
        for (var path : event.storagePaths()) {
            storageService.delete(path);
            log.atInfo()
                    .addKeyValue("skillId", event.aggregateId())
                    .addKeyValue("storagePath", path)
                    .log("Deleted skill package after skill delete");
        }
    }
}
