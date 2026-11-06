const {test}=require('node:test');
const assert=require('node:assert/strict');
const {EventEmitter}=require('node:events');
const {PassThrough}=require('node:stream');
test('桥接按请求 ID 配对响应，并转发实时事件',async()=>{
  const {Backend}=require('../electron/backend.cjs');
  const proc=new EventEmitter();proc.stdout=new PassThrough();proc.stderr=new PassThrough();proc.stdin=new PassThrough();
  const events=[];const backend=new Backend(proc,event=>events.push(event));
  proc.stdout.write('{"event":"ready"}\n');
  const promise=backend.call('state',{});
  await new Promise(resolve=>setImmediate(resolve));
  const request=JSON.parse(proc.stdin.read().toString());
  proc.stdout.write('{"event":"TextDelta","data":{"text":"你好"}}\n');
  proc.stdout.write(JSON.stringify({id:request.id,result:{sessions:[]}})+'\n');
  assert.deepEqual(await promise,{sessions:[]});assert.equal(events.at(-1).data.text,'你好');
});
test('进程退出拒绝等待请求，避免界面无限加载',async()=>{
  const {Backend}=require('../electron/backend.cjs');
  const proc=new EventEmitter();proc.stdout=new PassThrough();proc.stderr=new PassThrough();proc.stdin=new PassThrough();
  const backend=new Backend(proc,()=>{});proc.stdout.write('{"event":"ready"}\n');
  const pending=backend.call('state',{});await new Promise(resolve=>setImmediate(resolve));proc.emit('exit',1);
  await assert.rejects(pending,/后端进程/);
});
