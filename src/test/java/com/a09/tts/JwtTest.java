package com.a09.tts;

import com.auth0.jwt.JWT;
import org.junit.jupiter.api.Test;

public class JwtTest {

    @Test
    public void testGenerateToken() {
        //生成JWT
        JWT.create();

    }
}
