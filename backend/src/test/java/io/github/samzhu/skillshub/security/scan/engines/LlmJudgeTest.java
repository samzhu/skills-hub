package io.github.samzhu.skillshub.security.scan.engines;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import io.github.samzhu.skillshub.security.scan.Phase;
import io.github.samzhu.skillshub.security.scan.ScanContext;
import io.github.samzhu.skillshub.security.scan.SecurityFinding;
import io.github.samzhu.skillshub.security.scan.Severity;

class LlmJudgeTest {

	/** 攔截 ChatModel.call() 的 prompt 並回傳 canned content。 */
	static final class CapturingStubChatModel implements ChatModel {
		private final String cannedContent;
		final AtomicReference<Prompt> capturedPrompt = new AtomicReference<>();
		CapturingStubChatModel(String content) { this.cannedContent = content; }
		@Override
		public ChatResponse call(Prompt prompt) {
			capturedPrompt.set(prompt);
			return new ChatResponse(List.of(new Generation(
					new AssistantMessage(cannedContent), ChatGenerationMetadata.NULL)));
		}
	}

	/** 故意拋例外的 ChatModel — 驗 AC-6.3。 */
	static final class FailingStubChatModel implements ChatModel {
		@Override
		public ChatResponse call(Prompt prompt) {
			throw new RuntimeException("simulated API failure");
		}
	}

	private static ScanContext withPhase1(List<SecurityFinding> findings, String skillMd, Map<String, String> scripts) {
		return new ScanContext("skill-x", "1.0.0", Map.of("name", "demo", "description", "x"),
				skillMd, scripts, findings);
	}

	@Test
	@DisplayName("AC-6.1: LLM user prompt 含 Phase 1 finding 的 ruleId + filePath:line")
	@Tag("AC-6")
	void promptIncludesPhase1Summary() {
		var stub = new CapturingStubChatModel("""
				{"verdict":"SAFE","reasoning":"none","claims":[]}
				""");
		var judge = new LlmJudge(java.util.Optional.of(ChatClient.create(stub)));

		var phase1 = List.of(new SecurityFinding(
				"DANGEROUS_COMMAND_RM_RF", Severity.HIGH, "rm -rf",
				"scripts/setup.sh", 3, "rm -rf /home", "pattern", "AST06"));
		judge.analyze(withPhase1(phase1, "# safe content", Map.of()));

		var promptText = stub.capturedPrompt.get().getContents();
		assertThat(promptText).contains("DANGEROUS_COMMAND_RM_RF");
		assertThat(promptText).contains("scripts/setup.sh:3");
	}

	@Test
	@DisplayName("AC-6.2: 結構化輸出 → SecurityFinding(analyzer=llm-judge) + ScanNotice(verdict reasoning)")
	@Tag("AC-6")
	void structuredOutputProducesFindingAndNotice() {
		var canned = """
				{
				  "verdict":"SUSPICIOUS",
				  "reasoning":"Found pipe-to-shell pattern bypassing signature verification.",
				  "claims":[
				    {"ruleId":"OBFUSCATED_INTENT","severity":"HIGH",
				     "message":"Script downloads and runs unverified code","filePath":"scripts/install.sh",
				     "line":12,"owaspAst":"AST04"}
				  ]
				}
				""";
		var stub = new CapturingStubChatModel(canned);
		var judge = new LlmJudge(java.util.Optional.of(ChatClient.create(stub)));

		var output = judge.analyze(withPhase1(List.of(), "# md", Map.of()));

		assertThat(output.findings()).hasSize(1);
		var f = output.findings().get(0);
		assertThat(f.analyzer()).isEqualTo("llm-judge");
		assertThat(f.ruleId()).isEqualTo("OBFUSCATED_INTENT");
		assertThat(f.severity()).isEqualTo(Severity.HIGH);
		assertThat(f.filePath()).isEqualTo("scripts/install.sh");
		assertThat(f.line()).isEqualTo(12);
		assertThat(f.owaspAst()).isEqualTo("AST04");

		assertThat(output.notices()).hasSize(1);
		var n = output.notices().get(0);
		assertThat(n.source()).isEqualTo("llm-judge");
		assertThat(n.message()).contains("pipe-to-shell");
	}

	@Test
	@DisplayName("AC-6.2: claims 為空時，findings 空但 notices 仍含 reasoning")
	@Tag("AC-6")
	void emptyClaimsStillProducesNotice() {
		var stub = new CapturingStubChatModel("""
				{"verdict":"SAFE","reasoning":"No risky behavior detected.","claims":[]}
				""");
		var judge = new LlmJudge(java.util.Optional.of(ChatClient.create(stub)));

		var output = judge.analyze(withPhase1(List.of(), "", Map.of()));

		assertThat(output.findings()).isEmpty();
		assertThat(output.notices()).hasSize(1);
		assertThat(output.notices().get(0).message()).contains("No risky behavior");
	}

	@Test
	@DisplayName("AC-6.3: LLM 拋例外 → graceful degradation，回傳 empty AnalysisOutput")
	@Tag("AC-6")
	void llmFailureReturnsEmpty() {
		var judge = new LlmJudge(java.util.Optional.of(ChatClient.create(new FailingStubChatModel())));

		var output = judge.analyze(withPhase1(List.of(), "", Map.of()));

		assertThat(output.findings()).isEmpty();
		assertThat(output.notices()).isNotEmpty();   // 應有 1 筆說明 LLM 失敗
		assertThat(output.notices().get(0).source()).isEqualTo("llm-judge");
		assertThat(output.notices().get(0).message().toLowerCase()).contains("failed");
	}

	@Test
	@DisplayName("LlmJudge 是 SecurityAnalyzer，phase=LLM, name=\"llm-judge\"")
	void implementsSecurityAnalyzerContract() {
		var stub = new CapturingStubChatModel("{\"verdict\":\"SAFE\",\"reasoning\":\"x\",\"claims\":[]}");
		var judge = new LlmJudge(java.util.Optional.of(ChatClient.create(stub)));

		assertThat(judge.name()).isEqualTo("llm-judge");
		assertThat(judge.phase()).isEqualTo(Phase.LLM);
	}

	@Test
	@DisplayName("Prompt 含 frontmatter 與 SKILL.md（截斷後）")
	@Tag("AC-6")
	void promptIncludesFrontmatterAndSkillMd() {
		var stub = new CapturingStubChatModel("{\"verdict\":\"SAFE\",\"reasoning\":\"x\",\"claims\":[]}");
		var judge = new LlmJudge(java.util.Optional.of(ChatClient.create(stub)));

		var ctx = new ScanContext("s", "1.0.0",
				Map.of("name", "my-skill", "description", "do things"),
				"# Hello\nThis is the skill body content.",
				Map.of("scripts/run.sh", "#!/bin/bash\necho hi"),
				List.of());
		judge.analyze(ctx);

		var promptText = stub.capturedPrompt.get().getContents();
		assertThat(promptText).contains("my-skill");
		assertThat(promptText).contains("This is the skill body");
		assertThat(promptText).contains("scripts/run.sh");
	}
}
