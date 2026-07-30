package com.a09.tts.pojo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Text {
    private Integer textId;//文本文件id
    private Integer taskId;//关联语音合成任务id
    private String fileName;//文本文件名称
    private String filePath;//文本文件路径
}
