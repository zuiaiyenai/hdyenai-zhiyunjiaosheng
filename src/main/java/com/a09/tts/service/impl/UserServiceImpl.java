package com.a09.tts.service.impl;

import com.a09.tts.mapper.UserMapper;
import com.a09.tts.pojo.User;
import com.a09.tts.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 用户服务类，提供用户注册和登录的业务逻辑处理。
 *
 * 该类负责调用 UserMapper 接口中的方法，实现用户的注册和登录操作。
 * 它使用了 Spring 的依赖注入（@Autowired）将 UserMapper 注入到该类中，
 * 以方便调用数据访问层的操作。
 *
 * @author WSH
 * @version 1.0
 */
@Service
@Profile("!nodb")
public class UserServiceImpl implements UserService {

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    /**
     * 用户注册的服务方法。
     *
     * 该方法接收一个 User 对象作为参数，将用户信息传递给 UserMapper 的 register方法进行注册操作。
     * 将用户的密码进行sha256加密。
     * 并将注册结果作为返回值，通常返回插入记录的行数，如果插入成功则为 1(true)，失败为 0(false)。
     *
     * @param user 要注册的用户对象，包含用户名、密码和邮箱等信息。
     * @return 注册操作影响的行数，成功为 1，失败为 0。
     */
    public int register(User user) {
        if (user.getPermission() == null) {
            user.setPermission(false);
        }
        user.setPassword(passwordEncoder.encode(user.getPassword()));
        return userMapper.register(user);
    }

    /**
     * 检测注册的用户名是否已存在的服务方法。
     *
     * @param username
     * @return 用户名是否存在，存在返回1(true)，不存在返回0(false)
     */
    public Boolean isUsernameExist(String username){
        return userMapper.findByUsername(username) != null;
    }


    /**
     *用户登陆的服务方法
     *
     * @param username 用户名
     * @param password 密码
     * @return 密码正确则返回true，否则返回false
     */
    public Boolean login(String username, String password) {
        String storedPassword = userMapper.login(username);
        if (storedPassword == null) {
            return false;
        }
        if (storedPassword.startsWith("$2")) {
            return passwordEncoder.matches(password, storedPassword);
        }
        String legacyHash = new com.a09.tts.util.HashUtil().sha256(password);
        if (legacyHash.equals(storedPassword)) {
            userMapper.updatePassword(username, passwordEncoder.encode(password));
            return true;
        }
        return false;
    }
}
