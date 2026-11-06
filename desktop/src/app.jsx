import React,{useEffect,useRef,useState} from 'react';
import {createRoot} from 'react-dom/client';
import Markdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import {Blocks,Home,Clock3,Settings,Folder,FolderOpen,SquarePen,Search,PanelLeft,ArrowUp,Plus,X,ChevronRight,ChevronDown,ChevronLeft,ShieldCheck,Terminal,FileText,Image,Check,Square,LoaderCircle,Copy,Archive,Pin,MoreHorizontal,Undo2,Files,ExternalLink,SlidersHorizontal,ArrowLeft,AlertCircle,RefreshCw,MessageSquare} from 'lucide-react';
import './style.css';
import ScheduledTasks from './ScheduledTasks.jsx';
import {SkillsPage} from './skills.jsx';
import {commandSuggestions,needsModel} from './commands.mjs';
import {ComposerControls} from './composer-controls.jsx';

const api=window.xhl;
const running=turn=>['running','approval','canceling'].includes(turn?.status);
const statusLabel={running:'正在处理',approval:'等待你的确认',canceling:'正在停止',completed:'已完成',canceled:'已停止',failed:'执行失败',interrupted:'已中断',limit_reached:'达到执行限制'};
const request=(method,params={})=>api.request(method,params);
function IconButton({icon:Icon,label,active=false,...props}){return <button className={'icon-button'+(active?' active':'')} title={label} aria-label={label} {...props}><Icon size={19} strokeWidth={1.65}/></button>;}
function RichText({text}){return <Markdown remarkPlugins={[remarkGfm]} components={{a:({href,children})=><a href={href} onClick={event=>{event.preventDefault();if(href)api.openExternal(href).catch(()=>{});}}>{children}</a>,img:()=> <span className="muted">[外部图片未加载]</span>}}>{text}</Markdown>;}
function timeline(turn){
  const items=[];
  for(const event of turn.events||[]){
    if(event.type==='TextDelta'){
      if(items.at(-1)?.type==='text')items.at(-1).text+=event.data.text;else items.push({type:'text',text:event.data.text});
    }
    if(event.type==='tool.call')items.push({type:'tool',call:event.data});
    if(event.type==='tool.result'){const item=items.findLast(item=>item.type==='tool'&&item.call.id===event.data.callId);if(item)item.result=event.data;}
  }
  if(!items.some(item=>item.type==='text')&&turn.answer)items.push({type:'text',text:turn.answer});
  return items;
}
function Turn({turn,session,onChanges,onApproval,notify}){
  const resolved=new Set((turn.events||[]).filter(event=>event.type==='approval.resolved').map(event=>event.data.id));
  const approval=(turn.events||[]).findLast(event=>event.type==='approval.request'&&!resolved.has(event.data.id));
  return <article className="turn">
    <div className="user-message">{turn.input}{turn.attachments?.length>0&&<div className="attachment-list">{turn.attachments.map((file,index)=><span key={index}><FileText size={14}/>{file.path.split('/').pop()}</span>)}</div>}</div>
    <div className="turn-state">{running(turn)?<LoaderCircle className="spin" size={15}/>:<span className={'state-dot '+turn.status}/>}<span>{statusLabel[turn.status]||turn.status}</span><span className="turn-model">{turn.model?.split(':').slice(1).join(':')}</span></div>
    <div className="assistant-content">
      {timeline(turn).map((item,index)=>item.type==='text'?<div className="markdown" key={index}><RichText text={item.text}/></div>:<details className="tool-item" key={index}>
        <summary>{item.result?(item.result.status==='SUCCESS'?<Check size={15}/>:<AlertCircle size={15}/>):running(turn)?<LoaderCircle className="spin" size={15}/>:<Terminal size={15}/>}<span>{item.call.name}</span><span className="tool-summary">{item.result?.summary||'执行工具'}</span><ChevronRight size={14}/></summary>
        <div className="tool-detail"><div className="small-label">参数</div><pre>{formatJson(item.call.argumentsJson)}</pre>{item.result&&<><div className="small-label">结果 · {item.result.status}</div><pre>{item.result.summary}{'\n'}{formatJson(item.result.data)}</pre></>}</div>
      </details>)}
      {approval&&running(turn)&&<section className="approval"><div className="approval-heading"><ShieldCheck size={21}/><div><strong>允许这次操作？</strong><p>{approval.data.request.riskDescription}</p></div></div><pre>{approval.data.request.toolName}{'\n'}{formatJson(approval.data.request.arguments)}</pre><div className="approval-actions"><button onClick={()=>onApproval(approval.data.id,false)}>拒绝</button><button className="primary" onClick={()=>onApproval(approval.data.id,true)}>允许本次</button></div></section>}
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

function App(){
  const [state,setState]=useState({projects:[],sessions:[]}),[selected,setSelected]=useState(null),[projectId,setProjectId]=useState(null);
  const [page,setPage]=useState('chat'),[sidebar,setSidebar]=useState(true),[loading,setLoading]=useState(true),[error,setError]=useState(''),[toast,setToast]=useState('');
  const [query,setQuery]=useState(''),[searching,setSearching]=useState(false),[archives,setArchives]=useState(false),[menu,setMenu]=useState(false);
  const [drafts,setDrafts]=useState({}),[attachments,setAttachments]=useState([]),[sending,setSending]=useState(false),[models,setModels]=useState([]),[defaultModel,setDefaultModel]=useState(''),[diff,setDiff]=useState(null),[diffLoading,setDiffLoading]=useState(false);
  const [newModel,setNewModel]=useState(''),[newEffort,setNewEffort]=useState('default'),[modelSaving,setModelSaving]=useState(false);
  const [approvalSaving,setApprovalSaving]=useState(null),[newApprovalMode,setNewApprovalMode]=useState('on-request');
  const [commands,setCommands]=useState([]),[commandIndex,setCommandIndex]=useState(0),[dismissedCommand,setDismissedCommand]=useState(null);
  const [rename,setRename]=useState(null),[name,setName]=useState('');
  const viewport=useRef(null),inputRef=useRef(null),nearBottom=useRef(true);
  const session=state.sessions.find(item=>item.id===selected),project=state.projects.find(item=>item.id===(session?.projectId||projectId))||state.projects[0];
  const draftKey=selected||'new',draft=drafts[draftKey]||'';
  const activeTurn=session?.turns?.findLast(running);
  const directoryBusy=state.sessions.some(item=>item.path===project?.path&&item.turns.some(running));
  const notify=text=>{setToast(text);setTimeout(()=>setToast(''),3500);};
  const refresh=async()=>{const next=await request('state');setState(next);return next;};
  const act=async(action)=>{try{setError('');return await action();}catch(failure){setError(failure.message.replace(/^Error invoking remote method '[^']+': Error: /,''));return null;}};
  useEffect(()=>{refresh().then(next=>{setSelected(next.sessions.filter(s=>!s.archived).at(-1)?.id||null);setProjectId(next.projects[0]?.id||null);}).catch(failure=>setError(failure.message)).finally(()=>setLoading(false));
    return api.subscribe(event=>{
      if(event.event==='ui.settings'){setPage('settings');return;}
      if(event.event==='backend.error'){setError(event.data.message);return;}
      if(!event.sessionId)return;
      if(event.event==='session.model'){setState(current=>({...current,sessions:current.sessions.map(item=>item.id===event.sessionId?{...item,model:event.data.model,reasoningEffort:'default'}:item)}));return;}
      setState(current=>({...current,sessions:current.sessions.map(s=>s.id!==event.sessionId?s:{...s,turns:s.turns.map(turn=>turn.id!==event.turnId?turn:event.event==='turn.finished'?event.data:{...turn,status:event.event==='approval.request'?'approval':event.event==='approval.resolved'?'running':turn.status,events:[...(turn.events||[]),{type:event.event,data:event.data}]})})}));
    });
  },[]);
  useEffect(()=>{act(async()=>{const config=await request('config.get',session?{sessionId:session.id}:project?{projectId:project.id}:{});setModels(config.models);setDefaultModel(config.defaultModel);});},[selected,project?.id,page]);
  useEffect(()=>{let current=true;request('commands.list',session?{sessionId:session.id}:project?{projectId:project.id}:{}).then(items=>{if(current)setCommands(items);}).catch(failure=>{if(current)setError(failure.message);});return()=>{current=false;};},[selected,project?.id,session?.turns?.at(-1)?.status]);
  useEffect(()=>{if(nearBottom.current&&viewport.current)viewport.current.scrollTop=viewport.current.scrollHeight;},[session?.turns]);
  useEffect(()=>{setAttachments([]);setDiff(null);setMenu(false);nearBottom.current=true;},[selected]);
  useEffect(()=>{const handler=event=>{if((event.metaKey||event.ctrlKey)&&event.key==='n'){event.preventDefault();newChat();}if(event.key==='Escape'){setDiff(null);setMenu(false);setRename(null);}};window.addEventListener('keydown',handler);return()=>window.removeEventListener('keydown',handler);},[project]);
  const addProject=()=>act(async()=>{const path=await api.chooseProject();if(!path)return;const item=await request('project.add',{path});await refresh();setProjectId(item.id);setNewApprovalMode('on-request');setNewModel('');setNewEffort('default');setSelected(null);setPage('chat');});
  const newChat=()=>{setNewApprovalMode('on-request');setNewModel('');setNewEffort('default');setSelected(null);setPage('chat');setArchives(false);setProjectId(project?.id||null);setTimeout(()=>inputRef.current?.focus(),0);};
  const create=async(approvalMode=newApprovalMode)=>{if(!project)throw new Error('请先添加一个项目');const created=await request('session.create',{projectId:project.id,model:selectedModel,approvalMode,reasoningEffort:session?.reasoningEffort||newEffort});await refresh();setSelected(created.id);return created;};
  const approvalMode=approvalSaving?.key===draftKey?approvalSaving.value:(session?.approvalMode||newApprovalMode);
  const changeApprovalMode=value=>act(async()=>{
    if(approvalSaving)return;
    setApprovalSaving({key:draftKey,value});
    try {
      if(session){
        const updated=await request('session.update',{sessionId:session.id,approvalMode:value});
        setState(current=>({...current,sessions:current.sessions.map(item=>item.id===updated.id?{...item,approvalMode:updated.approvalMode}:item)}));
      }else setNewApprovalMode(value);
      notify(value==='auto'?'已开启帮我审批，仅对当前聊天生效':'已切换为按需审批');return true;
    }finally{setApprovalSaving(null);}
  });
  const setDraft=value=>setDrafts(current=>({...current,[draftKey]:value}));
  const selectedModel=models.some(model=>model.id===(session?.model||newModel||defaultModel))?(session?.model||newModel||defaultModel):'';
  const reasoningEffort=session?.reasoningEffort||newEffort;
  const changeModelSettings=values=>act(async()=>{
    if(modelSaving)return false;setModelSaving(true);
    try{
      if(session){const updated=await request('session.update',{sessionId:session.id,...values});setState(current=>({...current,sessions:current.sessions.map(item=>item.id===updated.id?{...item,model:updated.model,reasoningEffort:updated.reasoningEffort}:item)}));}
      else{if(values.model){setNewModel(values.model);setNewEffort('default');}if(values.reasoningEffort)setNewEffort(values.reasoningEffort);}
      return true;
    }finally{setModelSaving(false);}
  });
  const suggestions=dismissedCommand===draft||directoryBusy?[]:commandSuggestions(draft,commands);
  const chooseCommand=item=>{setDraft(item.command+' ');setCommandIndex(0);setDismissedCommand(null);inputRef.current?.focus();};
  const handleComposerKey=event=>{
    if(event.nativeEvent.isComposing)return;
    if(event.key==='Escape'&&suggestions.length){event.preventDefault();setDismissedCommand(draft);return;}
    if(suggestions.length&&['ArrowDown','ArrowUp'].includes(event.key)){event.preventDefault();setCommandIndex(current=>(current+(event.key==='ArrowDown'?1:-1)+suggestions.length)%suggestions.length);return;}
    if(suggestions.length&&(event.key==='Tab'||(event.key==='Enter'&&!event.shiftKey))){event.preventDefault();chooseCommand(suggestions[Math.min(commandIndex,suggestions.length-1)]);return;}
    if(event.key==='Enter'&&!event.shiftKey){event.preventDefault();if(!directoryBusy)send();}
  };
  useEffect(()=>{document.getElementById('slash-option-'+commandIndex)?.scrollIntoView({block:'nearest'});},[commandIndex]);
  const send=()=>act(async()=>{if(!draft.trim()||sending||approvalSaving||modelSaving)return;if(needsModel(draft,commands)&&!selectedModel)throw new Error(models.length?'请选择已配置的模型':'请先在设置中配置模型');setSending(true);try{const target=session||await create();await request('turn.start',{sessionId:target.id,input:draft,attachments});setDrafts(current=>({...current,[draftKey]:'',[target.id]:''}));setAttachments([]);await refresh();nearBottom.current=true;}finally{setSending(false);}});
  const chooseFiles=()=>act(async()=>{const paths=await api.chooseFiles();appendFiles(paths);});
  const appendFiles=paths=>setAttachments(current=>[...current,...paths.map(path=>({path,type:/\.(png|jpe?g|gif|webp)$/i.test(path)?'image':'file'}))].slice(0,8));
  const paste=event=>{const item=Array.from(event.clipboardData?.items||[]).find(item=>item.type==='image/png');if(!item)return;event.preventDefault();const reader=new FileReader();reader.onload=()=>act(async()=>appendFiles([await api.saveImage(reader.result)]));reader.readAsDataURL(item.getAsFile());};
  const update=(values)=>act(async()=>{await request('session.update',{sessionId:session.id,...values});await refresh();});
  const viewChanges=turn=>act(async()=>{setDiffLoading(true);setDiff({turn,files:[]});try{const files=await request('changes',{sessionId:session.id,turnId:turn.id});setDiff({turn,files});}finally{setDiffLoading(false);}});
  const undo=()=>act(async()=>{const result=await request('undo',{sessionId:session.id,turnId:diff.turn.id});if(!result.canceled){await refresh();setDiff(current=>({...current,turn:{...current.turn,undone:true}}));notify('本轮改动已撤销');}});
  const select=item=>{setSelected(item.id);setProjectId(item.projectId);setPage('chat');setError('');};
  const filtered=state.sessions.filter(item=>item.archived===archives&&item.title.toLowerCase().includes(query.toLowerCase()));
  const sessionRow=item=><button key={item.id} className={'session-row'+(selected===item.id?' selected':'')} onClick={()=>select(item)} title={item.title}><span>{item.title}</span>{item.turns.some(running)?<span className="activity-dot"/>:item.pinned?<Pin size={12}/>:null}</button>;
  return <div className="app-shell">
    <div className="titlebar"><div className="window-space"/><IconButton icon={ChevronLeft} label="返回聊天" onClick={()=>setPage('chat')}/><IconButton icon={PanelLeft} label="切换侧栏" onClick={()=>setSidebar(!sidebar)}/><div className="titlebar-divider"/><Folder size={17}/><span>{page==='schedules'?'定时任务':page==='skills'?'技能':page==='settings'?'设置':session?.title||'新的开始'}</span><div className="titlebar-spacer"/><span className="local-indicator"><span/>本地工作区</span></div>
    <div className="shell-body"><nav className="rail" aria-label="主导航"><IconButton icon={Home} label="聊天" active={page==='chat'} onClick={()=>setPage('chat')}/><IconButton icon={Clock3} label="定时任务" active={page==='schedules'} onClick={()=>setPage('schedules')}/><IconButton icon={Archive} label="已归档的聊天" active={archives&&page==='chat'} onClick={()=>{setArchives(!archives);setPage('chat');}}/><IconButton icon={Blocks} label="技能" active={page==='skills'} onClick={()=>setPage('skills')}/><div className="rail-spacer"/><IconButton icon={Settings} label="设置" active={page==='settings'} onClick={()=>setPage('settings')}/></nav>
    <div className="workspace">
      {page==='schedules'?<ScheduledTasks projects={state.projects} sidebar={sidebar} onProjectsChanged={refresh} onSettings={()=>setPage('settings')}/>:<>
      {sidebar&&<aside className="sidebar">{page==='skills'?<><h1 className="brand">自定义</h1><button className="nav-row selected" aria-current="page"><Blocks size={19}/>技能</button><p className="sidebar-hint">管理助手可使用的工作方法</p><div className="sidebar-bottom"><button className="nav-row" onClick={()=>setPage('chat')}><ArrowLeft size={17}/>返回工作区</button></div></>:page==='settings'?<><h1 className="brand">设置</h1><div className="sidebar-section-label">个人</div><button className="nav-row selected"><SlidersHorizontal size={19}/>模型与连接</button><button className="nav-row" onClick={()=>{setArchives(true);setPage('chat');}}><Archive size={19}/>已归档的聊天</button><div className="sidebar-bottom"><button className="nav-row" onClick={()=>setPage('chat')}><ArrowLeft size={17}/>返回工作区</button></div></>:<><div className="brand-row"><h1 className="brand">XhlCLI<ChevronDown size={15}/></h1><IconButton icon={Search} label="搜索聊天" onClick={()=>setSearching(!searching)}/></div><button className="new-chat" onClick={newChat}><SquarePen size={19}/>新聊天<span>⌘ N</span></button>{searching&&<div className="search-input"><Search size={16}/><input autoFocus aria-label="搜索聊天" placeholder="搜索聊天" value={query} onChange={event=>setQuery(event.target.value)}/></div>}<div className="sidebar-scroll">
        {archives?<div className="sidebar-section-label">已归档的聊天</div>:<><div className="sidebar-section-label">置顶</div>{filtered.filter(item=>item.pinned).map(sessionRow)}{!filtered.some(item=>item.pinned)&&<p className="sidebar-hint">常用聊天可以置顶在这里</p>}</>}
        <div className="section-heading"><span>{archives?'项目':'项目'}</span><IconButton icon={Plus} label="添加项目" onClick={addProject}/></div>
        {state.projects.map(item=><div className="project-group" key={item.id}><button className={'project-row'+(project?.id===item.id?' current':'')} onClick={()=>{setProjectId(item.id);setNewApprovalMode('on-request');setNewModel('');setNewEffort('default');setSelected(null);setPage('chat');}} title={item.path}>{project?.id===item.id?<FolderOpen size={18}/>:<Folder size={18}/>}<span>{item.name}</span></button>{filtered.filter(s=>s.projectId===item.id&&(!s.pinned||archives)).slice().reverse().map(sessionRow)}</div>)}
        {!state.projects.length&&<button className="add-first-project" onClick={addProject}><Plus size={16}/>添加你的第一个项目</button>}
      </div><div className="sidebar-bottom"><span className="engine-dot"/><span>XhlCLI 引擎</span><span className="version">MVP</span></div></>}</aside>}
      <main className="main-panel">
        {page==='skills'?<SkillsPage projects={state.projects} project={project} notify={notify}/>:page==='settings'?<SettingsPage project={project} session={session} approvalMode={approvalMode} onError={setError} notify={notify} onSaved={()=>{refresh();request('config.get',project?{projectId:project.id}:{}).then(config=>{setModels(config.models);setDefaultModel(config.defaultModel);}).catch(failure=>setError(failure.message));}}/>:<>
          <header className="chat-header"><div className="breadcrumb"><Folder size={16}/><span>{project?.name||'工作区'}</span>{session&&<><ChevronRight size={13}/><span className="breadcrumb-title">{session.title}</span></>}</div><div className="header-actions">{session&&<><button className="subtle-button" onClick={()=>act(()=>api.openTerminal(session.id))}><Terminal size={15}/>打开终端<ExternalLink size={12}/></button><div className="menu-wrapper"><IconButton icon={MoreHorizontal} label="聊天操作" onClick={()=>setMenu(!menu)}/>{menu&&<div className="dropdown"><button onClick={()=>{setRename(session.id);setName(session.title);setMenu(false);}}><SquarePen size={15}/>重命名</button><button onClick={()=>{update({pinned:!session.pinned});setMenu(false);}}><Pin size={15}/>{session.pinned?'取消置顶':'置顶聊天'}</button><button onClick={()=>{update({archived:!session.archived});setMenu(false);}}><Archive size={15}/>{session.archived?'恢复聊天':'归档聊天'}</button></div>}</div></>}</div></header>
          <div className="chat-workspace"><div className="conversation">
            <div className="conversation-scroll" ref={viewport} onScroll={event=>{const e=event.currentTarget;nearBottom.current=e.scrollHeight-e.scrollTop-e.clientHeight<100;}}>
              {loading?<div className="empty-state"><LoaderCircle size={28} className="spin"/><h2>正在连接 XhlCLI</h2><p>加载你的本地工作区</p></div>:!session?.turns.length?<div className="empty-state"><div className="empty-emblem"><Terminal size={32} strokeWidth={1.35}/></div><h2>一起完成下一件事</h2><p>{project?<>在 <strong>{project.name}</strong> 中探索、修改与验证代码</>:'打开一个项目，让想法变成代码'}</p>{!project?<button className="primary open-project" onClick={addProject}><FolderOpen size={17}/>打开项目</button>:<div className="prompt-suggestions">{['了解这个项目的结构','帮我检查最近的代码改动','运行测试并分析失败原因'].map(text=><button key={text} onClick={()=>{setDraft(text);inputRef.current?.focus();}}><MessageSquare size={15}/>{text}<ArrowUp size={14}/></button>)}</div>}</div>:<div className="conversation-content">{session.turns.map(turn=><Turn key={turn.id} turn={turn} session={session} onChanges={viewChanges} notify={notify} onApproval={(approvalId,allow)=>act(async()=>{await request('approval.respond',{sessionId:session.id,approvalId,allow});await refresh();})}/>)}</div>}
            </div>
            <div className="composer-area"><div className="composer" onDragOver={event=>event.preventDefault()} onDrop={event=>{event.preventDefault();appendFiles(Array.from(event.dataTransfer.files).map(file=>api.pathForFile(file)).filter(Boolean));}}>
              {attachments.length>0&&<div className="composer-attachments">{attachments.map((file,index)=><span key={file.path+index}>{file.type==='image'?<Image size={14}/>:<FileText size={14}/>}<span>{file.path.split('/').pop()}</span><button title="移除附件" aria-label={'移除附件 '+file.path.split('/').pop()} onClick={()=>setAttachments(current=>current.filter((_,i)=>i!==index))}><X size={12}/></button></span>)}</div>}
              {suggestions.length>0&&<div className="slash-menu"><div className="slash-menu-heading">指令<span>↑ ↓ 选择 · Enter / Tab 补全 · Esc 收起</span></div><div id="slash-commands" role="listbox" aria-label="斜杠指令" className="slash-options">{suggestions.map((item,index)=><button type="button" role="option" id={'slash-option-'+index} aria-selected={index===Math.min(commandIndex,suggestions.length-1)} key={item.command} onMouseDown={event=>event.preventDefault()} onMouseEnter={()=>setCommandIndex(index)} onClick={()=>chooseCommand(item)}><code>{item.command}</code><span>{item.description}</span></button>)}</div></div>}
              <textarea ref={inputRef} aria-label="任务输入" placeholder="和 XhlCLI 一起工作，输入 / 选择指令" value={draft} onChange={event=>{setDraft(event.target.value);setCommandIndex(0);setDismissedCommand(null);}} onPaste={paste} onKeyDown={handleComposerKey} aria-autocomplete="list" aria-controls={suggestions.length?'slash-commands':undefined} aria-expanded={suggestions.length>0} aria-activedescendant={suggestions.length?'slash-option-'+Math.min(commandIndex,suggestions.length-1):undefined} rows={2}/>
              <div className="composer-toolbar"><IconButton icon={Plus} label="添加文件或图片" onClick={chooseFiles}/><ComposerControls key={draftKey+':'+project?.id} models={models} model={selectedModel} effort={reasoningEffort} onModel={model=>changeModelSettings({model})} onEffort={reasoningEffort=>changeModelSettings({reasoningEffort})} approvalMode={approvalMode} onApproval={changeApprovalMode} approvalDisabled={!project||loading||sending||!!approvalSaving||activeTurn?.status==='canceling'} modelDisabled={loading||sending||modelSaving} approvalSaving={!!approvalSaving} modelSaving={modelSaving} onSettings={()=>setPage('settings')}/>{activeTurn?<button className="send-button stop" aria-label="停止执行" title="停止执行" disabled={activeTurn.status==='canceling'} onClick={()=>act(async()=>{await request('turn.cancel',{sessionId:session.id});await refresh();})}><Square size={15} fill="currentColor"/></button>:<button className="send-button" aria-label="发送任务" title="发送任务" disabled={!draft.trim()||!project||sending||!!approvalSaving||modelSaving||directoryBusy} onClick={send}>{sending?<LoaderCircle size={19} className="spin"/>:<ArrowUp size={21}/>}</button>}</div>
            </div><p className="composer-footnote">{directoryBusy&&!activeTurn?'当前项目已有任务运行，输入已保留':'Enter 发送 · Shift + Enter 换行'}<span>{project?.name||'本地优先'}</span></p></div>
          </div>
          {diff&&<aside className="diff-panel"><header><div><Files size={17}/><strong>本轮变更</strong></div><IconButton icon={X} label="关闭变更面板" onClick={()=>setDiff(null)}/></header><div className="diff-toolbar"><span>{diff.files.length} 个文件</span><button disabled={diff.turn.undone||diffLoading||directoryBusy||!diff.files.length} onClick={undo}><Undo2 size={14}/>{diff.turn.undone?'已撤销':'撤销本轮'}</button></div><div className="diff-scroll">{diffLoading?<p className="muted">正在读取差异…</p>:!diff.files.length?<div className="diff-empty"><Check size={24}/><p>本轮没有文件变化</p></div>:diff.files.map(file=><details className="diff-file" key={file.path} open><summary><FileText size={14}/><span>{file.path}</span><small>{file.type}</small></summary><button className="open-file" onClick={()=>act(()=>api.openFile(session.id,file.path))}>打开文件<ExternalLink size={12}/></button><pre>{file.diff.split('\n').map((line,index)=><span key={index} className={line.startsWith('+')?'added':line.startsWith('-')?'removed':line.startsWith('@@')?'hunk':''}>{line||' '}{'\n'}</span>)}</pre></details>)}</div></aside>}
          </div></>}
      </main>
      </>}
    </div></div>
    {error&&<div className="error-banner" role="alert"><AlertCircle size={18}/><span>{error}</span><button onClick={()=>act(refresh)} title="刷新状态"><RefreshCw size={16}/></button><button onClick={()=>setError('')} aria-label="关闭错误"><X size={16}/></button></div>}
    {toast&&<div className="toast" role="status"><Check size={16}/>{toast}</div>}
    {rename&&<div className="modal-backdrop"><form className="rename-dialog" onSubmit={event=>{event.preventDefault();act(async()=>{await request('session.update',{sessionId:rename,title:name});await refresh();setRename(null);});}}><h2>重命名聊天</h2><input autoFocus aria-label="聊天标题" maxLength={160} value={name} onChange={event=>setName(event.target.value)}/><div><button type="button" onClick={()=>setRename(null)}>取消</button><button className="primary" type="submit">保存</button></div></form></div>}
  </div>;
}

function SettingsPage({project,session,approvalMode,onError,notify,onSaved}){
  const [config,setConfig]=useState(null),[provider,setProvider]=useState('deepseek'),[form,setForm]=useState({model:'',baseUrl:'',apiKey:''}),[busy,setBusy]=useState('');
  const context=session?{sessionId:session.id}:project?{projectId:project.id}:{};
  useEffect(()=>{request('config.get',context).then(setConfig).catch(failure=>onError(failure.message));},[project?.id,session?.id]);
  useEffect(()=>{const item=config?.providers.find(item=>item.id===provider);if(item)setForm({model:item.model,baseUrl:item.baseUrl,apiKey:''});},[provider,config]);
  const current=config?.providers.find(item=>item.id===provider);
  const save=async()=>{setBusy('save');try{const updated=await request('config.save',{...context,provider,...form});setConfig(updated);notify('配置已保存');onSaved();}catch(failure){onError(failure.message);}finally{setBusy('');}};
  const test=async()=>{setBusy('test');try{await request('config.test',{...context,model:provider+':'+current.model});notify('连接成功');}catch(failure){onError(failure.message);}finally{setBusy('');}};
  return <div className="settings-scroll"><div className="settings-content"><h1>模型与连接</h1><p className="settings-intro">连接你使用的模型，继续熟悉的编程工作。</p><h2>模型提供方</h2><div className="provider-tabs">{['deepseek','openai','anthropic','ollama'].map(item=><button key={item} className={provider===item?'selected':''} onClick={()=>setProvider(item)}>{({deepseek:'DeepSeek',openai:'OpenAI',anthropic:'Anthropic',ollama:'Ollama'})[item]}</button>)}</div>
    <div className="settings-group"><div className="setting-row"><div><strong>连接状态</strong><p>当前项目生效的配置来源：{current?.source||'正在读取'}</p></div><span className={'connection-badge '+(current?.configured?'configured':'')}><span/>{current?.configured?'已配置':'尚未配置'}</span></div><label className="setting-row"><div><strong>模型名称</strong><p>输入提供方支持的模型标识</p></div><input aria-label="模型名称" value={form.model} onChange={event=>setForm({...form,model:event.target.value})}/></label><label className="setting-row"><div><strong>服务地址</strong><p>沿用 XhlCLI 的 Provider 协议</p></div><input aria-label="服务地址" value={form.baseUrl} onChange={event=>setForm({...form,baseUrl:event.target.value})}/></label>{provider!=='ollama'&&<label className="setting-row"><div><strong>API Key</strong><p>留空保留已保存的密钥</p></div><input aria-label="API Key" type="password" autoComplete="off" placeholder={current?.configured?'已配置 · 输入可替换':'输入 API Key'} value={form.apiKey} onChange={event=>setForm({...form,apiKey:event.target.value})}/></label>}</div>
    <div className="settings-actions"><span>保存到 XhlCLI 全局配置；环境变量与项目配置优先。</span><button disabled={!!busy||!current?.configured} onClick={test}>{busy==='test'?<LoaderCircle size={15} className="spin"/>:<RefreshCw size={15}/>}测试已保存配置</button><button className="primary" disabled={!!busy||!form.model||!form.baseUrl} onClick={save}>{busy==='save'?'正在保存…':'保存配置'}</button></div><p className="test-note">连接测试会向当前模型发送一次简短请求。修改后请先保存。</p>
    <h2>工作区</h2><div className="settings-group"><div className="setting-row"><div><strong>当前项目</strong><p>{project?.path||'尚未选择项目'}</p></div><Folder size={20}/></div><div className="setting-row"><div><strong>工具权限</strong><p>{approvalMode==='auto'?'当前聊天自动批准工具调用（含命令执行），路径保护仍有效。':'当前聊天需要权限的操作由你确认；可在输入框切换审批模式。'}</p></div><span className="value-text"><ShieldCheck size={16}/>{approvalMode==='auto'?'帮我审批':'按需审批'}</span></div><div className="setting-row"><div><strong>界面语言</strong><p>应用界面与操作提示</p></div><span>简体中文</span></div><div className="setting-row"><div><strong>本地会话</strong><p>消息和工具记录保存在本机，重新打开后可继续。</p></div><span className="value-text"><Check size={16}/>自动保存</span></div></div>
    <footer className="settings-footer">XhlCLI Desktop · MVP<span>由现有 XhlCLI 引擎提供能力</span></footer></div></div>;
}

createRoot(document.getElementById('root')).render(<App/>);
