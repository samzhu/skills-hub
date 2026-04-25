package io.github.samzhu.skillshub.skill.query;

import java.lang.invoke.MethodHandles;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.support.PageableExecutionUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import io.github.samzhu.skillshub.shared.events.DomainEvent;
import io.github.samzhu.skillshub.shared.events.DomainEventRepository;
import io.github.samzhu.skillshub.skill.domain.SkillDownloadedEvent;
import io.github.samzhu.skillshub.storage.StorageService;

/**
 * 技能查詢服務 — CQRS 讀取端的核心入口。
 *
 * <p>負責：關鍵字搜尋、分類篩選、技能詳情查詢、版本歷史、下載（含事件記錄）。
 * 搜尋使用 {@link MongoTemplate} + {@link Criteria} 做動態條件組合，
 * 避免 Spring Data Repository 固定方法簽名無法處理可選參數的限制。</p>
 *
 * <p>下載操作雖然是讀取，但附帶副作用（記錄 {@link SkillDownloadedEvent}），
 * 因此在此服務中同時處理讀取 + 事件發佈。</p>
 */
@Service
public class SkillQueryService {

	private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

	private final SkillReadModelRepository repo;
	private final SkillVersionReadModelRepository versionRepo;
	private final MongoTemplate mongoTemplate;
	private final StorageService storageService;
	private final DomainEventRepository eventStore;
	private final ApplicationEventPublisher events;

	public SkillQueryService(SkillReadModelRepository repo, SkillVersionReadModelRepository versionRepo,
			MongoTemplate mongoTemplate, StorageService storageService,
			DomainEventRepository eventStore, ApplicationEventPublisher events) {
		this.repo = repo;
		this.versionRepo = versionRepo;
		this.mongoTemplate = mongoTemplate;
		this.storageService = storageService;
		this.eventStore = eventStore;
		this.events = events;
	}

	/**
	 * 依 ID 查詢單一技能的 read model。
	 *
	 * @param id 技能的 aggregate ID
	 * @return 技能讀取模型
	 * @throws NoSuchElementException 找不到該技能
	 */
	public SkillReadModel findById(String id) {
		return repo.findById(id)
				.orElseThrow(() -> new NoSuchElementException("Skill not found: " + id));
	}

	/**
	 * 關鍵字 + 分類組合搜尋。
	 *
	 * <p>keyword 做 case-insensitive regex，同時比對 name 和 description（OR）。
	 * category 做精確比對。兩者皆為可選，都不帶則回傳全部。</p>
	 *
	 * @param keyword  關鍵字（可選）
	 * @param category 分類名稱（可選）
	 * @param pageable 分頁 + 排序參數
	 * @return 分頁結果
	 */
	public Page<SkillReadModel> search(String keyword, String category, Pageable pageable) {
		var filters = new ArrayList<Criteria>();

		if (StringUtils.hasText(keyword)) {
			// Pattern.quote 防止使用者輸入的特殊字元被當作 regex 語法。
			// Firestore 相容性注意：$regex 不支援 index 加速（非前綴錨點 pattern），
			// 每次查詢都是全表掃描。S007 語意搜尋完成後應以向量搜尋取代此邏輯。
			var pattern = Pattern.compile(Pattern.quote(keyword), Pattern.CASE_INSENSITIVE);
			filters.add(new Criteria().orOperator(
					Criteria.where("name").regex(pattern),
					Criteria.where("description").regex(pattern)
			));
		}

		if (StringUtils.hasText(category)) {
			filters.add(Criteria.where("category").is(category));
		}

		var criteria = filters.isEmpty() ? new Criteria() : new Criteria().andOperator(filters.toArray(Criteria[]::new));
		var query = new Query(criteria).with(pageable);

		var results = mongoTemplate.find(query, SkillReadModel.class);

		log.atDebug()
				.addKeyValue("keyword", keyword)
				.addKeyValue("category", category)
				.addKeyValue("resultCount", results.size())
				.log("技能搜尋完成");

		return PageableExecutionUtils.getPage(results, pageable,
				() -> mongoTemplate.count(Query.of(query).limit(-1).skip(-1), SkillReadModel.class));
	}

	/**
	 * 查詢某技能的所有版本，按發佈時間降序排列。
	 *
	 * @param skillId 技能 ID
	 * @return 版本列表（最新在前）
	 */
	public List<SkillVersionReadModel> findVersionsBySkillId(String skillId) {
		return versionRepo.findBySkillIdOrderByPublishedAtDesc(skillId);
	}

	/**
	 * 下載某技能的最新版本 zip，並記錄下載事件。
	 *
	 * @param skillId 技能 ID
	 * @return zip 檔案的原始位元組
	 * @throws NoSuchElementException 該技能沒有任何已發佈版本
	 */
	public byte[] downloadLatest(String skillId) {
		var versions = versionRepo.findBySkillIdOrderByPublishedAtDesc(skillId);
		if (versions.isEmpty()) {
			throw new NoSuchElementException("No versions found for skill: " + skillId);
		}
		return downloadAndRecord(skillId, versions.getFirst());
	}

	/**
	 * 下載某技能的指定版本 zip，並記錄下載事件。
	 *
	 * @param skillId 技能 ID
	 * @param version 指定版本號（如 "1.0.0"）
	 * @return zip 檔案的原始位元組
	 * @throws NoSuchElementException 找不到該版本
	 */
	public byte[] downloadVersion(String skillId, String version) {
		var versions = versionRepo.findBySkillIdOrderByPublishedAtDesc(skillId);
		var target = versions.stream()
				.filter(v -> version.equals(v.version()))
				.findFirst()
				.orElseThrow(() -> new NoSuchElementException("Version " + version + " not found"));
		return downloadAndRecord(skillId, target);
	}

	/**
	 * 從 GCS 下載 zip 並發佈 SkillDownloaded 事件（供 analytics projection 消費）。
	 */
	private byte[] downloadAndRecord(String skillId, SkillVersionReadModel version) {
		var zipBytes = storageService.download(version.storagePath());

		// 計算下一個 event sequence
		var latestEvent = eventStore.findTopByAggregateIdOrderBySequenceDesc(skillId);
		long nextSequence = latestEvent.map(e -> e.sequence() + 1).orElse(1L);

		var payload = Map.<String, Object>of("version", version.version());
		var domainEvent = new DomainEvent(
				UUID.randomUUID().toString(), skillId, "Skill", "SkillDownloaded",
				payload, nextSequence, Instant.now(), Map.of());
		eventStore.save(domainEvent);
		events.publishEvent(new SkillDownloadedEvent(skillId, version.version()));

		log.atInfo()
				.addKeyValue("skillId", skillId)
				.addKeyValue("version", version.version())
				.addKeyValue("fileSize", zipBytes.length)
				.log("技能下載完成，已記錄下載事件");

		return zipBytes;
	}

	/**
	 * 取得所有分類及其技能數量，按數量降序排列。
	 * 使用 MongoDB aggregation pipeline：group by category → count → sort。
	 *
	 * @return 分類計數列表
	 */
	public List<CategoryCount> getCategoryCounts() {
		var agg = Aggregation.newAggregation(
				Aggregation.group("category").count().as("count"),
				Aggregation.sort(org.springframework.data.domain.Sort.Direction.DESC, "count"),
				Aggregation.project().and("_id").as("name").and("count").as("count")
		);
		return mongoTemplate.aggregate(agg, "skills", CategoryCount.class).getMappedResults();
	}

}
