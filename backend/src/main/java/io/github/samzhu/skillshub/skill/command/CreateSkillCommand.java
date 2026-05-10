package io.github.samzhu.skillshub.skill.command;

import org.jspecify.annotations.Nullable;

import io.github.samzhu.skillshub.skill.domain.Visibility;

/**
 * 建立技能命令 — 攜帶初始化新技能所需的 metadata。
 *
 * <p>由 {@code SkillCommandController} 從 HTTP 請求反序列化後，
 * 傳入 {@code SkillCommandService#createSkill} 執行寫入操作。
 * 成功後產生 {@link io.github.samzhu.skillshub.skill.domain.SkillCreatedEvent}。
 *
 * <h2>S154 breaking change</h2>
 *
 * <p>{@link #author} 不再由 caller 提供 — controller 一律從 {@code currentUserProvider.userId()}
 * 注入 platform user_id。caller-supplied {@code author} 在 controller 端會被 server-side override
 * 蓋掉（forgery fix per AC-3）。
 *
 * <p>新增 {@link #authorNameSnapshot}（nullable）— 由 controller 從
 * {@code currentUserProvider.current().name()} 注入；publish 時由 {@link
 * io.github.samzhu.skillshub.skill.domain.Skill#create} 寫進 {@code skills.author_name_snapshot} 欄位。
 *
 * @param name               技能名稱（小寫連字號格式，最長 64 字元，符合 agentskills.io 規範）
 * @param description        技能功能描述（最長 1024 字元）
 * @param author             technical author identifier（S154 起為 platform user_id，由 server 注入）
 * @param category           技能分類（如 DevOps、Testing）
 * @param visibility         S116 — 可見性 (PUBLIC / PRIVATE)；缺省 PUBLIC
 * @param authorNameSnapshot S154 — publish 時凍結的 author 顯示名稱（nullable；test 可傳 null）
 */
public record CreateSkillCommand(
		String name,
		String description,
		String author,
		String category,
		Visibility visibility,
		@Nullable String authorNameSnapshot
) {
	/**
	 * S116 backward-compat — 4-arg ctor → 6-arg with PUBLIC default + null snapshot。
	 *
	 * <p>S154 後新增 snapshot null fallback：既有 4-arg call sites（測試 + 部分 production）
	 * 不需要關心 snapshot field（snapshot 為 display 欄位，null 仍 valid）。
	 */
	public CreateSkillCommand(String name, String description, String author, String category) {
		this(name, description, author, category, Visibility.PUBLIC, null);
	}

	/** S154 backward-compat — 5-arg ctor (S116 既有) → 6-arg with null snapshot。 */
	public CreateSkillCommand(String name, String description, String author, String category,
			Visibility visibility) {
		this(name, description, author, category, visibility, null);
	}
}
