const {contextBridge,ipcRenderer,webUtils}=require('electron');
contextBridge.exposeInMainWorld('xhl',{
  request:(method,params)=>ipcRenderer.invoke('desktop:request',method,params),
  chooseProject:()=>ipcRenderer.invoke('desktop:native','chooseProject'),
  chooseFiles:()=>ipcRenderer.invoke('desktop:native','chooseFiles'),
  openFile:(sessionId,path)=>ipcRenderer.invoke('desktop:native','openFile',{sessionId,path}),
  openTerminal:sessionId=>ipcRenderer.invoke('desktop:native','openTerminal',{sessionId}),
  openExternal:url=>ipcRenderer.invoke('desktop:native','openExternal',{url}),
  saveImage:data=>ipcRenderer.invoke('desktop:native','saveImage',{data}),
  pathForFile:file=>webUtils.getPathForFile(file),
  subscribe:callback=>{const listener=(_event,message)=>callback(message);ipcRenderer.on('desktop:event',listener);return()=>ipcRenderer.removeListener('desktop:event',listener);}
});
