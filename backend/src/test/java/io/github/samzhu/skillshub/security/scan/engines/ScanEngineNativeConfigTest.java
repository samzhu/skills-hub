package io.github.samzhu.skillshub.security.scan.engines;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;

class ScanEngineNativeConfigTest {

	@Test
	@Tag("AC-S175-3")
	@DisplayName("AC-S175-3: ScanEngineNativeConfig registers OSV DTO and metadata validation records")
	void scanEngineNativeConfigRegistersOsvAndMetadataRecordsForBinding() {
		var hint = ScanEngineNativeConfig.class.getAnnotation(RegisterReflectionForBinding.class);

		assertThat(hint).as(
				"ScanEngineNativeConfig must declare @RegisterReflectionForBinding for RestClient/validation records")
				.isNotNull();

		var registeredClasses = new ArrayList<Class<?>>();
		java.util.Collections.addAll(registeredClasses, hint.value());
		java.util.Collections.addAll(registeredClasses, hint.classes());

		assertThat(registeredClasses)
				.contains(
						OsvClient.OsvBatchRequest.class,
						OsvClient.OsvQuery.class,
						OsvClient.OsvPackage.class,
						OsvClient.OsvBatchResponse.class,
						OsvClient.OsvQueryResult.class,
						OsvClient.OsvVuln.class,
						OsvClient.OsvSeverity.class,
						MetadataValidator.SkillFrontmatter.class);
	}
}
