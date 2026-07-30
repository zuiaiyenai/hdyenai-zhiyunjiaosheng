package com.a09.tts.pojo;

import lombok.*;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class User {
    private Integer userId;//用户id
    private String username;//用户名
    private String password;//密码
    private Boolean permission;//用户权限，0为普通用户，1为高级用户

    public User(String username, String password, Boolean permission) {
        this.username=username;
        this.password=password;
        this.permission=permission;
    }

    public Object getPassword() {
        return password;
    }
}
