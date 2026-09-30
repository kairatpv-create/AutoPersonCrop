import {useEffect,useMemo,useState} from 'react'
import type {User} from 'firebase/auth'
import type {OrganizerState,Note,Project,ProjectStage,MoneyEntry} from './types'
import {loadState,saveState,uid,now} from './store'
import {cloudConfigured,loginGoogle,logout,watchUser,pushState,subscribeCloud,mergeCloud} from './firebase'
import './styles.css'

type Section='notes'|'projects'|'money'|'settings'
const d=(ms:number)=>ms?new Date(ms).toISOString().slice(0,10):''
const fromDate=(s:string)=>s?new Date(`${s}T12:00:00`).getTime():0

export default function App(){
  const [state,setState]=useState<OrganizerState>(()=>loadState())
  const [section,setSection]=useState<Section>('notes')
  const [calendar,setCalendar]=useState(false)
  const [user,setUser]=useState<User|null>(null)
  const [syncText,setSyncText]=useState(cloudConfigured?'Облако готово':'Локальный режим')
  useEffect(()=>watchUser(setUser),[])
  useEffect(()=>saveState(state),[state])
  useEffect(()=>{if(!user)return;setSyncText('Синхронизация…');pushState(user,state).then(()=>setSyncText('Синхронизировано')).catch(()=>setSyncText('Ошибка синхронизации'))},[user,state.revision])
  useEffect(()=>{if(!user)return;return subscribeCloud(user,rows=>setState(s=>mergeCloud(s,rows)))},[user])
  const commit=(fn:(s:OrganizerState)=>OrganizerState)=>setState(s=>({...fn(structuredClone(s)),revision:s.revision+1}))
  return <div className="app">
    <aside><div className="brand">Органайзер Про <small>WEB 0.1</small></div>
      {(['notes','projects','money','settings'] as Section[]).map(x=><button key={x} className={section===x?'nav active':'nav'} onClick={()=>setSection(x)}>{x==='notes'?'Заметки':x==='projects'?'Проекты':x==='money'?'Финансы':'Настройки'}</button>)}
      <div className="sync">{syncText}</div>
    </aside>
    <main><header><button className="calendarBtn" onClick={()=>setCalendar(true)}>▦ Календарь</button><div className="grow"/>{user?<><span className="user">{user.displayName||user.email}</span><button onClick={()=>logout()}>Выйти</button></>:<button disabled={!cloudConfigured} onClick={()=>loginGoogle().catch(()=>setSyncText('Не настроено облако'))}>Войти через Google</button>}</header>
      {section==='notes'&&<Notes state={state} commit={commit}/>} {section==='projects'&&<Projects state={state} commit={commit}/>} {section==='money'&&<Money state={state} commit={commit}/>} {section==='settings'&&<Settings user={user} configured={cloudConfigured}/>} 
    </main>
    {calendar&&<Calendar state={state} close={()=>setCalendar(false)}/>} 
  </div>
}

function Notes({state,commit}:{state:OrganizerState;commit:(f:(s:OrganizerState)=>OrganizerState)=>void}){
 const notes=state.notes.filter(x=>!x.deletedAt); const [selected,setSelected]=useState(notes[0]?.id||''); const cur=notes.find(x=>x.id===selected)
 const create=()=>{const t=now();const n:Note={id:uid(),title:'Новая заметка',body:'',colorKey:'blue',createdAt:t,updatedAt:t};commit(s=>{s.notes.unshift(n);return s});setSelected(n.id)}
 const patch=(p:Partial<Note>)=>cur&&commit(s=>{const i=s.notes.findIndex(x=>x.id===cur.id);s.notes[i]={...s.notes[i],...p,updatedAt:now()};return s})
 return <section className="workspace"><div className="listPane"><div className="paneTitle"><b>Заметки</b><button onClick={create}>Создать</button></div>{notes.map(n=><div className={n.id===selected?'row selected':'row'} onClick={()=>setSelected(n.id)} key={n.id}><b>{n.title||'Без названия'}</b><small>{new Date(n.updatedAt).toLocaleString()}</small></div>)}</div><div className="editorPane">{cur?<><input className="titleInput" value={cur.title} onChange={e=>patch({title:e.target.value})}/><textarea value={cur.body} onChange={e=>patch({body:e.target.value})}/><div className="editorActions"><button className="danger" onClick={()=>{patch({deletedAt:now()});setSelected('')}}>Удалить</button></div></>:<Empty text="Выберите заметку или создайте новую"/>}</div></section>
}

function Projects({state,commit}:{state:OrganizerState;commit:(f:(s:OrganizerState)=>OrganizerState)=>void}){
 const list=state.projects.filter(x=>!x.deletedAt); const [selected,setSelected]=useState(list[0]?.id||''); const p=list.find(x=>x.id===selected); const stages=state.stages.filter(x=>x.projectId===selected&&!x.deletedAt)
 const create=()=>{const t=now();const x:Project={id:uid(),name:'Новый проект',description:'',status:'planned',progress:0,startAt:t,dueAt:0,budget:0,client:'',address:'',contact:'',colorKey:'blue',createdAt:t,updatedAt:t};commit(s=>{s.projects.unshift(x);return s});setSelected(x.id)}
 const patch=(v:Partial<Project>)=>p&&commit(s=>{const i=s.projects.findIndex(x=>x.id===p.id);s.projects[i]={...s.projects[i],...v,updatedAt:now()};return s})
 const addStage=()=>p&&commit(s=>{const t=now();s.stages.push({id:uid(),projectId:p.id,title:'Новый этап',done:false,dueAt:p.dueAt||0,createdAt:t,updatedAt:t});return s})
 const patchStage=(id:string,v:Partial<ProjectStage>)=>commit(s=>{const i=s.stages.findIndex(x=>x.id===id);s.stages[i]={...s.stages[i],...v,updatedAt:now()};return s})
 return <section className="workspace"><div className="listPane"><div className="paneTitle"><b>Проекты</b><button onClick={create}>Создать</button></div>{list.map(x=><div className={x.id===selected?'row selected':'row'} onClick={()=>setSelected(x.id)} key={x.id}><b>{x.name}</b><small>{x.dueAt?'Срок '+d(x.dueAt):'Без срока'}</small></div>)}</div><div className="editorPane">{p?<><input className="titleInput" value={p.name} onChange={e=>patch({name:e.target.value})}/><textarea className="projectDesc" value={p.description} onChange={e=>patch({description:e.target.value})}/><div className="formGrid"><label>Статус<input value={p.status} onChange={e=>patch({status:e.target.value})}/></label><label>Прогресс %<input type="number" value={p.progress} onChange={e=>patch({progress:Number(e.target.value)})}/></label><label>Начало<input type="date" value={d(p.startAt)} onChange={e=>patch({startAt:fromDate(e.target.value)})}/></label><label>Срок<input type="date" value={d(p.dueAt)} onChange={e=>patch({dueAt:fromDate(e.target.value)})}/></label><label>Бюджет<input type="number" value={p.budget} onChange={e=>patch({budget:Number(e.target.value)})}/></label><label>Клиент<input value={p.client} onChange={e=>patch({client:e.target.value})}/></label></div><div className="paneTitle"><b>Этапы</b><button onClick={addStage}>Добавить этап</button></div>{stages.map(s=><div className="stage" key={s.id}><input type="checkbox" checked={s.done} onChange={e=>patchStage(s.id,{done:e.target.checked})}/><input value={s.title} onChange={e=>patchStage(s.id,{title:e.target.value})}/><input type="date" value={d(s.dueAt)} onChange={e=>patchStage(s.id,{dueAt:fromDate(e.target.value)})}/></div>)}</>:<Empty text="Выберите проект или создайте новый"/>}</div></section>
}

function Money({state,commit}:{state:OrganizerState;commit:(f:(s:OrganizerState)=>OrganizerState)=>void}){
 const rows=state.money.filter(x=>!x.deletedAt&&x.projectId==null); const inc=rows.filter(x=>x.type==='income').reduce((a,b)=>a+b.amount,0),exp=rows.filter(x=>x.type==='expense').reduce((a,b)=>a+b.amount,0)
 const add=(type:'income'|'expense')=>commit(s=>{const t=now();const x:MoneyEntry={id:uid(),type,amount:0,category:'',projectId:null,note:'',colorKey:'blue',createdAt:t,updatedAt:t};s.money.unshift(x);return s})
 const patch=(id:string,v:Partial<MoneyEntry>)=>commit(s=>{const i=s.money.findIndex(x=>x.id===id);s.money[i]={...s.money[i],...v,updatedAt:now()};return s})
 return <section className="single"><div className="summary"><div>Доход<b>{inc.toLocaleString()} ₸</b></div><div>Расход<b>{exp.toLocaleString()} ₸</b></div><div>Баланс<b>{(inc-exp).toLocaleString()} ₸</b></div></div><div className="paneTitle"><b>Операции</b><span><button onClick={()=>add('income')}>+ Доход</button> <button onClick={()=>add('expense')}>+ Расход</button></span></div>{rows.map(x=><div className="moneyRow" key={x.id}><select value={x.type} onChange={e=>patch(x.id,{type:e.target.value as any})}><option value="income">Доход</option><option value="expense">Расход</option></select><input type="number" value={x.amount} onChange={e=>patch(x.id,{amount:Number(e.target.value)})}/><input placeholder="Категория" value={x.category} onChange={e=>patch(x.id,{category:e.target.value})}/><input placeholder="Комментарий" value={x.note} onChange={e=>patch(x.id,{note:e.target.value})}/></div>)}</section>
}

function Settings({user,configured}:{user:User|null;configured:boolean}){return <section className="single"><h2>Настройки веб-версии</h2><div className="settingsCard"><b>Синхронизация</b><p>{configured?'Firebase подключаем после ввода конфигурации проекта.':'Сейчас работает локальное хранение браузера. Облачная конфигурация ещё не добавлена.'}</p><p>Аккаунт: {user?.email||'не выполнен вход'}</p></div><div className="settingsCard"><b>Совместимость Android</b><p>Модель данных подготовлена под заметки, проекты, этапы, финансы, напоминания и метаданные вложений Android Органайзера. Android v0.9.24 пока не изменён.</p></div></section>}

function Calendar({state,close}:{state:OrganizerState;close:()=>void}){const [cursor,setCursor]=useState(()=>new Date());useEffect(()=>{const h=(e:KeyboardEvent)=>{if(e.key==='Escape')close()};addEventListener('keydown',h);history.pushState({calendar:true},'');const pop=()=>close();addEventListener('popstate',pop);return()=>{removeEventListener('keydown',h);removeEventListener('popstate',pop)}},[]);const y=cursor.getFullYear(),m=cursor.getMonth(),first=new Date(y,m,1),days=new Date(y,m+1,0).getDate(),offset=(first.getDay()+6)%7;const items=useMemo(()=>[...state.projects.filter(x=>x.dueAt&&!x.deletedAt).map(x=>({date:new Date(x.dueAt),text:x.name,type:'project'})),...state.stages.filter(x=>x.dueAt&&!x.deletedAt).map(x=>({date:new Date(x.dueAt),text:x.title,type:'stage'}))],[state]);return <div className="calendarFull"><div className="calHead"><button onClick={()=>setCursor(new Date(y,m-1,1))}>‹</button><h2>{cursor.toLocaleString('ru',{month:'long',year:'numeric'})}</h2><button onClick={()=>setCursor(new Date(y,m+1,1))}>›</button></div><div className="week">{['Пн','Вт','Ср','Чт','Пт','Сб','Вс'].map(x=><b key={x}>{x}</b>)}</div><div className="monthGrid">{Array.from({length:offset}).map((_,i)=><div key={'e'+i}/>) }{Array.from({length:days}).map((_,i)=>{const day=i+1;const ev=items.filter(x=>x.date.getFullYear()===y&&x.date.getMonth()===m&&x.date.getDate()===day);return <div className="day" key={day}><strong>{day}</strong>{ev.map((x,j)=><small className={x.type} key={j}>{x.text}</small>)}</div>})}</div><div className="calHint">Возврат — кнопкой «Назад» браузера или Esc</div></div>}
function Empty({text}:{text:string}){return <div className="empty">{text}</div>}
