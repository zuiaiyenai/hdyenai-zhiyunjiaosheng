package com.a09.tts.service;

import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Service
public class MoonshotChatClient {
    private final OpenAiChatModel chatModel;

    public MoonshotChatClient(
            @Value("${moonshot.api.key:}") String apiKey,
            @Value("${moonshot.api.base-url:https://api.moonshot.cn/v1}") String baseUrl,
            @Value("${moonshot.api.model:kimi-k2.6}") String model) {
        if (apiKey == null || apiKey.isBlank()) {
            this.chatModel = null;
            return;
        }

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(10));
        requestFactory.setReadTimeout(Duration.ofMinutes(2));
        RestClient.Builder restClientBuilder = RestClient.builder()
                .requestFactory(requestFactory);

        OpenAiApi openAiApi = OpenAiApi.builder()
                .baseUrl(normalizeBaseUrl(baseUrl))
                .apiKey(apiKey)
                .restClientBuilder(restClientBuilder)
                .build();
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(model)
                .temperature(1.0)
                .build();
        this.chatModel = OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(options)
                .build();
    }

    static String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return "https://api.moonshot.cn";
        }
        return baseUrl.trim().replaceFirst("/v1/?$", "");
    }

    public boolean isConfigured() {
        return chatModel != null;
    }

    public String generate(String systemPrompt, String userPrompt) {
        if (chatModel == null) {
            throw new IllegalStateException("请配置 MOONSHOT_API_KEY");
        }
        ChatResponse response = chatModel.call(new Prompt(
                systemPrompt + "\n\n用户请求：\n" + userPrompt));
        String content = response.getResult().getOutput().getText();
        if (content == null || content.isBlank()) {
            throw new IllegalStateException("Moonshot 未返回有效文本");
        }
        return content;
    }
}
