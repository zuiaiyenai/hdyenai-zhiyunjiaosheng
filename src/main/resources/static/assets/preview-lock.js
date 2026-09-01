(function(){
  "use strict";
  let busy=false;
  let audioStarted=false;
  let activeAudio=null;
  let unlockTimer=0;

  function isPreviewButton(button){
    return button&&button.querySelector(".sr-only")?.textContent.trim()==="试听";
  }
  function previewButtons(){
    return Array.from(document.querySelectorAll("button")).filter(isPreviewButton);
  }
  function disablePreviewButtons(){
    previewButtons().forEach(button=>{
      if(!button.dataset.previewLocked){
        button.dataset.previewLocked="true";
        button.dataset.previewWasDisabled=String(button.disabled);
      }
      if(!button.disabled)button.disabled=true;
    });
  }
  function finishPreview(){
    if(!busy)return;
    busy=false;
    audioStarted=false;
    activeAudio=null;
    window.clearTimeout(unlockTimer);
    previewButtons().forEach(button=>{
      if(button.dataset.previewLocked){
        button.disabled=button.dataset.previewWasDisabled==="true";
        delete button.dataset.previewLocked;
        delete button.dataset.previewWasDisabled;
      }
    });
  }
  function beginPreview(){
    busy=true;
    audioStarted=false;
    disablePreviewButtons();
    unlockTimer=window.setTimeout(finishPreview,120000);
  }

  const nativePlay=window.HTMLMediaElement&&window.HTMLMediaElement.prototype.play;
  if(nativePlay){
    window.HTMLMediaElement.prototype.play=function(){
      if(busy&&this instanceof HTMLAudioElement){
        audioStarted=true;
        if(activeAudio&&activeAudio!==this)activeAudio.pause();
        activeAudio=this;
        const finish=()=>{
          if(activeAudio===this)finishPreview();
        };
        this.addEventListener("ended",finish,{once:true});
        this.addEventListener("error",finish,{once:true});
        this.addEventListener("abort",finish,{once:true});
        const result=nativePlay.apply(this,arguments);
        if(result&&typeof result.catch==="function")result.catch(finish);
        return result;
      }
      return nativePlay.apply(this,arguments);
    };
  }

  const nativeOpen=window.XMLHttpRequest&&window.XMLHttpRequest.prototype.open;
  if(nativeOpen){
    window.XMLHttpRequest.prototype.open=function(method,url){
      const isPreviewRequest=typeof url==="string"&&/\/voice_library\/[^/]+\/audio(?:\?|$)/.test(url);
      if(isPreviewRequest){
        this.addEventListener("loadend",()=>{
          window.setTimeout(()=>{
            if(busy&&!audioStarted)finishPreview();
          },0);
        },{once:true});
      }
      return nativeOpen.apply(this,arguments);
    };
  }

  document.addEventListener("click",event=>{
    const button=event.target.closest("button");
    if(!isPreviewButton(button)||!localStorage.getItem("token"))return;
    if(busy){
      event.preventDefault();
      event.stopImmediatePropagation();
      return;
    }
    beginPreview();
  },true);

  new MutationObserver(()=>{
    if(busy)disablePreviewButtons();
  }).observe(document.documentElement,{childList:true,subtree:true,attributes:true,attributeFilter:["disabled"]});
})();
