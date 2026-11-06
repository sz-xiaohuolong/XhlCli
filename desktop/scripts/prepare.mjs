import {mkdir,copyFile,mkdtemp,cp,rm,readFile} from 'node:fs/promises';
import {execFileSync} from 'node:child_process';
import path from 'node:path';
import {desktop,runtimeLock,downloadVerified} from './runtime-download.mjs';
if(process.platform!==runtimeLock.platform||process.arch!==runtimeLock.arch){
  throw new Error('当前分发脚本只支持在 Apple 芯片 Mac 上构建 macOS arm64 包。');
}
const resources=path.join(desktop,'resources');
await mkdir(resources,{recursive:true});
await copyFile(path.join(desktop,'../target/xhlcli-1.0.1.jar'),path.join(resources,'xhlcli.jar'));
const archive=await downloadVerified(runtimeLock.runtime);
const temp=await mkdtemp(path.join(resources,'runtime-'));
try{
  execFileSync('/usr/bin/tar',['-xzf',archive,'-C',temp,'--strip-components=1']);
  const java=path.join(temp,'Contents/Home/bin/java');
  const release=await readFile(path.join(temp,'Contents/Home/release'),'utf8');
  if(!release.includes('JAVA_VERSION="21.')||!release.includes('OS_ARCH="aarch64"'))throw new Error('运行环境版本或架构不匹配');
  execFileSync(java,['-version'],{stdio:'inherit'});
  const target=path.join(resources,'runtime');
  await rm(target,{recursive:true,force:true});
  await cp(temp,target,{recursive:true});
}finally{await rm(temp,{recursive:true,force:true});}
