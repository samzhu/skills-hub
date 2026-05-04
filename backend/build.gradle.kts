plugins {
	java
	id("org.springframework.boot") version "4.0.6"
	id("io.spring.dependency-management") version "1.1.7"
	// S132：org.graalvm.buildtools.native 走官方標準 plugin。auto-apply boot.aot
	// → processAot task 註冊 → Spring Boot AOT artifacts 烤進 jar。
	// 我們目前不做 native binary（部署用 Paketo JVM image），但 plugin 留著
	// 以便將來想試 native compile 時 0 line 改即可。
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
	implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.0.2")
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

tasks.named<Copy>("processResources") {
	// 把 backend/config/application-{prod,lab}.yaml 拷進 image classpath，讓 Cloud Run
	// 跑 SPRING_PROFILES_ACTIVE=gcp,prod 或 gcp,lab 時能載入。
	// dev profile 與 secrets properties 維持本地專用，不拷。
	//
	// S132：前端 dist 不再走 Gradle。由 CI cloudbuild.yaml step 2（或本機
	// scripts/gcp/03-build-push.sh）拷進 src/main/resources/static/；本機 bootRun
	// 純後端，frontend hot reload 改 `cd frontend && npm run dev`（避開 frontend
	// TS 錯誤擋住後端啟動）。
	from(projectDir.resolve("config")) {
		include("application-prod.yaml", "application-lab.yaml")
	}
}

// S132：processAot 階段啟用 `aot` profile —— 載入 application-aot.yaml + 觸發
// AotStubConfig（@Profile("aot")）提供 stub DataSource bean。
// args() 走 SimpleCommandLinePropertySource（最高優先級），覆蓋
// application.yaml 的 spring.profiles.default。Per Spring Boot AOT how-to。
// Ref: https://docs.spring.io/spring-boot/how-to/aot.html
tasks.withType<org.springframework.boot.gradle.tasks.aot.ProcessAot>().configureEach {
	args("--spring.profiles.active=aot")
}

// S132: bootBuildImage Paketo buildpack 環境變數
//
// 兩層 AOT 並存：
//   1. Spring Boot AOT (build-time)        — graalvm plugin 觸發 processAot task，產
//                                            ApplicationContextInitializer 烤進 jar
//   2. JVM AOT Cache (Paketo training run) — BP_JVM_AOTCACHE_ENABLED=true 讓 buildpack
//                                            在 build 跑一次 app capture class loading，
//                                            存 .aot file 進 image，runtime JVM 載入
//                                            (Java 25 JEP 514；docs.spring.io/spring-boot
//                                            /how-to/aot-cache.html)
//
// 變數說明：
//   BP_JVM_AOTCACHE_ENABLED=true              啟用 AOT Cache training run + 烤進 image
//   BP_JVM_CDS_ENABLED=false                  暫關 CDS — Spring Boot 4 + Java 25 + CDS
//                                             known bug (paketo-buildpacks/spring-boot#581)
//   TRAINING_RUN_JAVA_TOOL_OPTIONS=...        Training run 階段 JVM args；阻止連 DB / GCP
//                                             等 remote services（Paketo 官方 hook，per
//                                             spring-projects/spring-lifecycle-smoke-tests/
//                                             blob/main/README.adoc#training-run-configuration）
//   BPE_APPEND_JAVA_TOOL_OPTIONS=             Runtime JVM 載入 Spring Boot AOT artifacts
//     -Dspring.aot.enabled=true               （非必要，Paketo Spring Boot buildpack 偵測
//                                             META-INF/native-image 後自動設；顯式寫保險）
//   BPE_DELIM_JAVA_TOOL_OPTIONS=" "           多個 JAVA_TOOL_OPTIONS 用空白接
//
// BP_JVM_VERSION 不顯式設 — Paketo Java buildpack 從 jar 內 META-INF/MANIFEST.MF 的
// `Build-Jdk-Spec` header auto-detect（由 Spring Boot Gradle plugin 從
// java.toolchain.languageVersion line 14-16，目前 25 寫入）。
tasks.named<org.springframework.boot.gradle.tasks.bundling.BootBuildImage>("bootBuildImage") {
	// graalvm.buildtools.native plugin 貢獻 META-INF/native-image/ metadata 到 jar，
	// Paketo native-image buildpack 偵測到後 auto-trigger 真實 native binary 編譯
	// （15+ 分鐘）。我們只要 JVM AOT artifacts 給 runtime 用，不要 native compile。
	environment.put("BP_NATIVE_IMAGE", "false")
	environment.put("BP_JVM_AOTCACHE_ENABLED", "true")
	environment.put("BP_JVM_CDS_ENABLED", "false")
	// Training run 啟用 aot profile 載入 application-aot.yaml stub config（同 ProcessAot
	// 一份來源，不重複維護）。Spring Boot 從 -D 系統屬性讀 spring.profiles.active；
	// Paketo 把 TRAINING_RUN_JAVA_TOOL_OPTIONS 注進 training run JVM。
	environment.put("TRAINING_RUN_JAVA_TOOL_OPTIONS", "-Dspring.profiles.active=aot")
	environment.put("BPE_DELIM_JAVA_TOOL_OPTIONS", " ")
	environment.put("BPE_APPEND_JAVA_TOOL_OPTIONS", "-Dspring.aot.enabled=true")
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
