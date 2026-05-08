package io.github.samzhu.skillshub.shared.config;

import java.util.List;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.xml.MappingJackson2XmlHttpMessageConverter;
import org.springframework.web.servlet.config.annotation.ContentNegotiationConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * S162 AC-6 — 鎖死 REST API content negotiation 為 JSON only。
 *
 * <p>背景：Spring Boot 在 classpath 偵測到 {@code jackson-dataformat-xml}（transitive
 * dep，via SpringDoc / 其他 starter）時自動註冊 {@link MappingJackson2XmlHttpMessageConverter}
 * → {@code Accept: application/xml} 走 XML 序列化回傳；非預期 surface area，OpenAPI 3.1
 * spec 只承諾 JSON。
 *
 * <p><b>修法</b>：
 * <ul>
 *   <li>{@link #configureMessageConverters} 移除 XML converter — `Accept: application/xml`
 *       不再有 converter 匹配 → Spring 回 406 Not Acceptable</li>
 *   <li>{@link #configureContentNegotiation} 鎖預設 media type 為 JSON；不註冊 xml mapping</li>
 * </ul>
 *
 * <p><b>不影響</b>：
 * <ul>
 *   <li>Skill bundle 下載走 {@code application/octet-stream}（per-endpoint produces）</li>
 *   <li>SKILL.md / `text/markdown` endpoint 走 per-endpoint produces</li>
 *   <li>Actuator 自有 chain，不依本 config</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
class WebMvcConfig implements WebMvcConfigurer {

    @Override
    public void configureMessageConverters(List<HttpMessageConverter<?>> converters) {
        converters.removeIf(c -> c instanceof MappingJackson2XmlHttpMessageConverter);
    }

    @Override
    public void configureContentNegotiation(ContentNegotiationConfigurer configurer) {
        configurer
                .defaultContentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .mediaType("json", org.springframework.http.MediaType.APPLICATION_JSON);
        // 不註冊 xml mapping — `?format=xml` 也不認
    }
}
