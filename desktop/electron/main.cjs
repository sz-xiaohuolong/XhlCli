const {app,BrowserWindow,ipcMain,dialog,shell,Menu}=require('electron');
const {spawn,execFileSync,execFile}=require('node:child_process');
const fs=require('node:fs/promises');
const path=require('node:path');
const os=require('node:os');
const {pathToFileURL}=require('node:url');
const crypto=require('node:crypto');
const {Backend}=require('./backend.cjs');
const allowed=new Set(['state','project.add','session.create','session.update','turn.start','turn.cancel','approval.respond','changes','undo','config.get','config.save','config.test']);
let window,backend,child,quitting=false;
const index=path.join(__dirname,'../dist/index.html');
const home=process.env.XHLCLI_DESKTOP_TEST_HOME||os.homedir();
if(process.env.XHLCLI_DESKTOP_TEST_HOME)app.setPath('userData',path.join(home,'electron'));
app.setName('XhlCLI');
if(!app.requestSingleInstanceLock()){app.quit();}else{
  app.on('second-instance',()=>{window?.show();window?.focus();});
  app.whenReady().then(async()=>{
    let java=process.env.XHLCLI_JAVA||'java';
    if(!process.env.XHLCLI_JAVA&&process.platform==='darwin'){
      try{java=path.join(execFileSync('/usr/libexec/java_home',['-v','21'],{encoding:'utf8'}).trim(),'bin/java');}catch{}
    }
    const jar=app.isPackaged?path.join(process.resourcesPath,'xhlcli.jar'):path.join(__dirname,'../../target/xhlcli-1.0.1.jar');
    child=spawn(java,['-cp',jar,'com.xhlcli.desktop.DesktopMain',...(process.env.XHLCLI_DESKTOP_TEST_HOME?['--home',home]:[])],{stdio:['pipe','pipe','pipe'],cwd:home,env:process.env});
    backend=new Backend(child,message=>{if(window&&!window.isDestroyed())window.webContents.send('desktop:event',message);});
    window=new BrowserWindow({width:1360,height:900,minWidth:900,minHeight:620,title:'XhlCLI',backgroundColor:'#f4f5f5',titleBarStyle:'hiddenInset',trafficLightPosition:{x:16,y:17},webPreferences:{preload:path.join(__dirname,'preload.cjs'),contextIsolation:true,nodeIntegration:false,sandbox:true}});
    window.webContents.setWindowOpenHandler(()=>({action:'deny'}));
    window.webContents.on('will-navigate',event=>event.preventDefault());
    window.webContents.session.setPermissionRequestHandler((_wc,_permission,callback)=>callback(false));
    window.on('close',event=>{if(!quitting){event.preventDefault();window.hide();}});
    await window.loadFile(index);
    Menu.setApplicationMenu(Menu.buildFromTemplate([
      {label:'XhlCLI',submenu:[{label:'关于 XhlCLI',role:'about'},{type:'separator'},{label:'设置…',accelerator:'CmdOrCtrl+,',click:()=>window.webContents.send('desktop:event',{event:'ui.settings'})},{type:'separator'},{label:'退出 XhlCLI',role:'quit'}]},
      {label:'编辑',submenu:[{role:'undo',label:'撤销输入'},{role:'redo',label:'重做输入'},{type:'separator'},{role:'cut',label:'剪切'},{role:'copy',label:'复制'},{role:'paste',label:'粘贴'},{role:'selectAll',label:'全选'}]},
      {label:'显示',submenu:[{label:'显示主窗口',click:()=>window.show()},{role:'resetZoom',label:'实际大小'},{role:'zoomIn',label:'放大'},{role:'zoomOut',label:'缩小'},{role:'togglefullscreen',label:'全屏'}]}
    ]));
  }).catch(error=>dialog.showErrorBox('XhlCLI 启动失败',error.message));
}
function trusted(event){if(!window||event.sender!==window.webContents||event.senderFrame!==window.webContents.mainFrame||event.senderFrame.url!==pathToFileURL(index).href)throw new Error('拒绝不受信任的界面请求');}
ipcMain.handle('desktop:request',async(event,method,params={})=>{
  trusted(event);if(!allowed.has(method))throw new Error('不支持的操作');
  if(method==='undo'){
    const result=await dialog.showMessageBox(window,{type:'warning',message:'撤销这一轮的文件改动？',detail:'只恢复本轮变化的文件。若文件后来被修改，将拒绝撤销并保留现有内容。',buttons:['保留改动','撤销本轮'],defaultId:0,cancelId:0});
    if(result.response!==1)return {canceled:true};
  }
  return backend.call(method,params);
});
ipcMain.handle('desktop:native',async(event,method,p={})=>{
  trusted(event);
  if(method==='chooseProject'){const result=await dialog.showOpenDialog(window,{title:'选择代码项目',properties:['openDirectory','createDirectory']});return result.canceled?null:result.filePaths[0];}
  if(method==='chooseFiles'){const result=await dialog.showOpenDialog(window,{title:'添加文件或图片',properties:['openFile','multiSelections']});return result.canceled?[]:result.filePaths;}
  if(method==='openExternal'){const url=new URL(p.url);if(!['https:','http:'].includes(url.protocol))throw new Error('仅支持 HTTP/HTTPS 链接');return shell.openExternal(url.href);}
  if(method==='saveImage'){
    if(typeof p.data!=='string'||p.data.length>14*1024*1024||!p.data.startsWith('data:image/png;base64,'))throw new Error('图片格式或大小无效');
    const dir=path.join(home,'.xhlcli/desktop/imports');await fs.mkdir(dir,{recursive:true});const file=path.join(dir,crypto.randomUUID()+'.png');
    await fs.writeFile(file,Buffer.from(p.data.split(',')[1],'base64'),{mode:0o600});return file;
  }
  const state=await backend.call('state');const session=state.sessions.find(s=>s.id===p.sessionId);if(!session)throw new Error('会话不存在');
  const root=await fs.realpath(session.path);
  if(method==='openTerminal'){if(process.platform!=='darwin')return shell.openPath(root);return new Promise((resolve,reject)=>execFile('/usr/bin/open',['-a','Terminal',root],error=>error?reject(error):resolve(true)));}
  if(method==='openFile'){
    const file=await fs.realpath(path.resolve(root,p.path));if(file!==root&&!file.startsWith(root+path.sep))throw new Error('文件不在当前项目内');
    return shell.openPath(file).then(error=>{if(error)throw new Error(error);return true;});
  }
  throw new Error('不支持的系统操作');
});
app.on('activate',()=>window?.show());
app.on('before-quit',event=>{
  if(quitting)return;event.preventDefault();quitting=true;
  if(!child||child.exitCode!==null){app.quit();return;}
  child.stdin.end();const timer=setTimeout(()=>{child.kill('SIGKILL');app.quit();},10000);timer.unref();
  child.once('exit',()=>{clearTimeout(timer);app.quit();});
});
