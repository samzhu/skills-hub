package io.github.samzhu.skillshub.security.scan.engines;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import io.github.samzhu.skillshub.security.scan.AnalysisOutput;
import io.github.samzhu.skillshub.security.scan.Phase;
import io.github.samzhu.skillshub.security.scan.ScanContext;
import io.github.samzhu.skillshub.security.scan.ScanNotice;
import io.github.samzhu.skillshub.security.scan.SecurityAnalyzer;
import io.github.samzhu.skillshub.security.scan.SecurityFinding;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Phase 1 靜態引擎 — 驗證 SKILL.md frontmatter 是否符合 agentskills.io 規範。
 *
 * <p>規範來源：<a href="https://agentskills.io/specification">agentskills.io specification</a>
 * <ul>
 *   <li>{@code name}（必要）：lowercase-hyphen，最長 64 字元；regex {@code [a-z][a-z0-9-]*}</li>
 *   <li>{@code description}（必要）：最長 1024 字元</li>
 *   <li>{@code version}（選用）：SemVer 格式</li>
 * </ul>
 *
 * <p>違規以 {@link ScanNotice} 形式輸出（per spec §2.3 決策 #7：lint-style 不上升為 finding）。
 * 違規不影響掃描整體 {@link io.github.samzhu.skillshub.security.scan.Severity}，只在 SARIF
 * 的 {@code invocations.toolExecutionNotifications} 欄位呈現。
 *
 * <p>實作策略：把 frontmatter Map 轉成 {@link SkillFrontmatter} record，套用 Jakarta Bean
 * Validation 標準 annotations。失敗時 ConstraintViolation 直接轉為 ScanNotice，無自定的
 * lint-rule 引擎以避免維護成本。
 *
 * @see SecurityAnalyzer
 */
@Component("metadata")
@ConditionalOnProperty(
		name = "skillshub.scanner.engines.metadata.enabled",
		havingValue = "true",
		matchIfMissing = true)
public class MetadataValidator implements SecurityAnalyzer {

	/** Jakarta Bean Validation；由 Spring Boot {@code spring-boot-starter-validation} 自動配置。 */
	private final Validator validator;

	public MetadataValidator(Validator validator) {
		this.validator = validator;
	}

	@Override
	public String name() { return "metadata"; }

	@Override
	public Phase phase() { return Phase.STATIC; }

	@Override
	public AnalysisOutput analyze(ScanContext context) {
		// 從 ctx.frontmatter Map 抽取必要欄位；缺欄位即為 null，由 @NotBlank 攔下。
		// 使用 toString() 的安全轉型 — agentskills.io 規範這三欄都是 string。
		var frontmatter = context.frontmatter();
		var name = stringOrNull(frontmatter.get("name"));
		var description = stringOrNull(frontmatter.get("description"));
		var version = stringOrNull(frontmatter.get("version"));

		Set<ConstraintViolation<SkillFrontmatter>> violations =
				validator.validate(new SkillFrontmatter(name, description, version));

		var notices = new ArrayList<ScanNotice>();
		for (ConstraintViolation<SkillFrontmatter> v : violations) {
			// "name: must match ..." 格式；message 包含欄位名以便下游識別
			notices.add(new ScanNotice(name(),
					v.getPropertyPath() + ": " + v.getMessage()));
		}

		// MetadataValidator 不發 finding（per spec §2.3 #7）；只 lint
		return new AnalysisOutput(List.<SecurityFinding>of(), List.copyOf(notices));
	}

	/**
	 * 安全把 Map<String, Object> 的值轉為 String — null/非 String 一律回 null，讓
	 * {@code @NotBlank} 統一處理「missing or wrong type」場景。
	 */
	private static String stringOrNull(Object o) {
		return (o instanceof String s) ? s : null;
	}

	/**
	 * agentskills.io frontmatter 三大欄位的 Bean Validation 規格。
	 *
	 * @param name        小寫連字號名稱，最長 64 字元
	 * @param description 描述文字，最長 1024 字元
	 * @param version     可選 SemVer 字串；null 視為合法
	 */
	record SkillFrontmatter(
			@NotBlank
			@Size(max = 64)
			@Pattern(regexp = "[a-z][a-z0-9-]*", message = "must be lowercase-hyphen")
			String name,

			@NotBlank
			@Size(max = 1024)
			String description,

			// version 可為 null（Pattern 不檢 null，@NotBlank 不在此欄位）
			@Pattern(regexp = "\\d+\\.\\d+\\.\\d+(-.*)?", message = "must be SemVer")
			String version
	) {}
}
