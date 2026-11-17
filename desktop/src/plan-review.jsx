import React,{useEffect,useId,useMemo,useRef,useState} from 'react';
import Markdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import './plan-review.css';
import {FileText,LoaderCircle,Maximize2,MessageSquarePlus,Highlighter,Strikethrough,Pencil,Trash2,X,ChevronRight} from 'lucide-react';
import {MAX_PLAN_FEEDBACK_CHARS,clearDraft,formatPlanFeedback,loadDraft,planDraftKey,saveDraft} from './annotations.mjs';

const api=typeof window!=='undefined'?window.xhl:null;

const BLOCK_TAGS=new Set(['p','li','h1','h2','h3','h4','h5','h6','blockquote','pre','td','th']);

function collectLeaves(node,leaves,insideMarked=false,blockId={value:0}){
  if(!node)return;
  if(node.type==='root'||node.type==='element'){
    const isMarked=insideMarked||Boolean(node.properties?.['data-plan-annotation']);
    if(isMarked){
      blockId.value+=1;
      leaves.push({barrier:true,blockGroup:blockId.value});
      return;
    }
    if(node.type==='element'&&node.tagName==='br'){
      blockId.value+=1;
      return;
    }
    const isBlock=node.type==='element'&&BLOCK_TAGS.has(node.tagName);
    if(isBlock)blockId.value+=1;
    const children=node.children||[];
    for(let i=0;i<children.length;i+=1){
      collectLeaves(children[i],leaves,false,blockId);
    }
    if(isBlock)blockId.value+=1;
    return;
  }
  if(node.type==='text'&&!insideMarked&&typeof node.value==='string'&&node.value.length>0){
    leaves.push({node,blockGroup:blockId.value});
  }
}

function attachParents(node,parent=null){
  if(!node||!Array.isArray(node.children))return;
  for(let i=0;i<node.children.length;i+=1){
    const child=node.children[i];
    child._parent=node;
    attachParents(child,node);
  }
}

function normalizeAndMapLeaves(leaves){
  let norm='';
  const map=[];
  let prevWasSpace=true;
  let lastBlock=null;
  for(let leafIdx=0;leafIdx<leaves.length;leafIdx+=1){
    const leaf=leaves[leafIdx];
    if(leaf.barrier){
      norm+='\u0000';
      map.push(null);
      prevWasSpace=true;
      lastBlock=leaf.blockGroup;
      continue;
    }
    const {node,blockGroup}=leaf;
    const val=node.value;
    if(lastBlock!==null&&lastBlock!==blockGroup&&!prevWasSpace){
      norm+=' ';
      map.push(null);
      prevWasSpace=true;
    }
    lastBlock=blockGroup;
    for(let offset=0;offset<val.length;offset+=1){
      const ch=val[offset];
      if(/\s/.test(ch)){
        if(!prevWasSpace){
          norm+=' ';
          map.push({leafIdx,offset});
          prevWasSpace=true;
        }
      }else{
        norm+=ch;
        map.push({leafIdx,offset});
        prevWasSpace=false;
      }
    }
  }
  return {norm,map};
}

function findMatchRange(norm,needle){
  if(!needle)return -1;
  const idx=norm.indexOf(needle);
  if(idx!==-1)return idx;
  return norm.toLowerCase().indexOf(needle.toLowerCase());
}

function applyHastHighlights(tree,annotations){
  if(!tree||!Array.isArray(annotations)||!annotations.length)return;
  for(const ann of annotations){
    const needle=String(ann?.originalText||'').replace(/\s+/g,' ').trim();
    if(!needle)continue;
    const leaves=[];
    collectLeaves(tree,leaves);
    if(!leaves.length)continue;
    const {norm,map}=normalizeAndMapLeaves(leaves);
    const start=findMatchRange(norm,needle);
    if(start===-1)continue;
    const end=start+needle.length;

    const perLeaf=new Map();
    for(let i=start;i<end;i+=1){
      const pos=map[i];
      if(!pos)continue;
      const prev=perLeaf.get(pos.leafIdx);
      if(!prev)perLeaf.set(pos.leafIdx,{start:pos.offset,end:pos.offset+1});
      else{
        prev.start=Math.min(prev.start,pos.offset);
        prev.end=Math.max(prev.end,pos.offset+1);
      }
    }
    if(!perLeaf.size)continue;
    attachParents(tree);

    for(const [leafIdx,span] of perLeaf.entries()){
      const leaf=leaves[leafIdx].node;
      const parent=leaf._parent;
      if(!parent||!Array.isArray(parent.children))continue;
      const childIndex=parent.children.indexOf(leaf);
      if(childIndex===-1)continue;
      const val=leaf.value;
      const s=Math.max(0,span.start);
      const e=Math.min(val.length,span.end);
      const before=val.slice(0,s);
      const mid=val.slice(s,e);
      const after=val.slice(e);
      if(!mid)continue;
      const replacement=[];
      if(before)replacement.push({type:'text',value:before});
      replacement.push({
        type:'element',
        tagName:ann.type==='DELETION'?'del':'mark',
        properties:{
          className:[ann.type==='DELETION'?'plan-mark-deletion':'plan-mark-comment'],
          'data-annotation-id':ann.id,
          'data-plan-annotation':'true',
          title:ann.text||(ann.type==='DELETION'?'建议删除':'批注')
        },
        children:[{type:'text',value:mid}]
      });
      if(after)replacement.push({type:'text',value:after});
      parent.children.splice(childIndex,1,...replacement);
    }
  }
}

function createHighlightPlugin(annotations){
  if(!annotations||!annotations.length)return null;
  return ()=>tree=>{applyHastHighlights(tree,annotations);};
}

function AnnotatedMarkdown({text,annotations=[]}){
  const plugins=useMemo(()=>{
    const plugin=createHighlightPlugin(annotations);
    return plugin?[plugin]:[];
  },[annotations]);
  return <Markdown
    remarkPlugins={[remarkGfm]}
    rehypePlugins={plugins}
    components={{
      a:({href,children})=><a href={href} onClick={event=>{event.preventDefault();if(href&&api?.openExternal)api.openExternal(href).catch(()=>{});}}>{children}</a>,
      img:()=><span className="muted">[外部图片未加载]</span>
    }}>
    {text||''}
  </Markdown>;
}

function summarizeStepQuote(description){
  const plain=String(description||'')
    .replace(/```[\s\S]*?```/g,' ')
    .replace(/[`*_>#~[\]()]/g,' ')
    .replace(/\s+/g,' ')
    .trim();
  if(!plain)return '当前步骤';
  const sentence=plain.split(/[。！？\n]/)[0].trim()||plain;
  return sentence.length>120?sentence.slice(0,117)+'...':sentence;
}

function targetLabel(ann){
  if(ann.targetKind==='goal')return '计划目标';
  if(ann.targetKind==='summary')return '计划摘要';
  if(ann.targetKind==='step')return `步骤 ${ann.stepIndex}`;
  return '计划';
}

function createAnnotationId(){
  return 'ann_'+Date.now().toString(36)+'_'+Math.random().toString(36).slice(2,7);
}

function loadPlanDraftState(active,key){
  if(!active||!key)return {key:'',annotations:[],globalFeedback:''};
  const saved=loadDraft(key);
  return {
    key,
    annotations:Array.isArray(saved?.annotations)?saved.annotations:[],
    globalFeedback:typeof saved?.globalFeedback==='string'?saved.globalFeedback:''
  };
}

export function PlanReview({plan,active,resolution,sessionId,onRespond}){
  const draftKey=useMemo(()=>active?planDraftKey(sessionId,plan?.id):'',[active,sessionId,plan?.id]);
  const [draftState,setDraftState]=useState(()=>loadPlanDraftState(active,draftKey));
  const annotations=draftState.annotations;
  const globalFeedback=draftState.globalFeedback;
  const setAnnotations=updater=>setDraftState(current=>({
    ...current,
    key:draftKey,
    annotations:typeof updater==='function'?updater(current.annotations):updater
  }));
  const setGlobalFeedback=value=>setDraftState(current=>({
    ...current,
    key:draftKey,
    globalFeedback:typeof value==='function'?value(current.globalFeedback):value
  }));
  const [sending,setSending]=useState('');
  const [error,setError]=useState('');
  const [expanded,setExpanded]=useState(false);
  const [selectionBar,setSelectionBar]=useState(null);
  const [stepQuickComposer,setStepQuickComposer]=useState(null);
  const [editingId,setEditingId]=useState(null);
  const [editText,setEditText]=useState('');

  const inFlight=useRef(false);
  const inlineContentRef=useRef(null);
  const dialogContentRef=useRef(null);
  const popoverRef=useRef(null);
  const dialogRef=useRef(null);
  const rootRef=useRef(null);
  const dialogTitleId=useId();

  useEffect(()=>{
    setDraftState(loadPlanDraftState(active,draftKey));
    setError('');
    setSelectionBar(null);
    setStepQuickComposer(null);
    setEditingId(null);
  },[draftKey,active]);

  useEffect(()=>{
    if(!active||!draftKey||draftState.key!==draftKey)return;
    saveDraft(draftKey,{annotations:draftState.annotations,globalFeedback:draftState.globalFeedback});
  },[active,draftKey,draftState]);

  useEffect(()=>{
    if(!selectionBar||typeof document==='undefined')return undefined;
    const onPointerDown=event=>{
      if(popoverRef.current?.contains(event.target))return;
      if(inlineContentRef.current?.contains(event.target)||dialogContentRef.current?.contains(event.target))return;
      setSelectionBar(null);
    };
    document.addEventListener('pointerdown',onPointerDown);
    return()=>document.removeEventListener('pointerdown',onPointerDown);
  },[Boolean(selectionBar)]);

  useEffect(()=>{
    const dialog=dialogRef.current;
    if(!dialog)return;
    if(expanded&&!dialog.open){
      if(typeof dialog.showModal==='function')dialog.showModal();
    }else if(!expanded&&dialog.open){
      dialog.close();
    }
  },[expanded]);

  useEffect(()=>{
    if(!expanded)return;
    const dialog=dialogRef.current;
    if(!dialog||typeof HTMLDialogElement==='undefined'||'closedBy' in HTMLDialogElement.prototype)return;
    const outside=(event)=>{
      const rect=dialog.getBoundingClientRect();
      return event.clientX<rect.left||event.clientX>rect.right||event.clientY<rect.top||event.clientY>rect.bottom;
    };
    let pressedOutside=false;
    const onPointerDown=event=>{pressedOutside=outside(event);};
    const onPointerUp=event=>{if(pressedOutside&&outside(event))dialog.close();};
    dialog.addEventListener('pointerdown',onPointerDown);
    dialog.addEventListener('pointerup',onPointerUp);
    return()=>{
      dialog.removeEventListener('pointerdown',onPointerDown);
      dialog.removeEventListener('pointerup',onPointerUp);
    };
  },[expanded]);

  const tasks=Array.isArray(plan?.tasks)?plan.tasks:[];
  const taskNumber=id=>{const index=tasks.findIndex(task=>task.id===id);return index<0?id:String(index+1);};
  const compiledFeedback=useMemo(()=>formatPlanFeedback({annotations,globalFeedback}),[annotations,globalFeedback]);
  const hasFeedback=compiledFeedback.length>0;

  const goalAnnotations=useMemo(()=>annotations.filter(a=>a.targetKind==='goal'),[annotations]);
  const summaryAnnotations=useMemo(()=>annotations.filter(a=>a.targetKind==='summary'),[annotations]);
  const annotationsByStep=useMemo(()=>{
    const map=new Map();
    for(const item of annotations){
      if(item.targetKind!=='step')continue;
      const key=item.stepIndex;
      if(!map.has(key))map.set(key,[]);
      map.get(key).push(item);
    }
    return map;
  },[annotations]);

  const respond=async action=>{
    if(!active||inFlight.current)return;
    if(action==='supplement'&&!hasFeedback)return;
    if(action!=='cancel'&&compiledFeedback.length>MAX_PLAN_FEEDBACK_CHARS){
      setError(`当前批注与备注共 ${compiledFeedback.length} 字符，超过 ${MAX_PLAN_FEEDBACK_CHARS} 字符上限，请精简后再提交`);
      return;
    }
    inFlight.current=true;setSending(action);setError('');
    try{
      const payload=action==='cancel'?undefined:(compiledFeedback||undefined);
      await onRespond(plan.id,action,payload);
      if(draftKey)clearDraft(draftKey);
      setExpanded(false);
    }catch(failure){
      const msg=(failure?.message||'提交失败，请重试').replace(/^Error invoking remote method '[^']+': Error: /,'');
      setError(msg||'提交失败，请重试');
      inFlight.current=false;setSending('');
    }
  };

  const handleTextSelection=(containerRef,event)=>{
    if(!active||sending||typeof window==='undefined')return;
    if(popoverRef.current?.contains(event.target))return;
    const container=containerRef.current;
    if(!container)return;
    const sel=window.getSelection();
    if(!sel||sel.isCollapsed||!sel.rangeCount){
      setSelectionBar(null);
      return;
    }
    const raw=sel.toString();
    if(!raw||!raw.trim()){
      setSelectionBar(null);
      return;
    }
    const range=sel.getRangeAt(0);
    if(!container.contains(range.startContainer)||!container.contains(range.endContainer)){
      setSelectionBar(null);
      return;
    }
    const startEl=range.startContainer.nodeType===3?range.startContainer.parentElement:range.startContainer;
    const targetEl=startEl?.closest?.('[data-plan-target]');
    if(!targetEl||!container.contains(targetEl)){
      setSelectionBar(null);
      return;
    }
    const targetKind=targetEl.getAttribute('data-plan-target');
    const stepIndexAttr=targetEl.getAttribute('data-step-index');
    const taskIdAttr=targetEl.getAttribute('data-task-id');
    const rect=range.getBoundingClientRect();
    const vw=window.innerWidth||1200;
    const vh=window.innerHeight||800;
    const left=Math.max(12,Math.min(rect.left,vw-340));
    const placeBelow=rect.top<90;
    const top=placeBelow?Math.min(vh-180,rect.bottom+8):Math.max(12,rect.top-44);

    setSelectionBar({
      mode:'choose',
      type:'COMMENT',
      targetKind,
      stepIndex:stepIndexAttr?Number.parseInt(stepIndexAttr,10):undefined,
      taskId:taskIdAttr||undefined,
      originalText:raw.trim().slice(0,400),
      text:'',
      top,
      left
    });
  };

  const saveSelectionAnnotation=()=>{
    if(!selectionBar||sending)return;
    if(selectionBar.type==='COMMENT'&&!selectionBar.text.trim())return;
    const next={
      id:createAnnotationId(),
      type:selectionBar.type,
      targetKind:selectionBar.targetKind,
      stepIndex:selectionBar.stepIndex,
      taskId:selectionBar.taskId,
      originalText:selectionBar.originalText,
      text:selectionBar.text.trim()
    };
    setAnnotations(current=>[...current,next]);
    setSelectionBar(null);
    if(typeof window!=='undefined')window.getSelection()?.removeAllRanges?.();
  };

  const saveStepQuickAnnotation=()=>{
    if(!stepQuickComposer||sending||!stepQuickComposer.text.trim())return;
    const next={
      id:createAnnotationId(),
      type:'COMMENT',
      targetKind:'step',
      stepIndex:stepQuickComposer.stepIndex,
      taskId:stepQuickComposer.taskId,
      originalText:stepQuickComposer.originalText,
      text:stepQuickComposer.text.trim()
    };
    setAnnotations(current=>[...current,next]);
    setStepQuickComposer(null);
  };

  const removeAnnotation=id=>{
    if(sending)return;
    setAnnotations(current=>current.filter(item=>item.id!==id));
    if(editingId===id)setEditingId(null);
  };

  const commitEdit=id=>{
    if(sending)return;
    setAnnotations(current=>current.map(item=>{
      if(item.id!==id)return item;
      if(item.type==='COMMENT'&&!editText.trim())return item;
      return {...item,text:editText.trim()};
    }));
    setEditingId(null);
  };

  const handlePlanMarkClick=event=>{
    if(!active||sending)return;
    const el=event.target.closest?.('[data-annotation-id]');
    if(!el)return;
    const id=el.getAttribute('data-annotation-id');
    const found=annotations.find(item=>item.id===id);
    if(found){
      setEditingId(found.id);
      setEditText(found.text||'');
      if(typeof requestAnimationFrame==='function'){
        requestAnimationFrame(()=>{
          const scope=expanded?dialogRef.current:rootRef.current;
          scope?.querySelector?.(`[data-ann-item-id="${found.id}"]`)?.scrollIntoView?.({block:'nearest'});
        });
      }
    }
  };

  const renderPlanBody=(containerRef,interactive=false)=><div
    className={interactive?'plan-annotatable':'plan-readonly-body'}
    ref={containerRef}
    onClick={interactive?handlePlanMarkClick:undefined}
    onMouseUp={interactive?event=>handleTextSelection(containerRef,event):undefined}
    onKeyUp={interactive?event=>{if(event.shiftKey&&(event.key.startsWith('Arrow')||event.key==='Home'||event.key==='End'))handleTextSelection(containerRef,event);}:undefined}>
    <div className="plan-goal markdown" data-plan-target="goal">
      <AnnotatedMarkdown text={plan?.goal||''} annotations={interactive||!active?goalAnnotations:[]}/>
    </div>
    {plan?.summary&&<div className="plan-summary markdown" data-plan-target="summary">
      <AnnotatedMarkdown text={plan.summary} annotations={interactive||!active?summaryAnnotations:[]}/>
    </div>}
    <ol className="plan-steps">{tasks.map((task,index)=>{
      const stepNum=index+1;
      const stepAnns=annotationsByStep.get(stepNum)||[];
      const isQuickOpen=interactive&&stepQuickComposer?.stepIndex===stepNum;
      return <li key={task.id}>
        <div className="plan-step-row">
          <div className="plan-step-body markdown" data-plan-target="step" data-step-index={stepNum} data-task-id={task.id}>
            <AnnotatedMarkdown text={task.description||''} annotations={interactive?stepAnns:[]}/>
          </div>
          {interactive&&<button
            type="button"
            className="plan-step-annotate-btn"
            aria-label={`批注步骤 ${stepNum}`}
            disabled={!!sending}
            onClick={()=>{
              setSelectionBar(null);
              setStepQuickComposer(isQuickOpen?null:{
                stepIndex:stepNum,
                taskId:task.id,
                originalText:summarizeStepQuote(task.description),
                text:''
              });
            }}>
            <MessageSquarePlus size={14}/>
            <span>批注</span>
          </button>}
        </div>
        {task.dependencies?.length>0&&<p className="plan-dependency">在步骤 {task.dependencies.map(taskNumber).join('、')} 完成后执行</p>}
        {isQuickOpen&&<div className="plan-step-quick-form">
          <p className="plan-quote-preview">针对步骤 {stepNum}：「{stepQuickComposer.originalText}」</p>
          <textarea
            autoFocus
            rows={2}
            aria-label={`步骤 ${stepNum} 批注内容`}
            placeholder="输入针对此步骤的修改意见或约束…"
            value={stepQuickComposer.text}
            disabled={!!sending}
            onChange={event=>setStepQuickComposer(current=>current?{...current,text:event.target.value}:null)}
            onKeyDown={event=>{
              if((event.metaKey||event.ctrlKey)&&event.key==='Enter'){event.preventDefault();saveStepQuickAnnotation();}
              if(event.key==='Escape'){event.preventDefault();event.stopPropagation();setStepQuickComposer(null);}
            }}/>
          <div className="plan-inline-form-actions">
            <button type="button" onClick={()=>setStepQuickComposer(null)}>取消</button>
            <button type="button" className="primary" disabled={!stepQuickComposer.text.trim()||!!sending} onClick={saveStepQuickAnnotation}>添加批注</button>
          </div>
        </div>}
      </li>;
    })}</ol>
  </div>;

  const renderAnnotationList=()=>annotations.length>0&&<div className="plan-annotation-list" aria-label="已添加的计划批注">
    <div className="plan-annotation-list-head">
      <strong>已批注 {annotations.length} 处</strong>
      <button type="button" className="plan-text-action" disabled={!!sending} onClick={()=>{setAnnotations([]);setEditingId(null);}}>清空批注</button>
    </div>
    <ul>{annotations.map(item=><li key={item.id} data-ann-item-id={item.id} className={'plan-ann-item '+(item.type==='DELETION'?'is-deletion':'is-comment')}>
      <div className="plan-ann-meta">
        <span className="plan-ann-badge">{item.type==='DELETION'?'建议删除':'批注'}</span>
        <span className="plan-ann-target">{targetLabel(item)}</span>
        <div className="plan-ann-ops">
          <button type="button" className="icon-button" aria-label="编辑批注" disabled={!!sending} onClick={()=>{setEditingId(item.id);setEditText(item.text||'');}}><Pencil size={13}/></button>
          <button type="button" className="icon-button" aria-label="删除批注" disabled={!!sending} onClick={()=>removeAnnotation(item.id)}><Trash2 size={13}/></button>
        </div>
      </div>
      <blockquote className="plan-ann-quote">{item.originalText}</blockquote>
      {editingId===item.id?<div className="plan-ann-edit">
        <textarea
          autoFocus
          rows={2}
          aria-label="编辑批注内容"
          value={editText}
          disabled={!!sending}
          onChange={event=>setEditText(event.target.value)}
          onKeyDown={event=>{
            if((event.metaKey||event.ctrlKey)&&event.key==='Enter'){event.preventDefault();commitEdit(item.id);}
            if(event.key==='Escape'){event.preventDefault();event.stopPropagation();setEditingId(null);}
          }}/>
        <div className="plan-inline-form-actions">
          <button type="button" onClick={()=>setEditingId(null)}>取消</button>
          <button type="button" className="primary" disabled={item.type==='COMMENT'&&!editText.trim()} onClick={()=>commitEdit(item.id)}>保存</button>
        </div>
      </div>:(item.text?<p className="plan-ann-text">{item.text}</p>:<p className="plan-ann-text muted">建议删去此片段</p>)}
    </li>)}</ul>
  </div>;

  const openPopoverInput=type=>{
    const vh=typeof window!=='undefined'?(window.innerHeight||800):800;
    setSelectionBar(current=>current?{...current,mode:'input',type,top:Math.max(12,Math.min(current.top,vh-260))}:null);
  };

  const renderSelectionPopover=()=>selectionBar&&<div
    ref={popoverRef}
    className="plan-selection-popover"
    style={{top:`${selectionBar.top}px`,left:`${selectionBar.left}px`}}>
    {selectionBar.mode==='choose'?<div className="plan-popover-buttons">
      <button type="button" onMouseDown={e=>e.preventDefault()} onClick={()=>openPopoverInput('COMMENT')}>
        <Highlighter size={14}/><span>添加批注</span>
      </button>
      <button type="button" className="is-danger" onMouseDown={e=>e.preventDefault()} onClick={()=>openPopoverInput('DELETION')}>
        <Strikethrough size={14}/><span>红线删除</span>
      </button>
      <button type="button" className="icon-button" aria-label="关闭划词浮条" onClick={()=>setSelectionBar(null)}><X size={13}/></button>
    </div>:<div className="plan-popover-form">
      <div className="plan-popover-head">
        <span>{selectionBar.type==='DELETION'?'红线删除建议':'添加划词批注'}</span>
        <button type="button" className="icon-button" aria-label="关闭划词输入" onClick={()=>setSelectionBar(null)}><X size={13}/></button>
      </div>
      <p className="plan-quote-preview">「{selectionBar.originalText}」</p>
      <textarea
        autoFocus
        rows={2}
        aria-label={selectionBar.type==='DELETION'?'删除说明（可选）':'划词批注内容'}
        placeholder={selectionBar.type==='DELETION'?'可选：说明删除理由或替代做法（留空直接建议删除）':'输入修改或约束意见…'}
        value={selectionBar.text}
        onChange={event=>setSelectionBar(current=>current?{...current,text:event.target.value}:null)}
        onKeyDown={event=>{
          if(event.key==='Enter'&&!event.shiftKey&&!event.nativeEvent.isComposing){event.preventDefault();saveSelectionAnnotation();}
          if(event.key==='Escape'){event.preventDefault();event.stopPropagation();setSelectionBar(null);}
        }}/>
      <div className="plan-inline-form-actions">
        <button type="button" onClick={()=>setSelectionBar(null)}>取消</button>
        <button
          type="button"
          className="primary"
          disabled={selectionBar.type==='COMMENT'&&!selectionBar.text.trim()}
          onClick={saveSelectionAnnotation}>
          {selectionBar.type==='DELETION'?'确认标删':'保存批注'}
        </button>
      </div>
    </div>}
  </div>;

  const renderControls=(idSuffix='')=><>
    <label className="plan-feedback" htmlFor={`plan-global-${plan?.id||'x'}${idSuffix}`}>全局意见 / 执行备注（可选）</label>
    <textarea
      id={`plan-global-${plan?.id||'x'}${idSuffix}`}
      className="plan-feedback-input"
      aria-label="计划修改意见"
      placeholder="例如：先给出文档大纲，暂不创建文件；或直接选中上方步骤文字添加批注"
      rows={2}
      maxLength={MAX_PLAN_FEEDBACK_CHARS}
      value={globalFeedback}
      disabled={!!sending}
      onChange={event=>setGlobalFeedback(event.target.value)}/>
    {error&&<p className="plan-error" role="alert">{error}</p>}
    <div className="plan-actions">
      <button type="button" disabled={!!sending} onClick={()=>respond('cancel')}>取消计划</button>
      <div>
        <button type="button" disabled={!!sending||!hasFeedback} onClick={()=>respond('supplement')}>
          {sending==='supplement'?<LoaderCircle size={14} className="spin"/>:null}更新计划
        </button>
        <button type="button" className="primary" disabled={!!sending} onClick={()=>respond('execute')}>
          {sending==='execute'?<LoaderCircle size={14} className="spin"/>:null}{hasFeedback?'同意执行（含备注）':'同意并执行'}
        </button>
      </div>
    </div>
  </>;

  if(!active){
    const resAction=typeof resolution==='string'?resolution:resolution?.action;
    const resFeedback=typeof resolution==='string'?'':(resolution?.feedback||'').trim();
    let statusText='计划确认已失效';
    if(resAction==='execute')statusText=resFeedback?'你已同意执行（含备注）':'你已同意执行';
    else if(resAction==='supplement')statusText='已提交修改意见';
    else if(resAction==='cancel')statusText='已取消计划';

    return <details className="plan-review plan-history">
      <summary><FileText size={17}/><span>执行计划</span><small>{statusText}</small><ChevronRight size={15}/></summary>
      <div className="plan-history-body">
        {renderPlanBody(inlineContentRef,false)}
        {resFeedback&&<div className="plan-history-feedback">
          <strong>{resAction==='execute'?'附带执行备注':'提交的修改意见'}</strong>
          <pre>{resFeedback}</pre>
        </div>}
      </div>
    </details>;
  }

  return <section ref={rootRef} className="plan-review" aria-label="确认执行计划" aria-busy={!!sending}>
    <div className="plan-heading">
      <FileText size={20}/>
      <h2>执行计划</h2>
      <button type="button" className="plan-expand-btn" onClick={()=>{setSelectionBar(null);setExpanded(true);}} aria-label="展开精审">
        <Maximize2 size={14}/><span>展开精审</span>
      </button>
    </div>
    <p className="plan-selection-tip">在计划文本中划词可添加批注或红线删除，也可点击步骤右侧“批注”。</p>
    {renderPlanBody(inlineContentRef,true)}
    {!expanded&&renderSelectionPopover()}
    {renderAnnotationList()}
    <p className="plan-boundary">确认前不会执行计划中的文件修改或命令。“帮我审批”不会代替这次确认。</p>
    {renderControls('-inline')}

    {expanded&&<dialog
      ref={dialogRef}
      className="plan-review-dialog"
      closedby="any"
      aria-labelledby={dialogTitleId}
      onCancel={event=>{
        if(selectionBar||stepQuickComposer||editingId!==null){
          event.preventDefault();
          setSelectionBar(null);
          setStepQuickComposer(null);
          setEditingId(null);
        }
      }}
      onClose={()=>{if(dialogRef.current)dialogRef.current.returnValue='';setExpanded(false);setSelectionBar(null);}}>
      <div className="plan-dialog-header">
        <div className="plan-dialog-title">
          <FileText size={19}/>
          <h2 id={dialogTitleId}>计划双栏精审</h2>
          {annotations.length>0&&<span className="plan-count-pill">{annotations.length} 条批注</span>}
        </div>
        <button type="button" className="icon-button" aria-label="收起精审" onClick={()=>dialogRef.current?.close()}>
          <X size={18}/>
        </button>
      </div>
      <div className="plan-dialog-grid">
        <div className="plan-dialog-content">
          {renderPlanBody(dialogContentRef,true)}
          {renderSelectionPopover()}
        </div>
        <aside className="plan-dialog-sidebar">
          {renderAnnotationList()}
          {!annotations.length&&<p className="plan-dialog-empty">在左侧划词或点击步骤右侧「批注」，批注会集中汇总在这里。</p>}
          <p className="plan-boundary">确认前不会执行计划中的文件修改或命令。“帮我审批”不会代替这次确认。</p>
          {renderControls('-dialog')}
        </aside>
      </div>
    </dialog>}
  </section>;
}
