const path=require('node:path');
const {accessSync,constants}=require('node:fs');
const {execFileSync}=require('node:child_process');
function resolveJava({isPackaged=false,resourcesPath,platform=process.platform,env=process.env}={}){
  if(isPackaged){
    const bundled=path.join(resourcesPath,'runtime/Contents/Home/bin/java');
    try{accessSync(bundled,constants.X_OK);}catch{throw new Error('内置运行环境不完整，请重新解压 XhlCLI 压缩包。');}
    return bundled;
  }
  let java=env.XHLCLI_JAVA||'java';
  if(!env.XHLCLI_JAVA&&platform==='darwin'){
    try{java=path.join(execFileSync('/usr/libexec/java_home',['-v','21'],{encoding:'utf8'}).trim(),'bin/java');}catch{}
  }
  return java;
}
module.exports={resolveJava};
