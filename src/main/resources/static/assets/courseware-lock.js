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
