import {mkdir,readFile,rename,rm} from 'node:fs/promises';
import {createReadStream} from 'node:fs';
import {createHash} from 'node:crypto';
import {execFileSync} from 'node:child_process';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
export const desktop=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
export const runtimeLock=JSON.parse(await readFile(path.join(desktop,'scripts/runtime-lock.json'),'utf8'));
export async function sha256(file){
  const hash=createHash('sha256');
  for await(const chunk of createReadStream(file))hash.update(chunk);
  return hash.digest('hex');
}
export async function downloadVerified(asset){
  const cache=path.join(desktop,'resources/downloads');
  await mkdir(cache,{recursive:true});
  const file=path.join(cache,path.basename(new URL(asset.url).pathname));
  try{if(await sha256(file)===asset.sha256)return file;}catch(error){if(error.code!=='ENOENT')throw error;}
  const partial=file+'.partial';
  console.log('下载官方运行环境文件：'+path.basename(file));
  try{
    execFileSync('/usr/bin/curl',['--fail','--location','--retry','3','--connect-timeout','30','--max-time','600','--output',partial,asset.url],{stdio:'inherit'});
    if(await sha256(partial)!==asset.sha256)throw new Error('下载文件校验失败：'+path.basename(file));
    await rename(partial,file);
  }finally{await rm(partial,{force:true});}
  return file;
}
