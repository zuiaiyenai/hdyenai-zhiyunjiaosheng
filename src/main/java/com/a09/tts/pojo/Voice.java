package com.a09.tts.pojo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class Voice {
    private Integer voiceId;//声音样本id
    private String voiceName;//声音样本名称
    private String applicationScene;//应用场景，如适合讲解数学的沉稳男声，适合语文朗读的温柔女声
}
