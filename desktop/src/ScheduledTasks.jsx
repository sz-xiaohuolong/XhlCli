import React,{useEffect,useState} from 'react';
import {Clock3,Plus,Search,ChevronDown,ChevronRight,Play,Pause,Square,Trash2,Pencil,ArrowLeft,LoaderCircle,AlertCircle,FolderPlus,CalendarClock,RefreshCw} from 'lucide-react';
import Markdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import './schedules.css';

const request=(method,params={})=>window.xhl.request(method,params);
const active=run=>['enqueued','running','cancel_requested','waiting_for_approval'].includes(run?.status);
const labels={enqueued:'等待执行',running:'正在执行',cancel_requested:'正在停止',completed:'已完成',failed:'执行失败',canceled:'已取消'};
const stamp=(value,zone)=>value?new Intl.DateTimeFormat('zh-CN',{timeZone:zone,dateStyle:'medium',timeStyle:'short'}).format(new Date(value)):'—';
const localZone=()=>Intl.DateTimeFormat().resolvedOptions().timeZone||'UTC';
const defaultForm=projects=>({title:'',prompt:'',projectId:projects[0]?.id||'',model:'',time:'08:00',zone:localZone()});

export default function ScheduledTasks({projects,sidebar,onProjectsChanged,onSettings}) {
  const [items,setItems]=useState([]),[selected,setSelected]=useState(null),[runs,setRuns]=useState([]);
  const [loading,setLoading]=useState(true),[runsLoading,setRunsLoading]=useState(false),[error,setError]=useState(''),[busy,setBusy]=useState(false);
  const [query,setQuery]=useState(''),[searching,setSearching]=useState(false),[showPaused,setShowPaused]=useState(true);
  const [editing,setEditing]=useState(false),[form,setForm]=useState(()=>defaultForm(projects)),[models,setModels]=useState([]),[modelsLoading,setModelsLoading]=useState(false);
  const [confirmDelete,setConfirmDelete]=useState(false),[notice,setNotice]=useState('');
  const current=items.find(item=>item.id===selected);
  const load=async()=>{const state=await request('schedules.list');setItems(state.schedules);if(state.error)setError(state.error);return state.schedules;};
  useEffect(()=>{
    let valid=true,inFlight=false;
    const update=async()=>{if(inFlight)return;inFlight=true;try{const state=await request('schedules.list');if(valid){setItems(state.schedules);if(state.error)setError(state.error);}}catch(failure){if(valid)setError(failure.message);}finally{inFlight=false;if(valid)setLoading(false);}};
    update();const timer=setInterval(update,2000);return()=>{valid=false;clearInterval(timer);};
  },[]);
  useEffect(()=>{
    setRuns([]);setConfirmDelete(false);if(!selected)return;
    let valid=true,inFlight=false;setRunsLoading(true);
    const update=async()=>{if(inFlight)return;inFlight=true;try{const next=await request('schedules.runs',{id:selected});if(valid)setRuns(next);}catch(failure){if(valid)setError(failure.message);}finally{inFlight=false;if(valid)setRunsLoading(false);}};
    update();const timer=setInterval(update,2000);return()=>{valid=false;clearInterval(timer);};
  },[selected]);
  useEffect(()=>{
    if(!editing||!form.projectId){setModels([]);return;}
    let valid=true;setModelsLoading(true);setModels([]);
    request('config.get',{projectId:form.projectId}).then(config=>{if(valid){setModels(config.models);setForm(old=>({...old,model:config.models.some(m=>m.id===old.model)?old.model:config.defaultModel}));}}).catch(failure=>{if(valid)setError(failure.message);}).finally(()=>{if(valid)setModelsLoading(false);});
    return()=>{valid=false;};
  },[editing,form.projectId]);
  const act=async(action)=>{if(busy)return;setBusy(true);setError('');setNotice('');try{await action();await load();}catch(failure){setError(failure.message.replace(/^Error invoking remote method '[^']+': Error: /,''));}finally{setBusy(false);}};
  const openNew=()=>{setEditing(true);setForm(defaultForm(projects));setError('');setNotice('');setConfirmDelete(false);};
  const edit=()=>{setForm({...current,projectId:projects.find(p=>p.path===current.workspace)?.id||''});setEditing(true);setError('');};
  const select=id=>{if(busy)return;setSelected(id);setEditing(false);setNotice('');setError('');};
  const save=event=>{event.preventDefault();act(async()=>{const saved=await request('schedules.save',form);setSelected(saved.id);setEditing(false);setNotice('计划已保存，将在下一次计划时间执行。');});};
  const addProject=()=>act(async()=>{const path=await window.xhl.chooseProject();if(!path)return;const project=await request('project.add',{path});await onProjectsChanged();setForm(old=>({...old,projectId:project.id}));});
  const visible=items.filter(item=>(item.title+' '+item.prompt).toLowerCase().includes(query.toLowerCase()));
  const row=item=><button key={item.id} className={'schedule-row'+(selected===item.id&&!editing?' selected':'')} onClick={()=>select(item.id)} title={item.title}><Clock3 size={17}/><span><strong>{item.title}</strong><small>{item.active?'正在执行':`每天 ${item.time}`}</small></span>{item.active&&<span className="activity-dot"/>}</button>;
  const change=key=>event=>setForm(old=>({...old,[key]:event.target.value}));
  const zones=Array.from(new Set([localZone(),'UTC',...(Intl.supportedValuesOf?.('timeZone')||[])]));
  return <>
    {sidebar&&<aside className="sidebar schedule-sidebar" aria-label="定时任务列表"><div className="brand-row"><h1 className="brand">定时任务</h1><button className="icon-button" aria-label="搜索定时任务" onClick={()=>setSearching(!searching)}><Search size={18}/></button></div><button className="new-chat" onClick={openNew} disabled={busy}><Plus size={20}/>新建任务</button>
      {searching&&<div className="search-input"><Search size={16}/><input autoFocus aria-label="搜索定时任务名称" placeholder="搜索任务" value={query} onChange={e=>setQuery(e.target.value)}/></div>}
      <div className="sidebar-scroll">{visible.filter(item=>item.enabled).map(row)}<button className="schedule-group" aria-expanded={showPaused} onClick={()=>setShowPaused(!showPaused)}>{showPaused?<ChevronDown size={15}/>:<ChevronRight size={15}/>}已暂停<span>{items.filter(item=>!item.enabled).length||''}</span></button>{showPaused&&visible.filter(item=>!item.enabled).map(row)}{query&&!visible.length&&<p className="sidebar-hint">没有匹配的任务</p>}</div>
      <p className="schedule-sidebar-note">应用运行时自动执行<br/>退出后暂停调度</p></aside>}
    <main className="main-panel schedule-main">
      {error&&<div className="schedule-error" role="alert"><AlertCircle size={17}/><span>{error}</span><button aria-label="重试加载定时任务" onClick={()=>act(load)}><RefreshCw size={16}/></button></div>}
      {notice&&<p className="schedule-notice" role="status">{notice}</p>}
      {loading?<div className="schedule-empty"><LoaderCircle className="spin" size={30}/><p>正在读取定时任务…</p></div>:editing?<div className="schedule-scroll"><form className="schedule-form" onSubmit={save}>
        <button type="button" className="schedule-back" disabled={busy} onClick={()=>setEditing(false)}><ArrowLeft size={16}/>返回任务</button><h1>{form.id?'编辑任务':'新建任务'}</h1><p className="schedule-description">安排一次，按时为你完成搜索、阅读和整理。</p>
        <label>任务名称<input autoFocus required maxLength={160} placeholder="例如：每日 AI 新闻" value={form.title} onChange={change('title')} disabled={busy}/></label>
        <label>任务内容<textarea required maxLength={20000} rows={5} placeholder="搜索过去 24 小时的 AI 新闻，整理 5 条，附上来源链接与发布时间。" value={form.prompt} onChange={change('prompt')} disabled={busy}/><span className="schedule-help">后台任务可搜索网页和读取文件，不执行写文件或 Shell 命令。</span></label>
        <div className="schedule-fields"><label>项目<select required value={form.projectId} onChange={change('projectId')} disabled={busy}><option value="" disabled>选择项目</option>{projects.map(p=><option key={p.id} value={p.id}>{p.name}</option>)}</select></label><label>模型<select required value={form.model} onChange={change('model')} disabled={busy||modelsLoading}><option value="" disabled>{modelsLoading?'正在读取模型…':'选择已配置的模型'}</option>{models.map(m=><option value={m.id} key={m.id}>{m.modelName}</option>)}</select></label></div>
        {!projects.length&&<button type="button" className="schedule-link" onClick={addProject}><FolderPlus size={16}/>添加项目</button>}{form.projectId&&!modelsLoading&&!models.length&&<p className="schedule-help">此项目尚未配置模型。<button type="button" className="schedule-link" onClick={onSettings}>前往模型设置</button></p>}
        <div className="schedule-fields"><label>每天执行时间<input type="time" required value={form.time} onChange={change('time')} disabled={busy}/></label><label>时区<input list="schedule-zones" required value={form.zone} onChange={change('zone')} disabled={busy}/><datalist id="schedule-zones">{zones.map(z=><option key={z} value={z}/>)}</datalist></label></div>
        <p className="schedule-policy"><Clock3 size={17}/><span>电脑需开机且应用运行。休眠或退出错过的计划会跳过，恢复后等待下一次计划时间。</span></p>
        <div className="schedule-form-actions"><button type="button" className="schedule-secondary" onClick={()=>setEditing(false)} disabled={busy}>取消</button><button type="submit" className="primary" disabled={busy||modelsLoading||!form.projectId||!form.model}>{busy?<LoaderCircle size={16} className="spin"/>:<CalendarClock size={17}/>}保存任务</button></div>
      </form></div>:current?<div className="schedule-scroll"><article className="schedule-detail">
        <header className="schedule-detail-header"><div><h1>{current.title}</h1><p>{current.enabled?`每天 ${current.time}`:'已暂停'} · {current.zone}</p></div><button className="schedule-secondary" disabled={busy} onClick={openNew}><Plus size={16}/>新建</button></header>
        <div className="schedule-actions"><button className="primary" disabled={busy||current.active} onClick={()=>act(async()=>{await request('schedules.run',{id:current.id});setRuns(await request('schedules.runs',{id:current.id}));setNotice('已提交本次执行，原有日程保持不变。');})}><Play size={15}/>立即运行</button><button className="schedule-secondary" disabled={busy} onClick={()=>act(async()=>{await request('schedules.toggle',{id:current.id,enabled:!current.enabled});setNotice(current.enabled?'已暂停后续计划；正在执行的任务会继续。':'已恢复，将从下一次计划时间开始。');})}>{current.enabled?<Pause size={15}/>:<Play size={15}/>} {current.enabled?'暂停':'恢复'}</button><button className="schedule-secondary" disabled={busy||current.active} onClick={edit}><Pencil size={15}/>编辑</button><button className="schedule-delete" disabled={busy||current.active} onClick={()=>setConfirmDelete(!confirmDelete)}><Trash2 size={16}/>删除</button></div>
        {confirmDelete&&<div className="schedule-delete-confirm" role="alert"><span>删除此计划及执行记录？</span><button onClick={()=>act(async()=>{await request('schedules.delete',{id:current.id});setSelected(null);setConfirmDelete(false);})} disabled={busy}>确认删除</button><button onClick={()=>setConfirmDelete(false)} disabled={busy}>保留</button></div>}
        <dl className="schedule-meta"><div><dt>下次执行</dt><dd>{current.enabled?stamp(current.nextRun,current.zone):'已暂停'}</dd></div><div><dt>项目</dt><dd title={current.workspace}>{projects.find(p=>p.path===current.workspace)?.name||current.workspace}</dd></div><div><dt>模型</dt><dd>{current.model}</dd></div></dl><section className="schedule-prompt"><h2>任务内容</h2><p>{current.prompt}</p></section>
        <section className="schedule-history"><h2>执行记录<span>最近 100 次</span></h2>{runsLoading?<p className="schedule-help">正在读取执行记录…</p>:!runs.length?<p className="schedule-no-runs">还没有执行记录。可等待计划时间，或点击“立即运行”。</p>:runs.map((run,index)=><details className="schedule-run" key={run.taskId} open={index===0}><summary><span className={'schedule-status '+run.status}>{labels[run.status]||run.status}</span><span>{stamp(run.scheduledAt,current.zone)}</span><small>{run.manual?'手动运行':'定时运行'}</small><ChevronDown size={15}/></summary><div className="schedule-run-body">{active(run)&&<div className="schedule-run-progress"><span><LoaderCircle size={15} className="spin"/>{labels[run.status]}</span><button className="schedule-secondary" disabled={busy||run.status==='cancel_requested'} onClick={()=>act(async()=>{await request('schedules.cancel',{id:current.id,taskId:run.taskId});setRuns(await request('schedules.runs',{id:current.id}));})}><Square size={13}/>停止执行</button></div>}{run.error&&<p className="schedule-run-error">{run.error}</p>}{run.result?<div className="markdown"><Markdown remarkPlugins={[remarkGfm]} components={{a:({href,children})=><a href={href} onClick={event=>{event.preventDefault();window.xhl.openExternal(href).catch(failure=>setError(failure.message));}}>{children}</a>}}>{run.result}</Markdown></div>:<p className="schedule-help">{active(run)?'结果生成后会显示在这里。':'本次没有产生文本结果。'}</p>}</div></details>)}</section>
      </article></div>:<div className="schedule-empty"><Clock3 size={64} strokeWidth={1.1}/><h1>安排任务</h1><p>让 XhlCLI 按时处理持续性的任务，<br/>为你搜集资讯、阅读资料和整理结果。</p><button className="primary schedule-empty-create" onClick={openNew}><Plus size={18}/>新建任务</button><small>应用运行时执行 · 后台只读</small>{!sidebar&&items.length>0&&<div className="schedule-compact-list">{items.map(row)}</div>}</div>}
    </main>
  </>;
}
