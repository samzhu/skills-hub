package io.github.samzhu.skillshub.analytics;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 分析（Analytics）REST Controller，提供平台統計資料查詢端點。
 *
 * <p>基礎路徑：{@code /api/v1/analytics}</p>
 *
 * <p>S096d3：加 {@code GET /api/v1/skills/{id}/stats?period=30d} 為 per-skill
 * 下載趨勢資料源（sparkline / trend chart）；對齊 Engineering Handoff §2.3「Download
 * sparkline」資料契約。
 */
@RestController
@RequestMapping("/api/v1")
public class AnalyticsController {

	private final AnalyticsService analyticsService;

	public AnalyticsController(AnalyticsService analyticsService) {
		this.analyticsService = analyticsService;
	}

	/**
	 * 取得平台整體概覽統計資料。
	 *
	 * @return 包含 Skill 總數、下載總數、本週新增數及排行榜的 {@link OverviewStats}
	 */
	@GetMapping("/analytics/overview")
	OverviewStats overview() {
		return analyticsService.getOverview();
	}

	/**
	 * S096d3 — 取得單一 skill 的每日下載趨勢資料（sparkline 用）。
	 *
	 * <p>{@code period} 接受 {@code 7d}、{@code 30d}、{@code 90d}（其他值 fallback 30d）。
	 * 回傳固定長度 int 陣列，index 0 = 最舊那天，index N-1 = 今天。
	 *
	 * @param id     skill UUID
	 * @param period 期間字串
	 * @return 長度 N（period 對應）的每日下載數陣列
	 */
	@GetMapping("/skills/{id}/stats")
	int[] skillStats(@PathVariable String id,
			@RequestParam(name = "period", defaultValue = "30d") String period) {
		int days = parseDays(period);
		return analyticsService.getSkillDownloadTrend(id, days);
	}

	/** "7d" / "30d" / "90d" → int days；其他 fallback 30 days. */
	private int parseDays(String period) {
		return switch (period) {
			case "7d" -> 7;
			case "90d" -> 90;
			default -> 30;
		};
	}

}
