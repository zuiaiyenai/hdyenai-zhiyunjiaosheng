package com.a09.tts.pojo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;


@Data
@AllArgsConstructor
@NoArgsConstructor
public class Audio {
    private Integer audioId;//音频文件id
    private Integer taskID;//关联语音合成任务id
    private String fileName;//音频文件名称
    private String filePath;//音频文件路径
}
