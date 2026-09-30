import type {OrganizerState} from './types'
const KEY='organizer-pro-web-v1'
export const emptyState:OrganizerState={notes:[],projects:[],stages:[],money:[],reminders:[],attachments:[],revision:1}
let memoryState:OrganizerState=emptyState
export function loadState():OrganizerState{
  try{
    const raw=window.localStorage?.getItem(KEY)
    memoryState={...emptyState,...JSON.parse(raw||'{}')}
    return memoryState
  }catch{
    return memoryState
  }
}
export function saveState(s:OrganizerState){
  memoryState=s
  try{window.localStorage?.setItem(KEY,JSON.stringify(s))}catch{}
}
export const uid=()=>globalThis.crypto?.randomUUID?.()||`web-${Date.now()}-${Math.random().toString(16).slice(2)}`
export const now=()=>Date.now()
