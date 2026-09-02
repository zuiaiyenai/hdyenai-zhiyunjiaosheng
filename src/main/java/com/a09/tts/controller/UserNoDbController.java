package com.a09.tts.controller;

import com.a09.tts.security.LoginRateLimiter;
import com.a09.tts.security.PasswordPolicy;
import com.a09.tts.util.JwtUtil;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
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

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private PasswordPolicy passwordPolicy;

    @Autowired
    private LoginRateLimiter loginRateLimiter;

    @PostConstruct
    public void init() {
        users.putIfAbsent("admin", passwordEncoder.encode("admin123"));
        users.putIfAbsent("demo", passwordEncoder.encode("demo123"));
        log.info("无数据库模式已启动，初始用户: admin/admin123, demo/demo123");
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

            passwordPolicy.validate(password);

            if (users.containsKey(username)) {
                result.put("code", 400);
                result.put("msg", "用户名已存在");
                return new ResponseEntity<>(result, HttpStatus.BAD_REQUEST);
            }

            users.put(username, passwordEncoder.encode(password));

            result.put("code", 201);
            result.put("msg", "注册成功");
            return new ResponseEntity<>(result, HttpStatus.CREATED);
        } catch (IllegalArgumentException e) {
            result.put("code", 400);
            result.put("msg", e.getMessage());
            return new ResponseEntity<>(result, HttpStatus.BAD_REQUEST);
        } catch (Exception e) {
            log.error("注册操作有误", e);
            result.put("code", 500);
            result.put("msg", "注册服务暂不可用");
            return new ResponseEntity<>(result, HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> loginUser(@RequestBody Map<String, String> body,
                                                          HttpServletRequest request) {
        Map<String, Object> result = new HashMap<>();
        try {
            String username = body.get("username");
            String password = body.get("password");

            if (!StringUtils.hasText(username) || !StringUtils.hasText(password)) {
                result.put("code", 400);
                result.put("msg", "用户名或密码不能为空");
                return new ResponseEntity<>(result, HttpStatus.BAD_REQUEST);
            }

            String ip = request.getRemoteAddr();
            if (loginRateLimiter.isBlocked(ip, username)) {
                result.put("code", 429);
                result.put("msg", "登录失败次数过多，请稍后再试");
                return new ResponseEntity<>(result, HttpStatus.TOO_MANY_REQUESTS);
            }
            String storedPassword = users.get(username);
            if (storedPassword == null || !passwordEncoder.matches(password, storedPassword)) {
                loginRateLimiter.recordFailure(ip, username);
                result.put("code", 401);
                result.put("msg", "用户名或密码错误");
                return new ResponseEntity<>(result, HttpStatus.UNAUTHORIZED);
            }
            loginRateLimiter.recordSuccess(ip, username);
            String token = jwtUtil.generateToken(username);

            result.put("code", 200);
            result.put("msg", "登录成功");
            result.put("token", token);
            result.put("username", username);
            return new ResponseEntity<>(result, HttpStatus.OK);
        } catch (Exception e) {
            log.error("登录操作有误", e);
            result.put("code", 500);
            result.put("msg", "登录服务暂不可用");
            return new ResponseEntity<>(result, HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }
}
