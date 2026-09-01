(function(){
  "use strict";

  const OUTPUT_RATE=16000;
  const MAX_DURATION_MS=5*60*1000;
  const WORKLET_URL="/assets/pcm-recorder-worklet.js?v=20260830-1";
  const controls=new Set();
  let activeControl=null;

  function button(text,className){
    const item=document.createElement("button");
    item.type="button";
    item.className=className||"secondary";
    item.textContent=text;
    return item;
  }

  function mergeBuffers(buffers){
    const length=buffers.reduce((sum,item)=>sum+item.length,0);
    const merged=new Float32Array(length);
    let offset=0;
    buffers.forEach(item=>{merged.set(item,offset);offset+=item.length});
    return merged;
  }

  function resample(input,inputRate,outputRate){
    if(!input.length||inputRate===outputRate)return input.slice(0);
    const ratio=inputRate/outputRate;
    const output=new Float32Array(Math.max(1,Math.round(input.length/ratio)));
    for(let index=0;index<output.length;index+=1){
      const start=Math.floor(index*ratio);
      const end=Math.min(input.length,Math.max(start+1,Math.floor((index+1)*ratio)));
      let sum=0;
      for(let cursor=start;cursor<end;cursor+=1)sum+=input[cursor];
      output[index]=sum/(end-start);
    }
    return output;
  }

  function pcm16(samples){
    const bytes=new ArrayBuffer(samples.length*2);
    const view=new DataView(bytes);
    for(let index=0;index<samples.length;index+=1){
      const sample=Math.max(-1,Math.min(1,samples[index]));
      view.setInt16(index*2,sample<0?sample*32768:sample*32767,true);
    }
    return bytes;
  }

  function wavBlob(samples){
    const pcm=pcm16(samples);
    const buffer=new ArrayBuffer(44+pcm.byteLength);
    const view=new DataView(buffer);
    const write=(offset,text)=>{
      for(let index=0;index<text.length;index+=1)view.setUint8(offset+index,text.charCodeAt(index));
    };
    write(0,"RIFF");
    view.setUint32(4,36+pcm.byteLength,true);
    write(8,"WAVE");
    write(12,"fmt ");
    view.setUint32(16,16,true);
    view.setUint16(20,1,true);
    view.setUint16(22,1,true);
    view.setUint32(24,OUTPUT_RATE,true);
    view.setUint32(28,OUTPUT_RATE*2,true);
    view.setUint16(32,2,true);
    view.setUint16(34,16,true);
    write(36,"data");
    view.setUint32(40,pcm.byteLength,true);
    new Uint8Array(buffer,44).set(new Uint8Array(pcm));
    return new Blob([buffer],{type:"audio/wav"});
  }

  class PcmRecorder {
    constructor(onPcm,onLimit){
      this.onPcm=onPcm;
      this.onLimit=onLimit;
      this.buffers=[];
      this.startedAt=0;
    }

    async start(){
      if(!navigator.mediaDevices||!window.AudioContext||!window.AudioWorkletNode){
        throw new Error("当前浏览器不支持麦克风录音，请使用最新版 Chrome 或 Edge");
      }
      this.stream=await navigator.mediaDevices.getUserMedia({
        audio:{channelCount:1,echoCancellation:true,noiseSuppression:true,autoGainControl:true}
      });
      this.context=new AudioContext();
      await this.context.audioWorklet.addModule(WORKLET_URL);
      this.source=this.context.createMediaStreamSource(this.stream);
      this.node=new AudioWorkletNode(this.context,"pcm-recorder");
      this.silent=this.context.createGain();
      this.silent.gain.value=0;
      this.node.port.onmessage=event=>{
        const samples=event.data;
        this.buffers.push(samples);
        if(this.onPcm){
          const converted=resample(samples,this.context.sampleRate,OUTPUT_RATE);
          this.onPcm(pcm16(converted));
        }
      };
      this.source.connect(this.node);
      this.node.connect(this.silent);
      this.silent.connect(this.context.destination);
      this.startedAt=Date.now();
      this.limitTimer=window.setTimeout(()=>this.onLimit&&this.onLimit(),MAX_DURATION_MS);
    }

    async stop(){
      window.clearTimeout(this.limitTimer);
      if(this.node)this.node.disconnect();
      if(this.source)this.source.disconnect();
      if(this.silent)this.silent.disconnect();
      if(this.stream)this.stream.getTracks().forEach(track=>track.stop());
      if(this.context&&this.context.state!=="closed")await this.context.close();
      const sourceRate=this.context?this.context.sampleRate:OUTPUT_RATE;
      return wavBlob(resample(mergeBuffers(this.buffers),sourceRate,OUTPUT_RATE));
    }
  }

  function titleFor(input){
    const container=input.closest("article,.panel,section");
    const title=container&&container.querySelector(".panel-title,h3");
    return title?title.textContent.trim():"录音";
  }

  function assignFile(input,blob){
    const file=new File([blob],"microphone-"+Date.now()+".wav",{type:"audio/wav"});
    const transfer=new DataTransfer();
    transfer.items.add(file);
    input.files=transfer.files;
    input.dispatchEvent(new Event("change",{bubbles:true}));
    return file;
  }

  function websocketUrl(){
    const configured=localStorage.getItem("zyjs_api")||window.location.origin;
    const url=new URL(configured,window.location.origin);
    url.protocol=url.protocol==="https:"?"wss:":"ws:";
    url.pathname="/ws/asr/stream";
    url.search="";
    url.hash="";
    return url.toString();
  }

  class MicrophoneControl {
    constructor(input){
      this.input=input;
      this.title=titleFor(input);
      this.liveEnabled=this.title==="语音识别";
      this.recording=false;
      this.live=false;
      this.objectUrl="";
      this.render();
      controls.add(this);
    }

    render(){
      this.root=document.createElement("div");
      this.root.className="microphone-control";
      this.recordButton=button("麦克风录音","secondary");
      this.stopButton=button("停止","secondary");
      this.retryButton=button("重录","secondary");
      this.previewButton=button("试听","secondary");
      this.stopButton.disabled=true;
      this.retryButton.disabled=true;
      this.previewButton.disabled=true;
      this.status=document.createElement("span");
      this.status.className="microphone-status";
      this.status.textContent="也可以直接使用麦克风";
      this.root.append(this.recordButton,this.stopButton,this.retryButton,this.previewButton);
      if(this.liveEnabled){
        this.liveButton=button("实时识别","primary");
        this.root.append(this.liveButton);
        this.liveButton.addEventListener("click",()=>this.start(true));
      }
      this.root.append(this.status);
      const label=this.input.closest("label");
      (label||this.input).insertAdjacentElement("afterend",this.root);
      this.recordButton.addEventListener("click",()=>this.start(false));
      this.stopButton.addEventListener("click",()=>this.stop());
      this.retryButton.addEventListener("click",()=>this.start(false));
      this.previewButton.addEventListener("click",()=>this.preview());
      this.input.addEventListener("change",()=>{
        const file=this.input.files&&this.input.files[0];
        if(file&&!file.name.startsWith("microphone-")){
          this.file=file;
          this.status.textContent="已选择文件："+file.name;
          this.retryButton.disabled=false;
          this.previewButton.disabled=false;
        }
      });
    }

    async start(live){
      if(activeControl&&activeControl!==this)await activeControl.stop();
      if(this.recording)return;
      if(live&&!localStorage.getItem("token")){
        this.status.textContent="请先登录后使用实时识别";
        return;
      }
      this.resetAudio();
      this.live=live;
      this.finalText="";
      this.partialText="";
      try{
        if(live)await this.openSocket();
        this.recorder=new PcmRecorder(
          live?pcm=>this.sendPcm(pcm):null,
          ()=>this.stop("已达到 5 分钟上限")
        );
        await this.recorder.start();
        this.recording=true;
        activeControl=this;
        this.recordButton.disabled=true;
        this.stopButton.disabled=false;
        this.retryButton.disabled=true;
        this.previewButton.disabled=true;
        if(this.liveButton)this.liveButton.disabled=true;
        this.startedAt=Date.now();
        this.status.textContent=live?"实时识别中 00:00":"录音中 00:00";
        this.clock=window.setInterval(()=>this.updateClock(),250);
      }catch(error){
        this.status.textContent=this.message(error);
        this.closeSocket();
        await this.releaseRecorder();
      }
    }

    async stop(reason){
      if(!this.recording){
        this.closeSocket();
        return;
      }
      this.recording=false;
      window.clearInterval(this.clock);
      try{
        const blob=await this.recorder.stop();
        this.file=assignFile(this.input,blob);
        this.objectUrl=URL.createObjectURL(blob);
        if(this.live&&this.socket&&this.socket.readyState===WebSocket.OPEN){
          this.socket.send(JSON.stringify({type:"stop"}));
          this.status.textContent=reason||"正在生成最终识别结果…";
          this.socketCloseTimer=window.setTimeout(()=>this.closeSocket(),5000);
        }else{
          this.status.textContent=reason||"录音完成，可试听或提交";
        }
      }catch(error){
        this.status.textContent=this.message(error);
        this.closeSocket();
      }finally{
        this.recorder=null;
        if(activeControl===this)activeControl=null;
        this.recordButton.disabled=false;
        this.stopButton.disabled=true;
        this.retryButton.disabled=false;
        this.previewButton.disabled=false;
        if(this.liveButton)this.liveButton.disabled=false;
      }
    }

    async releaseRecorder(){
      if(!this.recorder)return;
      try{await this.recorder.stop()}catch{}
      this.recorder=null;
      this.recording=false;
      if(activeControl===this)activeControl=null;
    }

    async openSocket(){
      await new Promise((resolve,reject)=>{
        let settled=false;
        const socket=new WebSocket(websocketUrl());
        this.socket=socket;
        const timeout=window.setTimeout(()=>{
          if(!settled){settled=true;reject(new Error("实时识别连接超时"));socket.close()}
        },10000);
        socket.onopen=()=>socket.send(JSON.stringify({
          type:"start",
          token:localStorage.getItem("token"),
          language:"zh",
          sampleRate:OUTPUT_RATE
        }));
        socket.onmessage=event=>{
          let message;
          try{message=JSON.parse(event.data)}catch{return}
          if(message.type==="ready"&&!settled){
            settled=true;
            window.clearTimeout(timeout);
            resolve();
          }else if(message.type==="partial"){
            this.partialText=message.text||"";
            this.showTranscript();
          }else if(message.type==="final"){
            this.finalText+=message.text||"";
            this.partialText="";
            this.showTranscript();
          }else if(message.type==="complete"){
            this.finalText=message.text||this.finalText;
            this.partialText="";
            this.showTranscript();
            this.status.textContent="实时识别完成，录音也可再次提交";
            this.closeSocket();
          }else if(message.type==="error"){
            const error=new Error(message.message||"实时识别失败");
            if(!settled){settled=true;window.clearTimeout(timeout);reject(error)}
            else this.failLive(error);
          }
        };
        socket.onerror=()=>{
          const error=new Error("实时识别连接失败");
          if(!settled){settled=true;window.clearTimeout(timeout);reject(error)}
          else this.failLive(error);
        };
        socket.onclose=()=>{
          window.clearTimeout(timeout);
          if(!settled){settled=true;reject(new Error("实时识别连接已关闭"))}
        };
      });
    }

    sendPcm(pcm){
      if(!this.socket||this.socket.readyState!==WebSocket.OPEN)return;
      if(this.socket.bufferedAmount>1048576){
        this.failLive(new Error("网络拥塞，实时识别已停止"));
        return;
      }
      this.socket.send(pcm);
    }

    async failLive(error){
      if(this.liveFailing)return;
      this.liveFailing=true;
      const message=this.message(error);
      if(this.recording)await this.stop(message);
      this.closeSocket();
      this.status.textContent=message;
      this.liveFailing=false;
    }

    showTranscript(){
      const panel=this.input.closest("article,.panel,section");
      const result=panel&&panel.querySelector("pre.result");
      if(result)result.textContent="识别文本："+this.finalText+this.partialText+"\n状态：实时识别中";
    }

    updateClock(){
      const seconds=Math.floor((Date.now()-this.startedAt)/1000);
      const minute=String(Math.floor(seconds/60)).padStart(2,"0");
      const second=String(seconds%60).padStart(2,"0");
      this.status.textContent=(this.live?"实时识别中 ":"录音中 ")+minute+":"+second;
    }

    preview(){
      if(!this.objectUrl&&this.file)this.objectUrl=URL.createObjectURL(this.file);
      if(!this.objectUrl)return;
      if(this.audio)this.audio.pause();
      this.audio=new Audio(this.objectUrl);
      this.audio.play().catch(error=>{this.status.textContent=this.message(error)});
    }

    resetAudio(){
      if(this.audio)this.audio.pause();
      if(this.objectUrl)URL.revokeObjectURL(this.objectUrl);
      this.objectUrl="";
    }

    closeSocket(){
      window.clearTimeout(this.socketCloseTimer);
      if(this.socket&&this.socket.readyState<2)this.socket.close();
      this.socket=null;
    }

    message(error){
      if(error&&error.name==="NotAllowedError")return"未获得麦克风权限";
      if(error&&error.name==="NotFoundError")return"没有检测到麦克风设备";
      return error&&error.message?error.message:"录音失败";
    }

    async destroy(){
      this.closeSocket();
      await this.releaseRecorder();
      this.resetAudio();
    }
  }

  function enhance(){
    document.querySelectorAll('input[type="file"][accept*="audio"]').forEach(input=>{
      if(input.dataset.microphoneEnhanced)return;
      input.dataset.microphoneEnhanced="true";
      new MicrophoneControl(input);
    });
  }

  const observer=new MutationObserver(enhance);
  observer.observe(document.documentElement,{childList:true,subtree:true});
  document.addEventListener("click",event=>{
    if(event.target.closest(".nav-btn")&&activeControl)activeControl.stop();
  },true);
  window.addEventListener("beforeunload",()=>controls.forEach(control=>control.destroy()));
  window.ZyjsAudioRecorder={resample,pcm16,wavBlob};
  if(document.readyState==="loading")document.addEventListener("DOMContentLoaded",enhance);
  else enhance();
})();
