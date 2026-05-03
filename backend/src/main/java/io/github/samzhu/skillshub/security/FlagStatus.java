package io.github.samzhu.skillshub.security;

/**
 * S098e3 — Flag 審核狀態 enum + state machine。
 *
 * <p>有限狀態：
 * <ul>
 *   <li>{@code OPEN} — 初始狀態（{@link FlagService#createFlag} 寫入時）</li>
 *   <li>{@code RESOLVED} — reviewer 確認問題已處理</li>
 *   <li>{@code DISMISSED} — reviewer 認為非問題（假警報）</li>
 * </ul>
 *
 * <p>合法 transition：{@code OPEN → RESOLVED} 或 {@code OPEN → DISMISSED}；
 * RESOLVED / DISMISSED 為 terminal — 不可回 OPEN，不可互轉。為什麼不可逆：審核紀錄
 * 應 append-only 維 audit trail；要重開應另建新 flag（保留歷史 vs erase history）。
 */
public enum FlagStatus {
    OPEN, RESOLVED, DISMISSED;

    public boolean canTransitionTo(FlagStatus target) {
        if (target == null) {
            return false;
        }
        return switch (this) {
            case OPEN -> target == RESOLVED || target == DISMISSED;
            case RESOLVED, DISMISSED -> false;
        };
    }

    /** 寬鬆 parser：拒絕 unknown name + null。回傳 null = invalid. */
    public static FlagStatus fromName(String name) {
        if (name == null) return null;
        try {
            return FlagStatus.valueOf(name);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
