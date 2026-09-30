import {StrictMode} from 'react'
import {createRoot} from 'react-dom/client'
import App from './App'

function showFatal(message:string){
  const root=document.getElementById('root')
  if(root) root.innerHTML=`<div style="font-family:Segoe UI,Arial,sans-serif;padding:24px;color:#8b1e1e"><h2>Органайзер Про Web</h2><p>Ошибка запуска веб-версии.</p><pre style="white-space:pre-wrap">${message.replace(/[&<>]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;'}[c]||c))}</pre></div>`
}
window.addEventListener('error',e=>showFatal(e.message||'Неизвестная ошибка'))
window.addEventListener('unhandledrejection',e=>showFatal(String(e.reason||'Неизвестная ошибка')))
try{
  const root=document.getElementById('root')
  if(!root) throw new Error('Не найден корневой элемент интерфейса')
  createRoot(root).render(<StrictMode><App/></StrictMode>)
}catch(e){
  showFatal(e instanceof Error?e.message:String(e))
}
