(function(){
  "use strict";
  function config(){return{baseUrl:(localStorage.getItem("zyjs_api")||window.location.origin).replace(/\/$/,""),token:localStorage.getItem("token")||""};}
  async function errorMessage(response){
    const raw=await response.text().catch(()=>"");
    try{const data=JSON.parse(raw);return data.message||data.msg||raw||("请求失败（"+response.status+"）");}
    catch{return raw||("请求失败（"+response.status+"）");}
  }
  async function submitAndWait(path,form,onProgress){
    const api=config();
    const headers=api.token?{Authorization:"Bearer "+api.token}:{};
    const response=await fetch(api.baseUrl+path,{method:"POST",headers,body:form});
    if(!response.ok)throw new Error(await errorMessage(response));
    const submission=await response.json();
    if(!submission||!submission.taskId)throw new Error("任务提交失败：未返回 taskId");
    const deadline=Date.now()+16*60*1000;
    let delay=1000;
    while(Date.now()<deadline){
      const taskResponse=await fetch(api.baseUrl+"/api/tasks/"+encodeURIComponent(submission.taskId),{headers});
      if(!taskResponse.ok)throw new Error(await errorMessage(taskResponse));
      const task=await taskResponse.json();
      if(task.status==="SUCCESS"){
        const result=await fetch(api.baseUrl+"/api/tasks/"+encodeURIComponent(task.id)+"/result",{headers});
        if(!result.ok)throw new Error(await errorMessage(result));
        return result;
      }
      if(["FAILED","CANCELLED","TIMEOUT"].includes(task.status))throw new Error(task.errorMessage||("任务"+task.status));
      if(onProgress)onProgress(task);
      await new Promise(resolve=>window.setTimeout(resolve,delay));
      delay=Math.min(5000,delay+1000);
    }
    throw new Error("任务状态查询超时，请稍后重试");
  }
  function panelByTitle(button,title){
    const panel=button.closest("article.panel,section.page");
    return panel&&panel.querySelector(".panel-title")?.textContent.trim()===title?panel:null;
  }
  async function run(button,output,work){
    button.disabled=true;
    const oldText=button.textContent;
    button.textContent="任务处理中…";
    try{await work(message=>{if(output)output.textContent=message;});}
    catch(error){if(output)output.textContent=error&&error.message?error.message:"任务执行失败";else window.alert(error&&error.message?error.message:"任务执行失败");}
    finally{button.disabled=false;button.textContent=oldText;}
  }
  function intercept(button){
    const label=button.textContent.trim();
    if(label==="开始转写"){
      const panel=panelByTitle(button,"语音识别");if(!panel)return false;
      const file=panel.querySelector('input[type="file"]')?.files?.[0];const output=panel.querySelector("pre");
      run(button,output,async progress=>{if(!file)throw new Error("请选择音频文件");const form=new FormData();form.append("file",file);form.append("language","zh");const response=await submitAndWait("/asr/transcribe",form,()=>progress("语音识别任务正在后台执行……"));const data=await response.json();output.textContent="识别文本："+(data.text||"")+"\n流利度："+(data.fluency??"-")+"\n发音："+(data.pronunciation??"-")+"\n准确度："+(data.accuracy??"-");});
      return true;
    }
    if(label==="生成总结"){
      const panel=panelByTitle(button,"PPT 课件总结");if(!panel)return false;
      const file=panel.querySelector('input[type="file"]')?.files?.[0];const output=panel.querySelector("pre");
      run(button,output,async progress=>{if(!file)throw new Error("请选择 PPT 文件");const form=new FormData();form.append("file",file);const response=await submitAndWait("/courseware/summary",form,()=>progress("课件总结任务正在后台执行……"));output.textContent=await response.text();});
      return true;
    }
    if(label==="保存笔记"){
      const panel=panelByTitle(button,"语音笔记");if(!panel)return false;
      const file=panel.querySelector('input[type="file"]')?.files?.[0];const title=panel.querySelector('input[type="text"]')?.value?.trim()||"未命名笔记";const output=panel.querySelector("pre");
      run(button,output,async progress=>{if(!file)throw new Error("请选择语音笔记录音");const form=new FormData();form.append("audio",file);form.append("title",title);const response=await submitAndWait("/accessibility/voice-note",form,()=>progress("语音笔记任务正在后台执行……"));const data=await response.json();output.textContent=data.transcribedText||JSON.stringify(data,null,2);panel.querySelectorAll("button").forEach(item=>{if(item.textContent.trim()==="查看笔记列表")item.click();});});
      return true;
    }
    if(label==="提交评测"){
      const panel=panelByTitle(button,"口语评测");if(!panel)return false;
      const file=panel.querySelector('input[type="file"]')?.files?.[0];const text=panel.querySelector("textarea")?.value?.trim();const output=panel.querySelector("pre");
      run(button,output,async progress=>{if(!file||!text)throw new Error("请填写参考文本并上传录音");const form=new FormData();form.append("file",file);form.append("text",text);form.append("mode","standard");form.append("language","zh");const response=await submitAndWait("/speaking_practice/evaluate",form,()=>progress("口语评测任务正在后台执行……"));output.textContent=JSON.stringify(await response.json(),null,2);});
      return true;
    }
    if(label==="生成克隆语音"){
      const panel=panelByTitle(button,"声音克隆");if(!panel)return false;
      const localProvider=panel.querySelector('input[type="radio"][value="local"]');
      if(localProvider&&!localProvider.checked)return false;
      const file=panel.querySelector('input[type="file"]')?.files?.[0];const prompt=panel.querySelector('input[placeholder*="参考音频"]')?.value?.trim();const text=panel.querySelector("textarea")?.value?.trim();
      run(button,null,async()=>{if(!file||!prompt||!text)throw new Error("请完整填写参考音频、参考文本和合成文本");const form=new FormData();form.append("prompt_text",prompt);form.append("prompt_lang","zh");form.append("text",text);form.append("text_lang","zh");form.append("audioFile",file);const response=await submitAndWait("/sound_clone/upload",form);const url=URL.createObjectURL(await response.blob());let output=panel.querySelector("audio");if(!output){output=document.createElement("audio");output.className="audio";output.controls=true;button.parentElement.appendChild(output);}output.src=url;output.play().catch(()=>{});});
      return true;
    }
    return false;
  }
  document.addEventListener("click",event=>{const button=event.target.closest("button");if(!button||!intercept(button))return;event.preventDefault();event.stopImmediatePropagation();},true);
})();
