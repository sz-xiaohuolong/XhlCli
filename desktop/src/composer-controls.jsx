import React,{useEffect,useRef,useState} from 'react';
import {Check,ChevronDown,ChevronLeft,ChevronRight,ShieldCheck,Hand,LoaderCircle,RotateCcw} from 'lucide-react';
import './composer-controls.css';

const labels={default:'默认',none:'关闭',low:'低',medium:'中',high:'高',max:'最高'};
const permissions=[
  {id:'on-request',label:'按需审批',description:'需要权限的工具操作先由你确认。',icon:Hand},
  {id:'auto',label:'帮我审批',description:'自动批准工具调用（含命令执行）及等待中的审批，路径保护仍有效。',icon:ShieldCheck}
];
export function ComposerControls({models,model,effort,onModel,onEffort,approvalMode,onApproval,approvalDisabled,modelDisabled,approvalSaving,modelSaving,onSettings}){
  const [open,setOpen]=useState(null),[choosingModel,setChoosingModel]=useState(false),[preview,setPreview]=useState(null);
  const root=useRef(null),permissionButton=useRef(null),modelButton=useRef(null),panel=useRef(null);
  const selected=models.find(item=>item.id===model),levels=selected?.reasoningEfforts||[];
  const displayed=preview??effort;
  const defaultLevel=levels.includes('high')&&selected?.provider==='deepseek'?'high':levels.includes('medium')?'medium':levels[0];
  const levelIndex=Math.max(0,levels.indexOf(displayed==='default'?defaultLevel:displayed));
  const close=(restore=false)=>{if(restore)(open==='permission'?permissionButton:modelButton).current?.focus();setOpen(null);setPreview(null);};
  useEffect(()=>{
    if(!open)return;
    const outside=event=>{if(!root.current?.contains(event.target))close();};
    const escape=event=>{if(event.key==='Escape'){event.preventDefault();event.stopPropagation();close(true);}};
    document.addEventListener('pointerdown',outside);document.addEventListener('keydown',escape,true);
    return()=>{document.removeEventListener('pointerdown',outside);document.removeEventListener('keydown',escape,true);};
  },[open]);
  useEffect(()=>{setPreview(null);},[model,effort]);
  const toggle=(value,event)=>{setChoosingModel(false);setPreview(null);setOpen(open===value?null:value);if(event.detail===0)requestAnimationFrame(()=>panel.current?.querySelector('button:not(:disabled),input:not(:disabled)')?.focus());};
  const saveEffort=async value=>{if(modelSaving)return;await onEffort(value);setPreview(null);};
  const keyboard=event=>{
    if(event.target.type==='range'||!['ArrowDown','ArrowUp','Home','End'].includes(event.key))return;
    const buttons=[...panel.current.querySelectorAll('[data-option]:not(:disabled)')];if(!buttons.length)return;
    event.preventDefault();const current=buttons.indexOf(document.activeElement);
    const index=event.key==='Home'?0:event.key==='End'?buttons.length-1:(current+(event.key==='ArrowDown'?1:-1)+buttons.length)%buttons.length;buttons[index].focus();
  };
  return <div className="composer-controls" ref={root} onBlur={event=>{if(event.relatedTarget&&!event.currentTarget.contains(event.relatedTarget))close();}}>
    <div className="composer-permission">
      <button ref={permissionButton} className={'composer-chip permission-chip'+(approvalMode==='auto'?' automatic':'')} aria-label="审批模式" aria-expanded={open==='permission'} aria-haspopup="dialog" disabled={approvalDisabled} onClick={event=>toggle('permission',event)}>
        {approvalSaving?<LoaderCircle size={17} className="spin"/>:<ShieldCheck size={17}/>}<span>{approvalMode==='auto'?'帮我审批':'按需审批'}</span><ChevronDown size={13}/>
      </button>
      {open==='permission'&&<div ref={panel} className="composer-popover permission-popover" role="dialog" aria-label="选择审批模式" onKeyDown={keyboard}>
        <p className="popover-heading">如何批准助手操作？</p>
        {permissions.map(({id,label,description,icon:Icon})=><button type="button" data-option key={id} className={'permission-option'+(id==='auto'?' automatic':'')} aria-pressed={approvalMode===id} disabled={approvalDisabled} onClick={async()=>{if(await onApproval(id))close(true);}}>
          <Icon size={20}/><span><strong>{label}</strong><small>{description}</small></span>{approvalMode===id&&<Check size={17}/>}</button>)}
        <p className="permission-scope">仅对当前聊天生效。</p>
      </div>}
    </div>
    <div className="composer-model">
      <button ref={modelButton} className="composer-chip model-chip" aria-label="模型与推理强度" aria-expanded={open==='model'} aria-haspopup="dialog" disabled={modelDisabled} onClick={event=>toggle('model',event)}>
        <span className="composer-model-name" title={selected?.modelName}>{selected?.modelName||(models.length?'选择模型':'配置模型')}</span>{levels.length>0&&<span className="effort-value">{labels[effort]||'默认'}</span>}{modelSaving?<LoaderCircle size={14} className="spin"/>:<ChevronDown size={13}/>}
      </button>
      {open==='model'&&<div ref={panel} className="composer-popover model-popover" role="dialog" aria-label="模型与推理设置" onKeyDown={keyboard}>
        {choosingModel||!selected?<><div className="model-menu-heading">{selected&&<button aria-label="返回推理设置" onClick={()=>setChoosingModel(false)}><ChevronLeft size={17}/></button>}<span>选择模型</span></div><div className="model-options">{models.map(item=><button data-option key={item.id} aria-pressed={model===item.id} disabled={modelSaving} onClick={async()=>{if(await onModel(item.id))setChoosingModel(false);}}><span>{item.modelName}</span>{model===item.id&&<Check size={16}/>}</button>)}</div>{!models.length&&<p className="model-empty">先连接一个模型，即可开始聊天。</p>}<button className="configure-model" onClick={()=>{close();onSettings();}}>模型设置<ChevronRight size={15}/></button></>:<>
          <div className="effort-heading"><span>{levels.length?'推理强度':'模型'}</span>{levels.length>0&&<button aria-label="恢复默认推理强度" title="恢复默认推理强度" disabled={modelSaving||effort==='default'} onClick={()=>saveEffort('default')}><RotateCcw size={17}/></button>}</div>
          {levels.length>0&&<strong className="effort-current">{labels[displayed]||'默认'}</strong>}
          <button className="model-submenu" onClick={()=>setChoosingModel(true)}><span title={selected.modelName}>{selected.modelName}</span><ChevronRight size={15}/></button>
          {levels.length>0?<div className="effort-control">
            <input type="range" min="0" max={levels.length-1} step="1" aria-label="推理强度" aria-valuetext={labels[displayed==='default'?defaultLevel:displayed]} value={levelIndex} disabled={modelSaving} style={{'--effort-fill':`${levelIndex/(levels.length-1)*100}%`}} onChange={event=>setPreview(levels[Number(event.target.value)])} onPointerUp={event=>saveEffort(levels[Number(event.currentTarget.value)])} onPointerCancel={()=>setPreview(null)} onKeyUp={event=>{if(['ArrowLeft','ArrowRight','ArrowUp','ArrowDown','Home','End'].includes(event.key))saveEffort(levels[Number(event.currentTarget.value)]);}}/>
            <div className="effort-steps">{levels.map(level=><button key={level} aria-label={'推理强度：'+labels[level]} aria-pressed={displayed===level} disabled={modelSaving} onClick={()=>saveEffort(level)}>{labels[level]}</button>)}</div>
            <p className="effort-help">{displayed==='none'?'关闭思考，优先快速响应。':'强度越高，通常需要更多时间与用量。'} 下一轮任务生效。</p>
          </div>:<p className="effort-help unsupported-effort">该模型暂未接入可调推理强度。</p>}
        </>}
      </div>}
    </div>
  </div>;
}
