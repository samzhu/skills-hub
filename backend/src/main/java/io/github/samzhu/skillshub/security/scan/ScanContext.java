package io.github.samzhu.skillshub.security.scan;

import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Collections;

/**
 * 掃描輸入聚合 — 由 {@code ScanOrchestrator} 從 {@code SkillVersionPublishedEvent} 與
 * {@code StorageService} / {@code PackageService} 解壓出的內容組合而成。
 *
 * <p>{@link #phase1Findings} 在 Phase 1 (STATIC) 期間為空 list；
 * Phase 2 (LLM) 與 Phase 3 (META) 透過 {@link #withPhase1Findings(List)} 接收前一階段的 findings。
 * 工廠方法以 immutable record 風格回傳新實例，保持原 context 不變。
 *
 * @param skillId        Skill aggregateId
 * @param version        SemVer 版本字串
 * @param frontmatter    SKILL.md YAML frontmatter 解析後的 map（可含 nested map / list）
 * @param skillMd        SKILL.md 全文（含 frontmatter delimiter；分析時可選擇性過濾）
 * @param scripts        scripts/ 目錄下檔案內容（key = 相對路徑，value = UTF-8 內容）
 * @param packageFiles   zip package 內所有可讀 UTF-8 文字檔內容（key = 相對路徑）
 * @param packagePaths   zip package 內的檔案路徑（相對於 zip root 的 entry name）
 * @param phase1Findings Phase 1 已產出的 findings；給 Phase 2/3 使用
 *
 * @see SecurityAnalyzer#analyze(ScanContext)
 */
public record ScanContext(
		String skillId,
		String version,
		Map<String, Object> frontmatter,
		String skillMd,
		Map<String, String> scripts,
		Map<String, String> packageFiles,
		List<String> packagePaths,
		List<SecurityFinding> phase1Findings
) {
	public ScanContext {
		frontmatter = frontmatter == null ? Map.of() : frontmatter;
		skillMd = skillMd == null ? "" : skillMd;
		scripts = scripts == null ? Map.of() : scripts;
		packageFiles = packageFiles == null || packageFiles.isEmpty()
				? defaultPackageFiles(skillMd, scripts)
				: Collections.unmodifiableMap(new LinkedHashMap<>(packageFiles));
		packagePaths = packagePaths == null ? List.copyOf(packageFiles.keySet()) : List.copyOf(packagePaths);
		phase1Findings = phase1Findings == null ? List.of() : List.copyOf(phase1Findings);
	}

	/** Backward-compatible constructor for pre-S147 tests and analyzer fixtures. */
	public ScanContext(
			String skillId,
			String version,
			Map<String, Object> frontmatter,
			String skillMd,
			Map<String, String> scripts,
			List<SecurityFinding> phase1Findings) {
		this(skillId, version, frontmatter, skillMd, scripts,
				defaultPackageFiles(skillMd, scripts),
				(skillMd == null || skillMd.isEmpty()) ? List.of() : List.of("SKILL.md"),
				phase1Findings);
	}

	/** Backward-compatible constructor for S147-W014 package path fixtures. */
	public ScanContext(
			String skillId,
			String version,
			Map<String, Object> frontmatter,
			String skillMd,
			Map<String, String> scripts,
			List<String> packagePaths,
			List<SecurityFinding> phase1Findings) {
		this(skillId, version, frontmatter, skillMd, scripts,
				defaultPackageFiles(skillMd, scripts), packagePaths, phase1Findings);
	}

	/**
	 * 產生一個新的 ScanContext，{@code phase1Findings} 替換為傳入值。
	 * 用於 Phase 2 / Phase 3 以前一階段的結果作為輸入。
	 *
	 * @param findings 上一階段的 findings 列表
	 * @return 新的 ScanContext 實例（原 context 不變）
	 */
	public ScanContext withPhase1Findings(List<SecurityFinding> findings) {
		return new ScanContext(skillId, version, frontmatter, skillMd, scripts, packageFiles, packagePaths, findings);
	}

	private static Map<String, String> defaultPackageFiles(String skillMd, Map<String, String> scripts) {
		var files = new LinkedHashMap<String, String>();
		if (skillMd != null && !skillMd.isEmpty()) {
			files.put("SKILL.md", skillMd);
		}
		if (scripts != null) {
			files.putAll(scripts);
		}
		return Collections.unmodifiableMap(new LinkedHashMap<>(files));
	}
}
