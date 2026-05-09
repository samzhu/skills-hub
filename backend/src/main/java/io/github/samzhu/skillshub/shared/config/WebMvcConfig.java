package io.github.samzhu.skillshub.shared.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import io.github.samzhu.skillshub.shared.api.UnknownQueryParamInterceptor;

/**
 * S159a — 註冊 {@link UnknownQueryParamInterceptor} 攔截 SkillQuery / categories
 * 端點的未知 query 參數。
 *
 * <p>Path 限定 {@code /api/v1/skills/**} + {@code /api/v1/categories} 漸進啟用：
 * 老 client（CLI / bookmark）若其他端點帶 stale param 暫不擋，待後續 sub-spec
 * （S159b/c/d）擴展全 controller。
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(new UnknownQueryParamInterceptor())
				.addPathPatterns("/api/v1/skills/**", "/api/v1/skills", "/api/v1/categories");
	}
}
