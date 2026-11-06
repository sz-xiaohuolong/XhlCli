export function applyTurnEvent(turn,event){
  if(turn.id!==event.turnId)return turn;
  if(event.event==='turn.finished')return event.data;
  // 终态与正在停止的状态不能被迟到事件回退；事件正文仍保留供历史查看。
  const canTransition=['running','approval','plan_review'].includes(turn.status);
  const status=!canTransition?turn.status:event.event==='plan.ready'?'plan_review':event.event==='plan.resolved'?(event.data.action==='cancel'?'canceling':'running'):event.event==='approval.request'?'approval':event.event==='approval.resolved'?'running':turn.status;
  return {...turn,status,events:[...(turn.events||[]),{type:event.event,data:event.data}]};
}
