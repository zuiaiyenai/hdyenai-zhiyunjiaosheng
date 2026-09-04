(function(){
  "use strict";
  let busy=false;
  let requestStarted=false;
  let unlockTimer=0;
  let activeRequest="";
  let extractedFileKey="";

  function coursewarePanel(){
    return Array.from(document.querySelectorAll("article")).find(panel=>{
      const title=panel.querySelector(".panel-title");
      return title&&title.textContent.trim()==="PPT 课件总结";
    });
  }

  function coursewareInput(){
    const panel=coursewarePanel();
    return panel&&panel.querySelector('input[type="file"]');
  }

  function currentFileKey(){
    const file=coursewareInput()?.files?.[0];
    return file?`${file.name}:${file.size}:${file.lastModified}`:"";
  }

  function statusElement(){
    const panel=coursewarePanel();
    if(!panel)return null;
    let status=panel.querySelector("[data-courseware-status]");
    if(status)return status;
    status=document.createElement("p");
    status.dataset.coursewareStatus="true";
    status.setAttribute("role","status");
    status.setAttribute("aria-live","polite");
    status.style.cssText="margin:12px 0 0;padding:10px 14px;border-radius:8px;background:rgba(255,255,255,.72);color:#46677c;font-size:16px;line-height:1.5";
    const buttons=coursewareButtons()[0]?.parentElement;
    (buttons||panel).insertAdjacentElement(buttons?"afterend":"beforeend",status);
    return status;
  }

  function showStatus(message,type="info"){
    const status=statusElement();
    if(!status)return;
    status.textContent=message;
    status.style.color=type==="error"?"#b42318":type==="success"?"#217a4b":"#46677c";
  }

  function isCoursewareButton(button){
    if(!button)return false;
    const panel=button.closest("article");
    const title=panel&&panel.querySelector(".panel-title");
    const text=button.textContent.trim();
    return title&&title.textContent.trim()==="PPT 课件总结"&&
      (text==="生成总结"||text==="提取文本");
  }
  function coursewareButtons(){
    return Array.from(document.querySelectorAll("button")).filter(isCoursewareButton);
  }
  function lockButtons(){
    coursewareButtons().forEach(button=>{
      if(!button.dataset.coursewareLocked){
        button.dataset.coursewareLocked="true";
        button.dataset.coursewareWasDisabled=String(button.disabled);
      }
      if(!button.disabled)button.disabled=true;
    });
  }
  function finish(){
    if(!busy)return;
    busy=false;
    requestStarted=false;
    activeRequest="";
    window.clearTimeout(unlockTimer);
    coursewareButtons().forEach(button=>{
      if(button.dataset.coursewareLocked){
        button.disabled=button.dataset.coursewareWasDisabled==="true";
        delete button.dataset.coursewareLocked;
        delete button.dataset.coursewareWasDisabled;
      }
    });
  }
  function begin(requestType){
    busy=true;
    requestStarted=false;
    activeRequest=requestType;
    lockButtons();
    showStatus(requestType==="extract"?"正在提取 PPT 文本，请稍候…":"正在生成总结，请稍候…");
    unlockTimer=window.setTimeout(finish,180000);
    window.setTimeout(()=>{
      if(busy&&!requestStarted)finish();
    },1000);
  }
  function isCoursewareRequest(input){
    const url=typeof input==="string"?input:input&&input.url;
    return typeof url==="string"&&
      /\/(?:courseware\/summary|accessibility\/read-ppt)(?:\?|$)/.test(url);
  }

  function requestType(input){
    const url=typeof input==="string"?input:input&&input.url;
    if(typeof url!=="string")return "";
    if(/\/accessibility\/read-ppt(?:\?|$)/.test(url))return "extract";
    if(/\/courseware\/summary(?:\?|$)/.test(url))return "summary";
    return "";
  }

  function completeRequest(type,ok){
    if(type==="extract"){
      if(ok){
        extractedFileKey=currentFileKey();
        showStatus("文本提取完成，可直接点击“生成总结”。","success");
      }else{
        showStatus("文本提取失败，请重试或重新选择文件。","error");
      }
    }else if(type==="summary"){
      showStatus(ok?"总结生成完成。":"总结生成失败，请稍后重试。",ok?"success":"error");
    }
    finish();
  }

  const nativeFetch=window.fetch;
  if(nativeFetch){
    window.fetch=function(input){
      if(!isCoursewareRequest(input))return nativeFetch.apply(this,arguments);
      requestStarted=true;
      const type=requestType(input)||activeRequest;
      return nativeFetch.apply(this,arguments).then(response=>{
        completeRequest(type,response.ok);
        return response;
      },error=>{
        completeRequest(type,false);
        throw error;
      });
    };
  }

  const nativeOpen=XMLHttpRequest.prototype.open;
  const nativeSend=XMLHttpRequest.prototype.send;
  XMLHttpRequest.prototype.open=function(method,url){
    this._coursewareRequestType=requestType(url);
    return nativeOpen.apply(this,arguments);
  };
  XMLHttpRequest.prototype.send=function(){
    const type=this._coursewareRequestType;
    if(type){
      requestStarted=true;
      this.addEventListener("loadend",()=>completeRequest(type,this.status>=200&&this.status<300),{once:true});
    }
    return nativeSend.apply(this,arguments);
  };

  document.addEventListener("click",event=>{
    const button=event.target.closest("button");
    if(!isCoursewareButton(button))return;
    if(busy){
      event.preventDefault();
      event.stopImmediatePropagation();
      return;
    }
    begin(button.textContent.trim()==="提取文本"?"extract":"summary");
  },true);

  document.addEventListener("change",event=>{
    const input=event.target;
    if(input!==coursewareInput())return;
    extractedFileKey="";
    const file=input.files?.[0];
    if(!file){
      showStatus("请选择 PPT 或 PPTX 文件。");
      return;
    }
    showStatus(`已选择 ${file.name}，正在自动提取文本…`);
    window.setTimeout(()=>{
      if(currentFileKey()!==extractedFileKey){
        const extractButton=coursewareButtons().find(button=>button.textContent.trim()==="提取文本");
        if(extractButton&&!busy)extractButton.click();
      }
    },0);
  });

  showStatus("选择 PPT 或 PPTX 文件后将自动提取文本。");

  new MutationObserver(()=>{
    if(busy)lockButtons();
  }).observe(document.documentElement,{childList:true,subtree:true,attributes:true,attributeFilter:["disabled"]});
})();
(function(){
  "use strict";

  function findPanel(){
    return Array.from(document.querySelectorAll("article")).find(article=>{
      const title=article.querySelector(".panel-title");
      return title&&(title.textContent.trim()==="PPT 课件总结"||
        title.textContent.trim()==="AI 课件制作与下载");
    });
  }

  function apiBase(){
    return (localStorage.getItem("zyjs_api")||window.location.origin).replace(/\/$/,"");
  }

  function authHeaders(headers={}){
    const token=localStorage.getItem("token");
    return token?{...headers,Authorization:`Bearer ${token}`}:headers;
  }

  async function request(path,options={}){
    const response=await fetch(apiBase()+path,{
      ...options,
      headers:authHeaders(options.headers||{})
    });
    if(options.download){
      if(!response.ok)throw new Error(await errorMessage(response));
      return response.blob();
    }
    const text=await response.text();
    let data=text;
    try{data=text?JSON.parse(text):null}catch{}
    if(!response.ok){
      const message=data&&typeof data==="object"?(data.message||data.msg):data;
      throw new Error(message||`请求失败：${response.status}`);
    }
    return data;
  }

  async function errorMessage(response){
    const text=await response.text();
    try{
      const data=JSON.parse(text);
      return data.message||data.msg||text;
    }catch{
      return text||`请求失败：${response.status}`;
    }
  }

  function template(){
    return `
      <div class="courseware-workflow" style="display:grid;gap:16px">
        <div style="display:flex;gap:8px;flex-wrap:wrap;font-weight:700;color:#46677c">
          <span>① 上传 PPT</span><span>→</span><span>② 优化讲稿</span><span>→</span>
          <span>③ 生成语音</span><span>→</span><span>④ 录播/虚拟教师</span><span>→</span><span>⑤ 下载</span>
        </div>

        <section data-step="upload" style="display:grid;gap:10px">
          <label class="drop compact-drop">选择 PPT / PPTX
            <input data-role="ppt" type="file" accept=".ppt,.pptx">
          </label>
          <button data-action="create" class="primary">上传并生成讲稿</button>
        </section>

        <section data-role="workspace" hidden style="display:grid;gap:12px">
          <div style="display:flex;justify-content:space-between;gap:8px;flex-wrap:wrap">
            <b data-role="title">未创建课件</b>
            <span data-role="revision">讲稿版本 0</span>
          </div>
          <textarea data-role="script" class="editor" style="min-height:260px"
            placeholder="AI 生成的讲稿会显示在这里，也可以直接修改"></textarea>
          <button data-action="save" class="secondary">保存当前讲稿</button>

          <div style="display:grid;grid-template-columns:minmax(0,1fr) auto;gap:8px">
            <input data-role="instruction" class="wide"
              placeholder="例如：减少专业术语，增加课堂提问，并控制在 10 分钟">
            <button data-action="optimize" class="primary">按要求优化讲稿</button>
          </div>

          <div style="display:grid;gap:10px;padding:12px;border:1px solid rgba(70,103,124,.2);border-radius:10px">
            <b>讲稿配音</b>
            <label>音色
              <select data-role="voice" class="wide">
                <option value="longxiao">龙小晓 · 中文女声</option>
                <option value="longxiao-en">Katherine · 英文女声</option>
                <option value="default">默认音色</option>
              </select>
            </label>
            <label>语气
              <select data-role="tone" class="wide">
                <option value="natural">自然</option>
                <option value="gentle">温柔</option>
                <option value="lively">活泼</option>
                <option value="serious">严肃</option>
              </select>
            </label>
            <label>语速 <output data-output="speed">1.0</output>
              <input data-role="speed" type="range" min="0.5" max="2" step="0.1" value="1">
            </label>
            <label>语调 <output data-output="pitch">1.0</output>
              <input data-role="pitch" type="range" min="0.5" max="2" step="0.1" value="1">
            </label>
            <label>节奏 <output data-output="rhythm">1.0</output>
              <input data-role="rhythm" type="range" min="0.5" max="2" step="0.1" value="1">
            </label>
            <div class="actions">
              <button data-action="audio" class="primary">生成讲稿语音</button>
              <button data-action="download-audio" class="secondary">下载语音</button>
            </div>
            <audio data-role="audio" class="audio" controls hidden></audio>
          </div>

          <div style="display:grid;gap:10px;padding:12px;border:1px solid rgba(70,103,124,.2);border-radius:10px">
            <b>录播课程与虚拟教师</b>
            <small>不上传人物图片时生成 PPT 画面录播；上传后将数字教师叠加到画面右下角。</small>
            <input data-role="avatar" type="file" accept="image/*">
            <div class="actions">
              <button data-action="avatar" class="secondary">上传虚拟教师图片</button>
              <button data-action="video" class="primary">生成录播课程</button>
              <button data-action="download-video" class="secondary">下载录播视频</button>
            </div>
          </div>

          <div class="actions">
            <button data-action="package" class="primary">下载完整课件材料 ZIP</button>
          </div>
          <p data-role="readiness" style="margin:0;color:#46677c"></p>
        </section>

        <p data-courseware-status="true" role="status" aria-live="polite"
          style="margin:0;padding:10px 14px;border-radius:8px;background:rgba(255,255,255,.72);color:#46677c">
          请选择 PPT 或 PPTX 文件开始制作。
        </p>
      </div>`;
  }

  function initialize(){
    const panel=findPanel();
    if(!panel||panel.dataset.coursewareWorkflow)return;
    panel.dataset.coursewareWorkflow="true";
    panel.querySelector(".panel-title").textContent="AI 课件制作与下载";
    const body=panel.querySelector(".panel-body");
    if(!body)return;
    body.innerHTML=template();

    let project=null;
    let busy=false;
    let audioUrl="";
    let pollTimer=0;

    function stopPolling(){
      window.clearTimeout(pollTimer);
      pollTimer=0;
    }

    function waitToPoll(milliseconds){
      return new Promise(resolve=>{
        pollTimer=window.setTimeout(resolve,milliseconds);
      });
    }

    async function pollTask(taskId){
      const deadline=Date.now()+16*60*1000;
      stopPolling();
      while(body.isConnected&&Date.now()<deadline){
        let task;
        try{
          task=await request(`/api/tasks/${encodeURIComponent(taskId)}`);
        }catch{
          show("服务暂不可用，正在重新查询任务状态…");
          await waitToPoll(2000);
          continue;
        }
        if(task.status==="SUCCESS"){
          stopPolling();
          show("任务完成，正在刷新结果…","success");
          return task;
        }
        if(["FAILED","CANCELLED","TIMEOUT"].includes(task.status)){
          stopPolling();
          const messages={
            FAILED:"任务执行失败。",
            CANCELLED:"任务已取消。",
            TIMEOUT:"任务执行超时。"
          };
          throw new Error(task.errorMessage||messages[task.status]);
        }
        const progress=Number.isFinite(task.progress)?`（${task.progress}%）`:"";
        show(task.status==="RUNNING"?`任务执行中${progress}…`:`任务已提交，等待执行${progress}…`);
        await waitToPoll(1000);
      }
      stopPolling();
      throw new Error(body.isConnected?"任务状态查询超时，请稍后重试。":"页面已切换，已停止查询任务状态。");
    }

    async function submitTask(path,options){
      const submission=await request(path,options);
      if(!submission||!submission.taskId)throw new Error("任务提交失败：未返回 taskId。");
      await pollTask(submission.taskId);
      project=await request(`/courseware/projects/${project.id}`);
      render();
    }

    window.addEventListener("pagehide",stopPolling,{once:true});
    const get=role=>body.querySelector(`[data-role="${role}"]`);
    const action=name=>body.querySelector(`[data-action="${name}"]`);
    const status=getStatus;

    function getStatus(){
      return body.querySelector("[data-courseware-status]");
    }

    function show(message,type="info"){
      const element=status();
      element.textContent=message;
      element.style.color=type==="error"?"#b42318":type==="success"?"#217a4b":"#46677c";
    }

    function setBusy(value,message){
      busy=value;
      body.querySelectorAll("button").forEach(button=>button.disabled=value);
      if(message)show(message);
      if(!value)render();
    }

    function render(){
      const ready=Boolean(project);
      get("workspace").hidden=!ready;
      if(!ready)return;
      get("title").textContent=`${project.title} · ${project.fileName}`;
      get("revision").textContent=`讲稿版本 ${project.revision}`;
      if(document.activeElement!==get("script"))get("script").value=project.script||"";
      get("voice").value=project.voice||get("voice").value;
      action("download-audio").disabled=busy||!project.audioReady;
      action("video").disabled=busy||!project.audioReady;
      action("download-video").disabled=busy||!project.videoReady;
      get("readiness").textContent=[
        project.audioReady?"✓ 语音已生成":"○ 待生成语音",
        project.avatarReady?"✓ 已设置虚拟教师":"○ 未设置虚拟教师（可选）",
        project.videoReady?"✓ 录播已生成":"○ 待生成录播"
      ].join("　");
    }

    async function run(message,task){
      if(busy)return;
      setBusy(true,message);
      try{
        await task();
        show("操作完成。","success");
      }catch(error){
        show(error.message||"操作失败，请重试。","error");
      }finally{
        setBusy(false);
      }
    }

    async function syncScript(){
      const script=get("script").value.trim();
      if(!project||script===project.script)return;
      project=await request(`/courseware/projects/${project.id}/script`,{
        method:"PUT",
        headers:{"Content-Type":"application/json"},
        body:JSON.stringify({script})
      });
      render();
    }

    async function download(kind,fileName){
      await syncScript();
      const blob=await request(`/courseware/projects/${project.id}/download/${kind}`,{download:true});
      const url=URL.createObjectURL(blob);
      const link=document.createElement("a");
      link.href=url;
      link.download=fileName;
      link.click();
      window.setTimeout(()=>URL.revokeObjectURL(url),1000);
      return blob;
    }

    action("create").addEventListener("click",()=>run("正在读取 PPT 并生成讲稿，请稍候…",async()=>{
      const file=get("ppt").files?.[0];
      if(!file)throw new Error("请先选择 PPT 或 PPTX 文件。");
      const form=new FormData();
      form.append("file",file);
      project=await request("/courseware/projects",{method:"POST",body:form});
      get("script").value=project.script||"";
      render();
      show("讲稿已生成，可直接修改或通过 AI 多轮优化。","success");
    }));

    action("save").addEventListener("click",()=>run("正在保存讲稿…",async()=>{
      await syncScript();
      show("讲稿已保存。","success");
    }));

    action("optimize").addEventListener("click",()=>run("AI 正在按本轮要求优化讲稿…",async()=>{
      const instruction=get("instruction").value.trim();
      if(!instruction)throw new Error("请输入本轮讲稿调整要求。");
      await syncScript();
      await submitTask(`/courseware/projects/${project.id}/optimize/tasks`,{
        method:"POST",
        headers:{"Content-Type":"application/json"},
        body:JSON.stringify({instruction})
      });
      get("script").value=project.script||"";
      get("instruction").value="";
      render();
      show("本轮讲稿优化完成，历史版本已保留。","success");
    }));

    get("tone").addEventListener("change",()=>{
      const presets={
        natural:[1.0,1.0,1.0],
        gentle:[0.9,0.9,1.1],
        lively:[1.15,1.15,0.9],
        serious:[0.85,0.85,1.15]
      };
      const values=presets[get("tone").value]||presets.natural;
      ["speed","pitch","rhythm"].forEach((name,index)=>{
        get(name).value=values[index];
        body.querySelector(`[data-output="${name}"]`).value=values[index].toFixed(1);
      });
    });
    ["speed","pitch","rhythm"].forEach(name=>{
      get(name).addEventListener("input",()=>{
        body.querySelector(`[data-output="${name}"]`).value=Number(get(name).value).toFixed(1);
      });
    });

    action("audio").addEventListener("click",()=>run("正在按所选语音参数生成讲稿语音…",async()=>{
      await syncScript();
      await submitTask(`/courseware/projects/${project.id}/audio/tasks`,{
        method:"POST",
        headers:{"Content-Type":"application/json"},
        body:JSON.stringify({
          voice:get("voice").value.trim(),
          speed:Number(get("speed").value),
          pitch:Number(get("pitch").value),
          rhythm:Number(get("rhythm").value)
        })
      });
      if(audioUrl)URL.revokeObjectURL(audioUrl);
      const blob=await request(`/courseware/projects/${project.id}/download/audio`,{download:true});
      audioUrl=URL.createObjectURL(blob);
      get("audio").src=audioUrl;
      get("audio").hidden=false;
      render();
      show("讲稿语音已生成，可以试听或下载。","success");
    }));

    action("download-audio").addEventListener("click",()=>run("正在准备语音下载…",
      ()=>download("audio",`${project.title}-讲稿语音.wav`)));

    action("avatar").addEventListener("click",()=>run("正在上传虚拟教师图片…",async()=>{
      const file=get("avatar").files?.[0];
      if(!file)throw new Error("请先选择虚拟教师图片。");
      const form=new FormData();
      form.append("avatar",file);
      project=await request(`/courseware/projects/${project.id}/avatar`,{method:"POST",body:form});
      render();
      show("虚拟教师已设置，重新生成录播后生效。","success");
    }));

    action("video").addEventListener("click",()=>run("正在渲染 PPT 并合成录播课程，可能需要几分钟…",async()=>{
      await syncScript();
      await submitTask(`/courseware/projects/${project.id}/video/tasks`,{method:"POST"});
      render();
      show("录播课程已生成，可以下载 MP4。","success");
    }));

    action("download-video").addEventListener("click",()=>run("正在准备录播视频下载…",
      ()=>download("video",`${project.title}-录播课程.mp4`)));

    action("package").addEventListener("click",()=>run("正在打包完整课件材料…",
      ()=>download("package",`${project.title}-课件材料.zip`)));

    render();
  }

  initialize();
  new MutationObserver(initialize).observe(document.documentElement,{childList:true,subtree:true});
})();
