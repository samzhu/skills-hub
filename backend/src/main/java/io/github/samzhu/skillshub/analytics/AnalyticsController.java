package io.github.samzhu.skillshub.analytics;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 分析（Analytics）REST Controller，提供平台統計資料查詢端點。
 *
 * <p>基礎路徑：{@code /api/v1/analytics}</p>
 */
@RestController
@RequestMapping("/api/v1/analytics")
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
	@GetMapping("/overview")
	OverviewStats overview() {
		return analyticsService.getOverview();
	}

}
