package com.a09.tts.util;

import com.alibaba.fastjson.JSONObject;

/**
 * 用于从json字符串取出特定属性/将json字符串转化为实体对象的工具类
 *
 * @author WSH
 * @version 1.0
 */

public class JsonToEntity {

    public String oneAttribute(String attribute,String json) {
        //将json字符串解析为JSONObject类
        JSONObject jsonObject = JSONObject.parseObject(json);

        //取出attribute属性对应的键值，并返回
        String value = jsonObject.getString(attribute);
        return value;
    }
}
