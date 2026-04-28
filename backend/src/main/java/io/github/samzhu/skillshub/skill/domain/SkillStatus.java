package io.github.samzhu.skillshub.skill.domain;

/**
 * 技能的生命週期狀態。
 *
 * <ul>
 *   <li>{@link #DRAFT} — 草稿，尚未公開，僅建立者可見</li>
 *   <li>{@link #PUBLISHED} — 已發布，可被搜尋與下載</li>
 *   <li>{@link #SUSPENDED} — 已停用，因安全風險或違規而下架，不可下載</li>
 * </ul>
 *
 * <p>S018：state machine 不變量集中於本 enum，每個 status 透過 {@link #publish()} /
 * {@link #suspend()} / {@link #reactivate()} method override 表達合法 transition；
 * 違規 transition 拋 {@link IllegalStateException} 由 aggregate 端 propagate。
 *
 * <pre>
 *   DRAFT  ──publish()──▶  PUBLISHED  ──suspend()──▶  SUSPENDED
 *                              ▲                          │
 *                              └─────reactivate()─────────┘
 * </pre>
 */
public enum SkillStatus {
	/**
	 * 草稿狀態：技能已建立但尚未發布任何版本。
	 *
	 * <p>合法 transition：{@code publish()} → PUBLISHED；其他皆 throw。
	 */
	DRAFT {
		@Override
		public SkillStatus publish() {
			return PUBLISHED;
		}
	},

	/**
	 * 發布狀態：至少有一個版本通過安全評估並對外公開。
	 *
	 * <p>合法 transition：{@code publish()} idempotent → PUBLISHED；
	 * {@code suspend()} → SUSPENDED；{@code reactivate()} 拋例外（非 SUSPENDED 不能 reactivate）。
	 */
	PUBLISHED {
		@Override
		public SkillStatus publish() {
			// idempotent — 後續發版不重複改 status（per AC-3）
			return PUBLISHED;
		}

		@Override
		public SkillStatus suspend() {
			return SUSPENDED;
		}
	},

	/**
	 * 停用狀態：技能因安全評估結果或管理員決策而暫時或永久下架。
	 *
	 * <p>合法 transition：{@code reactivate()} → PUBLISHED；其他皆 throw。
	 */
	SUSPENDED {
		@Override
		public SkillStatus reactivate() {
			return PUBLISHED;
		}
	};

	/**
	 * 從本 status 嘗試發版 transition。
	 *
	 * @return 新狀態（DRAFT → PUBLISHED；PUBLISHED idempotent）
	 * @throws IllegalStateException 若當前 status 不允許發版（如 SUSPENDED）
	 */
	public SkillStatus publish() {
		throw new IllegalStateException("Cannot publish version while skill is in " + name() + " status");
	}

	/**
	 * 從本 status 嘗試停用 transition。
	 *
	 * @return SUSPENDED（僅 PUBLISHED → SUSPENDED 合法）
	 * @throws IllegalStateException 若當前 status 不允許停用（如 DRAFT 尚未發版；SUSPENDED 已停用）
	 */
	public SkillStatus suspend() {
		throw new IllegalStateException("Cannot suspend skill in " + name() + " status");
	}

	/**
	 * 從本 status 嘗試重新啟用 transition。
	 *
	 * @return PUBLISHED（僅 SUSPENDED → PUBLISHED 合法）
	 * @throws IllegalStateException 若當前 status 不允許重啟（如 DRAFT / PUBLISHED）
	 */
	public SkillStatus reactivate() {
		throw new IllegalStateException("Cannot reactivate skill in " + name() + " status");
	}
}
