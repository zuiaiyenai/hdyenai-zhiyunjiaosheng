package com.a09.tts.pojo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Task {
    private Integer taskId;//任务id
    private Integer userId;//关联用户id
    private Integer voiceId;//关联声音样本id
    private Integer textId;//关联文本文件id
    private Integer audioId;//关联音频文件id
}
