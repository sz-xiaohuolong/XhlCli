const {test}=require('node:test');
const assert=require('node:assert/strict');
const catalog=[
  {command:'/help',requiresModel:false},
  {command:'/plan',requiresModel:true},
  {command:'/mcp',requiresModel:false},
  {command:'/mcp list',requiresModel:false},
  {command:'/mcp status',requiresModel:false},
  {command:'/skill',requiresModel:false},
  {command:'/skill run',requiresModel:true},
  {command:'/example',requiresModel:true}
];
test('斜杠筛选区分主命令、子命令与任务参数',async()=>{
  const {commandSuggestions}=await import('../src/commands.mjs');
  assert.deepEqual(commandSuggestions('/',catalog).map(x=>x.command),['/help','/plan','/mcp','/skill','/example']);
  assert.deepEqual(commandSuggestions('/M',catalog).map(x=>x.command),['/mcp']);
  assert.deepEqual(commandSuggestions('/mcp ',catalog).map(x=>x.command),['/mcp list','/mcp status']);
  assert.deepEqual(commandSuggestions('/mcp st',catalog).map(x=>x.command),['/mcp status']);
  assert.deepEqual(commandSuggestions('/plan 修改模块',catalog),[]);
  assert.deepEqual(commandSuggestions('请查看 /plan',catalog),[]);
  assert.deepEqual(commandSuggestions('/未知',catalog),[]);
});
test('本地指令无需模型，真正执行任务时才检查模型',async()=>{
  const {needsModel}=await import('../src/commands.mjs');
  assert.equal(needsModel('/help',catalog),false);
  assert.equal(needsModel('/mcp status example',catalog),false);
  assert.equal(needsModel('/plan',catalog),false);
  assert.equal(needsModel('/plan 调查模块',catalog),true);
  assert.equal(needsModel('/skill run example 调查模块',catalog),true);
  assert.equal(needsModel('/example\n调查模块',catalog),true);
  assert.equal(needsModel('/unknown',catalog),false);
  assert.equal(needsModel('普通消息',catalog),true);
});
