package io.github.samzhu.skillshub.shared.api;

/** S135a AC-S135a-4: 品質評分尚未計算 → GlobalExceptionHandler 映射為 404 QUALITY_NOT_EVALUATED。 */
public class QualityNotEvaluatedException extends RuntimeException {

    public QualityNotEvaluatedException(String skillId) {
        super("Score will be available shortly after publish.");
    }
}
