package io.github.samzhu.skillshub;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ModularityTests {

	@Test
	@DisplayName("AC-3: Spring Modulith module 結構驗證 — 無模組邊界違規")
	void verifyModuleStructure() {
		ApplicationModules.of(SkillshubApplication.class).verify();
	}

}
