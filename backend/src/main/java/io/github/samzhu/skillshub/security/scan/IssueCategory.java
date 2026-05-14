package io.github.samzhu.skillshub.security.scan;

/**
 * S147-T01 — Snyk-like issue code 在 SecurityReport 中顯示給 UI 的分類。
 *
 * @see SkillIssueCode
 */
public enum IssueCategory {
	PROMPT_SAFETY("prompt-safety", "Prompt Safety"),
	DOWNLOADS_DEPENDENCIES("downloads-dependencies", "Downloads & Dependencies"),
	CREDENTIALS("credentials", "Credentials"),
	FINANCIAL_ACTIONS("financial-actions", "Financial Actions"),
	EXTERNAL_CONTENT("external-content", "External Content"),
	DESTRUCTIVE_ACTIONS("destructive-actions", "Destructive Actions"),
	PACKAGE_STRUCTURE("package-structure", "Package Structure"),
	SENSITIVE_DATA("sensitive-data", "Sensitive Data");

	private final String key;
	private final String label;

	IssueCategory(String key, String label) {
		this.key = key;
		this.label = label;
	}

	public String key() {
		return key;
	}

	public String label() {
		return label;
	}
}
