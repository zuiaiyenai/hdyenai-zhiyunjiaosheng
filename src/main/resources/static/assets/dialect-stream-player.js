(function () {
  "use strict";

  var busy = false;
  var activeAbortController = null;
  var activeMediaUrl = "";

  function once(target, successEvent, errorEvent) {
    return new Promise(function (resolve, reject) {
      function cleanup() {
        target.removeEventListener(successEvent, onSuccess);
        if (errorEvent) target.removeEventListener(errorEvent, onError);
      }
      function onSuccess() {
        cleanup();
        resolve();
      }
      function onError() {
        cleanup();
        reject(new Error("浏览器无法缓冲方言音频"));
      }
      target.addEventListener(successEvent, onSuccess, { once: true });
      if (errorEvent) target.addEventListener(errorEvent, onError, { once: true });
    });
  }

  async function append(sourceBuffer, bytes) {
    sourceBuffer.appendBuffer(bytes);
    await once(sourceBuffer, "updateend", "error");
  }

  async function errorMessage(response) {
    var message = await response.text();
    try {
      var body = JSON.parse(message);
      return body.message || body.msg || message;
    } catch (ignored) {
      return message;
    }
  }

  async function streamDialect(options) {
    if (!window.MediaSource || !MediaSource.isTypeSupported("audio/mpeg")) {
      throw new Error("当前浏览器不支持 MP3 流式播放，请使用最新版 Chrome");
    }
    if (activeAbortController) activeAbortController.abort();
    activeAbortController = new AbortController();

    var mediaSource = new MediaSource();
    if (activeMediaUrl) URL.revokeObjectURL(activeMediaUrl);
    activeMediaUrl = URL.createObjectURL(mediaSource);
    options.audio.src = activeMediaUrl;
    var playPromise = options.audio.play().catch(function () {
      return null;
    });

    var headers = { "Content-Type": "application/json" };
    if (options.token) headers.Authorization = "Bearer " + options.token;
    var response = await fetch(options.url, {
      method: "POST",
      headers: headers,
      body: JSON.stringify(options.body),
      signal: activeAbortController.signal
    });
    if (!response.ok) {
      throw new Error(await errorMessage(response) || "方言流式合成失败：" + response.status);
    }
    if (!response.body) throw new Error("浏览器没有提供流式响应读取能力");

    await once(mediaSource, "sourceopen", "error");
    var sourceBuffer = mediaSource.addSourceBuffer("audio/mpeg");
    var reader = response.body.getReader();
    var started = false;

    while (true) {
      var result = await reader.read();
      if (result.done) break;
      if (!result.value.length) continue;
      await append(sourceBuffer, result.value);
      if (!started) {
        started = true;
        await playPromise;
        if (options.audio.paused) await options.audio.play();
        options.onStart();
      }
    }
    if (!started) throw new Error("阿里云没有返回方言音频数据");
    if (mediaSource.readyState === "open") mediaSource.endOfStream();
  }

  function findDialectPanel(button) {
    var panel = button.closest("article");
    if (!panel) return null;
    var title = panel.querySelector("h2");
    return title && title.textContent.trim() === "方言语音合成" ? panel : null;
  }

  document.addEventListener("click", async function (event) {
    var button = event.target.closest("button");
    if (!button || button.textContent.trim() !== "生成方言语音") return;
    var panel = findDialectPanel(button);
    if (!panel) return;

    event.preventDefault();
    event.stopImmediatePropagation();
    if (busy) return;

    var text = panel.querySelector("textarea");
    var selects = panel.querySelectorAll("select");
    var result = panel.querySelector("pre.result");
    if (!text || !text.value.trim() || selects.length < 2) {
      if (result) result.textContent = "请输入需要合成的方言文本";
      return;
    }

    var audio = panel.querySelector("audio");
    if (!audio) {
      audio = document.createElement("audio");
      audio.className = "audio";
      audio.controls = true;
      audio.autoplay = true;
      panel.querySelector(".panel-body").insertBefore(audio, result);
    }

    busy = true;
    button.disabled = true;
    button.textContent = "生成中…";
    if (result) result.textContent = "正在连接方言流式语音服务…";
    try {
      var baseUrl = (localStorage.getItem("zyjs_api") || window.location.origin).replace(/\/$/, "");
      await streamDialect({
        url: baseUrl + "/dialect/stream",
        token: localStorage.getItem("token") || "",
        audio: audio,
        body: {
          text: text.value,
          dialect: selects[0].value,
          voice: selects[1].value
        },
        onStart: function () {
          if (result) result.textContent = "方言语音正在流式播放…";
        }
      });
      if (result) result.textContent = "方言语音流式生成完成";
    } catch (error) {
      if (error.name !== "AbortError" && result) result.textContent = error.message;
    } finally {
      busy = false;
      button.disabled = false;
      button.textContent = "生成方言语音";
    }
  }, true);
})();
