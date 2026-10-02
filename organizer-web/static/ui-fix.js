(function(){
'use strict';

// Organizer Pro Web 0.5.3 compatibility/UI hotfix.
// Cloud synchronization stores Android-compatible numeric IDs. HTML data-* attributes
// are strings, so the legacy strict ID lookup could no longer open synced records.
var originalGetAttribute=Element.prototype.getAttribute;
var numericIdAttrs={
  'data-note':true,
  'data-project':true,
  'data-stage':true,
  'data-money':true
};
Element.prototype.getAttribute=function(name){
  var value=originalGetAttribute.call(this,name);
  if(numericIdAttrs[name]&&typeof value==='string'&&/^\d+$/.test(value))return Number(value);
  return value;
};

function triggerInput(node){
  if(!node)return;
  node.dispatchEvent(new Event('input',{bubbles:true}));
}

function markSaved(button){
  if(!button)return;
  var old=button.textContent;
  button.textContent='Сохранено';
  button.disabled=true;
  setTimeout(function(){
    if(!button.isConnected)return;
    button.textContent=old;
    button.disabled=false;
  },900);
}

function installNoteSave(){
  var root=document.getElementById('notesSection');
  var title=document.getElementById('noteTitle');
  var body=document.getElementById('noteBody');
  var del=document.getElementById('deleteNote');
  if(!root||!title||!body||!del||document.getElementById('saveNote'))return;
  var b=document.createElement('button');
  b.id='saveNote';
  b.textContent='Сохранить';
  b.style.marginRight='10px';
  del.parentNode.insertBefore(b,del);
  b.onclick=function(){
    triggerInput(title);
    triggerInput(body);
    markSaved(b);
  };
}

function installProjectSave(){
  var root=document.getElementById('projectsSection');
  var name=document.getElementById('projectName');
  var del=document.getElementById('deleteProject');
  if(!root||!name||!del||document.getElementById('saveProject'))return;
  var b=document.createElement('button');
  b.id='saveProject';
  b.textContent='Сохранить';
  b.style.marginRight='10px';
  del.parentNode.insertBefore(b,del);
  b.onclick=function(){
    ['projectName','projectDesc','projectStatus','projectProgress','projectStart','projectDue','projectBudget','projectClient'].forEach(function(id){triggerInput(document.getElementById(id));});
    markSaved(b);
  };
}

function fixVersionLabel(){
  var root=document.getElementById('settingsSection');
  if(!root)return;
  var nodes=root.querySelectorAll('p');
  nodes.forEach(function(p){
    if(/Органайзер Про Web 0\.5\.[0-2]/.test(p.textContent||''))p.textContent='Органайзер Про Web 0.5.3';
  });
}

function refreshFixes(){
  installNoteSave();
  installProjectSave();
  fixVersionLabel();
}

var observer=new MutationObserver(refreshFixes);
observer.observe(document.documentElement,{childList:true,subtree:true});
if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',refreshFixes);else refreshFixes();
})();
