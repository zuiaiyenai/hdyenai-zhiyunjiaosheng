package com.a09.tts;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties = "app.storage.provider=local")
@ActiveProfiles("nodb")
class TtsApplicationTests {

    @Test
    void contextLoads() {
    }

}
