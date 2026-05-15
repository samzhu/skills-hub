package io.github.samzhu.skillshub.security.scan;

import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;
import org.springframework.context.annotation.Configuration;

import io.github.samzhu.skillshub.security.scan.engines.LlmJudgement;

// S175: native binding hints for scanner output and persistence JSON records.
@Configuration(proxyBeanMethods = false)
@RegisterReflectionForBinding({
		AnalysisOutput.class,
		SecurityFinding.class,
		ScanNotice.class,
		ScanResult.class,
		LlmJudgement.class,
		LlmJudgement.RiskClaim.class
})
class ScanNativeConfig {}
