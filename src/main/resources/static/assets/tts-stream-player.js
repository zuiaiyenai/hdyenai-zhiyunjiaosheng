(function () {
  "use strict";

  var activePlayback = null;

  function joinBytes(left, right) {
    var joined = new Uint8Array(left.length + right.length);
    joined.set(left);
    joined.set(right, left.length);
    return joined;
  }

  function createWavBlob(chunks, sampleRate, channels) {
    var dataLength = chunks.reduce(function (total, chunk) {
      return total + chunk.length;
    }, 0);
    var header = new ArrayBuffer(44);
    var view = new DataView(header);
    var write = function (offset, value) {
      for (var index = 0; index < value.length; index += 1) {
        view.setUint8(offset + index, value.charCodeAt(index));
      }
    };
    write(0, "RIFF");
    view.setUint32(4, 36 + dataLength, true);
    write(8, "WAVEfmt ");
    view.setUint32(16, 16, true);
    view.setUint16(20, 1, true);
    view.setUint16(22, channels, true);
    view.setUint32(24, sampleRate, true);
    view.setUint32(28, sampleRate * channels * 2, true);
    view.setUint16(32, channels * 2, true);
    view.setUint16(34, 16, true);
    write(36, "data");
    view.setUint32(40, dataLength, true);
    return new Blob([header].concat(chunks), { type: "audio/wav" });
  }

  function readFourCc(bytes, offset) {
    return String.fromCharCode(
      bytes[offset], bytes[offset + 1], bytes[offset + 2], bytes[offset + 3]
    );
  }

  function parseWavHeader(bytes) {
    if (bytes.length < 12) return null;
    if (readFourCc(bytes, 0) !== "RIFF" || readFourCc(bytes, 8) !== "WAVE") {
      throw new Error("GPT-SoVITS 返回的不是 WAV 音频");
    }

    var view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
    var offset = 12;
    var format = null;
    while (offset + 8 <= bytes.length) {
      var chunkId = readFourCc(bytes, offset);
      var chunkLength = view.getUint32(offset + 4, true);
      var dataOffset = offset + 8;
      if (chunkId === "fmt ") {
        if (chunkLength < 16 || dataOffset + 16 > bytes.length) return null;
        format = {
          audioFormat: view.getUint16(dataOffset, true),
          channels: view.getUint16(dataOffset + 2, true),
          sampleRate: view.getUint32(dataOffset + 4, true),
          bitsPerSample: view.getUint16(dataOffset + 14, true)
        };
      } else if (chunkId === "data") {
        if (!format) throw new Error("WAV 音频缺少 fmt 信息");
        if (format.audioFormat !== 1 || format.bitsPerSample !== 16) {
          throw new Error("仅支持 16 位 PCM WAV 流式音频");
        }
        if (!format.channels || !format.sampleRate) {
          throw new Error("WAV 音频参数无效");
        }
        return {
          dataOffset: dataOffset,
          channels: format.channels,
          sampleRate: format.sampleRate
        };
      }

      if (dataOffset + chunkLength > bytes.length) return null;
      offset = dataOffset + chunkLength + (chunkLength % 2);
    }
    return null;
  }

  function validateBuiltInVoiceLanguage(body) {
    var text = body.text || "";
    var voice = body.voice || "";
    var containsChinese = /[\u4e00-\u9fff]/.test(text);
    if (voice === "longxiao-en" && containsChinese) {
      throw new Error("请输入英文");
    }
    if ((voice === "longxiao" || voice === "default") && !containsChinese) {
      throw new Error("请输入中文");
    }
  }

  async function play(options) {
    validateBuiltInVoiceLanguage(options.body || {});
    if (activePlayback) {
      activePlayback.abortController.abort();
      activePlayback.context.close();
    }

    var AudioContextClass = window.AudioContext || window.webkitAudioContext;
    if (!AudioContextClass) {
      throw new Error("当前浏览器不支持流式音频播放");
    }
    var context = new AudioContextClass();
    var abortController = new AbortController();
    activePlayback = { context: context, abortController: abortController };
    await context.resume();

    var headers = { "Content-Type": "application/json" };
    if (options.token) headers.Authorization = "Bearer " + options.token;
    var response = await fetch(options.url, {
      method: "POST",
      headers: headers,
      body: JSON.stringify(options.body),
      signal: abortController.signal
    });
    if (!response.ok) {
      var message = await response.text();
      try {
        var errorBody = JSON.parse(message);
        message = errorBody.message || errorBody.msg || message;
      } catch (ignored) {}
      throw new Error(message || "流式语音生成失败：" + response.status);
    }
    if (!response.body) throw new Error("浏览器没有提供流式响应读取能力");

    var reader = response.body.getReader();
    var headerBytes = new Uint8Array(0);
    var headerParsed = false;
    var pendingByte = new Uint8Array(0);
    var pcmChunks = [];
    var sampleRate = 32000;
    var channels = 1;
    var nextStartTime = 0;
    var started = false;

    function schedulePcm(bytes) {
      var complete = joinBytes(pendingByte, bytes);
      var byteLength = complete.length - complete.length % 2;
      pendingByte = complete.slice(byteLength);
      if (!byteLength) return;
      var pcm = complete.slice(0, byteLength);
      pcmChunks.push(pcm);
      var samples = new Float32Array(byteLength / 2);
      var pcmView = new DataView(pcm.buffer, pcm.byteOffset, pcm.byteLength);
      for (var index = 0; index < samples.length; index += 1) {
        samples[index] = pcmView.getInt16(index * 2, true) / 32768;
      }
      var buffer = context.createBuffer(channels, samples.length / channels, sampleRate);
      for (var channel = 0; channel < channels; channel += 1) {
        var channelData = buffer.getChannelData(channel);
        for (var frame = 0; frame < channelData.length; frame += 1) {
          channelData[frame] = samples[frame * channels + channel];
        }
      }
      var source = context.createBufferSource();
      source.buffer = buffer;
      source.connect(context.destination);
      nextStartTime = Math.max(nextStartTime, context.currentTime + 0.08);
      source.start(nextStartTime);
      nextStartTime += buffer.duration;
      if (!started) {
        started = true;
        if (options.onStart) options.onStart();
      }
    }

    while (true) {
      var result = await reader.read();
      if (result.done) break;
      var bytes = result.value;
      if (!headerParsed) {
        headerBytes = joinBytes(headerBytes, bytes);
        var wav = parseWavHeader(headerBytes);
        if (!wav) continue;
        headerParsed = true;
        channels = wav.channels;
        sampleRate = wav.sampleRate;
        bytes = headerBytes.slice(wav.dataOffset);
      }
      schedulePcm(bytes);
    }
    if (!headerParsed) throw new Error("GPT-SoVITS 返回的 WAV 头不完整");
    if (!started) throw new Error("GPT-SoVITS 没有返回音频数据");
    return createWavBlob(pcmChunks, sampleRate, channels);
  }

  window.ttsStreamPlayer = { play: play };
})();
