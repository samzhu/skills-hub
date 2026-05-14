package io.github.samzhu.skillshub.security.scan;

/**
 * S147-T01 — Agent-skill issue-code taxonomy adopted from Snyk Agent Scan.
 *
 * @see IssueCategory
 * @see SecurityFinding
 */
public enum SkillIssueCode {
	E004("Prompt injection in skill", Severity.HIGH, IssueCategory.PROMPT_SAFETY),
	E005("Suspicious download URL", Severity.HIGH, IssueCategory.DOWNLOADS_DEPENDENCIES),
	E006("Malicious code patterns", Severity.HIGH, IssueCategory.DOWNLOADS_DEPENDENCIES),
	W007("Insecure credential handling", Severity.HIGH, IssueCategory.CREDENTIALS),
	W008("Hardcoded secrets", Severity.HIGH, IssueCategory.CREDENTIALS),
	W009("Direct financial execution", Severity.MEDIUM, IssueCategory.FINANCIAL_ACTIONS),
	W011("Third-party content exposure", Severity.MEDIUM, IssueCategory.EXTERNAL_CONTENT),
	W012("Unverifiable external dependency", Severity.HIGH, IssueCategory.DOWNLOADS_DEPENDENCIES),
	W013("System service modification", Severity.MEDIUM, IssueCategory.DESTRUCTIVE_ACTIONS),
	W014("Missing SKILL.md", Severity.LOW, IssueCategory.PACKAGE_STRUCTURE),
	W017("Sensitive data exposure", Severity.MEDIUM, IssueCategory.SENSITIVE_DATA),
	W018("Workspace data exposure", Severity.LOW, IssueCategory.SENSITIVE_DATA),
	W019("Destructive capabilities", Severity.MEDIUM, IssueCategory.DESTRUCTIVE_ACTIONS),
	W020("Local destructive capabilities", Severity.LOW, IssueCategory.DESTRUCTIVE_ACTIONS);

	private final String title;
	private final Severity defaultSeverity;
	private final IssueCategory category;

	SkillIssueCode(String title, Severity defaultSeverity, IssueCategory category) {
		this.title = title;
		this.defaultSeverity = defaultSeverity;
		this.category = category;
	}

	public String code() {
		return name();
	}

	public String title() {
		return title;
	}

	public Severity defaultSeverity() {
		return defaultSeverity;
	}

	public IssueCategory category() {
		return category;
	}
}
