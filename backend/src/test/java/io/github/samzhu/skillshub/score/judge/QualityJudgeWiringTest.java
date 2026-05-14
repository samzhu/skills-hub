package io.github.samzhu.skillshub.score.judge;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class QualityJudgeWiringTest {

    @Test
    @DisplayName("AC-S171-2: QualityJudge depends on ChatClient, not GoogleGenAiChatModel")
    void qualityJudgeDependsOnChatClientNotGoogleProvider() throws Exception {
        var qualityJudge = Files.readString(Path.of(
                "src/main/java/io/github/samzhu/skillshub/score/judge/QualityJudge.java"));
        var qualityConfig = Files.readString(Path.of(
                "src/main/java/io/github/samzhu/skillshub/score/judge/QualityJudgeConfig.java"));
        var scannerConfig = Files.readString(Path.of(
                "src/main/java/io/github/samzhu/skillshub/security/scan/ScannerAiConfig.java"));

        assertThat(qualityJudge).contains("import org.springframework.ai.chat.client.ChatClient;");
        assertThat(qualityJudge).doesNotContain("GoogleGenAiChatModel");
        assertThat(qualityJudge).doesNotContain("com.google.genai.Client");
        assertThat(qualityConfig).doesNotContain("GoogleGenAiChatModel");
        assertThat(qualityConfig).doesNotContain("com.google.genai.Client");
        assertThat(scannerConfig).doesNotContain("GoogleGenAiChatModel");
        assertThat(scannerConfig).doesNotContain("com.google.genai.Client");
    }
}
