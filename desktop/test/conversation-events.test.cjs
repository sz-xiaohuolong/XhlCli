const {test}=require('node:test');
const assert=require('node:assert/strict');
const event=(type,data={})=>({turnId:'turn-1',event:type,data});
test('迟到的审批和计划事件只追加历史，不能复活已经结束的轮次',async()=>{
  const {applyTurnEvent}=await import('../src/conversation-events.mjs');
  for(const status of ['completed','canceled','failed','interrupted','limit_reached']){
    const turn={id:'turn-1',status,answer:'保留最终结果',events:[]};
    for(const type of ['approval.request','approval.resolved','plan.ready','plan.resolved']){
      const next=applyTurnEvent(turn,event(type,{id:'review-1',action:'execute'}));
      assert.equal(next.status,status);assert.equal(next.answer,turn.answer);
      assert.deepEqual(next.events,[{type,data:{id:'review-1',action:'execute'}}]);
      assert.equal(turn.events.length,0);
    }
  }
});
test('停止中的轮次不会退回等待审批，最终完成事件仍能结束它',async()=>{
  const {applyTurnEvent}=await import('../src/conversation-events.mjs');
  const turn={id:'turn-1',status:'canceling',events:[]};
  assert.equal(applyTurnEvent(turn,event('approval.resolved')).status,'canceling');
  assert.equal(applyTurnEvent(turn,event('plan.ready')).status,'canceling');
  const finished={...turn,status:'canceled',answer:'已停止'};
  assert.equal(applyTurnEvent(turn,event('turn.finished',finished)),finished);
});
test('活动轮次正常响应计划与审批事件，其他轮次保持原样',async()=>{
  const {applyTurnEvent}=await import('../src/conversation-events.mjs');
  const turn={id:'turn-1',status:'running',events:[]};
  assert.equal(applyTurnEvent(turn,event('plan.ready')).status,'plan_review');
  assert.equal(applyTurnEvent({...turn,status:'plan_review'},event('plan.resolved',{action:'execute'})).status,'running');
  assert.equal(applyTurnEvent({...turn,status:'plan_review'},event('plan.resolved',{action:'cancel'})).status,'canceling');
  assert.equal(applyTurnEvent(turn,event('approval.request')).status,'approval');
  assert.equal(applyTurnEvent({...turn,status:'approval'},event('approval.resolved')).status,'running');
  assert.equal(applyTurnEvent(turn,{...event('plan.ready'),turnId:'another'}),turn);
});
