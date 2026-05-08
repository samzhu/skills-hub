package io.github.samzhu.skillshub.score;

import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;
import org.springframework.context.annotation.Configuration;

import io.github.samzhu.skillshub.score.judge.JudgeResponse;

/**
 * S148: GraalVM AOT reflection hints for LLM judge JSON binding.
 *
 * <p>Spring AI {@code BeanOutputConverter.entity(Class<?>)} 透過 Jackson 反射讀取 record
 * components；GraalVM native image 在 build 時無法靜態分析這個泛型路徑，所以 AOT processor
 * 不會自動為 {@link JudgeResponse} 與其巢狀 record 產生反射 metadata，runtime 觸發
 * {@code UnsupportedFeatureError: Record components not available}。
 *
 * <p>本 config 只用來提供 AOT hint，不宣告任何 bean。
 *
 * @see <a href="https://docs.spring.io/spring-framework/reference/core/aot.html#aot.hints.register-reflection-for-binding">Spring AOT Hints</a>
 */
@Configuration(proxyBeanMethods = false)
@RegisterReflectionForBinding({JudgeResponse.class, JudgeResponse.DimensionScore.class})
class ScoreNativeConfig {}
