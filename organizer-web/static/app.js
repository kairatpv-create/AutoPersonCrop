(function(){
'use strict';

var STORAGE_KEY='organizer-pro-web-v1';
var state={notes:[],projects:[],stages:[],money:[],reminders:[],attachments:[],revision:1};
var selectedNote='';
var selectedProject='';
var currentSection='notes';
var calendarCursor=new Date();
var storageOk=true;

function el(id){return document.getElementById(id)}
function esc(v){return String(v==null?'':v).replace(/[&<>"']/g,function(c){return {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]})}
function uid(){try{if(window.crypto&&crypto.randomUUID)return crypto.randomUUID()}catch(e){}return 'web-'+Date.now()+'-'+Math.random().toString(16).slice(2)}
function now(){return Date.now()}
function dateValue(ms){if(!ms)return'';var d=new Date(ms),m=String(d.getMonth()+1).padStart(2,'0'),day=String(d.getDate()).padStart(2,'0');return d.getFullYear()+'-'+m+'-'+day}
function dateMs(v){if(!v)return 0;var d=new Date(v+'T12:00:00');return isNaN(d.getTime())?0:d.getTime()}
function fmt(ms){try{return new Date(ms).toLocaleString('ru-RU')}catch(e){return''}}
function money(n){return Number(n||0).toLocaleString('ru-RU')+' ₸'}
function showFatal(err){var box=el('fatal');if(!box)return;box.hidden=false;box.textContent='Ошибка запуска веб-версии:\n'+(err&&err.stack?err.stack:String(err));}
window.addEventListener('error',function(e){showFatal(e.error||e.message)});
window.addEventListener('unhandledrejection',function(e){showFatal(e.reason||'Неизвестная ошибка')});

function load(){
  try{
    var raw=localStorage.getItem(STORAGE_KEY);
    if(raw){var parsed=JSON.parse(raw);state=Object.assign(state,parsed||{});}
    storageOk=true;
  }catch(e){storageOk=false;}
  state.notes=Array.isArray(state.notes)?state.notes:[];
  state.projects=Array.isArray(state.projects)?state.projects:[];
  state.stages=Array.isArray(state.stages)?state.stages:[];
  state.money=Array.isArray(state.money)?state.money:[];
  state.reminders=Array.isArray(state.reminders)?state.reminders:[];
  state.attachments=Array.isArray(state.attachments)?state.attachments:[];
  var notes=active(state.notes),projects=active(state.projects);
  selectedNote=notes[0]?notes[0].id:'';
  selectedProject=projects[0]?projects[0].id:'';
  updateStorageStatus();
}
function save(){
  state.revision=Number(state.revision||0)+1;
  try{localStorage.setItem(STORAGE_KEY,JSON.stringify(state));storageOk=true;}catch(e){storageOk=false;}
  updateStorageStatus();
}
function updateStorageStatus(){var s=el('storageStatus');if(s)s.textContent=storageOk?'Локально сохранено':'Память браузера недоступна';}
function active(arr){return arr.filter(function(x){return !x.deletedAt})}
function findBy(arr,id){for(var i=0;i<arr.length;i++)if(arr[i].id===id)return arr[i];return null}

function switchSection(name){
  currentSection=name;
  document.querySelectorAll('.nav').forEach(function(b){b.classList.toggle('active',b.getAttribute('data-section')===name)});
  ['notes','projects','money','settings'].forEach(function(x){el(x+'Section').hidden=x!==name});
  renderCurrent();
}
function renderCurrent(){if(currentSection==='notes')renderNotes();else if(currentSection==='projects')renderProjects();else if(currentSection==='money')renderMoney();else renderSettings();}

function renderNotes(){
  var root=el('notesSection'),notes=active(state.notes),cur=findBy(notes,selectedNote);
  if(!cur&&notes[0]){selectedNote=notes[0].id;cur=notes[0];}
  var list='<div class="list-pane"><div class="pane-title"><b>Заметки</b><button id="newNote">Создать</button></div>';
  notes.forEach(function(n){list+='<div class="row '+(n.id===selectedNote?'selected':'')+'" data-note="'+esc(n.id)+'"><b>'+esc(n.title||'Без названия')+'</b><small>'+esc(fmt(n.updatedAt||n.createdAt||0))+'</small></div>'});
  if(!notes.length)list+='<div class="empty">Заметок пока нет</div>';
  list+='</div>';
  var editor='<div class="editor-pane">';
  if(cur){editor+='<input id="noteTitle" class="title-input" value="'+esc(cur.title||'')+'" placeholder="Название"><textarea id="noteBody" placeholder="Текст заметки">'+esc(cur.body||'')+'</textarea><div><button id="deleteNote" class="danger">Удалить</button></div>'}
  else editor+='<div class="empty">Создайте новую заметку</div>';
  editor+='</div>';
  root.innerHTML='<div class="workspace">'+list+editor+'</div>';
  el('newNote').onclick=function(){var t=now(),n={id:uid(),title:'Новая заметка',body:'',colorKey:'blue',createdAt:t,updatedAt:t};state.notes.unshift(n);selectedNote=n.id;save();renderNotes();};
  root.querySelectorAll('[data-note]').forEach(function(r){r.onclick=function(){selectedNote=r.getAttribute('data-note');renderNotes();}});
  if(cur){
    el('noteTitle').oninput=function(){cur.title=this.value;cur.updatedAt=now();save();renderNoteListOnly();};
    el('noteBody').oninput=function(){cur.body=this.value;cur.updatedAt=now();save();};
    el('deleteNote').onclick=function(){cur.deletedAt=now();selectedNote='';save();renderNotes();};
  }
}
function renderNoteListOnly(){
  var notes=active(state.notes),pane=el('notesSection').querySelector('.list-pane');if(!pane)return;
  var html='<div class="pane-title"><b>Заметки</b><button id="newNote">Создать</button></div>';
  notes.forEach(function(n){html+='<div class="row '+(n.id===selectedNote?'selected':'')+'" data-note="'+esc(n.id)+'"><b>'+esc(n.title||'Без названия')+'</b><small>'+esc(fmt(n.updatedAt||n.createdAt||0))+'</small></div>'});
  pane.innerHTML=html;
  el('newNote').onclick=function(){var t=now(),n={id:uid(),title:'Новая заметка',body:'',colorKey:'blue',createdAt:t,updatedAt:t};state.notes.unshift(n);selectedNote=n.id;save();renderNotes();};
  pane.querySelectorAll('[data-note]').forEach(function(r){r.onclick=function(){selectedNote=r.getAttribute('data-note');renderNotes();}});
}

function renderProjects(){
  var root=el('projectsSection'),projects=active(state.projects),p=findBy(projects,selectedProject);
  if(!p&&projects[0]){selectedProject=projects[0].id;p=projects[0];}
  var list='<div class="list-pane"><div class="pane-title"><b>Проекты</b><button id="newProject">Создать</button></div>';
  projects.forEach(function(x){list+='<div class="row '+(x.id===selectedProject?'selected':'')+'" data-project="'+esc(x.id)+'"><b>'+esc(x.name||'Без названия')+'</b><small>'+(x.dueAt?'Срок '+esc(dateValue(x.dueAt)):'Без срока')+'</small></div>'});
  if(!projects.length)list+='<div class="empty">Проектов пока нет</div>';
  list+='</div>';
  var editor='<div class="editor-pane">';
  if(p){
    var stages=active(state.stages).filter(function(s){return s.projectId===p.id});
    editor+='<input id="projectName" class="title-input" value="'+esc(p.name||'')+'" placeholder="Название проекта">';
    editor+='<textarea id="projectDesc" class="project-desc" placeholder="Описание">'+esc(p.description||'')+'</textarea>';
    editor+='<div class="form-grid">'+
      '<label>Статус<input id="projectStatus" value="'+esc(p.status||'planned')+'"></label>'+
      '<label>Прогресс %<input id="projectProgress" type="number" min="0" max="100" value="'+esc(p.progress||0)+'"></label>'+
      '<label>Начало<input id="projectStart" type="date" value="'+esc(dateValue(p.startAt))+'"></label>'+
      '<label>Срок<input id="projectDue" type="date" value="'+esc(dateValue(p.dueAt))+'"></label>'+
      '<label>Бюджет<input id="projectBudget" type="number" value="'+esc(p.budget||0)+'"></label>'+
      '<label>Клиент<input id="projectClient" value="'+esc(p.client||'')+'"></label>'+
      '</div>';
    editor+='<div class="pane-title"><b>Этапы</b><button id="addStage">Добавить этап</button></div><div id="stages">';
    stages.forEach(function(s){editor+='<div class="stage" data-stage="'+esc(s.id)+'"><input class="stageDone" type="checkbox" '+(s.done?'checked':'')+'><input class="stageTitle" value="'+esc(s.title||'')+'"><input class="stageDue" type="date" value="'+esc(dateValue(s.dueAt))+'"><button class="stageDelete">×</button></div>'});
    editor+='</div><div><button id="deleteProject" class="danger">Удалить проект</button></div>';
  }else editor+='<div class="empty">Создайте новый проект</div>';
  editor+='</div>';
  root.innerHTML='<div class="workspace">'+list+editor+'</div>';
  el('newProject').onclick=function(){var t=now(),x={id:uid(),name:'Новый проект',description:'',status:'planned',progress:0,startAt:t,dueAt:0,budget:0,client:'',address:'',contact:'',colorKey:'blue',createdAt:t,updatedAt:t};state.projects.unshift(x);selectedProject=x.id;save();renderProjects();};
  root.querySelectorAll('[data-project]').forEach(function(r){r.onclick=function(){selectedProject=r.getAttribute('data-project');renderProjects();}});
  if(p){
    function bind(id,key,conv){el(id).oninput=function(){p[key]=conv?conv(this.value):this.value;p.updatedAt=now();save();};}
    bind('projectName','name');bind('projectDesc','description');bind('projectStatus','status');bind('projectProgress','progress',function(v){return Math.max(0,Math.min(100,Number(v||0)))});bind('projectStart','startAt',dateMs);bind('projectDue','dueAt',dateMs);bind('projectBudget','budget',function(v){return Number(v||0)});bind('projectClient','client');
    el('addStage').onclick=function(){var t=now();state.stages.push({id:uid(),projectId:p.id,title:'Новый этап',done:false,dueAt:p.dueAt||0,createdAt:t,updatedAt:t});save();renderProjects();};
    el('deleteProject').onclick=function(){p.deletedAt=now();selectedProject='';save();renderProjects();};
    root.querySelectorAll('[data-stage]').forEach(function(row){var id=row.getAttribute('data-stage'),s=findBy(state.stages,id);if(!s)return;row.querySelector('.stageDone').onchange=function(){s.done=this.checked;s.updatedAt=now();save();};row.querySelector('.stageTitle').oninput=function(){s.title=this.value;s.updatedAt=now();save();};row.querySelector('.stageDue').oninput=function(){s.dueAt=dateMs(this.value);s.updatedAt=now();save();};row.querySelector('.stageDelete').onclick=function(){s.deletedAt=now();save();renderProjects();};});
  }
}

function renderMoney(){
  var root=el('moneySection'),rows=active(state.money).filter(function(x){return x.projectId==null}),inc=0,exp=0;
  rows.forEach(function(x){if(x.type==='income')inc+=Number(x.amount||0);else exp+=Number(x.amount||0)});
  var html='<div class="single"><div class="summary"><div>Доход<b>'+money(inc)+'</b></div><div>Расход<b>'+money(exp)+'</b></div><div>Баланс<b>'+money(inc-exp)+'</b></div></div>';
  html+='<div class="pane-title"><b>Операции</b><span><button id="addIncome">+ Доход</button> <button id="addExpense">+ Расход</button></span></div><div id="moneyRows">';
  rows.forEach(function(x){html+='<div class="money-row" data-money="'+esc(x.id)+'"><select class="moneyType"><option value="income" '+(x.type==='income'?'selected':'')+'>Доход</option><option value="expense" '+(x.type==='expense'?'selected':'')+'>Расход</option></select><input class="moneyAmount" type="number" value="'+esc(x.amount||0)+'"><input class="moneyCategory" placeholder="Категория" value="'+esc(x.category||'')+'"><input class="moneyNote" placeholder="Комментарий" value="'+esc(x.note||'')+'"><button class="moneyDelete">×</button></div>'});
  if(!rows.length)html+='<div class="empty">Операций пока нет</div>';
  html+='</div></div>';root.innerHTML=html;
  function add(type){var t=now();state.money.unshift({id:uid(),type:type,amount:0,category:'',projectId:null,note:'',colorKey:'blue',createdAt:t,updatedAt:t});save();renderMoney();}
  el('addIncome').onclick=function(){add('income')};el('addExpense').onclick=function(){add('expense')};
  root.querySelectorAll('[data-money]').forEach(function(row){var x=findBy(state.money,row.getAttribute('data-money'));if(!x)return;row.querySelector('.moneyType').onchange=function(){x.type=this.value;x.updatedAt=now();save();renderMoney();};row.querySelector('.moneyAmount').oninput=function(){x.amount=Number(this.value||0);x.updatedAt=now();save();updateMoneySummaryOnly();};row.querySelector('.moneyCategory').oninput=function(){x.category=this.value;x.updatedAt=now();save();};row.querySelector('.moneyNote').oninput=function(){x.note=this.value;x.updatedAt=now();save();};row.querySelector('.moneyDelete').onclick=function(){x.deletedAt=now();save();renderMoney();};});
}
function updateMoneySummaryOnly(){var rows=active(state.money).filter(function(x){return x.projectId==null}),inc=0,exp=0;rows.forEach(function(x){if(x.type==='income')inc+=Number(x.amount||0);else exp+=Number(x.amount||0)});var cards=el('moneySection').querySelectorAll('.summary b');if(cards.length===3){cards[0].textContent=money(inc);cards[1].textContent=money(exp);cards[2].textContent=money(inc-exp);}}

function renderSettings(){
  var root=el('settingsSection');
  root.innerHTML='<div class="single"><h2>Настройки веб-версии</h2><div class="settings-card"><b>Хранение данных</b><p>'+(storageOk?'<span class="ok">Данные сохраняются в браузере этого компьютера.</span>':'<span class="warn">Браузер запретил локальное хранение. Изменения сохраняются только до закрытия страницы.</span>')+'</p><p class="hint">Облачная синхронизация с Android будет подключена отдельным этапом после стабилизации локальной версии.</p></div><div class="settings-card"><b>Резервная копия веб-данных</b><div class="backup-row"><button id="exportData">Экспорт JSON</button><label class="accent" style="display:inline-block;cursor:pointer">Импорт JSON<input id="importData" type="file" accept="application/json,.json" style="display:none"></label></div><p class="hint">Экспорт сохраняет заметки, проекты, этапы и финансы веб-версии.</p></div><div class="settings-card"><b>Версия</b><p>Органайзер Про Web 0.2.0 Static</p></div></div>';
  el('exportData').onclick=function(){var blob=new Blob([JSON.stringify(state,null,2)],{type:'application/json'}),a=document.createElement('a');a.href=URL.createObjectURL(blob);a.download='Organizer-Pro-Web-backup-'+new Date().toISOString().slice(0,10)+'.json';document.body.appendChild(a);a.click();setTimeout(function(){URL.revokeObjectURL(a.href);a.remove()},0);};
  el('importData').onchange=function(){var f=this.files&&this.files[0];if(!f)return;var r=new FileReader();r.onload=function(){try{var parsed=JSON.parse(String(r.result||''));if(!parsed||typeof parsed!=='object')throw new Error('Неверный формат');state=Object.assign({notes:[],projects:[],stages:[],money:[],reminders:[],attachments:[],revision:1},parsed);save();selectedNote='';selectedProject='';alert('Резервная копия загружена');renderSettings();}catch(e){alert('Не удалось загрузить файл: '+e.message)}};r.readAsText(f);};
}

function openCalendar(){calendarCursor=new Date();el('calendarView').hidden=false;renderCalendar();}
function closeCalendar(){el('calendarView').hidden=true;}
function renderCalendar(){
  var y=calendarCursor.getFullYear(),m=calendarCursor.getMonth(),days=new Date(y,m+1,0).getDate(),offset=(new Date(y,m,1).getDay()+6)%7;
  el('calendarTitle').textContent=calendarCursor.toLocaleString('ru-RU',{month:'long',year:'numeric'});
  var events=[];
  active(state.projects).forEach(function(p){if(p.dueAt)events.push({date:new Date(p.dueAt),text:p.name||'Проект',stage:false})});
  active(state.stages).forEach(function(s){if(s.dueAt)events.push({date:new Date(s.dueAt),text:s.title||'Этап',stage:true})});
  var html='';for(var i=0;i<offset;i++)html+='<div></div>';
  for(var day=1;day<=days;day++){html+='<div class="day"><strong>'+day+'</strong>';events.filter(function(ev){return ev.date.getFullYear()===y&&ev.date.getMonth()===m&&ev.date.getDate()===day}).forEach(function(ev){html+='<span class="event '+(ev.stage?'stage-event':'')+'">'+esc(ev.text)+'</span>'});html+='</div>';}
  el('calendarGrid').innerHTML=html;
}

function init(){
  load();
  document.querySelectorAll('.nav').forEach(function(b){b.addEventListener('click',function(){switchSection(b.getAttribute('data-section'))})});
  el('calendarBtn').onclick=openCalendar;el('calendarBack').onclick=closeCalendar;
  el('prevMonth').onclick=function(){calendarCursor=new Date(calendarCursor.getFullYear(),calendarCursor.getMonth()-1,1);renderCalendar();};
  el('nextMonth').onclick=function(){calendarCursor=new Date(calendarCursor.getFullYear(),calendarCursor.getMonth()+1,1);renderCalendar();};
  el('calendarToday').onclick=function(){calendarCursor=new Date();renderCalendar();};
  document.addEventListener('keydown',function(e){if(e.key==='Escape'&&!el('calendarView').hidden)closeCalendar();});
  switchSection('notes');
}

try{init();}catch(e){showFatal(e);}
})();
