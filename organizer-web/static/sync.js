(function(){
'use strict';

var STATE_KEY='organizer-pro-web-v1';
var CONFIG_KEY='organizer-sync-config-v1';
var SESSION_KEY='organizer-sync-session-v1';
var DEFAULT_STATE={notes:[],projects:[],stages:[],money:[],reminders:[],attachments:[],revision:1};
var busy=false;

function q(s){return document.querySelector(s)}
function esc(v){return String(v==null?'':v).replace(/[&<>"']/g,function(c){return {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]})}
function readJson(key,fallback){try{return JSON.parse(localStorage.getItem(key)||'null')||fallback}catch(e){return fallback}}
function writeJson(key,value){localStorage.setItem(key,JSON.stringify(value))}
function config(){return readJson(CONFIG_KEY,{apiKey:'',projectId:''})}
function session(){return readJson(SESSION_KEY,{})}
function setStatus(text,isError){var x=q('#syncStatus');if(x){x.textContent=text||'';x.className=isError?'sync-status error':'sync-status'}}
function configured(){var c=config();return !!(c.apiKey&&c.projectId)}
function friendlyError(data,status){var m=(data&&data.error&&data.error.message)||('HTTP '+status);return String(m).replace(/_/g,' ')}

async function api(url,opts){
  var r=await fetch(url,opts||{}),text=await r.text(),data={};
  try{data=text?JSON.parse(text):{}}catch(e){data={raw:text}}
  if(!r.ok)throw new Error(friendlyError(data,r.status));
  return data;
}
async function authRequest(mode,email,password){
  var c=config();if(!c.apiKey)throw new Error('Сначала укажите Web API key Firebase');
  var data=await api('https://identitytoolkit.googleapis.com/v1/accounts:'+mode+'?key='+encodeURIComponent(c.apiKey),{
    method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({email:email,password:password,returnSecureToken:true})
  });
  var s={email:email,idToken:data.idToken,refreshToken:data.refreshToken,uid:data.localId,expiresAt:Date.now()+Number(data.expiresIn||3600)*1000};
  writeJson(SESSION_KEY,s);return s;
}
async function validSession(){
  var s=session(),c=config();
  if(!s.uid)throw new Error('Сначала войдите в аккаунт синхронизации');
  if(s.idToken&&Number(s.expiresAt||0)>Date.now()+60000)return s;
  if(!s.refreshToken)throw new Error('Сессия истекла. Войдите снова.');
  var body='grant_type=refresh_token&refresh_token='+encodeURIComponent(s.refreshToken);
  var data=await api('https://securetoken.googleapis.com/v1/token?key='+encodeURIComponent(c.apiKey),{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:body});
  s.idToken=data.id_token;s.refreshToken=data.refresh_token||s.refreshToken;s.uid=data.user_id||s.uid;s.expiresAt=Date.now()+Number(data.expires_in||3600)*1000;
  writeJson(SESSION_KEY,s);return s;
}
function firestoreUrl(uid){var c=config();return 'https://firestore.googleapis.com/v1/projects/'+encodeURIComponent(c.projectId)+'/databases/(default)/documents/users/'+encodeURIComponent(uid)+'/organizer/main'}

function loadState(){return Object.assign({},DEFAULT_STATE,readJson(STATE_KEY,DEFAULT_STATE))}
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
  st.revision=Number(st.revision||0)+1;writeJson(STATE_KEY,st);return st;
}
function toBackup(st){
  st=normalizeState(st);
  return {
    format:'my-organizer-backup',version:7,createdAt:Date.now(),cloudSchema:'organizer-sync-v1',
    notes:(st.notes||[]).filter(function(x){return !x.deletedAt}).map(function(x){return {id:x.id,title:x.title||'',body:x.body||'',createdAt:Number(x.createdAt||Date.now()),updatedAt:Number(x.updatedAt||x.createdAt||Date.now()),colorKey:x.colorKey||'blue'}}),
    tasks:[],
    projects:(st.projects||[]).filter(function(x){return !x.deletedAt}).map(function(x){return {id:x.id,name:x.name||'',description:x.description||'',status:x.status||'planned',progress:Number(x.progress||0),startAt:Number(x.startAt||x.createdAt||Date.now()),dueAt:Number(x.dueAt||0),budget:Number(x.budget||0),client:x.client||'',address:x.address||'',contact:x.contact||'',createdAt:Number(x.createdAt||Date.now()),updatedAt:Number(x.updatedAt||x.createdAt||Date.now()),colorKey:x.colorKey||'blue'}}),
    projectStages:(st.stages||[]).filter(function(x){return !x.deletedAt}).map(function(x){return {id:x.id,projectId:x.projectId,title:x.title||'',done:!!x.done,dueAt:Number(x.dueAt||0),createdAt:Number(x.createdAt||Date.now())}}),
    projectJournal:[],projectNoteLinks:[],
    money:(st.money||[]).filter(function(x){return !x.deletedAt}).map(function(x){var y={id:x.id,type:x.type||'expense',amount:Number(x.amount||0),category:x.category||'',projectName:x.projectName||'',note:x.note||'',createdAt:Number(x.createdAt||Date.now()),colorKey:x.colorKey||'blue'};if(x.projectId!=null)y.projectId=x.projectId;return y}),
    reminders:(st.reminders||[]).filter(function(x){return !x.deletedAt}).map(function(x){return {id:x.id,targetType:x.targetType||'note',targetId:Number(x.targetId||0),triggerAt:Number(x.triggerAt||0),sound:x.sound!==false,vibrate:x.vibrate!==false,soundKey:x.soundKey||'system',createdAt:Number(x.createdAt||Date.now()),scheduleType:x.scheduleType||'once',daysMask:Number(x.daysMask||0),dayMode:x.dayMode||'single',baseMinutes:Number(x.baseMinutes||540)}})
  };
}
function fromBackup(b){
  var old=loadState();
  return {
    notes:Array.isArray(b.notes)?b.notes:[],
    projects:Array.isArray(b.projects)?b.projects:[],
    stages:Array.isArray(b.projectStages)?b.projectStages:[],
    money:Array.isArray(b.money)?b.money:[],
    reminders:Array.isArray(b.reminders)?b.reminders:[],
    attachments:old.attachments||[],
    revision:Number(old.revision||0)+1
  };
}
async function uploadCloud(){
  var s=await validSession(),payload=JSON.stringify(toBackup(loadState()));
  if(payload.length>850000)throw new Error('Объём данных слишком большой для первой версии облачной синхронизации. Вложения пока не синхронизируются.');
  await api(firestoreUrl(s.uid),{method:'PATCH',headers:{'Content-Type':'application/json','Authorization':'Bearer '+s.idToken},body:JSON.stringify({fields:{payload:{stringValue:payload},updatedAt:{integerValue:String(Date.now())},schema:{stringValue:'organizer-sync-v1'}}})});
  setStatus('Данные этого компьютера отправлены в облако.');
}
async function downloadCloud(){
  var s=await validSession(),doc=await api(firestoreUrl(s.uid),{headers:{'Authorization':'Bearer '+s.idToken}});
  var payload=doc&&doc.fields&&doc.fields.payload&&doc.fields.payload.stringValue;
  if(!payload)throw new Error('В облаке пока нет данных Органайзера');
  var backup=JSON.parse(payload);writeJson(STATE_KEY,fromBackup(backup));setStatus('Данные получены. Перезагружаю интерфейс…');setTimeout(function(){location.reload()},450);
}
function setBusy(v){busy=v;document.querySelectorAll('#syncModal button').forEach(function(b){b.disabled=v})}
async function action(fn){if(busy)return;setBusy(true);setStatus('Выполняю…');try{await fn();renderAccount()}catch(e){setStatus(e&&e.message?e.message:String(e),true)}finally{setBusy(false)}}

function renderAccount(){
  var s=session(),logged=!!s.uid,box=q('#syncAccount');if(!box)return;
  if(logged){box.innerHTML='<div class="sync-user">Аккаунт: <b>'+esc(s.email||'')+'</b></div><div class="sync-actions"><button id="syncUpload">Отправить с компьютера</button><button id="syncDownload">Получить из облака</button><button id="syncLogout" class="secondary">Выйти</button></div><p class="sync-hint">В первой версии синхронизация запускается вручную. Перед получением данных убедитесь, что изменения на другом устройстве уже отправлены в облако.</p>';
    q('#syncUpload').onclick=function(){action(uploadCloud)};q('#syncDownload').onclick=function(){action(downloadCloud)};q('#syncLogout').onclick=function(){localStorage.removeItem(SESSION_KEY);setStatus('Выход выполнен');renderAccount();updateTop()};
  }else{box.innerHTML='<div class="sync-grid"><label>Email<input id="syncEmail" type="email" autocomplete="username"></label><label>Пароль<input id="syncPassword" type="password" autocomplete="current-password"></label></div><div class="sync-actions"><button id="syncLogin">Войти</button><button id="syncRegister" class="secondary">Создать аккаунт</button></div><p class="sync-hint">Используйте один и тот же email и пароль на телефоне и компьютере. Google-вход добавим после стабилизации первой синхронизации.</p>';
    q('#syncLogin').onclick=function(){var e=q('#syncEmail').value.trim(),p=q('#syncPassword').value;action(function(){return authRequest('signInWithPassword',e,p).then(function(){setStatus('Вход выполнен');updateTop()})})};
    q('#syncRegister').onclick=function(){var e=q('#syncEmail').value.trim(),p=q('#syncPassword').value;if(p.length<6){setStatus('Пароль должен быть не короче 6 символов',true);return}action(function(){return authRequest('signUp',e,p).then(function(){setStatus('Аккаунт создан');updateTop()})})};
  }
}
function renderConfig(){var c=config();q('#syncProjectId').value=c.projectId||'';q('#syncApiKey').value=c.apiKey||'';}
function updateTop(){var b=q('#syncTopBtn'),m=q('.mode'),s=session();if(b)b.textContent=s.uid?'Синхронизация ✓':'Синхронизация';if(m)m.textContent=configured()?(s.uid?'Облако подключено':'Облако настроено, вход не выполнен'):'Локально • облако не настроено'}
function saveConfig(){var c={projectId:q('#syncProjectId').value.trim(),apiKey:q('#syncApiKey').value.trim()};writeJson(CONFIG_KEY,c);setStatus(c.projectId&&c.apiKey?'Сервер сохранён. Теперь войдите в аккаунт.':'Нужно заполнить Project ID и Web API key',!(c.projectId&&c.apiKey));updateTop();}
function openModal(){q('#syncModal').hidden=false;renderConfig();renderAccount();updateTop();}
function closeModal(){q('#syncModal').hidden=true}
function install(){
  var brand=q('.brand small');if(brand)brand.textContent='WEB 0.3.0';
  var top=q('.topbar');if(top&&!q('#syncTopBtn')){var b=document.createElement('button');b.id='syncTopBtn';b.className='accent';b.textContent='Синхронизация';b.onclick=openModal;var sp=q('.topbar .spacer');top.insertBefore(b,sp)}
  if(!q('#syncModal')){var wrap=document.createElement('div');wrap.id='syncModal';wrap.className='sync-modal';wrap.hidden=true;wrap.innerHTML='<div class="sync-dialog"><div class="sync-head"><h2>Синхронизация с телефоном</h2><button id="syncClose" class="secondary">Закрыть</button></div><div class="sync-card"><b>Сервер Firebase</b><div class="sync-grid"><label>Project ID<input id="syncProjectId" placeholder="например organizer-pro-123"></label><label>Web API key<input id="syncApiKey" type="password" placeholder="AIza…"></label></div><div class="sync-actions"><button id="syncSaveConfig">Сохранить сервер</button></div><p class="sync-hint">Эти два значения берутся из настроек Firebase. Они должны быть одинаковыми на телефоне и компьютере.</p></div><div class="sync-card"><b>Аккаунт Органайзера</b><div id="syncAccount"></div></div><div id="syncStatus" class="sync-status"></div><p class="sync-hint"><b>Сейчас:</b> заметки, проекты, этапы, финансы и напоминания. Вложения остаются локально и будут добавлены отдельным этапом.</p></div>';document.body.appendChild(wrap);q('#syncClose').onclick=closeModal;q('#syncSaveConfig').onclick=saveConfig;wrap.onclick=function(e){if(e.target===wrap)closeModal()}}
  updateTop();
}
if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',install);else install();
})();
