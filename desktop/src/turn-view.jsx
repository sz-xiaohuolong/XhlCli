import React,{useRef,useState} from 'react';
import Markdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import './plan-review.css';
import {ShieldCheck,FileText,LoaderCircle,Check,AlertCircle,Terminal,ChevronRight,Files,Copy} from 'lucide-react';
const api=window.xhl;
const running=turn=>['running','approval','plan_review','canceling'].includes(turn?.status);
const statusLabel={running:'正在处理',approval:'等待你的确认',plan_review:'等待你确认计划',canceling:'正在停止',completed:'已完成',canceled:'已停止',failed:'执行失败',interrupted:'已中断',limit_reached:'达到执行限制'};
function IconButton({icon:Icon,label,...props}){return <button className="icon-button" title={label} aria-label={label} {...props}><Icon size={19} strokeWidth={1.65}/></button>;}
export function RichText({text}){return <Markdown remarkPlugins={[remarkGfm]} components={{a:({href,children})=><a href={href} onClick={event=>{event.preventDefault();if(href)api.openExternal(href).catch(()=>{});}}>{children}</a>,img:()=> <span className="muted">[外部图片未加载]</span>}}>{text}</Markdown>;}
export function timeline(turn){
  const items=[];
  for(const event of turn.events||[]){
    if(event.type==='TextDelta'){
      if(items.at(-1)?.type==='text')items.at(-1).text+=event.data.text;else items.push({type:'text',text:event.data.text});
    }
    if(event.type==='plan.ready')items.push({type:'plan',plan:event.data});
    if(event.type==='tool.call')items.push({type:'tool',call:event.data});
    if(event.type==='tool.result'){const item=items.findLast(item=>item.type==='tool'&&item.call.id===event.data.callId);if(item)item.result=event.data;}
  }
  if(turn.answer){
    const last=items.at(-1);
    // 完成态正文是权威结果；工具前的中间说明继续留在原位置。
    if(last?.type==='text'&&(last.text===turn.answer||turn.answer.startsWith(last.text)))last.text=turn.answer;
    else if(last?.type!=='text'||last.text!==turn.answer)items.push({type:'text',text:turn.answer});
  }
  return items;
}
export function Turn({turn,session,onChanges,onApproval,onPlan,notify}){
  const resolved=new Set((turn.events||[]).filter(event=>event.type==='approval.resolved').map(event=>event.data.id));
  const approval=(turn.events||[]).findLast(event=>event.type==='approval.request'&&!resolved.has(event.data.id));
  const planResolutions=new Map((turn.events||[]).filter(event=>event.type==='plan.resolved').map(event=>[event.data.id,event.data.action]));
  const pendingPlan=(turn.events||[]).findLast(event=>event.type==='plan.ready'&&event.data.id&&!planResolutions.has(event.data.id));
  return <article className="turn">
    <div className="user-message">{turn.input}{turn.attachments?.length>0&&<div className="attachment-list">{turn.attachments.map((file,index)=><span key={index}><FileText size={14}/>{file.path.split('/').pop()}</span>)}</div>}</div>
    <div className="turn-state">{running(turn)?<LoaderCircle className="spin" size={15}/>:<span className={'state-dot '+turn.status}/>}<span>{statusLabel[turn.status]||turn.status}</span><span className="turn-model">{turn.model?.split(':').slice(1).join(':')}</span></div>
    <div className="assistant-content">
      {timeline(turn).map((item,index)=>item.type==='text'?<div className="markdown" key={index}><RichText text={item.text}/></div>:item.type==='plan'?<PlanReview key={item.plan.id||index} plan={item.plan} active={turn.status==='plan_review'&&item.plan.id===pendingPlan?.data.id} resolution={planResolutions.get(item.plan.id)} onRespond={onPlan}/>:<details className="tool-item" key={index}>
        <summary>{item.result?(item.result.status==='SUCCESS'?<Check size={15}/>:<AlertCircle size={15}/>):running(turn)?<LoaderCircle className="spin" size={15}/>:<Terminal size={15}/>}<span>{item.call.name}</span><span className="tool-summary">{item.result?.summary||'执行工具'}</span><ChevronRight size={14}/></summary>
        <div className="tool-detail"><div className="small-label">参数</div><pre>{formatJson(item.call.argumentsJson)}</pre>{item.result&&<><div className="small-label">结果 · {item.result.status}</div><pre>{item.result.summary}{'\n'}{formatJson(item.result.data)}</pre></>}</div>
      </details>)}
      {approval&&approval.data.request.toolName!=='plan.execute'&&running(turn)&&<section className="approval"><div className="approval-heading"><ShieldCheck size={21}/><div><strong>允许这次操作？</strong><p>{approval.data.request.riskDescription}</p></div></div><pre>{approval.data.request.toolName}{'\n'}{formatJson(approval.data.request.arguments)}</pre><div className="approval-actions"><button onClick={()=>onApproval(approval.data.id,false)}>拒绝</button><button className="primary" onClick={()=>onApproval(approval.data.id,true)}>允许本次</button></div></section>}
      {(turn.events||[]).some(event=>event.type==='command.progress')&&<details className="command-progress"><summary>查看指令执行过程</summary><pre>{turn.events.filter(event=>event.type==='command.progress').map(event=>event.data.text).join('\n')}</pre></details>}
      {turn.error&&!['completed','canceled'].includes(turn.status)&&<div className="inline-error"><AlertCircle size={17}/><span>{turn.error}</span></div>}
      {(turn.events||[]).some(event=>event.type==='approval.automatic')&&<p className="notice">帮我审批已自动批准 {(turn.events||[]).filter(event=>event.type==='approval.automatic').length} 次工具操作</p>}
      {(turn.events||[]).filter(event=>event.type==='notice').map((event,index)=><p className="notice" key={index}>{event.data.message}</p>)}
      {turn.before&&turn.after&&turn.changeCount!==0&&<div className="change-card"><div className="change-symbol"><Files size={22}/></div><div><strong>{turn.undone?'本轮改动已撤销':'本轮文件改动'}</strong><p>查看差异并检查工作成果</p></div><button onClick={()=>onChanges(turn)}><span>查看变更</span><ChevronRight size={15}/></button></div>}
      {!running(turn)&&<div className="message-actions"><IconButton icon={Copy} label="复制回答" onClick={()=>{api.copyText(turn.answer||timeline(turn).filter(x=>x.type==='text').map(x=>x.text).join('\n')).then(()=>notify('已复制回答')).catch(()=>notify('复制失败'));}}/><time>{new Date(turn.finishedAt||turn.startedAt).toLocaleTimeString('zh-CN',{hour:'2-digit',minute:'2-digit'})}</time></div>}
    </div>
  </article>;
}
function formatJson(value){if(typeof value!=='string')return JSON.stringify(value,null,2);try{return JSON.stringify(JSON.parse(value),null,2);}catch{return value;}}

export function PlanReview({plan,active,resolution,onRespond}){
  const [feedback,setFeedback]=useState(''),[sending,setSending]=useState(''),[error,setError]=useState('');
  const inFlight=useRef(false);
  const tasks=Array.isArray(plan.tasks)?plan.tasks:[];
  const taskNumber=id=>{const index=tasks.findIndex(task=>task.id===id);return index<0?id:String(index+1);};
  const respond=async action=>{
    if(!active||inFlight.current||(action==='supplement'&&!feedback.trim()))return;
    inFlight.current=true;setSending(action);setError('');
    try{await onRespond(plan.id,action,action==='supplement'?feedback.trim():undefined);}
    catch(failure){setError(failure.message||'提交失败，请重试');inFlight.current=false;setSending('');}
  };
  const body=<>
    <div className="plan-goal markdown"><RichText text={plan.goal||''}/></div>
    {plan.summary&&<div className="plan-summary markdown"><RichText text={plan.summary}/></div>}
    <ol className="plan-steps">{tasks.map(task=><li key={task.id}>
      <div className="markdown"><RichText text={task.description||''}/></div>
      {task.dependencies?.length>0&&<p className="plan-dependency">在步骤 {task.dependencies.map(taskNumber).join('、')} 完成后执行</p>}
    </li>)}</ol>
  </>;
  if(!active)return <details className="plan-review plan-history"><summary><FileText size={17}/><span>执行计划</span><small>{({execute:'你已同意执行',supplement:'已提交修改意见',cancel:'已取消计划'})[resolution]||'计划确认已失效'}</small><ChevronRight size={15}/></summary><div className="plan-history-body">{body}</div></details>;
  return <section className="plan-review" aria-label="确认执行计划" aria-busy={!!sending}>
    <div className="plan-heading"><FileText size={20}/><h2>执行计划</h2></div>
    {body}
    <p className="plan-boundary">确认前不会执行计划中的文件修改或命令。“帮我审批”不会代替这次确认。</p>
    <label className="plan-feedback">修改意见（可选）<textarea aria-label="计划修改意见" placeholder="例如：先给出文档大纲，暂不创建文件" rows={2} maxLength={8000} value={feedback} disabled={!!sending} onChange={event=>setFeedback(event.target.value)}/></label>
    {error&&<p className="plan-error" role="alert">{error}</p>}
    <div className="plan-actions"><button disabled={!!sending} onClick={()=>respond('cancel')}>取消计划</button><div><button disabled={!!sending||!feedback.trim()} onClick={()=>respond('supplement')}>{sending==='supplement'?<LoaderCircle size={14} className="spin"/>:null}更新计划</button><button className="primary" disabled={!!sending} onClick={()=>respond('execute')}>{sending==='execute'?<LoaderCircle size={14} className="spin"/>:null}同意并执行</button></div></div>
  </section>;
}
