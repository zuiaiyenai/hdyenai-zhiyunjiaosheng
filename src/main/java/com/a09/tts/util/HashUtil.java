package com.a09.tts.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 用于进行哈希加密的工具类，使用时需先在方法中实例化HashUtil
 */

public class HashUtil {
    /**
     * sha256加密
     *
     * @param password 密码
     * @return 密态密码
     */
    public String sha256(String password) {
        String cipherString = null;
        try {
            // 获取实例
            MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
            // 计算摘要
            byte[] cipherBytes = messageDigest.digest(password.getBytes(StandardCharsets.UTF_8));
            // 输出为16进制字符串
            StringBuilder sb = new StringBuilder();
            for (byte b : cipherBytes) {
                sb.append(String.format("%02x", b));
            }
            cipherString = sb.toString();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return cipherString;
    }

}
