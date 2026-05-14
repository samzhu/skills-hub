package io.github.samzhu.skillshub.security.scan;

import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;
import org.springframework.context.annotation.Configuration;

import io.github.samzhu.skillshub.security.scan.engines.LlmJudgement;

// S173: native binding hints for LlmJudge structured output records.
@Configuration(proxyBeanMethods = false)
@RegisterReflectionForBinding({LlmJudgement.class, LlmJudgement.RiskClaim.class})
class ScanNativeConfig {}
