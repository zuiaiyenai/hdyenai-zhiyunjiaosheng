(function(){
  "use strict";
  const labels=["文本转语音","声音样本库","声音克隆","辅助功能","课件制作下载","口语练习与点评","学习报告"];
  let initialized=false;
  let objectUrl="";
  let audioObjectUrl="";
  let activeAudio=null;
  let authTokenAtLoad=localStorage.getItem("token")||"";
  const designWidth=1280;
  const designHeight=720;

  function fitViewport(){
    const app=document.querySelector("#app");
    if(!app)return;
    if(window.innerWidth<=800){
      app.style.zoom="";
      app.style.width="";
      app.style.height="";
      return;
    }
    const scale=Math.max(.75,Math.min(window.innerWidth/designWidth,window.innerHeight/designHeight));
    app.style.zoom=String(scale);
    app.style.width=(window.innerWidth/scale)+"px";
    app.style.height=(window.innerHeight/scale)+"px";
  }

  function node(tag,className,text){
    const item=document.createElement(tag);
    if(className)item.className=className;
    if(text)item.textContent=text;
    return item;
  }
  function apiConfig(){
    return {
      baseUrl:(localStorage.getItem("zyjs_api")||window.location.origin).replace(/\/$/,""),
      token:localStorage.getItem("token")||""
    };
  }
  async function apiError(response){
    const contentType=response.headers.get("content-type")||"";
    if(contentType.includes("application/json")){
      const data=await response.json().catch(()=>null);
      return data&&(data.msg||data.message||data.error)||("请求失败（"+response.status+"）");
    }
    return (await response.text().catch(()=>""))||("请求失败（"+response.status+"）");
  }
  function requireLogin(){
    if(apiConfig().token)return true;
    window.alert("请先登录后再使用此功能");
    return false;
  }
  function applyReadingStyle(button){
    const style=button.textContent.trim();
    const speeds={"自然":"1","温柔":"0.9","活泼":"1.2"};
    if(!Object.hasOwn(speeds,style))return false;
    const group=button.closest(".chips");
    const settings=button.closest(".settings");
    const speedInput=settings&&settings.querySelector('input[type="range"]');
    if(!group||!speedInput)return false;
    group.querySelectorAll("button.chip").forEach(item=>{
      const active=item===button;
      item.classList.toggle("active",active);
      item.setAttribute("aria-pressed",String(active));
    });
    speedInput.value=speeds[style];
    speedInput.dispatchEvent(new Event("input",{bubbles:true}));
    speedInput.dispatchEvent(new Event("change",{bubbles:true}));
    button.title="已应用"+style+"风格，语速 "+speeds[style]+" 倍";
    return true;
  }
  async function previewDemo(button,cardIndex){
    if(!requireLogin())return;
    const config=apiConfig();
    const demoFiles=["male.mp3","female.mp3","child.mp3"];
    const demoFile=demoFiles[cardIndex]||demoFiles[1];
    const oldContent=button.innerHTML;
    button.disabled=true;
    button.textContent="…";
    try{
      const response=await fetch(config.baseUrl+"/audio-demos/"+demoFile,{
        headers:{Authorization:"Bearer "+config.token}
      });
      if(!response.ok)throw new Error(await apiError(response));
      const blob=await response.blob();
      if(activeAudio)activeAudio.pause();
      if(audioObjectUrl)URL.revokeObjectURL(audioObjectUrl);
      audioObjectUrl=URL.createObjectURL(blob);
      activeAudio=new Audio(audioObjectUrl);
      await activeAudio.play();
    }catch(error){
      window.alert(error&&error.message?error.message:"试听失败");
    }finally{
      button.disabled=false;
      button.innerHTML=oldContent;
    }
  }
  async function generateSummary(button,article){
    if(!requireLogin())return;
    const textarea=article.querySelector("textarea");
    const result=article.querySelector("pre.result");
    const textContent=textarea&&textarea.value.trim();
    if(!textContent){
      if(result)result.textContent="请先输入需要摘要的文本";
      if(textarea)textarea.focus();
      return;
    }
    const config=apiConfig();
    const oldText=button.textContent;
    button.disabled=true;
    button.textContent="正在生成…";
    if(result)result.textContent="正在连接后端生成摘要，请稍候……";
    try{
      const response=await fetch(config.baseUrl+"/accessibility/generate-summary",{
        method:"POST",
        headers:{"Content-Type":"application/json",Authorization:"Bearer "+config.token},
        body:JSON.stringify({text:textContent})
      });
      if(!response.ok)throw new Error(await apiError(response));
      const data=await response.json();
      if(result)result.textContent=data.summary||"后端未返回摘要内容";
    }catch(error){
      if(result)result.textContent=error&&error.message?error.message:"摘要生成失败";
    }finally{
      button.disabled=false;
      button.textContent=oldText;
    }
  }
  function setActive(button){
    document.querySelectorAll(".topbar .nav-btn").forEach(item=>item.classList.toggle("active",item===button));
  }
  function hideVuePages(){
    document.querySelectorAll("main > section.page").forEach(section=>{section.style.display="none"});
  }
  function hideCustomPages(){
    document.querySelectorAll("main > .video-home,main > .video-custom-page").forEach(page=>{page.style.display="none"});
  }
  function showHome(button){
    hideVuePages();
    hideCustomPages();
    const page=document.querySelector(".video-home");
    if(page)page.style.display="grid";
    setActive(button);
  }
  function showVideoSwap(button){
    hideVuePages();
    hideCustomPages();
    const page=document.querySelector(".video-custom-page");
    if(page)page.style.display="grid";
    setActive(button);
  }
  function showVuePage(button,originalButton){
    hideCustomPages();
    originalButton.click();
    window.setTimeout(()=>{
      const pages=document.querySelectorAll("main > section.page");
      const pageMap=[[0],[1],[2],[3,4],[5],[6],[7]];
      hideVuePages();
      (pageMap[Number(originalButton.dataset.videoIndex)]||[]).forEach(index=>{
        if(pages[index])pages[index].style.display="";
      });
      setActive(button);
    },0);
  }
  function createHome(main,nav,firstVueButton){
    const page=node("section","video-home");
    page.innerHTML='<div class="video-home__content"><h1>智韵教声 年度口语PK报名中</h1><p>Zhiyun Teaching Voice Annual Oral Competition Registration Open...</p><button class="primary" type="button">进入文本转语音</button></div>';
    page.querySelector("button").addEventListener("click",()=>showVuePage(firstVueButton,firstVueButton));
    main.insertBefore(page,main.firstChild);
    const homeButton=node("button","nav-btn active","首页");
    homeButton.type="button";
    homeButton.addEventListener("click",()=>showHome(homeButton));
    nav.insertBefore(homeButton,nav.firstChild);
    return homeButton;
  }
  function createVideoPage(main,nav,beforeButton){
    const page=node("section","video-custom-page");
    page.style.display="none";
    page.innerHTML=[
      '<div class="video-swap-controls">',
      '<h2>上传视频</h2>',
      '<label class="video-file-drop">选择需要换声的 MP4 / MOV 视频<input id="video-swap-file" type="file" accept="video/*"></label>',
      '<label class="video-voice-field">选择音色替换原声<select id="video-swap-voice"><option value="longxiao">女声 · 温柔自然</option><option value="longcheng">男声 · 沉稳清晰</option><option value="default">默认音色</option></select></label>',
      '<div class="video-swap-actions"><button id="video-swap-submit" class="primary" type="button">开始换声</button><button id="video-swap-download" class="secondary" type="button" disabled>下载视频</button></div>',
      '<p id="video-swap-status" class="video-swap-status">上传后可先预览原视频</p>',
      '</div>',
      '<div class="video-swap-preview"><h2>视频预览</h2><video id="video-swap-player" controls></video></div>'
    ].join("");
    main.appendChild(page);
    const button=node("button","nav-btn","视频换声");
    button.type="button";
    button.addEventListener("click",()=>showVideoSwap(button));
    nav.insertBefore(button,beforeButton||null);

    const fileInput=page.querySelector("#video-swap-file");
    const player=page.querySelector("#video-swap-player");
    const status=page.querySelector("#video-swap-status");
    const submit=page.querySelector("#video-swap-submit");
    const download=page.querySelector("#video-swap-download");
    fileInput.addEventListener("change",()=>{
      const file=fileInput.files&&fileInput.files[0];
      if(!file)return;
      if(objectUrl)URL.revokeObjectURL(objectUrl);
      objectUrl=URL.createObjectURL(file);
      player.src=objectUrl;
      download.disabled=true;
      download.removeAttribute("data-url");
      status.className="video-swap-status";
      status.textContent="已选择："+file.name;
    });
    submit.addEventListener("click",async()=>{
      const file=fileInput.files&&fileInput.files[0];
      if(!file){
        status.className="video-swap-status error";
        status.textContent="请先选择视频文件";
        return;
      }
      const baseUrl=(localStorage.getItem("zyjs_api")||window.location.origin).replace(/\/$/,"");
      const token=localStorage.getItem("token")||"";
      const form=new FormData();
      form.append("video",file);
      form.append("voiceType",page.querySelector("#video-swap-voice").value);
      submit.disabled=true;
      download.disabled=true;
      status.className="video-swap-status";
      status.textContent="正在识别、合成并替换视频原声，请稍候……";
      try{
        const headers=token?{Authorization:"Bearer "+token}:{};
        const response=await fetch(baseUrl+"/video_voice_swap/process",{method:"POST",headers,body:form});
        if(!response.ok)throw new Error((await response.text())||("处理失败（"+response.status+"）"));
        const blob=await response.blob();
        if(objectUrl)URL.revokeObjectURL(objectUrl);
        objectUrl=URL.createObjectURL(blob);
        player.src=objectUrl;
        download.dataset.url=objectUrl;
        download.dataset.name="智韵教声-换声视频.mp4";
        download.disabled=false;
        status.textContent="视频换声完成，可预览或下载";
      }catch(error){
        status.className="video-swap-status error";
        status.textContent=error&&error.message?error.message:"视频换声失败";
      }finally{
        submit.disabled=false;
      }
    });
    download.addEventListener("click",()=>{
      if(!download.dataset.url)return;
      const link=document.createElement("a");
      link.href=download.dataset.url;
      link.download=download.dataset.name||"换声视频.mp4";
      link.click();
    });
    return button;
  }
  function addOralSubnav(main,oralButton,reportButton){
    const pages=main.querySelectorAll(":scope > section.page");
    const oralPage=pages[6];
    const reportPage=pages[7];
    if(!oralPage||!reportPage)return;
    function makeSubnav(){
      const subnav=node("div","video-subnav");
      const practice=node("button","primary","口语评测");
      const dialogue=node("button","secondary","多轮对话");
      const history=node("button","secondary","历史报告");
      practice.type=dialogue.type=history.type="button";
      practice.addEventListener("click",()=>{
        oralButton.click();
        window.setTimeout(()=>oralPage.querySelector("article.panel.mt")?.scrollIntoView({behavior:"smooth",block:"start"}),50);
      });
      dialogue.addEventListener("click",()=>{
        oralButton.click();
        window.setTimeout(()=>oralPage.querySelector("article.panel:not(.mt)")?.scrollIntoView({behavior:"smooth",block:"start"}),50);
      });
      history.addEventListener("click",()=>reportButton.click());
      subnav.append(practice,dialogue,history);
      return subnav;
    }
    oralPage.insertBefore(makeSubnav(),oralPage.firstChild);
    reportPage.insertBefore(makeSubnav(),reportPage.firstChild);
  }
  function updateBuiltInVoiceLabels(){
    document.querySelectorAll('select option[value="longxiao-en"]').forEach(option=>{
      option.textContent="Katherine · 英文女声";
    });
  }
  function updateOralStartLabel(){
    const oralPage=document.querySelectorAll("main > section.page")[6];
    if(!oralPage)return;
    const conversation=oralPage.querySelector(".conversation");
    const button=oralPage.querySelector(".conversation ~ .actions button.primary");
    if(button&&conversation&&conversation.textContent.includes("选择场景后点击")&&button.textContent.trim()==="重新开始"){
      button.textContent="开始对话";
    }
  }
  function ensureUserMenu(){
    const header=document.querySelector(".topbar");
    if(!header||header.querySelector(".video-user-menu"))return;
    const menu=node("div","video-user-menu");
    const name=node("strong","",localStorage.getItem("username")||"已登录用户");
    const logout=node("button","","退出登录");
    logout.type="button";
    logout.addEventListener("click",()=>{
      localStorage.removeItem("token");
      localStorage.removeItem("role");
      localStorage.removeItem("username");
      sessionStorage.removeItem("video-layout-auth-refresh");
      window.location.reload();
    });
    menu.append(name,logout);
    header.appendChild(menu);
  }
  function bindEnhancements(){
    document.addEventListener("click",event=>{
      const styleButton=event.target.closest("button.chip");
      if(styleButton&&applyReadingStyle(styleButton)){
        event.preventDefault();
        return;
      }
      const button=event.target.closest("button");
      if(!button)return;
      const card=button.closest(".voice-card");
      if(card&&button.querySelector(".sr-only")?.textContent.trim()==="试听"){
        event.preventDefault();
        event.stopImmediatePropagation();
        const cards=Array.from(card.parentElement.querySelectorAll(":scope > .voice-card"));
        previewDemo(button,cards.indexOf(card));
        return;
      }
      const article=button.closest("article.panel");
      if(article&&article.querySelector(".panel-title")?.textContent.trim()==="文本朗读"&&button.textContent.trim()==="智能摘要"){
        event.preventDefault();
        event.stopImmediatePropagation();
        generateSummary(button,article);
        return;
      }
      const header=button.closest(".topbar");
      if(header&&button.parentElement===header&&apiConfig().token){
        event.preventDefault();
        event.stopImmediatePropagation();
        ensureUserMenu();
        header.querySelector(".video-user-menu").classList.toggle("open");
      }
    },true);
    document.addEventListener("click",event=>{
      const menu=document.querySelector(".video-user-menu.open");
      if(menu&&!event.target.closest(".video-user-menu")&&!event.target.closest(".topbar > button"))menu.classList.remove("open");
    });
    window.setInterval(()=>{
      const token=localStorage.getItem("token")||"";
      if(token&&token!==authTokenAtLoad&&sessionStorage.getItem("video-layout-auth-refresh")!==token){
        sessionStorage.setItem("video-layout-auth-refresh",token);
        window.location.reload();
        return;
      }
      if(!token)sessionStorage.removeItem("video-layout-auth-refresh");
      authTokenAtLoad=token;
      updateBuiltInVoiceLabels();
      updateOralStartLabel();
    },400);
  }
  function init(){
    if(initialized)return;
    const header=document.querySelector(".topbar");
    const nav=header&&header.querySelector("nav");
    const main=document.querySelector("main");
    const originalButtons=nav?Array.from(nav.querySelectorAll(".nav-btn")):[];
    if(!header||!nav||!main||originalButtons.length<7)return;
    initialized=true;
    fitViewport();
    document.body.classList.add("video-layout-ready");
    originalButtons.forEach((button,index)=>{
      button.dataset.videoIndex=String(index);
      button.textContent=labels[index];
      button.addEventListener("click",()=>{
        hideCustomPages();
        window.setTimeout(()=>{
          const pages=document.querySelectorAll("main > section.page");
          const pageMap=[[0],[1],[2],[3,4],[5],[6],[7]];
          hideVuePages();
          pageMap[index].forEach(pageIndex=>{
            if(pages[pageIndex])pages[pageIndex].style.display="";
          });
          setActive(index===6?originalButtons[5]:button);
        },0);
      });
    });
    originalButtons[2].dataset.videoHidden="true";
    originalButtons[6].dataset.videoHidden="true";
    const homeButton=createHome(main,nav,originalButtons[0]);
    const videoButton=createVideoPage(main,nav,originalButtons[3]);
    addOralSubnav(main,originalButtons[5],originalButtons[6]);
    bindEnhancements();
    updateBuiltInVoiceLabels();
    updateOralStartLabel();
    showHome(homeButton);
    window.addEventListener("beforeunload",()=>{
      if(objectUrl)URL.revokeObjectURL(objectUrl);
      if(audioObjectUrl)URL.revokeObjectURL(audioObjectUrl);
    });
    window.videoLayout={showHome:()=>showHome(homeButton),showVideoSwap:()=>showVideoSwap(videoButton)};
  }
  const observer=new MutationObserver(init);
  observer.observe(document.documentElement,{childList:true,subtree:true});
  if(document.readyState==="loading")document.addEventListener("DOMContentLoaded",init);
  else init();
  window.addEventListener("resize",fitViewport);
  fitViewport();
  window.setTimeout(init,500);
})();
