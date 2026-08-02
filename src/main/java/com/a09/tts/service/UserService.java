package com.a09.tts.service;

import com.a09.tts.pojo.User;

public interface UserService {

    int register(User user);

    Boolean isUsernameExist(String username);

    Boolean login(String username, String password);
}
