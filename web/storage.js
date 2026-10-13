(function(root) {
"use strict";
const bytes=s=>Uint8Array.from(atob(s),c=>c.charCodeAt(0));
const b64=b=>{let s="";for(const n of new Uint8Array(b))s+=String.fromCharCode(n);return btoa(s);};
let keyPromise;
function key(create=true) {
 if(keyPromise)return keyPromise;
 keyPromise=new Promise((resolve,reject)=>{
  if(!root.crypto?.subtle||!root.indexedDB){reject(Error("Versleutelde opslag is niet beschikbaar in deze browser."));return;}
  const open=indexedDB.open("yshott-keys",1);
  open.onupgradeneeded=()=>open.result.createObjectStore("keys");
  open.onerror=()=>reject(open.error);
  open.onsuccess=()=>{
   const db=open.result,read=db.transaction("keys").objectStore("keys").get("planner");
   read.onerror=()=>{db.close();reject(read.error);};
   read.onsuccess=async()=>{
    try{
     if(read.result){db.close();resolve(read.result);return;}
     if(!create){db.close();reject(Error("De sleutel van je planner ontbreekt."));return;}
     const k=await crypto.subtle.generateKey({name:"AES-GCM",length:256},false,["encrypt","decrypt"]);
     const tx=db.transaction("keys","readwrite");tx.objectStore("keys").put(k,"planner");
     tx.oncomplete=()=>{db.close();resolve(k);};tx.onerror=()=>{db.close();reject(tx.error);};
    }catch(e){db.close();reject(e);}
   };
  };
 });
 return keyPromise;
}
async function load() {
 const raw=localStorage.getItem("yshott-vault-v1");if(!raw)return null;
 const envelope=JSON.parse(raw);if(envelope.v!==1)throw Error("Onbekende opslagversie.");
 const decoded=await crypto.subtle.decrypt({name:"AES-GCM",iv:bytes(envelope.iv)},await key(false),bytes(envelope.data));
 return JSON.parse(new TextDecoder().decode(decoded));
}
async function save(data,prefs) {
 const iv=crypto.getRandomValues(new Uint8Array(12));
 const encrypted=await crypto.subtle.encrypt({name:"AES-GCM",iv},await key(),new TextEncoder().encode(JSON.stringify({data,prefs})));
 localStorage.setItem("yshott-vault-v1",JSON.stringify({v:1,iv:b64(iv),data:b64(encrypted)}));
}
async function digest(pin,salt) {
 const k=await crypto.subtle.importKey("raw",new TextEncoder().encode(pin),"PBKDF2",false,["deriveBits"]);
 return b64(await crypto.subtle.deriveBits({name:"PBKDF2",hash:"SHA-256",salt:bytes(salt),iterations:210000},k,256));
}
async function setPin(pin) {
 if(!/^\d{4}$/.test(pin))throw Error("Kies precies vier cijfers.");
 const salt=b64(crypto.getRandomValues(new Uint8Array(16)));
 return {salt,hash:await digest(pin,salt),failures:0,lockedUntil:0};
}
async function checkPin(pin,security) {
 if(!security)throw Error("Stel eerst een ouderpincode in.");
 if(Date.now()<(security.lockedUntil||0))throw Error("Te veel pogingen. Probeer later opnieuw.");
 const a=bytes(await digest(pin,security.salt)),b=bytes(security.hash);
 let mismatch=a.length^b.length;for(let i=0;i<Math.max(a.length,b.length);i++)mismatch|=(a[i]||0)^(b[i]||0);
 if(mismatch)return {ok:false,security:{...security,failures:(security.failures||0)+1,lockedUntil:(security.failures||0)+1>=5?Date.now()+15*60*1000:0}};
 return {ok:true,security:{...security,failures:0,lockedUntil:0}};
}
root.YShottStore={load,save,setPin,checkPin};
})(globalThis);
