import {initializeApp} from 'firebase/app'
import {getAuth,GoogleAuthProvider,signInWithPopup,signOut,onAuthStateChanged,type User} from 'firebase/auth'
import {getFirestore,collection,doc,onSnapshot,runTransaction,setDoc,type Firestore} from 'firebase/firestore'
import type {OrganizerState,CloudRecord,RecordKind} from './types'

const cfg={apiKey:import.meta.env.VITE_FIREBASE_API_KEY,authDomain:import.meta.env.VITE_FIREBASE_AUTH_DOMAIN,projectId:import.meta.env.VITE_FIREBASE_PROJECT_ID,storageBucket:import.meta.env.VITE_FIREBASE_STORAGE_BUCKET,appId:import.meta.env.VITE_FIREBASE_APP_ID}
export const cloudConfigured=Object.values(cfg).every(Boolean)
const app=cloudConfigured?initializeApp(cfg):null
export const auth=app?getAuth(app):null
export const db=app?getFirestore(app):null
const provider=new GoogleAuthProvider()

export async function loginGoogle(){if(!auth)throw new Error('Firebase not configured');return signInWithPopup(auth,provider)}
export async function logout(){if(auth)await signOut(auth)}
export function watchUser(cb:(u:User|null)=>void){return auth?onAuthStateChanged(auth,cb):()=>{}}

const groups:[RecordKind,keyof OrganizerState][]=[['note','notes'],['project','projects'],['stage','stages'],['money','money'],['reminder','reminders'],['attachment','attachments']]
export async function pushState(user:User,state:OrganizerState){if(!db)return;for(const [kind,key] of groups){for(const item of state[key] as any[]){const ref=doc(db,'users',user.uid,'organizerRecords',`${kind}_${item.id}`);await runTransaction(db,async tx=>{const snap=await tx.get(ref);const remote=snap.data() as CloudRecord|undefined;if(!remote||Number(remote.updatedAt||0)<=Number(item.updatedAt||0))tx.set(ref,{kind,id:item.id,updatedAt:item.updatedAt||item.createdAt||Date.now(),deletedAt:item.deletedAt||null,data:item})})}}}
export function subscribeCloud(user:User,onRows:(rows:CloudRecord[])=>void){if(!db)return()=>{};return onSnapshot(collection(db,'users',user.uid,'organizerRecords'),s=>onRows(s.docs.map(d=>d.data() as CloudRecord)))}
export function mergeCloud(local:OrganizerState,rows:CloudRecord[]):OrganizerState{const next:OrganizerState=structuredClone(local);for(const r of rows){const key=groups.find(x=>x[0]===r.kind)?.[1];if(!key)continue;const arr=next[key] as any[];const i=arr.findIndex(x=>x.id===r.id);const remote:any={...(r.data as object),deletedAt:r.deletedAt||undefined};if(i<0)arr.push(remote);else if(Number(arr[i].updatedAt||0)<=Number(r.updatedAt||0))arr[i]=remote}next.revision++;return next}
