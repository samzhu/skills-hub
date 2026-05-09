plugins {
	java
	id("org.springframework.boot") version "4.0.6"
	id("io.spring.dependency-management") version "1.1.7"
	// graalvm plugin → 觸發 processAot task + 貢獻 META-INF/native-image/ metadata。
	// Paketo `noble-java-tiny` 第一個 order group `java-native-image` 內含 required
	// native-image buildpack，metadata 存在即觸發 native compile chain — bootBuildImage
	// 直接產 native binary。
	id("org.graalvm.buildtools.native") version "0.11.5"
	id("org.cyclonedx.bom") version "3.2.4"
	// /actuator/info git 區塊 — 從 .git/ 抽 commit hash / branch / message 寫進
	// META-INF/git.properties，Spring Boot GitInfoContributor 自動 expose。
	// build 階段需要 .git/ 可訪問（Cloud Build 預設 source upload 含 .git，未排除）。
	id("com.gorylenko.gradle-git-properties") version "2.5.2"
	jacoco
}

group = "io.github.samzhu"
version = "0.0.1-SNAPSHOT"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(25)
	}
}

repositories {
	mavenCentral()
}

extra["springAiVersion"] = "2.0.0-M5"
extra["springCloudGcpVersion"] = "8.0.2"
extra["springCloudVersion"] = "2025.1.1"
extra["springModulithVersion"] = "2.0.6"
// ShedLock 無 BOM，須顯式版本（7.x 支援 Spring Boot 4.x）
extra["shedlockVersion"] = "7.7.0"

dependencies {
	// implementation("org.springframework.boot:spring-boot-micrometer-tracing-brave")
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("org.springframework.boot:spring-boot-starter-data-jdbc")
	implementation("org.springframework.boot:spring-boot-starter-jdbc")
	// core artifact（非 starter）— 自寫 SkillshubPgVectorStore 子類控制 6-欄 INSERT
	implementation("org.springframework.ai:spring-ai-pgvector-store")
	// implementation("org.springframework.boot:spring-boot-starter-opentelemetry")
	implementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")
	// S134：OAuth2 Login (Client) — local dev real-IdP integration trial。
	// 啟用 oauth2Login() chain 處理 redirect URI `/login/oauth2/code/{registrationId}`；
	// 預設 dev/lab/prod 路徑不受影響（由 skillshub.security.oauth.login.enabled toggle，預設 false）。
	implementation("org.springframework.boot:spring-boot-starter-oauth2-client")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	implementation("com.google.cloud:spring-cloud-gcp-starter")
	implementation("com.google.cloud:spring-cloud-gcp-starter-storage")
	// 供 application-gcp.yaml 內 ${sm@<secret-id>} placeholder startup 時 resolve
	implementation("com.google.cloud:spring-cloud-gcp-starter-secretmanager")
	implementation("io.micrometer:micrometer-tracing-bridge-brave")
	implementation("org.springframework.ai:spring-ai-markdown-document-reader")
	implementation("org.springframework.ai:spring-ai-google-genai-embedding")
	implementation("org.springframework.ai:spring-ai-google-genai")
	implementation("org.springframework.ai:spring-ai-client-chat")
	implementation("org.springframework.ai:spring-ai-vector-store")
	implementation("org.springframework.modulith:spring-modulith-starter-core")
	// Modulith Event Publication Registry (transactional outbox)
	implementation("org.springframework.modulith:spring-modulith-starter-jdbc")
	// 多 Cloud Run instance @Scheduled retry 互斥
	implementation("net.javacrumbs.shedlock:shedlock-spring:${property("shedlockVersion")}")
	implementation("net.javacrumbs.shedlock:shedlock-provider-jdbc-template:${property("shedlockVersion")}")
	developmentOnly("org.springframework.boot:spring-boot-devtools")
	developmentOnly("org.springframework.boot:spring-boot-docker-compose")
	// Spring Boot 4 把 Flyway auto-config 拆到獨立 artifact，需顯式引入
	implementation("org.springframework.boot:spring-boot-flyway")
	implementation("org.flywaydb:flyway-core")
	runtimeOnly("org.flywaydb:flyway-database-postgresql")
	runtimeOnly("org.postgresql:postgresql")
	runtimeOnly("io.micrometer:micrometer-registry-otlp")
	developmentOnly("org.springframework.ai:spring-ai-spring-boot-docker-compose")
	runtimeOnly("org.springframework.modulith:spring-modulith-actuator")
	runtimeOnly("org.springframework.modulith:spring-modulith-observability")
	annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")
	testImplementation("org.springframework.boot:spring-boot-micrometer-tracing-test")
	testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
	testImplementation("org.springframework.boot:spring-boot-starter-data-jdbc-test")
	testImplementation("org.springframework.boot:spring-boot-starter-opentelemetry-test")
	testImplementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server-test")
	testImplementation("org.springframework.boot:spring-boot-starter-validation-test")
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	testImplementation("org.springframework.boot:spring-boot-testcontainers")
	testImplementation("org.springframework.ai:spring-ai-spring-boot-testcontainers")
	testImplementation("org.springframework.modulith:spring-modulith-starter-test")
	testImplementation("org.testcontainers:testcontainers-gcloud")
	testImplementation("org.testcontainers:testcontainers-grafana")
	testImplementation("org.testcontainers:testcontainers-junit-jupiter")
	testImplementation("org.testcontainers:testcontainers-postgresql")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

dependencyManagement {
	imports {
		mavenBom("org.springframework.modulith:spring-modulith-bom:${property("springModulithVersion")}")
		mavenBom("com.google.cloud:spring-cloud-gcp-dependencies:${property("springCloudGcpVersion")}")
		mavenBom("org.springframework.ai:spring-ai-bom:${property("springAiVersion")}")
		mavenBom("org.springframework.cloud:spring-cloud-dependencies:${property("springCloudVersion")}")
	}
}

// /actuator/info build 區塊 — Spring Boot Gradle plugin 內建 buildInfo task，
// 寫 build-info.properties (group/artifact/name/version/time) 到 META-INF/，
// BuildInfoContributor 啟動時自動讀取 expose。
springBoot {
	buildInfo()
}

// /actuator/info git 區塊 — Gradle 在 backend/ subdir 跑，預設找 backend/.git/ 不存在，
// 必須顯式指 monorepo root 的 .git 目錄。
// failOnNoGitDirectory=false：Cloud Build 環境 `gcloud builds submit` 上傳的 source
// 不含 .git/（除非 .gcloudignore 顯式 keep）；本機 build 有 .git/ 正常產 git.properties，
// CI build 沒 .git/ 則 silently skip — /actuator/info 不顯示 git 區塊但不 break build。
gitProperties {
	dotGitDirectory = file("${rootDir}/../.git")
	failOnNoGitDirectory = false
}

// S132 §8: ProcessAot baked profile 機制 — AOT 階段就要列齊 native runtime 想用的
// profile（runtime SPRING_PROFILES_ACTIVE 不能移除 baked 的）。預設 aot,local；
// 換環境用 -Pspring.profiles.active=aot,gcp,{lab|prod} 覆蓋。
tasks.withType<org.springframework.boot.gradle.tasks.aot.ProcessAot>().configureEach {
	val profiles = (project.findProperty("spring.profiles.active") as? String) ?: "aot,local"
	args("--spring.profiles.active=$profiles")
}

tasks.withType<Test> {
	useJUnitPlatform()
}

// JaCoCo 0.8.14：首版官方支援 Java 25 bytecode
jacoco {
	toolVersion = "0.8.14"
}

tasks.test {
	finalizedBy(tasks.jacocoTestReport)
	// Spring TestContext cache 預設 max 32 contexts；@MockitoBean / @TestPropertySource 任一變動皆新 context。
	// 各 context 持 Tomcat + Hikari pool + OpenTelemetry + Modulith runtime → ~90-150 MB／context。
	// 2g 在 18+ contexts 下會 thrash GC / BatchSpanProcessor OOM；3g 給安全 buffer。
	maxHeapSize = "3g"
	// 觀察 context cache hit/miss/eviction — 平時靜音；troubleshoot 用 -Pcache-debug 開啟
	if (project.hasProperty("cache-debug")) {
		systemProperty("logging.level.org.springframework.test.context.cache", "DEBUG")
	}
}

tasks.jacocoTestReport {
	dependsOn(tasks.test)
	reports {
		xml.required = true
		html.required = true
		csv.required = true  // verify-all.sh awk 解析來源（cross-spec contract）
	}
	classDirectories.setFrom(
		files(classDirectories.files.map {
			fileTree(it) {
				exclude(
					"**/SkillshubApplication*",
					"**/config/**",
					"**/*Configuration*",
					"**/db/migration/**"
				)
			}
		})
	)
}

// project-wide LINE coverage gate
// S019 ship 時 baseline 0.88，threshold 0.80。多 spec 累積後降至 ~0.77。本 session
// 補 GlobalExceptionHandlerTest（35% → 100% +148 lines）拉回 baseline 0.81，threshold
// 同步 ratchet 回 0.80 防 future regression。後續 spec 持續補測逐步往 0.85 推（per backlog #17）。
tasks.jacocoTestCoverageVerification {
	dependsOn(tasks.test)
	classDirectories.setFrom(tasks.jacocoTestReport.get().classDirectories)
	violationRules {
		rule {
			element = "BUNDLE"
			limit {
				counter = "LINE"
				value = "COVEREDRATIO"
				minimum = "0.80".toBigDecimal()
			}
		}
	}
}

// JaCoCo plugin 預設不接 check lifecycle，須顯式宣告
tasks.check {
	dependsOn(tasks.jacocoTestCoverageVerification)
}
