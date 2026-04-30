plugins {
	java
	id("org.springframework.boot") version "4.0.6"
	id("io.spring.dependency-management") version "1.1.7"
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

extra["springAiVersion"] = "2.0.0-M4"
extra["springCloudGcpVersion"] = "8.0.2"
extra["springCloudVersion"] = "2025.1.1"
extra["springModulithVersion"] = "2.0.6"
// S023: ShedLock — 多 instance @Scheduled 互斥；無 BOM 須顯式版本（ShedLock 7.x 支援 Spring Boot 4.x）
extra["shedlockVersion"] = "7.7.0"

dependencies {
	// implementation("org.springframework.boot:spring-boot-micrometer-tracing-brave")
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	// implementation("org.springframework.boot:spring-boot-starter-cache")
	// S014: PostgreSQL data layer（取代 spring-boot-starter-data-mongodb）
	implementation("org.springframework.boot:spring-boot-starter-data-jdbc")
	implementation("org.springframework.boot:spring-boot-starter-jdbc")
	// S014 T8: Spring AI core artifact（非 auto-config starter）
	// 自寫 SkillshubPgVectorStore 子類控制 6-欄 INSERT；§4.14 / §2.1 決策 #2/#12（再修訂）
	implementation("org.springframework.ai:spring-ai-pgvector-store")
	// implementation("org.springframework.boot:spring-boot-starter-opentelemetry")
	implementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	// S014 T7: google-cloud-firestore dep 移除（決策 #10；FirestoreVectorStore.java 同步刪除）
	implementation("com.google.cloud:spring-cloud-gcp-starter")
	implementation("com.google.cloud:spring-cloud-gcp-starter-storage")
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
	// NOTE: Cloud SQL Java Connector (com.google.cloud.sql:postgres-socket-factory)
	// 加入會觸發 spring-cloud-gcp-autoconfigure 的 CloudSqlEnvironmentPostProcessor，
	// 要求 spring.cloud.gcp.sql.database-name；屬 T5 GCP profile 完整配置範圍，
	// T1 不引入。
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

val frontendDir = file("${rootProject.projectDir}/../frontend")

val npmInstall by tasks.registering(Exec::class) {
	workingDir = frontendDir
	commandLine("npm", "install")
	inputs.file(frontendDir.resolve("package.json"))
	outputs.dir(frontendDir.resolve("node_modules"))
}

val npmBuild by tasks.registering(Exec::class) {
	dependsOn(npmInstall)
	workingDir = frontendDir
	commandLine("npm", "run", "build")
	inputs.dir(frontendDir.resolve("src"))
	inputs.file(frontendDir.resolve("package.json"))
	inputs.file(frontendDir.resolve("vite.config.ts"))
	inputs.file(frontendDir.resolve("tsconfig.json"))
	outputs.dir(frontendDir.resolve("dist"))
}

val copyFrontend by tasks.registering(Copy::class) {
	dependsOn(npmBuild)
	from(frontendDir.resolve("dist"))
	into(layout.buildDirectory.dir("resources/main/static"))
}

tasks.named("processResources") {
	dependsOn(copyFrontend)
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
