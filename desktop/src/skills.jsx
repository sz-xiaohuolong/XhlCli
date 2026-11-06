import React,{useEffect,useRef,useState} from 'react';
import {Blocks,Search,RefreshCw,FolderOpen,LoaderCircle,AlertCircle,Check} from 'lucide-react';
import './skills.css';

const sources={USER:'个人',PROJECT:'项目',BUILTIN:'内置'};
const filters=[['ALL','全部'],['USER','个人'],['PROJECT','项目'],['BUILTIN','内置']];
export function SkillsPage({projects,project,notify}){
  const [projectId,setProjectId]=useState(project?.id||'');
  const [skills,setSkills]=useState([]),[query,setQuery]=useState(''),[filter,setFilter]=useState('ALL');
  const [loading,setLoading]=useState(true),[saving,setSaving]=useState(''),[error,setError]=useState('');
  const generation=useRef(0);
  const context=projectId?{projectId}:{};
  const fail=failure=>setError(failure.message.replace(/^Error invoking remote method '[^']+': Error: /,''));
  async function reload(){
    const id=++generation.current;setLoading(true);setError('');setSkills([]);
    try{const result=await window.xhl.request('skills.list',context);if(id===generation.current)setSkills(result.skills);}
    catch(failure){if(id===generation.current)fail(failure);}
    finally{if(id===generation.current)setLoading(false);}
  }
  useEffect(()=>{reload();return()=>{generation.current++;};},[projectId]);
  async function toggle(skill){
    if(saving)return;setSaving(skill.name);setError('');
    const id=++generation.current;
    try{
      const result=await window.xhl.request('skills.set',{...context,name:skill.name,enabled:!skill.enabled});
      if(id===generation.current){setSkills(result.skills);notify(`${skill.enabled?'已停用':'已启用'} ${skill.name}，从下一轮任务生效`);}
    }catch(failure){if(id===generation.current)fail(failure);}
    finally{if(id===generation.current)setSaving('');}
  }
  const search=query.trim().toLowerCase();
  const visible=skills.filter(skill=>(filter==='ALL'||skill.source===filter)&&(!search||`${skill.name} ${skill.description}`.toLowerCase().includes(search)));
  return <section className="skills-page" aria-labelledby="skills-title">
    <div className="skills-content">
      <header className="skills-heading"><h1 id="skills-title">技能</h1><p>为助手选择工作方法，随时启用或停用。</p></header>
      <div className="skills-toolbar">
        <label className="skills-search"><Search size={19}/><input aria-label="搜索技能" placeholder="搜索技能" value={query} onChange={event=>setQuery(event.target.value)}/></label>
        <button className="icon-button" aria-label="刷新技能" title="刷新技能" disabled={loading||!!saving} onClick={reload}><RefreshCw size={20} className={loading?'spin':''}/></button>
        <button className="skills-folder" onClick={()=>window.xhl.openSkillsFolder().catch(fail)}><FolderOpen size={17}/>打开技能文件夹</button>
      </div>
      <div className="skills-controls">
        <div className="skills-filters" aria-label="技能来源">{filters.map(([value,label])=><button key={value} aria-pressed={filter===value} onClick={()=>setFilter(value)}>{label}</button>)}</div>
        <label className="skills-project">项目<select aria-label="技能所属项目" value={projectId} disabled={!!saving} onChange={event=>setProjectId(event.target.value)}><option value="">仅个人与内置</option>{projects.map(item=><option key={item.id} value={item.id}>{item.name}</option>)}</select></label>
      </div>
      <p className="skills-scope">开关在此设备的桌面聊天中通用，同名技能共用设置。从下一轮任务生效，已进入聊天记录的内容不会删除。</p>
      {error&&<div className="skills-error" role="alert"><AlertCircle size={18}/><span>{error}</span><button disabled={loading||!!saving} onClick={reload}>重试</button></div>}
      <div className="skills-list-title"><h2>已安装</h2>{!loading&&<span>{visible.length} 项</span>}</div>
      {loading?<div className="skills-empty" role="status"><LoaderCircle size={24} className="spin"/><p>正在读取技能…</p></div>:visible.length?<ul className="skills-list">{visible.map(skill=><li className={'skill-row'+(!skill.enabled?' skill-off':'')} key={skill.name}>
        <div className="skill-symbol"><Blocks size={26} strokeWidth={1.5}/></div>
        <div className="skill-info"><div className="skill-title"><h3 title={skill.name}>{skill.name}</h3><span>{sources[skill.source]||skill.source}</span></div>{skill.healthy&&<p title={skill.description}>{skill.description||'暂无描述'}</p>}{!skill.healthy&&<div className="skill-invalid"><AlertCircle size={14}/><span>{skill.error||'技能文件有误，请修复后刷新'}</span></div>}</div>
        <div className="skill-state"><span>{saving===skill.name?'正在保存':!skill.healthy?'文件有误':skill.enabled?'已启用':'已停用'}</span><button type="button" className="skill-switch" role="switch" aria-label={`启用技能 ${skill.name}`} aria-checked={skill.enabled} aria-busy={saving===skill.name} disabled={!!saving||!skill.healthy} onClick={()=>toggle(skill)}><span>{saving===skill.name?<LoaderCircle size={12} className="spin"/>:skill.enabled?<Check size={12}/>:null}</span></button></div>
      </li>)}</ul>:!error&&<div className="skills-empty"><Blocks size={28}/><h3>{search?'没有找到匹配的技能':'这里还没有技能'}</h3><p>{search?'换个名称或关键词试试。':filter==='PROJECT'&&!projectId?'选择一个项目，查看该项目中的技能。':'将包含 SKILL.md 的技能文件夹放入对应目录，然后点击刷新。'}</p>{search&&<button onClick={()=>setQuery('')}>清除搜索</button>}</div>}
      <p className="skills-footnote">添加个人技能：点击“打开技能文件夹”，放入包含 SKILL.md 的文件夹后刷新。同名技能优先使用项目版本，其次是个人版本。</p>
    </div>
  </section>;
}
