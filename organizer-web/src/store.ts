import type {OrganizerState} from './types'
const KEY='organizer-pro-web-v1'
export const emptyState:OrganizerState={notes:[],projects:[],stages:[],money:[],reminders:[],attachments:[],revision:1}
export function loadState():OrganizerState{try{return {...emptyState,...JSON.parse(localStorage.getItem(KEY)||'{}')}}catch{return emptyState}}
export function saveState(s:OrganizerState){localStorage.setItem(KEY,JSON.stringify(s))}
export const uid=()=>crypto.randomUUID()
export const now=()=>Date.now()
