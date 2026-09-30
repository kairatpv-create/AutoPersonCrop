import type {User} from 'firebase/auth'
import type {OrganizerState,CloudRecord} from './types'

// Portable desktop build: cloud sync stays disabled until the hosted HTTPS
// version is connected to the Firebase project. Keeping Firebase out of the
// startup bundle makes file:// launch reliable in Chrome/Edge.
export const cloudConfigured=false
export const auth=null
export const db=null

export async function loginGoogle(){throw new Error('Облачная синхронизация ещё не подключена')}
export async function logout(){}
export function watchUser(cb:(u:User|null)=>void){cb(null);return()=>{}}
export async function pushState(_user:User,_state:OrganizerState){}
export function subscribeCloud(_user:User,_onRows:(rows:CloudRecord[])=>void){return()=>{}}
export function mergeCloud(local:OrganizerState,_rows:CloudRecord[]):OrganizerState{return local}
