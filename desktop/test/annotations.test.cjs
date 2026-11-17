const {test}=require('node:test');
const assert=require('node:assert/strict');

function createMemoryStorage({quotaLimit=Infinity,failGet=false,failSet=false}={}){
  const map=new Map();
  return {
    getItem(k){if(failGet)throw new Error('storage read error');return map.has(k)?map.get(k):null;},
    setItem(k,v){if(failSet||map.size>=quotaLimit&&!map.has(k))throw new Error('QuotaExceededError');map.set(k,String(v));},
    removeItem(k){map.delete(k);},
    _map:map
  };
}

test('parseUnifiedDiff 准确计算多 hunk、增删改、新删文件与特殊元信息行号',async()=>{
  const {parseUnifiedDiff}=await import('../src/annotations.mjs');
  const diff=[
    'diff --git a/src/App.java b/src/App.java',
    'index 111..222 100644',
    '--- a/src/App.java',
    '+++ b/src/App.java',
    '@@ -10,4 +10,5 @@ class App {',
    ' line10',
    '-old11',
    '+new11',
    '+new12',
    ' line12',
    '@@ -30 +31,2 @@',
    ' line30',
    '+added32',
    '\\ No newline at end of file',
    '差异过长，已截断显示。',
    ''
  ].join('\n');

  const lines=parseUnifiedDiff(diff);
  assert.equal(lines.length,15);
  assert.deepEqual(lines.slice(0,4).map(l=>[l.type,l.commentable]),[
    ['header',false],['header',false],['header',false],['header',false]
  ]);
  assert.deepEqual([lines[4].type,lines[4].oldLine,lines[4].newLine,lines[4].commentable],['hunk',null,null,false]);
  assert.deepEqual([lines[5].type,lines[5].oldLine,lines[5].newLine,lines[5].content],['context',10,10,'line10']);
  assert.deepEqual([lines[6].type,lines[6].oldLine,lines[6].newLine,lines[6].content],['del',11,null,'old11']);
  assert.deepEqual([lines[7].type,lines[7].oldLine,lines[7].newLine,lines[7].content],['add',null,11,'new11']);
  assert.deepEqual([lines[8].type,lines[8].oldLine,lines[8].newLine,lines[8].content],['add',null,12,'new12']);
  assert.deepEqual([lines[9].type,lines[9].oldLine,lines[9].newLine,lines[9].content],['context',12,13,'line12']);
  assert.deepEqual([lines[10].type,lines[10].oldLine,lines[10].newLine],['hunk',null,null]);
  assert.deepEqual([lines[11].type,lines[11].oldLine,lines[11].newLine],['context',30,31]);
  assert.deepEqual([lines[12].type,lines[12].oldLine,lines[12].newLine],['add',null,32]);
  assert.deepEqual([lines[13].type,lines[13].oldLine,lines[13].newLine,lines[13].commentable],['meta',null,null,false]);
  assert.deepEqual([lines[14].type,lines[14].oldLine,lines[14].newLine,lines[14].commentable],['meta',null,null,false]);
});

test('parseUnifiedDiff 安全处理二进制与空 diff 而不抛出异常',async()=>{
  const {parseUnifiedDiff,buildDiffSelectionAnchor}=await import('../src/annotations.mjs');
  const bin=parseUnifiedDiff('diff --git a/logo.png b/logo.png\nBinary files a/logo.png and b/logo.png differ\n');
  assert.equal(bin.length,2);
  assert.ok(bin.every(l=>l.type==='header'&&!l.commentable));
  assert.equal(buildDiffSelectionAnchor('logo.png',bin,0,1),null);
  assert.deepEqual(parseUnifiedDiff(''),[]);
  assert.deepEqual(parseUnifiedDiff(null),[]);
});

test('buildDiffSelectionAnchor 与 formatDiffFeedback 正确标注多行新行、纯删行并防止反引号冲突',async()=>{
  const {parseUnifiedDiff,buildDiffSelectionAnchor,formatDiffFeedback,appendDiffFeedbackToDraft}=await import('../src/annotations.mjs');
  const diffA=[
    'diff --git a/a.java b/a.java',
    '--- a/a.java',
    '+++ b/a.java',
    '@@ -10,5 +10,7 @@',
    ' c10',
    ' c11',
    '+add12',
    '+add13',
    '+add14',
    '+add15',
    ' c16'
  ].join('\n');
  const linesA=parseUnifiedDiff(diffA);
  const anchorA=buildDiffSelectionAnchor('a.java',linesA,6,9);
  assert.equal(anchorA.side,'new');
  assert.equal(anchorA.startLine,12);
  assert.equal(anchorA.endLine,15);
  assert.match(anchorA.snippet,/\+add12[\s\S]*\+add15/);

  const diffB=[
    'diff --git a/b.jsx b/b.jsx',
    '--- a/b.jsx',
    '+++ b/b.jsx',
    '@@ -17,3 +17,1 @@',
    ' keep17',
    '-del18',
    '-```backticks```'
  ].join('\n');
  const linesB=parseUnifiedDiff(diffB);
  const anchorB=buildDiffSelectionAnchor('b.jsx',linesB,5,6);
  assert.equal(anchorB.side,'old');
  assert.equal(anchorB.startLine,18);
  assert.equal(anchorB.endLine,19);

  const md=formatDiffFeedback({
    comments:[
      {...anchorA,id:'c1',text:'请补充边界检查'},
      {...anchorB,id:'c2',text:'这行误删了'}
    ],
    generalNote:'顺便跑一下构建'
  });
  assert.match(md,/总体说明\n顺便跑一下构建/);
  assert.match(md,/文件：`a\.java`/);
  assert.match(md,/行号 `L12-L15`/);
  assert.match(md,/文件：`b\.jsx`/);
  assert.match(md,/行号 `old L18-L19 \(已删除\)`/);
  assert.match(md,/````/);

  assert.equal(appendDiffFeedbackToDraft('',md),md);
  assert.equal(appendDiffFeedbackToDraft('已有说明',md),'已有说明\n\n'+md);
  assert.equal(appendDiffFeedbackToDraft('已有说明\n\n'+md,md),'已有说明\n\n'+md);
});

test('mixed 选区以删除行结尾时 resolveDiffCommentRowIndex 保持锚定在末行且不向上漂移',async()=>{
  const {parseUnifiedDiff,buildDiffSelectionAnchor,resolveDiffCommentRowIndex}=await import('../src/annotations.mjs');
  const diff=[
    'diff --git a/c.js b/c.js',
    '--- a/c.js',
    '+++ b/c.js',
    '@@ -10,3 +10,2 @@',
    ' keep10',
    '-del11',
    ' keep12'
  ].join('\n');
  const lines=parseUnifiedDiff(diff);
  const anchor=buildDiffSelectionAnchor('c.js',lines,4,5);
  assert.equal(anchor.side,'mixed');
  assert.equal(anchor.startLine,10);
  assert.equal(anchor.endLine,10);
  assert.equal(anchor.anchorRowIndex,5);
  assert.equal(anchor.anchorLineType,'del');
  assert.equal(resolveDiffCommentRowIndex({...anchor,id:'m1',text:'保留删除行提示'},lines),5);
});

test('formatPlanFeedback 按规范顺序拼装全局意见、行内批注与红线删除',async()=>{
  const {formatPlanFeedback}=await import('../src/annotations.mjs');
  assert.equal(formatPlanFeedback({annotations:[],globalFeedback:'   '}),'');
  const out=formatPlanFeedback({
    globalFeedback:'优先保证向后兼容',
    annotations:[
      {id:'1',type:'COMMENT',targetKind:'step',stepIndex:2,taskId:'task_2',originalText:'创建新配置',text:'复用现有 config.json'},
      {id:'2',type:'DELETION',targetKind:'summary',originalText:'顺便重写旧模块',text:''},
      {id:'3',type:'DELETION',targetKind:'goal',originalText:'删除历史日志',text:'需保留审计日志'}
    ]
  });
  const gIdx=out.indexOf('### 全局意见');
  const cIdx=out.indexOf('### 行内批注');
  const dIdx=out.indexOf('### 建议删除内容');
  assert.ok(gIdx>=0&&cIdx>gIdx&&dIdx>cIdx);
  assert.match(out,/步骤 2 \(task_2\)/);
  assert.match(out,/复用现有 config\.json/);
  assert.match(out,/建议删除此片段/);
  assert.match(out,/需保留审计日志/);
});

test('草稿持久化支持恢复、清除、LRU 100 上限及损坏/超限静默降级',async()=>{
  const {planDraftKey,diffDraftKey,loadDraft,saveDraft,clearDraft,loadDiffWidth,saveDiffWidth}=await import('../src/annotations.mjs');
  const storage=createMemoryStorage();
  const pKey=planDraftKey('s1','p1');
  const dKey=diffDraftKey('s1','t1');
  assert.equal(saveDraft(pKey,{annotations:[{id:'a1'}],globalFeedback:'备注'},storage),true);
  assert.equal(saveDraft(dKey,{comments:[{id:'c1'}],generalNote:''},storage),true);
  assert.deepEqual(loadDraft(pKey,storage),{annotations:[{id:'a1'}],globalFeedback:'备注'});
  assert.deepEqual(loadDraft(dKey,storage),{comments:[{id:'c1'}],generalNote:''});

  clearDraft(pKey,storage);
  assert.equal(loadDraft(pKey,storage),null);

  // 空草稿自动清理不占位
  saveDraft(dKey,{comments:[],generalNote:'  '},storage);
  assert.equal(loadDraft(dKey,storage),null);

  // 超过 100 条草稿自动淘汰最旧条
  for(let i=0;i<105;i++){
    saveDraft(diffDraftKey('s1','t_'+i),{comments:[{id:'c'+i}],generalNote:''},storage);
  }
  assert.equal(loadDraft(diffDraftKey('s1','t_0'),storage),null);
  assert.ok(loadDraft(diffDraftKey('s1','t_104'),storage));

  // JSON 损坏与读写异常静默降级
  storage.setItem('xhl:draft:'+pKey,'{broken-json');
  assert.equal(loadDraft(pKey,storage),null);
  const broken=createMemoryStorage({failGet:true,failSet:true});
  assert.equal(loadDraft(pKey,broken),null);
  assert.equal(saveDraft(pKey,{annotations:[{id:'x'}],globalFeedback:''},broken),false);

  // 宽度偏好合法值校验
  assert.equal(loadDiffWidth(storage),'standard');
  saveDiffWidth('wide',storage);
  assert.equal(loadDiffWidth(storage),'wide');
  saveDiffWidth('invalid-width',storage);
  assert.equal(loadDiffWidth(storage),'standard');
});
