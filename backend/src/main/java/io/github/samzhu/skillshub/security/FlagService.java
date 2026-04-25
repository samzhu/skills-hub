package io.github.samzhu.skillshub.security;

import java.lang.invoke.MethodHandles;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import io.github.samzhu.skillshub.shared.events.DomainEvent;
import io.github.samzhu.skillshub.shared.events.DomainEventRepository;

/**
 * Flag（舉報）應用服務，負責協調 Flag 的建立流程與查詢操作。
 *
 * <p>建立 Flag 時遵循 Event Sourcing 流程：</p>
 * <ol>
 *   <li>產生領域事件並持久化至 {@code domain_events}</li>
 *   <li>更新讀取模型（{@code flags} Collection）</li>
 *   <li>透過 {@code ApplicationEventPublisher} 發布 {@link SkillFlaggedEvent}</li>
 * </ol>
 */
@Service
public class FlagService {

	private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

	private final FlagReadModelRepository flagRepo;
	private final DomainEventRepository eventStore;
	private final ApplicationEventPublisher events;

	public FlagService(FlagReadModelRepository flagRepo, DomainEventRepository eventStore,
			ApplicationEventPublisher events) {
		this.flagRepo = flagRepo;
		this.eventStore = eventStore;
		this.events = events;
	}

	/**
	 * 建立一筆新的 Flag（舉報），並依 Event Sourcing 流程持久化後發布領域事件。
	 *
	 * @param skillId     被舉報的 Skill 識別碼
	 * @param type        舉報類型代碼，例如 {@code MALICIOUS_CODE}
	 * @param description 舉報原因說明
	 * @return 新建立的 Flag UUID 字串
	 */
	public String createFlag(String skillId, String type, String description) {
		var flagId = UUID.randomUUID().toString();

		var latestEvent = eventStore.findTopByAggregateIdOrderBySequenceDesc(skillId);
		long nextSequence = latestEvent.map(e -> e.sequence() + 1).orElse(1L);

		var payload = Map.<String, Object>of(
				"flagId", flagId,
				"type", type,
				"description", description
		);

		var domainEvent = new DomainEvent(
				UUID.randomUUID().toString(),
				skillId,
				"Skill",
				"SkillFlagged",
				payload,
				nextSequence,
				Instant.now(),
				Map.of()
		);
		eventStore.save(domainEvent);

		var flag = new FlagReadModel(flagId, skillId, type, description, "anonymous", Instant.now(), "OPEN");
		flagRepo.save(flag);

		events.publishEvent(new SkillFlaggedEvent(skillId, type, description, "anonymous"));

		log.atInfo()
				.addKeyValue("skillId", skillId)
				.addKeyValue("type", type)
				.addKeyValue("flagId", flagId)
				.log("Flag 建立成功");

		return flagId;
	}

	/**
	 * 查詢指定 Skill 的所有 Flag，依建立時間降冪排序。
	 *
	 * @param skillId 目標 Skill 的識別碼
	 * @return Flag 清單，若無則回傳空列表
	 */
	public List<FlagReadModel> getFlagsBySkillId(String skillId) {
		return flagRepo.findBySkillIdOrderByCreatedAtDesc(skillId);
	}

}
