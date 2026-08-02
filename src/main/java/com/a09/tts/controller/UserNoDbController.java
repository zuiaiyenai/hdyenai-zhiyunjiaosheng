package com.a09.tts.controller;

import com.a09.tts.util.HashUtil;
import com.a09.tts.util.JwtUtil;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/user")
@Profile("nodb")
public class UserNoDbController {

    private static final Logger log = LoggerFactory.getLogger(UserNoDbController.class);

    private final ConcurrentHashMap<String, String> users = new ConcurrentHashMap<>();

    @Autowired
    private JwtUtil jwtUtil;

    public UserNoDbController() {
        HashUtil hashUtil = new HashUtil();
        users.put("admin", hashUtil.sha256("admin123"));
        users.put("demo", hashUtil.sha256("demo123"));
    }

    @PostConstruct
    public void init() {
        log.info("无数据库模式已启动，初始用户: admin/admin123, demo/demo123 (JWT: {})", jwtUtil != null ? "正常" : "失败-使用备用Token");
    }

    @PostMapping("/register")
    public ResponseEntity<Map<String, Object>> registerUser(@RequestBody Map<String, String> body) {
        Map<String, Object> result = new HashMap<>();
        try {
            String username = body.get("username");
            String password = body.get("password");

            if (!StringUtils.hasText(username) || !StringUtils.hasText(password)) {
                result.put("code", 400);
                result.put("msg", "用户名或密码不能为空");
                return new ResponseEntity<>(result, HttpStatus.BAD_REQUEST);
            }

            if (password.length() < 6) {
                result.put("code", 400);
                result.put("msg", "密码不能少于6位");
                return new ResponseEntity<>(result, HttpStatus.BAD_REQUEST);
            }

            if (users.containsKey(username)) {
                result.put("code", 400);
                result.put("msg", "用户名已存在");
                return new ResponseEntity<>(result, HttpStatus.BAD_REQUEST);
            }

            HashUtil hashUtil = new HashUtil();
            users.put(username, hashUtil.sha256(password));

            result.put("code", 201);
            result.put("msg", "注册成功");
            return new ResponseEntity<>(result, HttpStatus.CREATED);
        } catch (Exception e) {
            log.error("注册操作有误", e);
            result.put("code", 500);
            result.put("msg", "注册失败：" + (e.getMessage() != null ? e.getMessage() : "系统内部错误"));
            return new ResponseEntity<>(result, HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> loginUser(@RequestBody Map<String, String> body) {
        Map<String, Object> result = new HashMap<>();
        try {
            String username = body.get("username");
            String password = body.get("password");

            if (!StringUtils.hasText(username) || !StringUtils.hasText(password)) {
                result.put("code", 400);
                result.put("msg", "用户名或密码不能为空");
                return new ResponseEntity<>(result, HttpStatus.BAD_REQUEST);
            }

            String storedPassword = users.get(username);
            if (storedPassword == null) {
                result.put("code", 400);
                result.put("msg", "用户名不存在，请先注册");
                return new ResponseEntity<>(result, HttpStatus.BAD_REQUEST);
            }

            HashUtil hashUtil = new HashUtil();
            if (!storedPassword.equals(hashUtil.sha256(password))) {
                result.put("code", 401);
                result.put("msg", "密码错误");
                return new ResponseEntity<>(result, HttpStatus.UNAUTHORIZED);
            }

            String token;
            if (jwtUtil != null) {
                token = jwtUtil.generateToken(username);
            } else {
                token = java.util.Base64.getEncoder().encodeToString((username + ":" + System.currentTimeMillis()).getBytes());
                log.warn("JwtUtil不可用，使用备用Token");
            }

            result.put("code", 200);
            result.put("msg", "登录成功");
            result.put("token", token);
            result.put("username", username);
            return new ResponseEntity<>(result, HttpStatus.OK);
        } catch (Exception e) {
            log.error("登录操作有误", e);
            result.put("code", 500);
            result.put("msg", "登录失败：" + (e.getMessage() != null ? e.getMessage() : "系统内部错误"));
            return new ResponseEntity<>(result, HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }
}