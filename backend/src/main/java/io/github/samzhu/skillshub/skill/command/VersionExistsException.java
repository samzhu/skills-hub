package io.github.samzhu.skillshub.skill.command;

/**
 * 版本重複例外 — 嘗試發布已存在的語意化版本號時拋出。
 *
 * <p>技能版本一旦發布即不可覆蓋（不可變原則），
 * 呼叫端應改用新版本號重新發布。
 * 由 {@code SkillCommandService#publishVersion} 在版本號衝突時拋出，
 * 並由全域例外處理器轉換為 HTTP 409 Conflict 回應。
 */
public class VersionExistsException extends RuntimeException {

	public VersionExistsException(String message) {
		super(message);
	}

}
