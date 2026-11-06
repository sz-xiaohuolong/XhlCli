import {mkdir,mkdtemp,readFile,readdir,copyFile,rm,rename,writeFile} from 'node:fs/promises';
import {execFileSync} from 'node:child_process';
import path from 'node:path';
import {desktop,runtimeLock,downloadVerified,sha256} from './runtime-download.mjs';
if(process.platform!=='darwin'||process.arch!=='arm64')throw new Error('请在 Apple 芯片 Mac 上生成分发包。');
const {version}=JSON.parse(await readFile(path.join(desktop,'package.json'),'utf8'));
const release=path.join(desktop,'release');
const name=`XhlCLI-${version}-mac-arm64`;
await mkdir(release,{recursive:true});
const temporary=await mkdtemp(path.join(release,'.distribution-'));
const stage=path.join(temporary,name);
try{
await mkdir(stage,{recursive:true});
const app=path.join(desktop,'release/bundled/mac-arm64/XhlCLI.app');
execFileSync('/usr/bin/codesign',['--verify','--deep','--strict',app],{stdio:'inherit'});
execFileSync('/usr/bin/ditto',['--norsrc','--noextattr',app,path.join(stage,'XhlCLI.app')]);
await copyFile(path.join(desktop,'distribution/快速开始.txt'),path.join(stage,'快速开始.txt'));
const sources=await downloadVerified(runtimeLock.sources);
const thirdParty=path.join(stage,'第三方软件');
await mkdir(thirdParty,{recursive:true});
await copyFile(sources,path.join(thirdParty,path.basename(sources)));
await copyFile(path.join(desktop,'scripts/runtime-lock.json'),path.join(thirdParty,'runtime-lock.json'));
await writeFile(path.join(thirdParty,'说明.txt'),`XhlCLI 内置 Eclipse Temurin ${runtimeLock.version} 的 macOS aarch64 JRE，未修改其功能。\nJava 的许可与第三方声明位于 XhlCLI.app/Contents/Resources/runtime/Contents/Home/legal。\n本目录附带对应版本的官方 OpenJDK 源码归档、官方下载地址与 SHA-256。\nElectron 的许可与第三方声明位于 XhlCLI.app/Contents/Resources/LICENSE.electron.txt 和 LICENSES.chromium.html。\n无需打开本目录即可使用应用。\n`);
const zip=path.join(release,name+'.zip');
const pending=path.join(temporary,name+'.zip');
execFileSync('/usr/bin/ditto',['-c','-k','--keepParent','--norsrc','--noextattr',stage,pending],{stdio:'inherit'});
await rename(pending,zip);
await writeFile(zip+'.sha256',`${await sha256(zip)}  ${path.basename(zip)}\n`);
// 新包成功后仅清理既有分发命名的旧版本，保留其他文件。
for(const entry of await readdir(release)){
  if(/^XhlCLI-[\d.]+-mac-arm64\.zip(?:\.sha256)?$/.test(entry)&&entry!==name+'.zip'&&entry!==name+'.zip.sha256'){
    await rm(path.join(release,entry),{force:true});
  }
}
console.log('可分发压缩包：'+zip);
}finally{await rm(temporary,{recursive:true,force:true});}
