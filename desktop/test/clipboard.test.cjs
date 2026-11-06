const {test}=require('node:test');
const assert=require('node:assert/strict');
const {readFileSync}=require('node:fs');
const path=require('node:path');
const vm=require('node:vm');

// 加载真实主进程与预加载入口，只替换 Electron 宿主，覆盖实际 IPC 的信任边界。
async function host(writeText){
  const handlers=new Map();let window,api;
  class BrowserWindow {
    constructor(){window=this;this.webContents={mainFrame:{},setWindowOpenHandler(){},on(){},session:{setPermissionRequestHandler(){}}};}
    on(){}
    async loadFile(file){this.webContents.mainFrame.url=require('node:url').pathToFileURL(file).href;}
  }
  const electron={app:{get isPackaged(){return false;},setName(){},requestSingleInstanceLock:()=>true,on(){},whenReady:()=>Promise.resolve()},
    BrowserWindow,ipcMain:{handle:(name,handler)=>handlers.set(name,handler)},clipboard:{writeText},Menu:{buildFromTemplate:x=>x,setApplicationMenu(){}},dialog:{showErrorBox:(_title,message)=>{throw Error(message);}},
    contextBridge:{exposeInMainWorld:(_name,value)=>{api=value;}},webUtils:{},
    ipcRenderer:{invoke:(name,method,params)=>handlers.get(name)({sender:window.webContents,senderFrame:window.webContents.mainFrame},method,params)}};
  const main=path.resolve(__dirname,'../electron/main.cjs');
  const localRequire=name=>name==='electron'?electron:name==='node:child_process'?{spawn:()=>({})}:name==='./runtime.cjs'?{resolveJava:()=>'/test/java'}:name==='./backend.cjs'?{Backend:class{call(){throw Error('复制不应读取会话或调用后端');}}}:require(name);
  vm.runInNewContext(readFileSync(main,'utf8'),{require:localRequire,__dirname:path.dirname(main),process:{env:{}},setTimeout,clearTimeout,Buffer});
  await new Promise(resolve=>setImmediate(resolve));
  vm.runInNewContext(readFileSync(path.resolve(__dirname,'../electron/preload.cjs'),'utf8'),{require:localRequire});
  return {api,window,native:handlers.get('desktop:native')};
}
test('复制通过预加载桥接并等待原生剪贴板写入完成',async()=>{
  let release,written;const h=await host(text=>{written=text;return new Promise(resolve=>{release=resolve;});});
  let finished=false;const text='中文回答\n\n**Markdown**';
  const pending=h.api.copyText(text).then(()=>{finished=true;});
  await new Promise(resolve=>setImmediate(resolve));
  assert.equal(written,text);assert.equal(finished,false);release();await pending;assert.equal(finished,true);
});
test('原生剪贴板失败会返回给界面，不误报复制成功',async()=>{
  const h=await host(async()=>{throw Error('剪贴板暂不可用');});
  await assert.rejects(h.api.copyText('内容'),/剪贴板暂不可用/);
});
test('复制拒绝非文本和非主帧请求，不写剪贴板',async()=>{
  let writes=0;const h=await host(async()=>{writes++;});
  await assert.rejects(h.api.copyText({text:'错误格式'}),/文本/);
  await assert.rejects(h.native({sender:h.window.webContents,senderFrame:{url:h.window.webContents.mainFrame.url}},'copyText',{text:'伪造子帧'}),/不受信任/);
  assert.equal(writes,0);
});
