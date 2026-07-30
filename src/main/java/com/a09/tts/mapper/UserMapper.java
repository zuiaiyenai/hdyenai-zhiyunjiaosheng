package com.a09.tts.mapper;

import com.a09.tts.pojo.User;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 *与用户注册登录操作相关的Mapper层接口
 *
 * @author WSH
 * @version 1.0 用户注册相关接口
 *          1.1 用户登陆相关接口
 *
 */

@Mapper
public interface UserMapper {

    /**
     * 用于用户注册的mapper代码
     *
     * @param user
     * @return 注册操作影响的行数，成功为 1，失败为 0。
     */
    @Insert("insert into user(username, password, permission) VALUES (#{username},#{password},#{permission})")
    public int register(User user);

    /**
     * 用于根据用户名查询用户信息，同时也可以用于检测注册的用户名是否已存在
     *
     * @param username
     * @return 指定用户名的实体类
     */
    @Select("select * from user where username=#{username}")
    public User findByUsername(String username);

    /**
     * 用于查询用户表中的所有数据，管理员权限
     *
     * @return 所有用户实体类
     */
    @Select("SELECT user_id, username, password, permission FROM user")
    public List<User> findAllUsers();

    /**
     * 用于用户登录
     *
     * @param username
     * @return 返回用户名对应的密码用于验证
     */
    @Select("select password from user where username=#{username}")
    public String login(String username);
}
