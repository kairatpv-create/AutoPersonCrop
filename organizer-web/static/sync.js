(function(){
'use strict';

var STATE_KEY='organizer-pro-web-v1';
var SESSION_KEY='organizer-pro-session-v3';
var META_KEY='organizer-pro-sync-meta-v3';
var SAFETY_PREFIX='organizer-pro-safety-';
var API_BASE=(location.protocol==='file:'?'https://organizer-pro.onrender.com':'');
var busy=false,timer=null,installPrompt=null,suppress=false;

function q(s){return document.querySelector(s)}
function esc(v){return String(v==null?'':v).replace(/[&<>"']/g,function(c){return {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]})}
function readJson(k,f){try{return JSON.parse(localStorage.getItem(k)||'null')||f}catch(e){return f}}
function writeJson(k,v){localStorage.setItem(k,JSON.stringify(v))}
function session(){return readJson(SESSION_KEY,{})}
function meta(){return readJson(META_KEY,{rev:0,lastSig:'',lastSyncAt:0})}
function setMeta(v){writeJson(META_KEY,v)}
function loadState(){return Object.assign({notes:[],projects:[],stages:[],money:[],reminders:[],attachments:[],passthrough:{},revision:1},readJson(STATE_KEY,{}))}
function saveState(v){suppress=true;try{writeJson(STATE_KEY,v)}finally{suppress=false}}
function sig(text){var h=2166136261;for(var i=0;i<text.length;i++){h^=text.charCodeAt(i);h=Math.imul(h,16777619)}return (h>>>0).toString(16)+'-'+text.length}
function activeCount(st){return ['notes','projects','stages','money','reminders'].reduce(function(n,k){var a=Array.isArray(st[k])?st[k]:[];return n+a.filter(function(x){return !x.deletedAt}).length},0)}
function safety(st,why){try{localStorage.setItem(SAFETY_PREFIX+Date.now(),JSON.stringify({reason:why,state:st}))}catch(e){}}
function friendly(data,status){if(data&&data.message)return data.message;if(status===401)return 'Сессия завершена. Войдите снова.';if(status===409)return 'Данные изменились на другом устройстве';if(status>=500)return 'Сервер временно недоступен';return 'Ошибка соединения'}

async function api(path,opts,allowConflict){
  var r=await fetch(API_BASE+path,opts||{}),text=await r.text(),data={};
  try{data=text?JSON.parse(text):{}}catch(e){data={raw:text}}
  if(allowConflict&&r.status===409){data.__conflict=true;return data}
  if(!r.ok)throw new Error(friendly(data,r.status));
  return data;
}
function authHeaders(){var s=session();return {'Content-Type':'application/json','Authorization':'Bearer '+(s.token||'')}}

function numericId(value,map,counter){
  if(typeof value==='number'&&isFinite(value)&&value>0)return Math.trunc(value);
  var k=String(value==null?'':value);if(map[k])return map[k];
  var n=Date.now()*1000+(counter.n++%900)+100;map[k]=n;return n;
}
function normalizeState(st){
  var noteMap={},projectMap={},stageMap={},moneyMap={},remMap={},counter={n:1};
  st.notes=(st.notes||[]).map(function(x){var y=Object.assign({},x);y.id=numericId(x.id,noteMap,counter);return y});
  st.projects=(st.projects||[]).map(function(x){var y=Object.assign({},x);y.id=numericId(x.id,projectMap,counter);return y});
  st.stages=(st.stages||[]).map(function(x){var y=Object.assign({},x);y.id=numericId(x.id,stageMap,counter);y.projectId=numericId(x.projectId,projectMap,counter);return y});
  st.money=(st.money||[]).map(function(x){var y=Object.assign({},x);y.id=numericId(x.id,moneyMap,counter);if(x.projectId!=null)y.projectId=numericId(x.projectId,projectMap,counter);return y});
  st.reminders=(st.reminders||[]).map(function(x){var y=Object.assign({},x);y.id=numericId(x.id,remMap,counter);if(x.targetType==='note')y.targetId=numericId(x.targetId,noteMap,counter);else if(x.targetType==='project')y.targetId=numericId(x.targetId,projectMap,counter);return y});
  saveState(st);return st;
}
function toBackup(st){
  st=normalizeState(st);var p=st.passthrough||{};
  return {
    format:'my-organizer-backup',version:7,createdAt:0,cloudSchema:'organizer-sync-v3',
    notes:(st.notes||[]).filter(function(x){return !x.deletedAt}).map(function(x){return {id:x.id,title:x.title||'',body:x.body||'',createdAt:Number(x.createdAt||0),updatedAt:Number(x.updatedAt||x.createdAt||0),colorKey:x.colorKey||'blue'}}),
    tasks:Array.isArray(p.tasks)?p.tasks:[],
    projects:(st.projects||[]).filter(function(x){return !x.deletedAt}).map(function(x){return {id:x.id,name:x.name||'',description:x.description||'',status:x.status||'planned',progress:Number(x.progress||0),startAt:Number(x.startAt||0),dueAt:Number(x.dueAt||0),budget:Number(x.budget||0),client:x.client||'',address:x.address||'',contact:x.contact||'',createdAt:Number(x.createdAt||0),updatedAt:Number(x.updatedAt||x.createdAt||0),colorKey:x.colorKey||'blue'}}),
    projectStages:(st.stages||[]).filter(function(x){return !x.deletedAt}).map(function(x){return {id:x.id,projectId:x.projectId,title:x.title||'',done:!!x.done,dueAt:Number(x.dueAt||0),createdAt:Number(x.createdAt||0)}}),
    projectJournal:Array.isArray(p.projectJournal)?p.projectJournal:[],
    projectNoteLinks:Array.isArray(p.projectNoteLinks)?p.projectNoteLinks:[],
    money:(st.money||[]).filter(function(x){return !x.deletedAt}).map(function(x){var y={id:x.id,type:x.type||'expense',amount:Number(x.amount||0),category:x.category||'',projectName:x.projectName||'',note:x.note||'',createdAt:Number(x.createdAt||0),colorKey:x.colorKey||'blue'};if(x.projectId!=null)y.projectId=x.projectId;return y}),
    reminders:(st.reminders||[]).filter(function(x){return !x.deletedAt}).map(function(x){return {id:x.id,targetType:x.targetType||'note',targetId:Number(x.targetId||0),triggerAt:Number(x.triggerAt||0),sound:x.sound!==false,vibrate:x.vibrate!==false,soundKey:x.soundKey||'system',createdAt:Number(x.createdAt||0),scheduleType:x.scheduleType||'once',daysMask:Number(x.daysMask||0),dayMode:x.dayMode||'single',baseMinutes:Number(x.baseMinutes||540)}})
  };
}
function fromBackup(b){
  var old=loadState();
  return {
    notes:Array.isArray(b.notes)?b.notes:[],projects:Array.isArray(b.projects)?b.projects:[],stages:Array.isArray(b.projectStages)?b.projectStages:[],money:Array.isArray(b.money)?b.money:[],reminders:Array.isArray(b.reminders)?b.reminders:[],attachments:old.attachments||[],
    passthrough:{tasks:Array.isArray(b.tasks)?b.tasks:[],projectJournal:Array.isArray(b.projectJournal)?b.projectJournal:[],projectNoteLinks:Array.isArray(b.projectNoteLinks)?b.projectNoteLinks:[]},
    revision:Number(old.revision||0)+1
  };
}
function canonicalLocal(){var payload=JSON.stringify(toBackup(loadState()));return {payload:payload,sig:sig(payload)}}
function applyRemote(payload,rev,remoteSig){
  var local=loadState();if(activeCount(local)>0)safety(local,'Перед получением данных из облака');
  var b=JSON.parse(payload);saveState(fromBackup(b));setMeta({rev:Number(rev||0),lastSig:remoteSig,lastSyncAt:Date.now()});
}
function notifyCloudApplied(){try{window.dispatchEvent(new CustomEvent('organizer:cloud-applied'))}catch(e){}}

async function login(email,password,create){
  var data=await api('/api/auth/'+(create?'register':'login'),{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({email:email,password:password})});
  writeJson(SESSION_KEY,{email:data.email||email,token:data.token||''});setMeta({rev:0,lastSig:'',lastSyncAt:0});
  renderAccount();updateTop('Вход выполнен');await autoSync(true);
}
function logout(){localStorage.removeItem(SESSION_KEY);localStorage.removeItem(META_KEY);renderAccount();updateTop('Выполнен выход')}

async function upload(local,baseRev){
  var data=await api('/api/sync',{method:'PUT',headers:authHeaders(),body:JSON.stringify({payload:local.payload,baseRev:Number(baseRev||0)})},true);
  if(data.__conflict){
    if(data.payload){var rs=sig(data.payload);applyRemote(data.payload,data.rev,rs);notifyCloudApplied();}
    return false;
  }
  setMeta({rev:Number(data.rev||0),lastSig:local.sig,lastSyncAt:Date.now()});return true;
}
async function autoSync(force){
  if(busy||!session().token)return updateTop();
  if(!navigator.onLine){return updateTop('Нет интернета')}
  busy=true;if(force)updateTop('Синхронизация…');
  try{
    var local=canonicalLocal(),m=meta(),remote=await api('/api/sync',{headers:authHeaders()});
    if(!remote.exists){await upload(local,0);updateTop('Синхронизировано');return}
    var remotePayload=remote.payload||'',remoteSig=sig(remotePayload),remoteRev=Number(remote.rev||0);
    if(!m.lastSig){
      if(activeCount(loadState())===0&&remotePayload){applyRemote(remotePayload,remoteRev,remoteSig);updateTop('Данные получены');notifyCloudApplied();return}
      if(local.sig===remoteSig){setMeta({rev:remoteRev,lastSig:local.sig,lastSyncAt:Date.now()});updateTop('Синхронизировано');return}
      if(remotePayload){applyRemote(remotePayload,remoteRev,remoteSig);updateTop('Данные получены');notifyCloudApplied();return}
      await upload(local,remoteRev);updateTop('Синхронизировано');return;
    }
    var localDirty=local.sig!==m.lastSig,remoteDirty=remoteRev!==Number(m.rev||0)||remoteSig!==m.lastSig;
    if(localDirty&&!remoteDirty){await upload(local,remoteRev);updateTop('Синхронизировано');return}
    if(!localDirty&&remoteDirty){applyRemote(remotePayload,remoteRev,remoteSig);updateTop('Данные обновлены');notifyCloudApplied();return}
    if(localDirty&&remoteDirty){applyRemote(remotePayload,remoteRev,remoteSig);updateTop('Данные обновлены');notifyCloudApplied();return}
    setMeta({rev:remoteRev,lastSig:local.sig,lastSyncAt:Date.now()});updateTop('Синхронизировано');
  }catch(e){
    if(/Сессия завершена|Требуется вход/.test(String(e.message||''))){localStorage.removeItem(SESSION_KEY);localStorage.removeItem(META_KEY);renderAccount();}
    updateTop(e.message||String(e),true);
  }finally{busy=false}
}
function scheduleSync(){clearTimeout(timer);timer=setTimeout(function(){autoSync(false)},1600)}

function updateTop(text,isError){
  var mode=q('.mode'),b=q('#syncTopBtn'),s=session();
  if(b){var label=s.token?'Аккаунт':'Войти';if(b.textContent!==label)b.textContent=label;}
  if(!mode)return;
  mode.classList.toggle('sync-error',!!isError);
  if(!navigator.onLine)mode.textContent='Офлайн • данные сохранены';
  else if(!s.token)mode.textContent='Войдите для синхронизации';
  else mode.textContent=text||'Синхронизация включена';
  var st=q('#syncStatus');if(st){st.textContent=text||'';st.className=isError?'sync-status error':'sync-status'}
}
function renderAccount(){
  var box=q('#syncAccount');if(!box)return;var s=session();
  if(s.token){
    box.innerHTML='<div class="sync-user"><b>'+esc(s.email||'')+'</b><br><span class="sync-ok">Синхронизация включена</span></div><p class="sync-hint">Заметки, проекты, этапы, финансы и напоминания синхронизируются автоматически. Вложения пока остаются на устройстве.</p><div class="sync-actions"><button id="syncLogout" class="secondary">Выйти</button></div>';
    q('#syncLogout').onclick=logout;
  }else{
    box.innerHTML='<div class="sync-grid"><label>Email<input id="syncEmail" type="email" autocomplete="username"></label><label>Пароль<input id="syncPassword" type="password" autocomplete="current-password"></label></div><div class="sync-actions"><button id="syncLogin">Войти</button><button id="syncRegister" class="secondary">Создать аккаунт</button></div><p class="sync-hint">На телефоне и компьютере используйте один и тот же email и пароль.</p>';
    function run(create){var e=q('#syncEmail').value.trim(),p=q('#syncPassword').value;if(!e){return updateTop('Введите email',true)}if(p.length<6){return updateTop('Пароль должен быть не короче 6 символов',true)}q('#syncLogin').disabled=true;q('#syncRegister').disabled=true;login(e,p,create).catch(function(err){updateTop(err.message||String(err),true)}).finally(function(){if(q('#syncLogin'))q('#syncLogin').disabled=false;if(q('#syncRegister'))q('#syncRegister').disabled=false})}
    q('#syncLogin').onclick=function(){run(false)};q('#syncRegister').onclick=function(){run(true)};
  }
}
function ensureSettingsCard(){
  var root=q('#settingsSection');if(!root||q('#syncSettingsCard'))return;
  var card=document.createElement('div');card.id='syncSettingsCard';card.className='settings-card';card.innerHTML='<b>Аккаунт и синхронизация</b><p class="hint">Синхронизация работает автоматически после входа. Технические настройки не требуются.</p><button id="syncSettingsOpen">Открыть аккаунт</button><span id="installWrap"></span>';
  var single=root.querySelector('.single')||root;single.appendChild(card);q('#syncSettingsOpen').onclick=openModal;renderInstallButton();
}
function renderInstallButton(){var w=q('#installWrap');if(!w)return;if(installPrompt){w.innerHTML=' <button id="installBtn" class="secondary">Установить на компьютер</button>';q('#installBtn').onclick=async function(){var p=installPrompt;installPrompt=null;await p.prompt().catch(function(){});renderInstallButton()}}else w.innerHTML='';}
function openModal(){q('#syncModal').hidden=false;renderAccount();updateTop()}
function closeModal(){q('#syncModal').hidden=true}
function buildUi(){
  var top=q('.topbar'),mode=q('.mode');if(top&&!q('#syncTopBtn')){var b=document.createElement('button');b.id='syncTopBtn';b.className='accent';b.textContent='Войти';b.onclick=openModal;top.insertBefore(b,mode)}
  if(!q('#syncModal')){var d=document.createElement('div');d.id='syncModal';d.className='sync-modal';d.hidden=true;d.innerHTML='<div class="sync-dialog"><div class="sync-head"><h2>Органайзер Про</h2><button id="syncClose" class="secondary">Закрыть</button></div><div class="sync-card" id="syncAccount"></div><div id="syncStatus" class="sync-status"></div></div>';document.body.appendChild(d);q('#syncClose').onclick=closeModal;d.onclick=function(e){if(e.target===d)closeModal()}}
  renderAccount();updateTop();ensureSettingsCard();
}

window.addEventListener('beforeinstallprompt',function(e){e.preventDefault();installPrompt=e;renderInstallButton()});
window.addEventListener('online',function(){updateTop();autoSync(false)});window.addEventListener('offline',function(){updateTop()});
document.addEventListener('visibilitychange',function(){if(!document.hidden)autoSync(false)});
window.addEventListener('organizer:local-change',function(){if(!suppress)scheduleSync()});
var settings=q('#settingsSection');if(settings)new MutationObserver(function(){ensureSettingsCard()}).observe(settings,{childList:true,subtree:false});

buildUi();setInterval(function(){autoSync(false)},30000);setTimeout(function(){autoSync(false)},1200);
})();
