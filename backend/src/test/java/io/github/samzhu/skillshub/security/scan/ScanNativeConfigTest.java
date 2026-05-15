package io.github.samzhu.skillshub.security.scan;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;

import io.github.samzhu.skillshub.security.scan.engines.LlmJudgement;

class ScanNativeConfigTest {

	@Test
	@Tag("AC-S175-1")
	@DisplayName("AC-S175-1: ScanNativeConfig registers scan output and risk_assessment records")
	void scanNativeConfigRegistersScanOutputAndRiskAssessmentRecordsForBinding() {
		var hint = ScanNativeConfig.class.getAnnotation(RegisterReflectionForBinding.class);

		assertThat(hint).as(
				"ScanNativeConfig must declare @RegisterReflectionForBinding for scanner JSON records")
				.isNotNull();

		var registeredClasses = new ArrayList<Class<?>>();
		java.util.Collections.addAll(registeredClasses, hint.value());
		java.util.Collections.addAll(registeredClasses, hint.classes());

		assertThat(registeredClasses)
				.contains(
						AnalysisOutput.class,
						SecurityFinding.class,
						ScanNotice.class,
						ScanResult.class,
						LlmJudgement.class,
						LlmJudgement.RiskClaim.class);
	}
}
