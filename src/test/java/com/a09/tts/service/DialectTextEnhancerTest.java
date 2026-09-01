package com.a09.tts.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DialectTextEnhancerTest {
    @Test
    void makesNortheastSpeechMoreColloquialWithoutChangingOtherDialects() {
        String text = "普通话是我们沟通的桥梁，不仅让大家聊天更顺畅。";

        assertThat(DialectTextEnhancer.enhance(text, "东北话"))
                .isEqualTo("普通话是咱们沟通的桥梁，不光让大伙儿唠嗑更顺溜。");
        assertThat(DialectTextEnhancer.enhance(text, "粤语")).isEqualTo(text);
    }
}
