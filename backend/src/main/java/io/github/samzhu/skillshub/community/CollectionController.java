package io.github.samzhu.skillshub.community;

import java.time.Instant;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * S096f1 — Collection stub controller (read-only empty list).
 *
 * <p>per PRD §P7 Collections + Engineering Handoff §2.11. 完整 aggregate +
 * install + create + 4 endpoints + domain events 留 S096f2 ship；本 stub
 * 只回 empty list 讓 frontend CollectionsPage 不 fetch 404。
 *
 * <p>同 RequestController 共用 `community/` package，等 S096f2 + S096g2 都 ship 後
 * 統一補 `@ApplicationModule` 標註並 register Modulith module 邊界。
 */
@RestController
@RequestMapping("/api/v1/collections")
public class CollectionController {

	/**
	 * List skill collections filtered by category / risk / compat.
	 * S096f1: returns empty list (stub); query params ignored until S096f2.
	 */
	@GetMapping
	List<CollectionSummary> list() {
		return List.of();
	}

	/**
	 * Skill collection public summary — frontend CollectionsPage card schema.
	 *
	 * @param id          unique collection id (UUID)
	 * @param name        display name
	 * @param description curator note
	 * @param skillCount  member skill count
	 * @param installs    cumulative install events
	 * @param category    primary category
	 * @param createdAt   creation time
	 */
	public record CollectionSummary(
			String id,
			String name,
			String description,
			int skillCount,
			int installs,
			String category,
			Instant createdAt) {}
}
