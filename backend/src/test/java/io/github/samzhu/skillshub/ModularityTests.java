package io.github.samzhu.skillshub;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ModularityTests {

	// 同一個 verify() 同時覆蓋多個 spec 的 AC：
	//   - S000 AC-3：初版 Modulith 結構驗證
	//   - S016 AC-14：skill module 加 "shared :: security" allowedDependency 後仍綠
	//   - S017 AC-11：search module 已有 "shared :: security"（S014 T7 加），SemanticSearchService
	//                 引入 CurrentUserProvider + AclPrincipalExpander 後 verify 仍綠
	//   - S023 AC-12：listener migration（@EventListener → @ApplicationModuleListener）+
	//                 加新 shared/config 套件 + IncompleteEventRepublishTask + EventPublicationMetrics
	//                 + AsyncListenerConfig 後仍無模組邊界違規
	@Test
	@DisplayName("AC-3 / AC-14 / AC-11 / AC-12: Spring Modulith 結構驗證 — 含 S023 hybrid migration + outbox + ShedLock")
	@Tag("AC-3")
	@Tag("AC-14")
	@Tag("AC-11")
	@Tag("AC-12")
	void verifyModuleStructure() {
		ApplicationModules.of(SkillshubApplication.class).verify();
	}

}
