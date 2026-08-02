package com.a09.tts.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("!nodb")
@MapperScan("com.a09.tts.mapper")
public class MyBatisConfig {
}