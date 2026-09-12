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
    @Insert("insert into voice (voice_name, application_scene, file_path, object_key, storage_provider, " +
            "storage_bucket, mime_type, file_size, checksum_sha256, public_visible, owner_username) " +
            "VALUES (#{voiceName}, #{applicationScene}, #{filePath}, #{objectKey}, #{storageProvider}, " +
            "#{storageBucket}, #{mimeType}, #{fileSize}, #{checksumSha256}, #{publicVisible}, #{ownerUsername})")
    @Options(useGeneratedKeys = true, keyProperty = "voiceId")
    public int addVoiceSample(Voice voice);

    @Select("(SELECT voice_id, voice_name, application_scene, file_path, object_key, storage_provider, " +
            "storage_bucket, mime_type, file_size, checksum_sha256, public_visible, owner_username, created_at " +
            "FROM voice WHERE public_visible = 1 AND voice_name LIKE CONCAT('%', #{voiceName}, '%') " +
            "ORDER BY created_at DESC, voice_id DESC LIMIT #{window}) UNION ALL " +
            "(SELECT voice_id, voice_name, application_scene, file_path, object_key, storage_provider, " +
            "storage_bucket, mime_type, file_size, checksum_sha256, public_visible, owner_username, created_at " +
            "FROM voice WHERE owner_username = #{username} AND public_visible = 0 " +
            "AND voice_name LIKE CONCAT('%', #{voiceName}, '%') " +
            "ORDER BY created_at DESC, voice_id DESC LIMIT #{window}) " +
            "ORDER BY created_at DESC, voice_id DESC LIMIT #{limit} OFFSET #{offset}")
    List<Voice> findVisibleVoiceByName(
            @Param("voiceName") String voiceName, @Param("username") String username,
            @Param("offset") int offset, @Param("limit") int limit,
            @Param("window") long window);

    @Select("(SELECT voice_id, voice_name, application_scene, file_path, object_key, storage_provider, " +
            "storage_bucket, mime_type, file_size, checksum_sha256, public_visible, owner_username, created_at " +
            "FROM voice WHERE public_visible = 1 " +
            "ORDER BY created_at DESC, voice_id DESC LIMIT #{window}) UNION ALL " +
            "(SELECT voice_id, voice_name, application_scene, file_path, object_key, storage_provider, " +
            "storage_bucket, mime_type, file_size, checksum_sha256, public_visible, owner_username, created_at " +
            "FROM voice WHERE owner_username = #{username} AND public_visible = 0 " +
            "ORDER BY created_at DESC, voice_id DESC LIMIT #{window}) " +
            "ORDER BY created_at DESC, voice_id DESC LIMIT #{limit} OFFSET #{offset}")
    List<Voice> findVisibleVoices(
            @Param("username") String username, @Param("offset") int offset,
            @Param("limit") int limit, @Param("window") long window);

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
    @Update("UPDATE voice SET voice_name = #{voiceName}, application_scene = #{applicationScene}, " +
            "public_visible = #{publicVisible} where voice_id = #{voiceId}")
    public int updateVoiceSample(Voice voice);

    @Select("SELECT voice_id, voice_name, application_scene, file_path, object_key, storage_provider, " +
            "storage_bucket, mime_type, file_size, checksum_sha256, public_visible, owner_username, created_at " +
            "FROM voice WHERE voice_id = #{voiceId}")
    Voice findVoiceById(int voiceId);

    @Select("SELECT COALESCE(SUM(file_size), 0) FROM voice WHERE owner_username = #{ownerUsername}")
    Long sumStoredBytesByOwner(String ownerUsername);
}


