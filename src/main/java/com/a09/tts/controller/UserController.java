package com.a09.tts.controller;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.a09.tts.pojo.User;
import com.a09.tts.service.UserService;
import com.a09.tts.util.JwtUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/user")
@Profile("!nodb")
public class UserController {

    private static final Logger log = LoggerFactory.getLogger(UserController.class);

    @Autowired
    private UserService userService;

    @Autowired
    private JwtUtil jwtUtil;

    @PostMapping("/register")
    public ResponseEntity<Map<String, Object>> registerUser(@RequestBody User user) {
        Map<String, Object> result = new HashMap<>();
        try {
            String username = user.getUsername();
            String password = user.getPassword();
            if (!StringUtils.hasText(username) || !StringUtils.hasText(password)) {
                result.put("code", 400);
                result.put("msg", "用户名或密码不能为空！");
                return new ResponseEntity<>(result, HttpStatus.BAD_REQUEST);
            }
            Boolean usernameExist = userService.isUsernameExist(username);
            if (usernameExist) {
                result.put("code", 400);
                result.put("msg", "用户名已存在！");
                return new ResponseEntity<>(result, HttpStatus.BAD_REQUEST);
            }
            User user1 = new User(username, password, user.getPermission());
            int insertResult = userService.register(user1);
            if (insertResult == 1) {
                result.put("code", 201);
                result.put("msg", "用户注册成功！");
                return new ResponseEntity<>(result, HttpStatus.CREATED);
            } else {
                result.put("code", 400);
                result.put("msg", "用户注册失败。");
                return new ResponseEntity<>(result, HttpStatus.BAD_REQUEST);
            }
        } catch (Exception e) {
            log.error("注册操作有误", e);
            result.put("code", 500);
            result.put("msg", "注册操作有误：" + e.getMessage());
            return new ResponseEntity<>(result, HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> loginUser(@RequestBody User user) {
        Map<String, Object> result = new HashMap<>();
        try {
            String username = user.getUsername();
            String password = user.getPassword();
            if (!StringUtils.hasText(username) || !StringUtils.hasText(password)) {
                result.put("code", 400);
                result.put("msg", "用户名或密码不能为空！");
                return new ResponseEntity<>(result, HttpStatus.BAD_REQUEST);
            }
            Boolean usernameExist = userService.isUsernameExist(username);
            if (!usernameExist) {
                result.put("code", 400);
                result.put("msg", "用户名不存在！");
                return new ResponseEntity<>(result, HttpStatus.BAD_REQUEST);
            }
            Boolean loginSuccess = userService.login(username, password);
            if (loginSuccess) {
                String token = jwtUtil.generateToken(username);
                result.put("code", 200);
                result.put("msg", "登陆成功！");
                result.put("token", token);
                result.put("username", username);
                return new ResponseEntity<>(result, HttpStatus.OK);
            } else {
                result.put("code", 401);
                result.put("msg", "密码有误，登陆失败。");
                return new ResponseEntity<>(result, HttpStatus.UNAUTHORIZED);
            }
        } catch (Exception e) {
            log.error("登陆操作有误", e);
            result.put("code", 500);
            result.put("msg", "登陆操作有误：" + e.getMessage());
            return new ResponseEntity<>(result, HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

}
