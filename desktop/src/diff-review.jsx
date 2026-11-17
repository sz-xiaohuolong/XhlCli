import React,{useEffect,useMemo,useRef,useState} from 'react';
import './diff-review.css';
import {Check,ExternalLink,Files,FileText,LoaderCircle,MessageSquarePlus,Pencil,Send,Trash2,Undo2,X} from 'lucide-react';
import {buildDiffSelectionAnchor,clearDraft,diffDraftKey,formatDiffFeedback,formatDiffLineLabel,loadDiffWidth,loadDraft,parseUnifiedDiff,resolveDiffCommentRowIndex,saveDiffWidth,saveDraft} from './annotations.mjs';

const WIDTH_OPTIONS=[
  {id:'standard',label:'标准'},
  {id:'wide',label:'加宽'},
  {id:'full',label:'全屏'}
];

function createCommentId(){
  return 'diffc_'+Date.now().toString(36)+'_'+Math.random().toString(36).slice(2,7);
}

function loadDiffDraftState(key){
  if(!key)return {key:'',comments:[],generalNote:''};
  const saved=loadDraft(key);
  return {
    key,
    comments:Array.isArray(saved?.comments)?saved.comments:[],
    generalNote:typeof saved?.generalNote==='string'?saved.generalNote:''
  };
}

export function DiffReviewPanel({
  diff,
  diffLoading=false,
  sessionId,
  directoryBusy=false,
  onClose,
  onUndo,
  onOpenFile,
  onInsertFeedback,
  onSendFeedback
}){
  const turnId=diff?.turn?.id||'';
  const draftKey=useMemo(()=>diffDraftKey(sessionId,turnId),[sessionId,turnId]);
  const [widthMode,setWidthMode]=useState(()=>loadDiffWidth());
  const [draftState,setDraftState]=useState(()=>loadDiffDraftState(draftKey));
  const comments=draftState.comments;
  const generalNote=draftState.generalNote;
  const [selection,setSelection]=useState(null);
  const [isDragging,setIsDragging]=useState(false);
  const [composerText,setComposerText]=useState('');
  const [editingId,setEditingId]=useState(null);
  const [editText,setEditText]=useState('');
  const [sendingDirect,setSendingDirect]=useState(false);

  const dragState=useRef({active:false,filePath:null,anchorIdx:-1});

  useEffect(()=>{
    setDraftState(loadDiffDraftState(draftKey));
    setSelection(null);
    setIsDragging(false);
    setComposerText('');
    setEditingId(null);
  },[draftKey]);

  useEffect(()=>{
    if(!draftKey||draftState.key!==draftKey)return;
    saveDraft(draftKey,{comments:draftState.comments,generalNote:draftState.generalNote});
  },[draftKey,draftState]);

  useEffect(()=>{
    if(typeof window==='undefined')return undefined;
    const stopDrag=()=>{
      if(!dragState.current.active)return;
      dragState.current={active:false,filePath:null,anchorIdx:-1};
      setIsDragging(false);
    };
    window.addEventListener('pointerup',stopDrag);
    window.addEventListener('pointercancel',stopDrag);
    return()=>{
      window.removeEventListener('pointerup',stopDrag);
      window.removeEventListener('pointercancel',stopDrag);
    };
  },[]);

  const files=Array.isArray(diff?.files)?diff.files:[];
  const parsedFiles=useMemo(()=>files.map(file=>({
    ...file,
    lines:parseUnifiedDiff(file.diff)
  })),[files]);

  const activeFileLines=useMemo(()=>{
    if(!selection)return null;
    return parsedFiles.find(item=>item.path===selection.filePath)?.lines||null;
  },[selection,parsedFiles]);

  const selectionAnchor=useMemo(()=>{
    if(!selection||!activeFileLines)return null;
    return buildDiffSelectionAnchor(selection.filePath,activeFileLines,selection.startIdx,selection.endIdx);
  },[selection,activeFileLines]);

  const compiledFeedback=useMemo(()=>formatDiffFeedback({comments,generalNote}),[comments,generalNote]);
  const hasFeedback=compiledFeedback.length>0;
  const undone=Boolean(diff?.turn?.undone);

  const selectLine=(filePath,rowIndex,shiftKey=false)=>{
    setSelection(current=>{
      if(shiftKey&&current?.filePath===filePath){
        return {
          filePath,
          anchorIdx:current.anchorIdx,
          startIdx:Math.min(current.anchorIdx,rowIndex),
          endIdx:Math.max(current.anchorIdx,rowIndex)
        };
      }
      return {filePath,anchorIdx:rowIndex,startIdx:rowIndex,endIdx:rowIndex};
    });
    setEditingId(null);
  };

  const setComments=updater=>setDraftState(current=>({
    ...current,
    key:draftKey,
    comments:typeof updater==='function'?updater(current.comments):updater
  }));
  const setGeneralNote=value=>setDraftState(current=>({
    ...current,
    key:draftKey,
    generalNote:typeof value==='function'?value(current.generalNote):value
  }));

  const handleGutterPointerDown=(event,filePath,rowIndex)=>{
    if(event.button!==0)return;
    event.preventDefault();
    if(event.shiftKey&&selection?.filePath===filePath){
      selectLine(filePath,rowIndex,true);
      return;
    }
    dragState.current={active:true,filePath,anchorIdx:rowIndex};
    setIsDragging(true);
    selectLine(filePath,rowIndex,false);
  };

  const handleRowPointerEnter=(filePath,rowIndex)=>{
    if(!dragState.current.active||dragState.current.filePath!==filePath)return;
    const anchorIdx=dragState.current.anchorIdx;
    setSelection({
      filePath,
      anchorIdx,
      startIdx:Math.min(anchorIdx,rowIndex),
      endIdx:Math.max(anchorIdx,rowIndex)
    });
  };

  const handleCodeCellClick=(event,filePath,rowIndex)=>{
    if(typeof window!=='undefined'){
      const selText=window.getSelection()?.toString()||'';
      if(selText.length>0)return;
    }
    selectLine(filePath,rowIndex,event.shiftKey);
  };

  const addComment=()=>{
    if(!selectionAnchor||!composerText.trim())return;
    const next={
      id:createCommentId(),
      ...selectionAnchor,
      text:composerText.trim()
    };
    setComments(current=>[...current,next]);
    setSelection(null);
    setComposerText('');
  };

  const removeComment=id=>{
    setComments(current=>current.filter(item=>item.id!==id));
    if(editingId===id)setEditingId(null);
  };

  const saveEdit=id=>{
    if(!editText.trim())return;
    setComments(current=>current.map(item=>item.id===id?{...item,text:editText.trim()}:item));
    setEditingId(null);
  };

  const clearAll=()=>{
    setComments([]);
    setGeneralNote('');
    setSelection(null);
    setComposerText('');
    setEditingId(null);
    if(draftKey)clearDraft(draftKey);
  };

  const changeWidth=mode=>{
    setWidthMode(mode);
    saveDiffWidth(mode);
  };

  const handleInsert=()=>{
    if(!hasFeedback||diffLoading)return;
    onInsertFeedback(compiledFeedback);
    const isOverlay=widthMode==='full'||(typeof window!=='undefined'&&window.innerWidth<=1150);
    if(isOverlay&&onClose)onClose();
  };

  const handleSendDirect=async()=>{
    if(!hasFeedback||diffLoading||directoryBusy||undone||sendingDirect)return;
    setSendingDirect(true);
    try{
      await onSendFeedback(compiledFeedback);
    }finally{
      setSendingDirect(false);
    }
  };

  const panelClass='diff-panel'+(widthMode==='wide'?' diff-panel-wide':widthMode==='full'?' diff-panel-full':'');

  return <aside className={panelClass} aria-label="代码变更审阅">
    <header className="diff-header">
      <div className="diff-header-title">
        <Files size={17}/>
        <strong>本轮变更</strong>
        {comments.length>0&&<span className="diff-badge">{comments.length} 条批注</span>}
      </div>
      <div className="diff-header-actions">
        <div className="diff-width-group" role="group" aria-label="Diff 面板宽度">
          {WIDTH_OPTIONS.map(opt=><button
            key={opt.id}
            type="button"
            aria-pressed={widthMode===opt.id}
            className={widthMode===opt.id?'active':''}
            onClick={()=>changeWidth(opt.id)}>
            {opt.label}
          </button>)}
        </div>
        <button type="button" className="icon-button" title="关闭变更面板" aria-label="关闭变更面板" onClick={onClose}>
          <X size={18}/>
        </button>
      </div>
    </header>

    <div className="diff-toolbar">
      <span>{files.length} 个文件</span>
      <button type="button" disabled={undone||diffLoading||directoryBusy||!files.length} onClick={onUndo}>
        <Undo2 size={14}/>
        <span>{undone?'已撤销':'撤销本轮'}</span>
      </button>
    </div>

    {undone&&<div className="diff-undone-notice" role="status">
      本轮改动已撤销，仅保留差异查看记录；如需让助手修改，请汇总填入输入框确认后再发送。
    </div>}

    <div className="diff-scroll">
      {diffLoading?<p className="muted">正在读取差异…</p>:!files.length?<div className="diff-empty">
        <Check size={24}/>
        <p>本轮没有文件变化</p>
      </div>:parsedFiles.map(file=>{
        const fileComments=comments.filter(c=>c.filePath===file.path);
        const byRow=new Map();
        const unanchored=[];
        for(const item of fileComments){
          const rowIdx=resolveDiffCommentRowIndex(item,file.lines);
          if(rowIdx==null)unanchored.push(item);
          else{
            if(!byRow.has(rowIdx))byRow.set(rowIdx,[]);
            byRow.get(rowIdx).push(item);
          }
        }
        const selectedRange=selection?.filePath===file.path&&selectionAnchor?selectionAnchor.selectedRowRange:null;

        return <details className="diff-file" key={file.path} open>
          <summary>
            <FileText size={14}/>
            <span className="diff-file-path">{file.path}</span>
            {fileComments.length>0&&<span className="diff-file-count">{fileComments.length}</span>}
            <small>{file.type}</small>
          </summary>
          <div className="diff-file-actions">
            <button type="button" className="open-file" onClick={()=>onOpenFile(file.path)}>
              <span>打开文件</span>
              <ExternalLink size={12}/>
            </button>
            <span className="diff-file-tip">点击或 Shift / 拖拽行号区评论</span>
          </div>

          {unanchored.length>0&&<div className="diff-unanchored-list">
            {unanchored.map(item=><DiffCommentCard
              key={item.id}
              comment={item}
              editing={editingId===item.id}
              editText={editText}
              onChangeEdit={setEditText}
              onStartEdit={()=>{setEditingId(item.id);setEditText(item.text);}}
              onSaveEdit={()=>saveEdit(item.id)}
              onCancelEdit={()=>setEditingId(null)}
              onDelete={()=>removeComment(item.id)}/>)}
          </div>}

          <div className="diff-code" role="table" aria-label={`${file.path} 差异代码`}>
            {file.lines.map(line=>{
              const isSelected=Boolean(selectedRange&&line.commentable&&line.index>=selectedRange[0]&&line.index<=selectedRange[1]);
              const isComposerAnchor=Boolean(selectionAnchor&&selectionAnchor.filePath===file.path&&selectionAnchor.anchorRowIndex===line.index);
              const rowComments=byRow.get(line.index)||[];
              const prefix=line.type==='add'?'+':line.type==='del'?'-':line.type==='context'?' ':'';
              const rowClass='diff-row diff-row-'+line.type+(line.commentable?' is-commentable':'')+(isSelected?' is-selected':'');

              return <React.Fragment key={line.index}>
                <div
                  className={rowClass}
                  role="row"
                  onPointerEnter={()=>handleRowPointerEnter(file.path,line.index)}>
                  <div
                    className="diff-gutter"
                    onPointerDown={event=>line.commentable&&handleGutterPointerDown(event,file.path,line.index)}>
                    <span className="diff-ln old">{line.oldLine??''}</span>
                    <span className="diff-ln new">{line.newLine??''}</span>
                    {line.commentable&&<button
                      type="button"
                      className="diff-line-add-btn"
                      aria-label={`评论 ${file.path} ${line.newLine!=null?`第 ${line.newLine} 行`:`原第 ${line.oldLine} 行`}`}
                      onClick={event=>{event.stopPropagation();selectLine(file.path,line.index,event.shiftKey);}}>
                      <MessageSquarePlus size={12}/>
                    </button>}
                  </div>
                  <div
                    className="diff-line-code"
                    role="cell"
                    onClick={event=>line.commentable&&handleCodeCellClick(event,file.path,line.index)}>
                    <code>{prefix}{line.content}</code>
                  </div>
                </div>

                {rowComments.length>0&&<div className="diff-inline-thread">
                  {rowComments.map(item=><DiffCommentCard
                    key={item.id}
                    comment={item}
                    editing={editingId===item.id}
                    editText={editText}
                    onChangeEdit={setEditText}
                    onStartEdit={()=>{setEditingId(item.id);setEditText(item.text);}}
                    onSaveEdit={()=>saveEdit(item.id)}
                    onCancelEdit={()=>setEditingId(null)}
                    onDelete={()=>removeComment(item.id)}/>)}
                </div>}

                {isComposerAnchor&&!isDragging&&<div className="diff-inline-composer">
                  <div className="diff-composer-head">
                    <span>评论 <code>{file.path}</code> · <strong>{formatDiffLineLabel(selectionAnchor)}</strong></span>
                    <button type="button" className="icon-button" aria-label="取消行内评论" onClick={()=>setSelection(null)}><X size={13}/></button>
                  </div>
                  <textarea
                    autoFocus
                    rows={2}
                    aria-label="输入代码修改意见"
                    placeholder="输入修改建议（⌘/Ctrl + Enter 添加，Esc 取消）"
                    value={composerText}
                    onChange={event=>setComposerText(event.target.value)}
                    onKeyDown={event=>{
                      if((event.metaKey||event.ctrlKey)&&event.key==='Enter'){event.preventDefault();addComment();}
                      if(event.key==='Escape'){event.preventDefault();event.stopPropagation();setSelection(null);}
                    }}/>
                  <div className="diff-composer-actions">
                    <button type="button" onClick={()=>setSelection(null)}>取消</button>
                    <button type="button" className="primary" disabled={!composerText.trim()} onClick={addComment}>添加评论</button>
                  </div>
                </div>}
              </React.Fragment>;
            })}
          </div>
        </details>;
      })}
    </div>

    <footer className="diff-footer">
      <div className="diff-footer-meta">
        {comments.length>0?<span>已添加 {comments.length} 条行内评论</span>:<span className="diff-hint">点击或 Shift / 拖拽行号区连选代码行即可添加批注</span>}
        {(comments.length>0||generalNote.trim())&&<button type="button" className="diff-clear-btn" onClick={clearAll}>清空评论</button>}
      </div>
      <textarea
        className="diff-general-note"
        aria-label="代码变更总体说明"
        placeholder="可选：补充整体说明或跨文件修复要求…"
        rows={2}
        value={generalNote}
        onChange={event=>setGeneralNote(event.target.value)}/>
      {directoryBusy&&hasFeedback&&<p className="diff-busy-hint">当前项目有任务运行中，可先「汇总填入输入框」暂存。</p>}
      <div className="diff-footer-actions">
        <button
          type="button"
          className="primary"
          disabled={!hasFeedback||diffLoading}
          onClick={handleInsert}>
          汇总填入输入框
        </button>
        <button
          type="button"
          disabled={!hasFeedback||diffLoading||directoryBusy||undone||sendingDirect}
          onClick={handleSendDirect}>
          {sendingDirect?<LoaderCircle size={14} className="spin"/>:<Send size={14}/>}
          <span>直接发送给 Agent</span>
        </button>
      </div>
    </footer>
  </aside>;
}

function DiffCommentCard({comment,editing,editText,onChangeEdit,onStartEdit,onSaveEdit,onCancelEdit,onDelete}){
  return <div className="diff-comment-card">
    <div className="diff-comment-head">
      <span className="diff-line-pill">{formatDiffLineLabel(comment)}</span>
      <div className="diff-comment-ops">
        <button type="button" className="icon-button" aria-label="编辑评论" onClick={onStartEdit}><Pencil size={13}/></button>
        <button type="button" className="icon-button" aria-label="删除评论" onClick={onDelete}><Trash2 size={13}/></button>
      </div>
    </div>
    {editing?<div className="diff-comment-edit">
      <textarea
        autoFocus
        rows={2}
        aria-label="编辑行内评论"
        value={editText}
        onChange={event=>onChangeEdit(event.target.value)}
        onKeyDown={event=>{
          if((event.metaKey||event.ctrlKey)&&event.key==='Enter'){event.preventDefault();onSaveEdit();}
          if(event.key==='Escape'){event.preventDefault();event.stopPropagation();onCancelEdit();}
        }}/>
      <div className="diff-composer-actions">
        <button type="button" onClick={onCancelEdit}>取消</button>
        <button type="button" className="primary" disabled={!editText.trim()} onClick={onSaveEdit}>保存</button>
      </div>
    </div>:<p className="diff-comment-text">{comment.text}</p>}
  </div>;
}
