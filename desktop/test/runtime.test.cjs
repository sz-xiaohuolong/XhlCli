const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const os=require('node:os');
const path=require('node:path');
const {resolveJava}=require('../electron/runtime.cjs');
test('分发包使用内置 Java，支持带空格路径且不依赖系统配置',t=>{
  const resources=fs.mkdtempSync(path.join(os.tmpdir(),'xhlcli runtime '));
  t.after(()=>fs.rmSync(resources,{recursive:true,force:true}));
  const java=path.join(resources,'runtime/Contents/Home/bin/java');
  fs.mkdirSync(path.dirname(java),{recursive:true});
  fs.writeFileSync(java,'#!/bin/sh\nexit 0\n',{mode:0o755});
  assert.equal(resolveJava({isPackaged:true,resourcesPath:resources,env:{PATH:'/nonexistent',XHLCLI_JAVA:'/nonexistent/java'}}),java);
});
test('分发包缺少内置 Java 时报告包损坏，不尝试系统 Java',()=>{
  assert.throws(()=>resolveJava({isPackaged:true,resourcesPath:'/nonexistent/xhlcli',env:{XHLCLI_JAVA:'/system/java'}}),/运行环境.*重新解压/);
});
test('开发模式仍允许显式指定 Java',()=>{
  assert.equal(resolveJava({isPackaged:false,env:{XHLCLI_JAVA:'/developer/java'}}),'/developer/java');
});
