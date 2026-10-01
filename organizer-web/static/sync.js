(function(){
'use strict';

var STATE_KEY='organizer-pro-web-v1';
var LEGACY_CONFIG_KEY='organizer-sync-config-v1';
var SESSION_KEY='organizer-sync-session-v1';
var META_KEY='organizer-sync-meta-v2';
var DEFAULT_STATE={notes:[],projects:[],stages:[],money:[],reminders:[],attachments:[],revision:1};
var busy=false,localTimer=null,suppressLocalEvent=false,deferredInstall=null;

function q(s){return document.querySelector(s)}
function esc(v){return String(v==null?'':v).replace(/[&<>"']/g,function(c){return {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]})}
function readJson(key,fallback){try{return JSON.parse(localStorage.getItem(key)||'null')||fallback}catch(e){return fallback}}
function writeJson(key,value){localStorage.setItem(key,JSON.stringify(value))}
function session(){return readJson(SESSION_KEY,{})}
function meta(){return readJson(META_KEY,{lastCloudAt:0,lastSyncSig:''})}
function setMeta(v){writeJson(META_KEY,v)}
function cloudConfig(){
  var fixed=window.ORGANIZER_CLOUD_CONFIG||{};
  if(fixed.projectId&&fixed.apiKey)return {projectId:String(fixed.projectId),apiKey:String(fixed.apiKey)};
  var old=readJson(LEGACY_CONFIG_KEY,{});
  return {projectId:old.projectId||'',apiKey:old.apiKey||''};
}
function configured(){var c=cloudConfig();return !!(c.projectId&&c.apiKey)}
function friendlyError(data,status){var m=(data&&data.error&&data.error.message)||('HTTP '+status);m=String(m).replace(/_/g,' ');if(/INVALID_LOGIN_CREDENTIALS|INVALID_PASSWORD|EMAIL_NOT_FOUND/i.test(m))return 'Неверный email или пароль';if(/EMAIL_EXISTS/i.test(m))return 'Аккаунт с таким email уже существует';if(/WEAK_PASSWORD/i.test(m))return 'Пароль должен быть не короче 6 символов';if(/NETWORK|Failed to fetch/i.test(m))return 'Нет соединения с интернетом';return m}
function setStatus(text,isError){var x=q('#syncStatus');if(x){x.textContent=text||'';x.className=isError?'sync-status error':'sync-status'};updateTop(text,isError)}
function loadState(){return Object.assign({},DEFAULT_STATE,readJson(STATE_KEY,DEFAULT_STATE))}
function writeState(v){suppressLocalEvent=true;try{writeJson(STATE_KEY,v)}finally{suppressLocalEvent=false}}
function activeCount(st){return ['notes','projects','stages','money','reminders'].reduce(function(n,k){return n+(Array.isArray(st[k])?st[k].filter(function(x){return !x.deletedAt}).length:0)},0)}
function signature(text){var h=2166136261;for(var i=0;i<text.length;i++){h^=text.charCodeAt(i);h=Math.imul(h,16777619)}return (h>>>0).toString(16)+'-'+text.length}
function saveSafetyCopy(st,reason){try{localStorage.setItem('organizer-safety-'+Date.now(),JSON.stringify({reason:reason,state:st}))}catch(e){}}

async function api(url,opts,allow404){
  var r=await fetch(url,opts||{}),text=await r.text(),data={};
  try{data=text?JSON.parse(text):{}}catch(e){data={raw:text}}
  if(allow404&&r.status===404)return null;
  if(!r.ok)throw new Error(friendlyError(data,r.status));
  return data;
}
async function authRequest(mode,email,password){
  var c=cloudConfig();if(!configured())throw new Error('Облачная синхронизация пока не подключена');
  var data=await api('https://identitytoolkit.googleapis.com/v1/accounts:'+mode+'?key='+encodeURIComponent(c.apiKey),{
    method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({email:email,password:password,returnSecureToken:true})
  });
  var s={email:email,idToken:data.idToken,refreshToken:data.refreshToken,uid:data.localId,expiresAt:Date.now()+Number(data.expiresIn||3600)*1000};
  writeJson(SESSION_KEY,s);return s;
}
async function validSession(){
  var s=session(),c=cloudConfig();
  if(!s.uid)throw new Error('Сначала войдите в Органайзер');
  if(s.idToken&&Number(s.expiresAt||0)>Date.now()+60000)return s;
  if(!s.refreshToken)throw new Error('Сессия завершена. Войдите снова.');
  var body='grant_type=refresh_token&refresh_token='+encodeURIComponent(s.refreshToken);
  var data=await api('https://securetoken.googleapis.com/v1/token?key='+encodeURIComponent(c.apiKey),{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:body});
  s.idToken=data.id_token;s.refreshToken=data.refresh_token||s.refreshToken;s.uid=data.user_id||s.uid;s.expiresAt=Date.now()+Number(data.expires_in||3600)*1000;
  writeJson(SESSION_KEY,s);return s;
}
function firestoreUrl(uid){var c=cloudConfig();return 'https://firestore.googleapis.com/v1/projects/'+encodeURIComponent(c.projectId)+'/databases/(default)/documents/users/'+encodeURIComponent(uid)+'/organizer/main'}

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
  st.revision=Number(st.revision||0)+1;writeState(st);return st;
}
function toBackup(st){
  st=normalizeState(st);
  return {
    format:'my-organizer-backup',version:7,createdAt:Date.now(),cloudSchema:'organizer-sync-v2',
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
  return {notes:Array.isArray(b.notes)?b.notes:[],projects:Array.isArray(b.projects)?b.projects:[],stages:Array.isArray(b.projectStages)?b.projectStages:[],money:Array.isArray(b.money)?b.money:[],reminders:Array.isArray(b.reminders)?b.reminders:[],attachments:old.attachments||[],revision:Number(old.revision||0)+1};
}
async function fetchCloud(s){return api(firestoreUrl(s.uid),{headers:{'Authorization':'Bearer '+s.idToken}},true)}
async function uploadPayload(s,payload,sig){
  if(payload.length>850000)throw new Error('Слишком большой объём данных. Вложения пока хранятся только на устройстве.');
  var t=Date.now();
  await api(firestoreUrl(s.uid),{method:'PATCH',headers:{'Content-Type':'application/json','Authorization':'Bearer '+s.idToken},body:JSON.stringify({fields:{payload:{stringValue:payload},updatedAt:{integerValue:String(t)},schema:{stringValue:'organizer-sync-v2'}}})});
  setMeta({lastCloudAt:t,lastSyncSig:sig,lastSyncAt:t});return t;
}
function applyCloud(payload,cloudAt,cloudSig){
  var backup=JSON.parse(payload),local=loadState();
  if(activeCount(local)>0)saveSafetyCopy(local,'Перед получением облачной копии');
  writeState(fromBackup(backup));setMeta({lastCloudAt:cloudAt,lastSyncSig:cloudSig,lastSyncAt:Date.now()});
}

async function autoSync(force){
  if(busy||!configured())return updateTop();
  if(!navigator.onLine){setStatus('Нет интернета — изменения сохраняются на этом компьютере');return}
  if(!session().uid)return updateTop();
  busy=true;setStatus('Синхронизация…');
  try{
    var s=await validSession(),local=loadState(),localPayload=JSON.stringify(toBackup(local)),localSig=signature(localPayload),m=meta(),doc=await fetchCloud(s);
    if(!doc){await uploadPayload(s,localPayload,localSig);setStatus('Синхронизировано');return}
    var fields=doc.fields||{},cloudPayload=fields.payload&&fields.payload.stringValue,cloudAt=Number(fields.updatedAt&&fields.updatedAt.integerValue||0);
    if(!cloudPayload){await uploadPayload(s,localPayload,localSig);setStatus('Синхронизировано');return}
    var cloudSig=signature(cloudPayload);
    if(!m.lastSyncSig){
      if(localSig===cloudSig){setMeta({lastCloudAt:cloudAt,lastSyncSig:localSig,lastSyncAt:Date.now()});setStatus('Синхронизировано');return}
      applyCloud(cloudPayload,cloudAt,cloudSig);setStatus('Данные получены');setTimeout(function(){location.reload()},250);return;
    }
    var localDirty=localSig!==m.lastSyncSig,remoteDirty=cloudSig!==m.lastSyncSig;
    if(localDirty&&!remoteDirty){await uploadPayload(s,localPayload,localSig);setStatus('Синхронизировано');return}
    if(!localDirty&&remoteDirty){applyCloud(cloudPayload,cloudAt,cloudSig);setStatus('Данные обновлены');setTimeout(function(){location.reload()},250);return}
    if(localDirty&&remoteDirty){saveSafetyCopy(local,'Конфликт синхронизации');applyCloud(cloudPayload,cloudAt,cloudSig);setStatus('Синхронизировано');setTimeout(function(){location.reload()},250);return}
    if(cloudAt!==m.lastCloudAt)setMeta({lastCloudAt:cloudAt,lastSyncSig:localSig,lastSyncAt:Date.now()});
    setStatus('Синхронизировано');
  }catch(e){setStatus(e&&e.message?e.message:String(e),true)}finally{busy=false}
}
function scheduleSync(){clearTimeout(localTimer);localTimer=setTimeout(function(){autoSync(false)},1400)}

function updateTop(message,isError){
  var b=q('#syncTopBtn'),m=q('.mode'),s=session();
  if(b)b.textContent=s.uid?'Аккаунт':'Войти';
  if(!m)return;
  if(!configured())m.textContent='Локальный режим';
  else if(!navigator.onLine)m.textContent='Офлайн • данные сохранены';
  else if(!s.uid)m.textContent='Войдите для синхронизации';
  else if(isError)m.textContent='Ошибка синхронизации';
  else if(message)m.textContent=message;
  else m.textContent='Синхронизация включена';
}
function renderAccount(){
  var s=session(),logged=!!s.uid,box=q('#syncAccount');if(!box)return;
  if(!configured()){
    box.innerHTML='<div class="sync-user"><b>Облачная синхронизация подготавливается.</b></div><p class="sync-hint">Пока Органайзер продолжает работать локально на этом компьютере.</p>';return;
  }
  if(logged){
    box.innerHTML='<div class="sync-user"><b>'+esc(s.email||'')+'</b><br><span class="sync-ok">Синхронизация включена</span></div><div class="sync-actions"><button id="syncNow" class="secondary">Синхронизировать сейчас</button><button id="syncLogout" class="secondary">Выйти</button></div><p class="sync-hint">Обычно ничего нажимать не нужно: изменения отправляются и получаются автоматически.</p>';
    q('#syncNow').onclick=function(){autoSync(true)};
    q('#syncLogout').onclick=function(){localStorage.removeItem(SESSION_KEY);localStorage.removeItem(META_KEY);setStatus('Выход выполнен');renderAccount();updateTop()};
  }else{
    box.innerHTML='<div class="sync-grid"><label>Email<input id="syncEmail" type="email" autocomplete="username"></label><label>Пароль<input id="syncPassword" type="password" autocomplete="current-password"></label></div><div class="sync-actions"><button id="syncLogin">Войти</button><button id="syncRegister" class="secondary">Создать аккаунт</button></div><p class="sync-hint">На телефоне и компьютере используйте один и тот же аккаунт.</p>';
    q('#syncLogin').onclick=function(){var e=q('#syncEmail').value.trim(),p=q('#syncPassword').value;if(!e||!p){setStatus('Введите email и пароль',true);return}accountAction(function(){return authRequest('signInWithPassword',e,p)},'Вход выполнен')};
    q('#syncRegister').onclick=function(){var e=q('#syncEmail').value.trim(),p=q('#syncPassword').value;if(!e){setStatus('Введите email',true);return}if(p.length<6){setStatus('Пароль должен быть не короче 6 символов',true);return}accountAction(function(){return authRequest('signUp',e,p)},'Аккаунт создан')};
  }
}
async function accountAction(fn,ok){if(busy)return;busy=true;setStatus('Подключение…');try{await fn();setStatus(ok);renderAccount();updateTop();busy=false;await autoSync(true)}catch(e){busy=false;setStatus(e&&e.message?e.message:String(e),true)}}
function openModal(){q('#syncModal').hidden=false;renderAccount();updateTop()}
function closeModal(){q('#syncModal').hidden=true}
function fixSettingsVersion(){setTimeout(function(){var root=q('#settingsSection');if(!root)return;root.innerHTML=root.innerHTML.replace('Облачная синхронизация с Android будет подключена отдельным этапом после стабилизации локальной версии.','При входе в аккаунт данные между телефоном и компьютером синхронизируются автоматически.').replace('Органайзер Про Web 0.2.0 Static','Органайзер Про Web 0.4.0')},0)}
function install(){
  var brand=q('.brand small');if(brand)brand.textContent='WEB 0.4.0';
  var top=q('.topbar');if(top&&!q('#syncTopBtn')){var b=document.createElement('button');b.id='syncTopBtn';b.className='accent';b.textContent='Войти';b.onclick=openModal;var sp=q('.topbar .spacer');top.insertBefore(b,sp)}
  if(!q('#syncModal')){var wrap=document.createElement('div');wrap.id='syncModal';wrap.className='sync-modal';wrap.hidden=true;wrap.innerHTML='<div class="sync-dialog"><div class="sync-head"><h2>Органайзер Про</h2><button id="syncClose" class="secondary">Закрыть</button></div><div class="sync-card"><div id="syncAccount"></div></div><div id="syncStatus" class="sync-status"></div></div>';document.body.appendChild(wrap);q('#syncClose').onclick=closeModal;wrap.onclick=function(e){if(e.target===wrap)closeModal()}}
  var sn=q('[data-section="settings"]');if(sn)sn.addEventListener('click',fixSettingsVersion);
  updateTop();renderAccount();
  if(session().uid)setTimeout(function(){autoSync(true)},700);
}

var originalSet=Storage.prototype.setItem;
Storage.prototype.setItem=function(key,value){originalSet.call(this,key,value);if(this===localStorage&&key===STATE_KEY&&!suppressLocalEvent)window.dispatchEvent(new Event('organizer-local-change'))};
window.addEventListener('organizer-local-change',scheduleSync);
window.addEventListener('online',function(){updateTop();autoSync(true)});
window.addEventListener('offline',updateTop);
window.addEventListener('focus',function(){autoSync(false)});
document.addEventListener('visibilitychange',function(){if(document.visibilityState==='visible')autoSync(false)});
setInterval(function(){autoSync(false)},30000);
window.addEventListener('beforeinstallprompt',function(e){e.preventDefault();deferredInstall=e;var top=q('.topbar'),sp=q('.topbar .spacer');if(top&&!q('#installBtn')){var b=document.createElement('button');b.id='installBtn';b.className='secondary';b.textContent='Установить';b.onclick=async function(){if(!deferredInstall)return;deferredInstall.prompt();await deferredInstall.userChoice;deferredInstall=null;b.remove()};top.insertBefore(b,sp)}});
window.addEventListener('appinstalled',function(){var b=q('#installBtn');if(b)b.remove();deferredInstall=null});

if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',install);else install();
})();
