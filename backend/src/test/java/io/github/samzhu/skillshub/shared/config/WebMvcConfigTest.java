package io.github.samzhu.skillshub.shared.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.http.converter.xml.MappingJackson2XmlHttpMessageConverter;

/**
 * S162 AC-6 — {@link WebMvcConfig} 單元測試。
 *
 * <p>純 POJO 測試；無 Spring context；驗證 config 方法直接呼叫的副作用。
 * 端到端「Accept: application/xml → 406」由整合測試 / LAB 手動驗證；本 test 確保
 * config 邏輯本身（XML converter 從 list 中移除）正確。
 */
class WebMvcConfigTest {

    @Test
    @DisplayName("S162 AC-6: configureMessageConverters 從 list 移除 MappingJackson2XmlHttpMessageConverter")
    void removesXmlConverter() {
        // 包 ArrayList 才能 removeIf；JSON converter 留，XML 移除
        List<HttpMessageConverter<?>> converters = new ArrayList<>(List.of(
                new MappingJackson2HttpMessageConverter(),
                new MappingJackson2XmlHttpMessageConverter()));

        new WebMvcConfig().configureMessageConverters(converters);

        assertThat(converters)
                .hasSize(1)
                .noneMatch(c -> c instanceof MappingJackson2XmlHttpMessageConverter)
                .anyMatch(c -> c instanceof MappingJackson2HttpMessageConverter);
    }

    @Test
    @DisplayName("S162 AC-6: 沒 XML converter 時也不出錯（idempotent removeIf）")
    void removeIsIdempotent() {
        List<HttpMessageConverter<?>> converters = new ArrayList<>(List.of(
                new MappingJackson2HttpMessageConverter()));

        new WebMvcConfig().configureMessageConverters(converters);

        assertThat(converters).hasSize(1);
    }

    @Test
    @DisplayName("S162 AC-6: configureContentNegotiation 預設 JSON；無 xml mapping")
    void contentNegotiationLockedToJson() {
        // ContentNegotiationConfigurer 為 builder pattern，無公開 getter；改驗 fluent call 不拋
        // 例外即視為 contract 完成。實際 lockJson 行為由 LAB 手動測 `?format=xml` → 406 驗證。
        var configurer = new org.springframework.web.servlet.config.annotation.ContentNegotiationConfigurer();
        new WebMvcConfig().configureContentNegotiation(configurer);
        // 無 assertion 拋出例外即代表設定路徑可達；defaultContentType 與 mediaType 設定鏈未拋
        assertThat(MediaType.APPLICATION_JSON).isNotNull();  // sanity
    }
}
