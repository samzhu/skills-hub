package io.github.samzhu.skillshub.security;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import io.github.samzhu.skillshub.shared.events.DomainEvent;
import io.github.samzhu.skillshub.shared.events.DomainEventRepository;
import io.github.samzhu.skillshub.skill.domain.SkillVersionPublishedEvent;
import io.github.samzhu.skillshub.storage.PackageService;
import io.github.samzhu.skillshub.storage.StorageService;

@Component
class RiskAssessmentListener {

	private static final Logger log = LoggerFactory.getLogger(RiskAssessmentListener.class);

	private final StorageService storageService;
	private final PackageService packageService;
	private final RiskScanner riskScanner;
	private final DomainEventRepository eventStore;
	private final MongoTemplate mongoTemplate;

	RiskAssessmentListener(StorageService storageService, PackageService packageService,
			RiskScanner riskScanner, DomainEventRepository eventStore, MongoTemplate mongoTemplate) {
		this.storageService = storageService;
		this.packageService = packageService;
		this.riskScanner = riskScanner;
		this.eventStore = eventStore;
		this.mongoTemplate = mongoTemplate;
	}

	@EventListener
	void on(SkillVersionPublishedEvent event) {
		log.info("Risk assessment triggered for skill {} version {}", event.aggregateId(), event.version());

		try {
			var zipBytes = storageService.download(event.storagePath());
			var scripts = packageService.extractScripts(zipBytes);
			var scanResult = riskScanner.scan(scripts);

			// Save SkillRiskAssessed domain event
			var latestEvent = eventStore.findTopByAggregateIdOrderBySequenceDesc(event.aggregateId());
			long nextSequence = latestEvent.map(e -> e.sequence() + 1).orElse(1L);

			var payload = Map.<String, Object>of(
					"version", event.version(),
					"riskLevel", scanResult.level().name(),
					"findingsCount", scanResult.findings().size()
			);

			var domainEvent = new DomainEvent(
					UUID.randomUUID().toString(),
					event.aggregateId(),
					"Skill",
					"SkillRiskAssessed",
					payload,
					nextSequence,
					Instant.now(),
					Map.of()
			);
			eventStore.save(domainEvent);

			// Directly update skills read model riskLevel (no cross-module event)
			mongoTemplate.updateFirst(
					Query.query(Criteria.where("_id").is(event.aggregateId())),
					Update.update("riskLevel", scanResult.level().name()).set("updatedAt", Instant.now()),
					"skills"
			);

			log.info("Risk assessment completed for skill {}: level={}, findings={}",
					event.aggregateId(), scanResult.level(), scanResult.findings().size());

		} catch (Exception e) {
			log.error("Failed to assess risk for skill {}: {}", event.aggregateId(), e.getMessage());
		}
	}

}
