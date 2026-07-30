package com.a09.tts.mapper;

import com.a09.tts.pojo.Voice;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * 用于声音样本库相关操作的Mapper层接口
 *
 * @author LZH
 * @version 1.0 样本库浏览相关接口
 *
 */
@Mapper
public interface VoiceMapper {
    /**
     * 添加声音样本
     *
     * @param voice 包含声音样本的详细信息
     * @return 插入操作影响的行数，成功为1，失败为0
     */
    @Insert("insert into voice (voice_name, application_scene) VALUES (#{voiceName}, #{applicationScene})")
    public int addVoiceSample(Voice voice);

    /**
     * 根据声音样本名称查询样本信息（可以实现模糊查询）
     *
     * @param voiceName 声音样本名称
     * @return 匹配的声音样本列表（支持重名或者多个匹配结果）
     */
    @Select("select voice_id, voice_name, application_scene FROM voice where voice_name LIKE CONCAT('%', #{voiceName}, '%')")
    public List<Voice> findVoiceByName(String voiceName);

    /**
     * 查询所有声音样本
     *
     * @return 声音样本的列表
     */
    @Select("SELECT voice_id, voice_name, application_scene FROM voice")
    public List<Voice> findAllVoices();

    /**
     * 根据声音样本的 ID 删除声音样本
     *
     * @param voiceId 声音样本的唯一 ID
     * @return 删除操作影响的行数，1 为删除成功，0 为失败
     */
    @Delete("DELETE FROM voice where voice_id = #{voiceId}")
    public int deleteVoiceById(int voiceId);

    /**
     * 更新声音样本信息
     *
     * @param voice 包含需要更新的声音样本信息（必须包含 ID）
     * @return 更新操作影响的行数，1 为更新成功，0 为失败
     */
    @Update("UPDATE voice SET voice_name = #{voiceName}, application_scene = #{applicationScene} where voice_id = #{voiceId}")
    public int updateVoiceSample(Voice voice);
}


