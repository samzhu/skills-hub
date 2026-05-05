plugins {
	java
	id("org.springframework.boot") version "4.0.6"
	id("io.spring.dependency-management") version "1.1.7"
	// org.graalvm.buildtools.native 走官方標準 plugin。auto-apply boot.aot →
	// processAot task 註冊 → Spring Boot AOT artifacts 烤進 jar。Paketo builder
	// `noble-java-tiny` 第一個 order group 是 `java-native-image`（內含 required
	// native-image buildpack），有 plugin 貢獻的 META-INF/native-image/ metadata
	// 即觸發 native compile chain — bootBuildImage 直接產 native binary。
	id("org.graalvm.buildtools.native") version "0.11.5"
	id("org.cyclonedx.bom") version "3.2.4"
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
// S023: ShedLock — 多 instance @Scheduled 互斥；無 BOM 須顯式版本（ShedLock 7.x 支援 Spring Boot 4.x）
extra["shedlockVersion"] = "7.7.0"

dependencies {
	// implementation("org.springframework.boot:spring-boot-micrometer-tracing-brave")
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	// implementation("org.springframework.boot:spring-boot-starter-cache")
	implementation("org.springframework.boot:spring-boot-starter-data-jdbc")
	implementation("org.springframework.boot:spring-boot-starter-jdbc")
	// S014 T8: Spring AI core artifact（非 auto-config starter）
	// 自寫 SkillshubPgVectorStore 子類控制 6-欄 INSERT；§4.14 / §2.1 決策 #2/#12（再修訂）
	implementation("org.springframework.ai:spring-ai-pgvector-store")
	// implementation("org.springframework.boot:spring-boot-starter-opentelemetry")
	implementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	implementation("com.google.cloud:spring-cloud-gcp-starter")
	implementation("com.google.cloud:spring-cloud-gcp-starter-storage")
	// Spring Cloud GCP Secret Manager — 供 application-gcp.yaml 內 ${sm@<secret-id>}
	// placeholder 在 startup 時自動 resolve 成實際值（取代 Cloud Run secretKeyRef block）。
	// BOM 已 manage 8.0.2；無需顯式版本。
	implementation("com.google.cloud:spring-cloud-gcp-starter-secretmanager")
	implementation("io.micrometer:micrometer-tracing-bridge-brave")
	implementation("org.springframework.ai:spring-ai-markdown-document-reader")
	implementation("org.springframework.ai:spring-ai-google-genai-embedding")
	implementation("org.springframework.ai:spring-ai-google-genai")
	implementation("org.springframework.ai:spring-ai-client-chat")
	implementation("org.springframework.ai:spring-ai-vector-store")
	implementation("org.springframework.modulith:spring-modulith-starter-core")
	// S023: Spring Modulith Event Publication Registry (transactional outbox)
	implementation("org.springframework.modulith:spring-modulith-starter-jdbc")
	// S023: ShedLock — 多 Cloud Run instance @Scheduled retry 互斥
	implementation("net.javacrumbs.shedlock:shedlock-spring:${property("shedlockVersion")}")
	implementation("net.javacrumbs.shedlock:shedlock-provider-jdbc-template:${property("shedlockVersion")}")
	developmentOnly("org.springframework.boot:spring-boot-devtools")
	developmentOnly("org.springframework.boot:spring-boot-docker-compose")
	// S014: Flyway schema migration（V1 建立 6 張表 + extensions + indexes）
	// Spring Boot 4 把 Flyway auto-config 拆到獨立 artifact，需顯式引入
	implementation("org.springframework.boot:spring-boot-flyway")
	implementation("org.flywaydb:flyway-core")
	runtimeOnly("org.flywaydb:flyway-database-postgresql")
	// S014: PostgreSQL JDBC driver
	runtimeOnly("org.postgresql:postgresql")
	runtimeOnly("io.micrometer:micrometer-registry-otlp")
	developmentOnly("org.springframework.ai:spring-ai-spring-boot-docker-compose")
	runtimeOnly("org.springframework.modulith:spring-modulith-actuator")
	runtimeOnly("org.springframework.modulith:spring-modulith-observability")
	annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")
	testImplementation("org.springframework.boot:spring-boot-micrometer-tracing-test")
	testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
	testImplementation("org.springframework.boot:spring-boot-starter-cache-test")
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

// processAot 階段啟用的 profile 組合 — Spring AOT freeze 該組合下的 @Profile bean
// definitions 進 native binary。runtime SPRING_PROFILES_ACTIVE 不能再 dynamically
// swap 出 baked profile（per spring-boot Issue #41562/#48408），所以 AOT 階段就要
// 列齊 native runtime 想用的 profile。
//
// Property key 對齊 Spring 標準名 `spring.profiles.active`（同 yaml 屬性 / runtime
// env var SPRING_PROFILES_ACTIVE / args 旗標），跨層命名一致零認知負擔。
//
// 預設 aot,local；不同環境用 -P 覆蓋：
//   本機:    ./gradlew bootBuildImage                                      → aot,local
//   CI lab:  ./gradlew bootBuildImage -Pspring.profiles.active=aot,gcp,lab
//   CI prod: ./gradlew bootBuildImage -Pspring.profiles.active=aot,gcp,prod
//
// Per Spring Boot AOT how-to: https://docs.spring.io/spring-boot/how-to/aot.html
tasks.withType<org.springframework.boot.gradle.tasks.aot.ProcessAot>().configureEach {
	val profiles = (project.findProperty("spring.profiles.active") as? String) ?: "aot,local"
	args("--spring.profiles.active=$profiles")
}

tasks.withType<Test> {
	useJUnitPlatform()
}

// S019 T1: JaCoCo coverage gate — pin toolVersion 0.8.14（首版官方支援 Java 25 bytecode）
jacoco {
	toolVersion = "0.8.14"
}

tasks.test {
	finalizedBy(tasks.jacocoTestReport)
	// S025b T05 ship — slice 重組落地後 cache key 從 baseline ~42 降至 ~18（per T05 indirect
	// measurement: pgvector container 啟動 18 次/run）。S023 T07 quick-win 的 cache.maxSize=8 已
	// 移除（T01）；maxHeapSize 從 3g 降至 2g（仍需 — 18 個 @SpringBootTest CONFIG bucket 受限於
	// 既有 customizer 多樣性）。後續 S025c 進一步 consolidate CONFIG bucket 至 ≤10 keys + 還原
	// default heap。詳 spec §7 AC-8 / AC-9 deviation rationale。
	maxHeapSize = "2g"
}

tasks.jacocoTestReport {
	dependsOn(tasks.test)
	reports {
		xml.required = true
		html.required = true
		csv.required = true  // S020 verify-all.sh awk 解析來源（cross-spec contract）
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

// S019 T2: project-wide LINE coverage gate；threshold = 0.80（T1 POC baseline = 0.8803 ≥ 0.80）
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

// S019 T2: 接 check lifecycle — Gradle JaCoCo plugin 預設不接（須顯式宣告）
tasks.check {
	dependsOn(tasks.jacocoTestCoverageVerification)
}
