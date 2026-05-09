package io.github.samzhu.skillshub.shared.api;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import tools.jackson.databind.MapperFeature;

/**
 * S165 — Jackson 3 / Spring Boot 4：強制 enable {@link MapperFeature#DEFAULT_VIEW_INCLUSION}。
 *
 * <p>Jackson 3 預設 {@code DEFAULT_VIEW_INCLUSION=false}，啟用 {@code @JsonView} 後未標欄位
 * 全部被排除，導致 {@code Page<Skill>} wrapper 序列化成 {@code {}}（S158 prod hotfix root cause）。
 *
 * <p>用 {@link JsonMapperBuilderCustomizer} bean 直接 enable feature 比走
 * {@code spring.jackson.mapper.default-view-inclusion} property 更直接、不依賴 property
 * binding 路徑（per Spring Boot 4 docs — "context's JsonMapper.Builder can be customized
 * by one or more JsonMapperBuilderCustomizer beans"）。
 *
 * @see <a href="https://docs.spring.io/spring-boot/how-to/spring-mvc.html#howto.spring-mvc.customize-jackson-jsonmapper">Spring Boot 4 — Customize Jackson JsonMapper</a>
 */
@Configuration
public class JacksonConfiguration {

    @Bean
    JsonMapperBuilderCustomizer enableDefaultViewInclusion() {
        return builder -> builder.enable(MapperFeature.DEFAULT_VIEW_INCLUSION);
    }
}
