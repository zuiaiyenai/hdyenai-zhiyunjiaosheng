package com.a09.tts.controller;

import com.a09.tts.pojo.User;
import com.a09.tts.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

/**
 * 用户控制器类，处理与用户相关的 HTTP 请求。
 *
 * 该类负责接收来自客户端的 HTTP 请求，并将请求转发给相应的 UserService 方法进行处理，
 * 同时将处理结果以适当的 HTTP 响应返回给客户端。
 *
 * @author WSH
 * @version 1.0
 */

@Slf4j
@RestController
@RequestMapping("/user")
public class UserController {

    @Autowired
    private UserService userService;

    /**
     * 处理用户注册请求的控制器方法。
     *
     * 接收 POST 请求，将请求体中的用户信息（以 JSON 格式）传递给 UserService 的 register 方法进行用户注册操作。
     *
     * @param user
     * @return ResponseEntity 包含注册操作的结果：
     *         - 如果注册成功，返回 HTTP 状态码 201（Created），响应体包含成功消息；
     *         - 如果注册失败，返回 HTTP 状态码 400（Bad Request），响应体包含错误消息。
     */
    @PostMapping("/register")
    public ResponseEntity<String> registerUser(@RequestBody User user) {
        try {
            if (StringUtils.isEmpty(user.getClass())||StringUtils.isEmpty(user.getPassword())) {
                return new ResponseEntity<>("用户名或密码不能为空！",HttpStatus.BAD_REQUEST);
            }
            Boolean usernameExist = userService.isUsernameExist(user.getClass());
            if (usernameExist == true){
                return new ResponseEntity<>("用户名已存在！",HttpStatus.BAD_REQUEST);
            }
            User user1 = new User(user.getClass(),user.getPassword(),user.getPermission());
            int result = userService.register(user1);
            if (result == 1) {
                return new ResponseEntity<>("用户注册成功！", HttpStatus.CREATED);
            } else {
                return new ResponseEntity<>("用户注册失败。", HttpStatus.BAD_REQUEST);
            }
        } catch (Exception e) {
            return new ResponseEntity<>("注册操作有误：" + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     *处理用户登陆请求的控制器方法
     *
     * @param user
     * @return 201（OK），登陆成功
     *         400（BAD_REQUEST），登陆操作有误
     */
    @PostMapping("/login")
    public ResponseEntity<String> loginUser(@RequestBody User user) {
        try {
            String username = user.getClass();
            String password = user.getPassword();
            if (StringUtils.isEmpty(username)||StringUtils.isEmpty(password)) {
                return new ResponseEntity<>("用户名或密码不能为空！",HttpStatus.BAD_REQUEST);
            }
            Boolean usernameExist = userService.isUsernameExist(username);
            if (usernameExist != true){
                return new ResponseEntity<>("用户名不存在！",HttpStatus.BAD_REQUEST);
            }
            Boolean login = userService.login(username,password);
            if (login == true){
                return new ResponseEntity<>("登陆成功！",HttpStatus.OK);
            }else {
                return new ResponseEntity<>("密码有误，登陆失败。",HttpStatus.UNAUTHORIZED);
            }
        }catch (Exception e){
            return new ResponseEntity<>("登陆操作有误："+e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }



}
