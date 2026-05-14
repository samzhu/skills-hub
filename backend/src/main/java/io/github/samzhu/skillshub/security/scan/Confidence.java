package io.github.samzhu.skillshub.security.scan;

/**
 * S147-T01 — issue-code finding 的信心值，讓 report API 能區分明確命中與低信心提示。
 *
 * @see SecurityFinding
 */
public enum Confidence {
	HIGH,
	MEDIUM,
	LOW
}
