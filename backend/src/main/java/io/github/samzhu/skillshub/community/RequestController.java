package io.github.samzhu.skillshub.community;

import java.time.Instant;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * S096g1 — Request Board stub controller (read-only empty list).
 *
 * <p>per PRD §P8 Request Board + Engineering Handoff §2.13. 完整 aggregate +
 * voting + claim + domain events 留 S096g2 ship；本 stub 只回 empty list 讓
 * frontend RequestBoardPage 不 fetch 404。
 *
 * <p>新 module path `community/`；Modulith 預設無 `@ApplicationModule` 標註的 root
 * package 為 module，與既有 7 modules 並列。Aggregate / Repository / domain events
 * 等 S096g2 加入後再正式註冊 community module 邊界。
 */
@RestController
@RequestMapping("/api/v1/requests")
public class RequestController {

	/**
	 * List skill requests filtered by status (open|in-progress|fulfilled|all).
	 * S096g1: returns empty list (stub); query param ignored until S096g2.
	 */
	@GetMapping
	List<RequestSummary> list() {
		return List.of();
	}

	/**
	 * Skill request public summary — frontend RequestBoardPage row schema.
	 *
	 * @param id          unique request id (UUID)
	 * @param title       short title
	 * @param description long-form need description
	 * @param votes       upvote count (S096g2 voting feature)
	 * @param status      OPEN / IN_PROGRESS / FULFILLED
	 * @param createdAt   request submission time
	 */
	public record RequestSummary(
			String id,
			String title,
			String description,
			int votes,
			String status,
			Instant createdAt) {}
}
