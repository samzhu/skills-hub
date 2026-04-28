package io.github.samzhu.skillshub;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ModularityTests {

	// 同一個 verify() 同時覆蓋多個 spec 的 AC：
	//   - S000 AC-3：初版 Modulith 結構驗證
	//   - S016 AC-14：skill module 加 "shared :: security" allowedDependency 後仍綠
	@Test
	@DisplayName("AC-3 / AC-14: Spring Modulith module 結構驗證 — 無模組邊界違規（含 S016 shared::security）")
	@Tag("AC-3")
	@Tag("AC-14")
	void verifyModuleStructure() {
		ApplicationModules.of(SkillshubApplication.class).verify();
	}

}
