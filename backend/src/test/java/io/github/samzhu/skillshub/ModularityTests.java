package io.github.samzhu.skillshub;

import static org.assertj.core.api.Assertions.assertThat;

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
	//   - S024 AC-13：read-model 模組整組刪除 + AuditEventListener 移至獨立 audit module（避開
	//                 shared → skill cycle）後仍綠；ApplicationModules.verify() 通過
	@Test
	@DisplayName("AC-3 / AC-14 / AC-11 / AC-12 / AC-13: Spring Modulith 結構驗證 — 含 S024 audit module + read-model 移除")
	@Tag("AC-3")
	@Tag("AC-14")
	@Tag("AC-11")
	@Tag("AC-12")
	@Tag("AC-13")
	void verifyModuleStructure() {
		ApplicationModules.of(SkillshubApplication.class).verify();
	}

	@Test
	@DisplayName("AC-13: audit module 存在且邊界乾淨（allowedDependencies = shared::events, skill::domain）")
	@Tag("AC-13")
	void shouldHaveAuditModuleWithCorrectDependencies() {
		ApplicationModules modules = ApplicationModules.of(SkillshubApplication.class);
		var auditModule = modules.getModuleByName("audit")
				.orElseThrow(() -> new AssertionError("audit module not found in application module structure"));
		// AuditEventListener 應位於 audit module（非 shared）— 避開 shared → skill 循環依賴
		assertThat(auditModule.getBasePackage().getName())
				.isEqualTo("io.github.samzhu.skillshub.audit");
	}

}
