package com.a09.tts.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.nls.client.protocol.NlsClient;
import com.alibaba.nls.client.protocol.OutputFormatEnum;
import com.alibaba.nls.client.protocol.SampleRateEnum;
import com.alibaba.nls.client.protocol.tts.SpeechSynthesizer;
import com.alibaba.nls.client.protocol.tts.SpeechSynthesizerListener;
import com.alibaba.nls.client.protocol.tts.SpeechSynthesizerResponse;
import com.aliyuncs.CommonRequest;
import com.aliyuncs.CommonResponse;
import com.aliyuncs.IAcsClient;
import com.aliyuncs.http.MethodType;
import com.aliyuncs.http.ProtocolType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class AliyunSpeechService {
    private final AliyunNlsCredentials credentials;

    @Autowired
    public AliyunSpeechService(AliyunNlsCredentials credentials) {
        this.credentials = credentials;
    }

    public AliyunSpeechService(String appKey, String token, String websocketUrl,
                               String accessKeyId, String accessKeySecret) {
        this(new AliyunNlsCredentials(appKey, token, websocketUrl, accessKeyId, accessKeySecret));
    }

    public byte[] synthesize(String text, String voice) {
        credentials.requireNlsConfiguration();
        ByteArrayOutputStream audio = new ByteArrayOutputStream();
        AtomicReference<String> failure = new AtomicReference<>();
        NlsClient client = credentials.createNlsClient();
        SpeechSynthesizer synthesizer = null;
        try {
            synthesizer = new SpeechSynthesizer(client, listener(audio, failure));
            synthesizer.setAppKey(credentials.appKey());
            synthesizer.setText(text);
            synthesizer.setFormat(OutputFormatEnum.MP3);
            synthesizer.setSampleRate(SampleRateEnum.SAMPLE_RATE_24K);
            synthesizer.setVoice(voice);
            synthesizer.setVolume(50);
            synthesizer.setPitchRate(0);
            synthesizer.setSpeechRate(0);
            synthesizer.start();
            synthesizer.waitForComplete();
            if (failure.get() != null) {
                throw new IllegalStateException(failure.get());
            }
            if (audio.size() == 0) {
                throw new IllegalStateException("阿里云未返回音频数据");
            }
            return audio.toByteArray();
        } catch (Exception exception) {
            throw new IllegalStateException("阿里云语音合成失败：" + exception.getMessage(), exception);
        } finally {
            if (synthesizer != null) {
                synthesizer.close();
            }
            client.shutdown();
        }
    }

    public void stream(String text, String voice, OutputStream outputStream) {
        credentials.requireNlsConfiguration();
        AtomicReference<String> failure = new AtomicReference<>();
        AtomicReference<IOException> writeFailure = new AtomicReference<>();
        NlsClient client = credentials.createNlsClient();
        SpeechSynthesizer synthesizer = null;
        try {
            synthesizer = new SpeechSynthesizer(client, streamingListener(outputStream, failure, writeFailure));
            synthesizer.setAppKey(credentials.appKey());
            synthesizer.setText(text);
            synthesizer.setFormat(OutputFormatEnum.MP3);
            synthesizer.setSampleRate(SampleRateEnum.SAMPLE_RATE_24K);
            synthesizer.setVoice(voice);
            synthesizer.setVolume(50);
            synthesizer.setPitchRate(0);
            synthesizer.setSpeechRate(0);
            synthesizer.start();
            synthesizer.waitForComplete();
            if (writeFailure.get() != null) {
                throw writeFailure.get();
            }
            if (failure.get() != null) {
                throw new IllegalStateException(failure.get());
            }
        } catch (Exception exception) {
            throw new IllegalStateException("阿里云流式语音合成失败：" + exception.getMessage(), exception);
        } finally {
            if (synthesizer != null) {
                synthesizer.close();
            }
            client.shutdown();
        }
    }

    public String cloneVoice(String voicePrefix, String audioUrl) {
        IAcsClient client = credentials.createAcsClient("cn-shanghai");
        try {
            CommonRequest request = new CommonRequest();
            request.setMethod(MethodType.POST);
            request.setDomain("nls-slp.cn-shanghai.aliyuncs.com");
            request.setVersion("2019-08-19");
            request.setAction("CosyVoiceClone");
            request.setProtocol(ProtocolType.HTTPS);
            request.putBodyParameter("VoicePrefix", voicePrefix);
            request.putBodyParameter("Url", audioUrl);
            request.setSysReadTimeout(20_000);
            CommonResponse response = client.getCommonResponse(request);
            JSONObject result = JSON.parseObject(response.getData());
            String voiceName = result.getString("VoiceName");
            if (voiceName == null || voiceName.isBlank()) {
                throw new IllegalStateException(result.getString("Message"));
            }
            return voiceName;
        } catch (Exception exception) {
            throw new IllegalStateException("阿里云声音复刻失败：" + exception.getMessage(), exception);
        } finally {
            client.shutdown();
        }
    }

    public Map<String, Object> capabilities(boolean localAvailable, String localApiUrl) {
        List<String> synthesisMissing = credentials.missingNlsConfiguration();
        List<String> cloneMissing = credentials.missingCloneConfiguration();

        Map<String, Object> aliyun = new LinkedHashMap<>();
        aliyun.put("synthesisReady", synthesisMissing.isEmpty());
        aliyun.put("cloneReady", cloneMissing.isEmpty());
        aliyun.put("tokenMode", credentials.tokenMode());
        aliyun.put("externalAudioUrlRequired", true);
        aliyun.put("missingSynthesisConfig", synthesisMissing);
        aliyun.put("missingCloneConfig", cloneMissing);

        Map<String, Object> local = new LinkedHashMap<>();
        local.put("available", localAvailable);
        local.put("apiUrl", localApiUrl);

        return Map.of("defaultProvider", "aliyun", "aliyun", aliyun, "local", local);
    }

    static SpeechSynthesizerListener listener(ByteArrayOutputStream audio,
                                              AtomicReference<String> failure) {
        return new SpeechSynthesizerListener() {
            @Override
            public void onMessage(ByteBuffer message) {
                byte[] bytes = new byte[message.remaining()];
                message.get(bytes);
                audio.write(bytes, 0, bytes.length);
            }

            @Override
            public void onFail(SpeechSynthesizerResponse response) {
                failure.set("taskId=" + response.getTaskId() + "，" + response.getStatusText());
            }

            @Override
            public void onComplete(SpeechSynthesizerResponse response) { }
        };
    }

    static SpeechSynthesizerListener streamingListener(OutputStream outputStream,
                                                       AtomicReference<String> failure,
                                                       AtomicReference<IOException> writeFailure) {
        return new SpeechSynthesizerListener() {
            @Override
            public void onMessage(ByteBuffer message) {
                if (writeFailure.get() != null) {
                    return;
                }
                byte[] bytes = new byte[message.remaining()];
                message.get(bytes);
                try {
                    outputStream.write(bytes);
                    outputStream.flush();
                } catch (IOException exception) {
                    writeFailure.compareAndSet(null, exception);
                }
            }

            @Override
            public void onFail(SpeechSynthesizerResponse response) {
                failure.set("taskId=" + response.getTaskId() + "，" + response.getStatusText());
            }

            @Override
            public void onComplete(SpeechSynthesizerResponse response) { }
        };
    }

}
