const {test}=require('node:test');
const assert=require('node:assert/strict');
const path=require('node:path');
const Module=require('node:module');
const React=require('react');
const {renderToStaticMarkup}=require('react-dom/server');
const {buildSync}=require('esbuild');

// 使用真实组件和 Markdown 解析器，只替换尚未点击的桌面桥接。
global.window={xhl:{}};
const filename=path.resolve(__dirname,'../src/turn-view.jsx');
const {outputFiles}=buildSync({entryPoints:[filename],bundle:true,write:false,platform:'node',format:'cjs',external:['react','react-dom','lucide-react'],loader:{'.css':'empty'}});
const loaded=new Module(filename,module);loaded.filename=filename;loaded.paths=Module._nodeModulePaths(path.dirname(filename));loaded._compile(outputFiles[0].text,filename);
const {Turn,RichText,timeline}=loaded.exports;
const render=turn=>renderToStaticMarkup(React.createElement(Turn,{turn,session:{},onPlan:async()=>{},onApproval:()=>{},notify:()=>{}}));
const plan={id:'plan-1',goal:'撰写 RAG 文档',summary:'先整理范围，再撰写正文。',tasks:[{id:'task_1',description:'列出 **技术范围**\n\n- 检索\n- 生成',type:'ANALYSIS',dependencies:[]},{id:'task_2',description:'确认后创建 `rag.md`',type:'FILE_WRITE',dependencies:['task_1']}]};
const turn={id:'turn-1',input:'/plan 撰写 RAG 文档',model:'deepseek:test',status:'plan_review',startedAt:'2026-11-06T12:00:00Z',events:[{type:'plan.ready',data:plan}]};

test('等待计划确认时展示可读步骤与独立操作，不显示工具审批按钮',()=>{
  const html=render(turn);
  assert.match(html,/等待你确认计划/);assert.match(html,/先整理范围，再撰写正文/);
  assert.match(html,/<strong>技术范围<\/strong>/);assert.match(html,/<li>检索<\/li>/);
  assert.match(html,/同意并执行/);assert.match(html,/取消计划/);assert.match(html,/更新计划/);
  assert.doesNotMatch(html,/允许本次/);
});
test('重启中断后保留计划，但不能点击旧计划执行',()=>{
  const html=render({...turn,status:'interrupted'});
  assert.match(html,/确认后创建/);assert.match(html,/计划确认已失效/);
  assert.doesNotMatch(html,/<button[^>]*>同意并执行/);
});
test('更新计划只允许最新未处理版本执行，历史版本保留',()=>{
  const html=render({...turn,events:[...turn.events,{type:'plan.resolved',data:{id:'plan-1',action:'supplement'}},{type:'plan.ready',data:{...plan,id:'plan-2',summary:'只写入一页简介'}}]});
  assert.match(html,/已提交修改意见/);assert.match(html,/只写入一页简介/);
  assert.equal((html.match(/>同意并执行</g)||[]).length,1);
});
test('已有中间文本和工具记录时，最终回答仍显示且保留 Markdown 换行',()=>{
  const answer='# 结果\n\n- 已完成\n- 已核对';
  const events=[{type:'TextDelta',data:{text:'先检查文件。'}},{type:'tool.call',data:{id:'call-1',name:'read_file'}},{type:'tool.result',data:{callId:'call-1',summary:'1 | # 原始工具输出'}}];
  const items=timeline({events,answer,status:'completed'});
  assert.equal(items.at(-1).text,answer);assert.equal(items.filter(x=>x.type==='text').length,2);
  assert.equal(items.find(x=>x.type==='tool').result.summary,'1 | # 原始工具输出');
});
test('最终流式回答完整或部分到达时，完成态使用最终正文且不重复',()=>{
  const answer='# 结果\n\n正文';
  for(const text of [answer,'# 结果\n\n正']){
    const items=timeline({events:[{type:'TextDelta',data:{text}}],answer,status:'completed'});
    assert.equal(items.length,1);assert.equal(items[0].text,answer);
  }
});
test('Markdown 正文正确渲染标题、列表、引用、代码、表格，普通行号不被改写',()=>{
  const text='# RAG\n\n一段 **重点**。\n\n## 步骤\n\n1. 检索\n2. 生成\n\n> 保留引用\n\n```js\nconst value = 1;\n```\n\n| 列一 | 列二 |\n| --- | --- |\n| 检索 | 生成 |\n\n1 | 普通文本';
  const html=renderToStaticMarkup(React.createElement(RichText,{text}));
  for(const pattern of [/<h1>RAG<\/h1>/,/<h2>步骤<\/h2>/,/<ol>/,/<blockquote>/,/<pre><code class="language-js">/,/<table>/,/<strong>重点<\/strong>/,/1 \| 普通文本/])assert.match(html,pattern);
});
