plugins {
	java
	id("org.springframework.boot") version "4.0.6"
	id("io.spring.dependency-management") version "1.1.7"
	id("org.graalvm.buildtools.native") version "0.11.5"
	id("org.cyclonedx.bom") version "3.2.4"
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

dependencies {
	// implementation("org.springframework.boot:spring-boot-micrometer-tracing-brave")
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	// implementation("org.springframework.boot:spring-boot-starter-cache")
	implementation("org.springframework.boot:spring-boot-starter-data-mongodb")
	// implementation("org.springframework.boot:spring-boot-starter-opentelemetry")
	// implementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	implementation("com.google.cloud:google-cloud-firestore:3.31.6")
	implementation("com.google.cloud:spring-cloud-gcp-starter")
	implementation("com.google.cloud:spring-cloud-gcp-starter-storage")
	implementation("io.micrometer:micrometer-tracing-bridge-brave")
	implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.0.2")
	implementation("org.springframework.ai:spring-ai-markdown-document-reader")
	implementation("org.springframework.modulith:spring-modulith-starter-core")
	developmentOnly("org.springframework.boot:spring-boot-devtools")
	developmentOnly("org.springframework.boot:spring-boot-docker-compose")
	runtimeOnly("io.micrometer:micrometer-registry-otlp")
	developmentOnly("org.springframework.ai:spring-ai-spring-boot-docker-compose")
	runtimeOnly("org.springframework.modulith:spring-modulith-actuator")
	runtimeOnly("org.springframework.modulith:spring-modulith-observability")
	annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")
	testImplementation("org.springframework.boot:spring-boot-micrometer-tracing-test")
	testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
	testImplementation("org.springframework.boot:spring-boot-starter-cache-test")
	testImplementation("org.springframework.boot:spring-boot-starter-data-mongodb-test")
	testImplementation("org.springframework.boot:spring-boot-starter-opentelemetry-test")
	// testImplementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server-test")
	testImplementation("org.springframework.boot:spring-boot-starter-validation-test")
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	testImplementation("org.springframework.boot:spring-boot-testcontainers")
	testImplementation("org.springframework.ai:spring-ai-spring-boot-testcontainers")
	testImplementation("org.springframework.modulith:spring-modulith-starter-test")
	testImplementation("org.testcontainers:testcontainers-gcloud")
	testImplementation("org.testcontainers:testcontainers-grafana")
	testImplementation("org.testcontainers:testcontainers-junit-jupiter")
	testImplementation("org.testcontainers:testcontainers-mongodb")
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
