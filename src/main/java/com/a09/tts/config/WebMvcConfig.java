package com.a09.tts.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {
    private final JwtAuthenticationInterceptor authenticationInterceptor;
    private final UserRateLimitInterceptor rateLimitInterceptor;

    public WebMvcConfig(JwtAuthenticationInterceptor authenticationInterceptor,
                        UserRateLimitInterceptor rateLimitInterceptor) {
        this.authenticationInterceptor = authenticationInterceptor;
        this.rateLimitInterceptor = rateLimitInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authenticationInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns(
                        "/", "/index.html", "/login.html", "/favicon.ico", "/error",
                        "/user/login", "/user/register", "/api/user/**",
                        "/actuator/health", "/css/**", "/js/**", "/assets/**",
                        "/images/**", "/static/**", "/ws/asr/stream");
        registry.addInterceptor(rateLimitInterceptor).addPathPatterns("/**");
    }
}
