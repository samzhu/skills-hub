package io.github.samzhu.skillshub.security.scan.engines;

import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;
import org.springframework.context.annotation.Configuration;

// S175: native binding hints for scanner engine DTOs used by RestClient/validation.
@Configuration(proxyBeanMethods = false)
@RegisterReflectionForBinding({
		OsvClient.OsvBatchRequest.class,
		OsvClient.OsvQuery.class,
		OsvClient.OsvPackage.class,
		OsvClient.OsvBatchResponse.class,
		OsvClient.OsvQueryResult.class,
		OsvClient.OsvVuln.class,
		OsvClient.OsvSeverity.class,
		MetadataValidator.SkillFrontmatter.class
})
class ScanEngineNativeConfig {}
