package com.a09.tts.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {
    private final JwtAuthenticationInterceptor authenticationInterceptor;

    public WebMvcConfig(JwtAuthenticationInterceptor authenticationInterceptor) {
        this.authenticationInterceptor = authenticationInterceptor;
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
    }
}
