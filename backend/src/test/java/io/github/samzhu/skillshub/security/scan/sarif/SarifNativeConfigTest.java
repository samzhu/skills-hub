package io.github.samzhu.skillshub.security.scan.sarif;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;

class SarifNativeConfigTest {

	@Test
	@Tag("AC-S175-2")
	@DisplayName("AC-S175-2: SarifNativeConfig registers all SARIF records")
	void sarifNativeConfigRegistersAllSarifRecordsForBinding() {
		var hint = SarifNativeConfig.class.getAnnotation(RegisterReflectionForBinding.class);

		assertThat(hint).as(
				"SarifNativeConfig must declare @RegisterReflectionForBinding for ObjectMapper SARIF records")
				.isNotNull();

		var registeredClasses = new ArrayList<Class<?>>();
		java.util.Collections.addAll(registeredClasses, hint.value());
		java.util.Collections.addAll(registeredClasses, hint.classes());

		assertThat(registeredClasses)
				.contains(
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
						SarifModels.ToolNotification.class);
	}
}
