package io.github.samzhu.skillshub.security.scan.sarif;

import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;
import org.springframework.context.annotation.Configuration;

// S175: native binding hints for SARIF records converted through ObjectMapper.
@Configuration(proxyBeanMethods = false)
@RegisterReflectionForBinding({
		SarifModels.SarifLog.class,
		SarifModels.Run.class,
		SarifModels.Tool.class,
		SarifModels.Driver.class,
		SarifModels.Result.class,
		SarifModels.Location.class,
		SarifModels.PhysicalLocation.class,
		SarifModels.ArtifactLocation.class,
		SarifModels.Region.class,
		SarifModels.Invocation.class,
		SarifModels.ToolNotification.class
})
class SarifNativeConfig {}
