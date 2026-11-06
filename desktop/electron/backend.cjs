const readline=require('node:readline');
class Backend {
  constructor(process,onEvent){
    this.process=process;this.pending=new Map();this.sequence=0;this.dead=false;
    this.ready=new Promise((resolve,reject)=>{this.resolveReady=resolve;this.rejectReady=reject;});
    this.ready.catch(()=>{});
    const reader=readline.createInterface({input:process.stdout});
    reader.on('line',line=>{
      let message;try{message=JSON.parse(line);}catch{return;}
      if(message.event==='ready')this.resolveReady();
      if(message.id!==undefined){const request=this.pending.get(message.id);if(request){clearTimeout(request.timer);this.pending.delete(message.id);message.error?request.reject(new Error(message.error)):request.resolve(message.result);}}
      else if(message.event)onEvent(message);
    });
    process.stderr.on('data',()=>{});
    const fail=()=>{
      if(this.dead)return;this.dead=true;const error=new Error('XhlCLI 后端进程已停止，请重新打开应用。请确认已安装 Java 21。');
      this.rejectReady(error);for(const request of this.pending.values()){clearTimeout(request.timer);request.reject(error);}this.pending.clear();
      onEvent({event:'backend.error',data:{message:error.message}});
    };
    process.on('exit',fail);process.on('error',fail);process.stdin.on('error',fail);
  }
  async call(method,params={}){
    if(this.dead)throw new Error('XhlCLI 后端进程已停止，请重新打开应用');
    await Promise.race([this.ready,new Promise((_,reject)=>{const timer=setTimeout(()=>reject(new Error('后端启动超时，请检查 Java 21 和 JAR 构建')),15000);timer.unref();this.ready.finally(()=>clearTimeout(timer)).catch(()=>{});})]);
    if(this.dead)throw new Error('XhlCLI 后端进程已停止');
    const id=++this.sequence;
    return new Promise((resolve,reject)=>{
      const timer=setTimeout(()=>{this.pending.delete(id);reject(new Error('操作等待超时，请刷新状态确认结果'));},60000);
      this.pending.set(id,{resolve,reject,timer});
      this.process.stdin.write(JSON.stringify({id,method,params})+'\n');
    });
  }
}
module.exports={Backend};
