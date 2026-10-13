/* YShott's browser model uses the Android PlannerData JSON field names. */
(function(root) {
"use strict";
const id=()=>globalThis.crypto?.randomUUID?.() || "id-"+Date.now().toString(36)+"-"+Math.random().toString(36).slice(2);
const date=d=>{const x=d||new Date();return [x.getFullYear(),String(x.getMonth()+1).padStart(2,"0"),String(x.getDate()).padStart(2,"0")].join("-");};
const parse=s=>{if(!/^\d{4}-\d{2}-\d{2}$/.test(s||""))throw Error("Controleer de datum.");const d=new Date(s+"T12:00:00");if(date(d)!==s)throw Error("Controleer de datum.");return d;};
const add=(s,n)=>{const d=parse(s);d.setDate(d.getDate()+n);return date(d);};
const monday=s=>{const d=parse(s);return add(s,-((d.getDay()+6)%7));};
const normalize=s=>String(s).trim().toLocaleLowerCase("nl-NL").replace(/\s+/g," ");
const occurs=(e,s)=>{
 if(s<e.date || (e.until && s>e.until))return false;
 if(s===e.date)return true;
 const d=parse(s),start=parse(e.date);
 return e.repeat==="daily" || e.repeat==="weekly" && d.getDay()===start.getDay() || e.repeat==="monthly" && d.getDate()===start.getDate();
};
const monthCells=s=>{
 const d=parse(s);d.setDate(1);const first=date(d);const start=monday(first);
 const last=new Date(d.getFullYear(),d.getMonth()+1,0);
 const count=Math.ceil(((d.getDay()+6)%7+last.getDate())/7)*7;
 return Array.from({length:count},(_,i)=>add(start,i));
};
const timeOK=s=>/^(?:[01]\d|2[0-3]):[0-5]\d$/.test(s||"");
function validate(data) {
 if(!data||!Array.isArray(data.people)||!Array.isArray(data.events))throw Error("Dit is geen YShott-back-up.");
 const arrays=["people","events","tasks","groceries","recipes","meals","rewards","rewardRequests","messages","proposals","residence"];
 for(const name of arrays) {
  if(!Array.isArray(data[name]) || data[name].length>5000)throw Error("Controleer de gegevens in "+name+".");
  const ids=data[name].map(x=>x.id);if(ids.some(x=>typeof x!=="string"||!x)||new Set(ids).size!==ids.length)throw Error("De back-up bevat lege of dubbele identificaties.");
 }
 const events=[...data.events,...data.proposals.map(x=>x.event)];
 for(const e of events) {
  parse(e.date);if(!String(e.title||"").trim()||!timeOK(e.time)||!timeOK(e.end)||e.end<=e.time||!["none","daily","weekly","monthly"].includes(e.repeat||"none"))throw Error("Controleer de afspraak en begin-/eindtijd.");
  if(e.until){parse(e.until);if(e.until<e.date)throw Error("De herhaling eindigt voor de begindatum.");}
 }
 for(const t of data.tasks)if(!["open","pending","done"].includes(t.status)||!Number.isInteger(t.points)||t.points<0)throw Error("Controleer de taak en punten.");else if(t.date)parse(t.date);
 for(const r of data.rewards)if(!Number.isInteger(r.points)||r.points<1)throw Error("Een beloning kost minstens één punt.");
 for(const r of data.rewardRequests)if(!Number.isInteger(r.points)||r.points<1||!["pending","approved","rejected"].includes(r.status))throw Error("Controleer de beloningsaanvraag.");
 for(const p of data.proposals)if(!["pending","approved","rejected"].includes(p.status))throw Error("Controleer het voorstel.");
 for(const m of data.meals){parse(m.date);if(!["Ontbijt","Lunch","Diner"].includes(m.slot))throw Error("Controleer de maaltijd.");}
 for(const r of data.residence){parse(r.starts);if(!Number.isInteger(r.repeatsWeeks)||r.repeatsWeeks<1||r.repeatsWeeks>8||r.days.length!==r.repeatsWeeks*7||r.days.some(n=>!String(n).trim())||!timeOK(r.switchTime)||!["concept","active"].includes(r.status))throw Error("Controleer het verblijfsschema.");}
 for(const p of data.people)if(points(data,p.id)<0)throw Error("Deze wijziging zou al bestede punten verwijderen.");
 if(!Array.isArray(data.staples)||!data.settings)throw Error("Controleer de instellingen.");
 return data;
}
const residence=(data,s,child="")=>{
 const r=data.residence.find(r=>r.status==="active"&&(!child||r.child===child));if(!r||s<r.starts)return null;
 const days=Math.round((parse(s)-parse(r.starts))/86400000);return r.days[days%r.days.length];
};
const points=(data,person)=>data.tasks.filter(t=>t.person===person&&t.status==="done").reduce((a,t)=>a+t.points,0)-data.rewardRequests.filter(r=>r.person===person&&r.status==="approved").reduce((a,r)=>a+r.points,0);
function complete(data,taskId,child="") {
 const t=data.tasks.find(x=>x.id===taskId);if(!t)throw Error("Dit klusje bestaat niet.");if(child&&t.person!==child)throw Error("Dit is niet jouw klusje.");
 if(t.status!=="open")return data;
 return {...data,tasks:data.tasks.map(x=>x.id===t.id?{...x,status:child&&t.parentCheck?"pending":"done"}:x)};
}
function approveTask(data,taskId) {return {...data,tasks:data.tasks.map(t=>t.id===taskId&&t.status==="pending"?{...t,status:"done"}:t)};}
function requestReward(data,rewardId,person) {
 const r=data.rewards.find(x=>x.id===rewardId);if(!r)throw Error("Beloning bestaat niet.");
 if(points(data,person)<r.points)throw Error("Je hebt nog niet genoeg punten.");
 if(data.rewardRequests.some(x=>x.person===person&&x.reward===r.id&&x.status==="pending"))throw Error("Je aanvraag wacht al op akkoord.");
 return {...data,rewardRequests:[...data.rewardRequests,{id:id(),person,reward:r.id,title:r.title,points:r.points,status:"pending"}]};
}
function approveReward(data,requestId) {
 const r=data.rewardRequests.find(x=>x.id===requestId);if(!r||r.status!=="pending")return data;
 if(points(data,r.person)<r.points)throw Error("Er zijn niet genoeg punten over.");
 return {...data,rewardRequests:data.rewardRequests.map(x=>x.id===r.id?{...x,status:"approved"}:x)};
}
function approveProposal(data,proposalId) {
 const p=data.proposals.find(x=>x.id===proposalId);if(!p||p.status!=="pending")return data;
 return {...data,proposals:data.proposals.map(x=>x.id===p.id?{...x,status:"approved"}:x),events:[...data.events.filter(e=>e.id!==p.oldEvent&&e.id!==p.event.id),p.event]};
}
function conflicts(e,events,s=e.date) {
 const participants=x=>[x.person,x.relatedChild,x.dropOff,x.pickUp].filter(Boolean);
 const a=participants(e);
 return events.filter(x=>x.id!==e.id&&occurs(x,s)&&occurs(e,s)&&e.time<x.end&&x.time<e.end&&(!a.length||!participants(x).length||a.some(p=>participants(x).includes(p))));
}
function groceriesFrom(data,ingredients,list="Boodschappen") {
 const known=new Set(data.groceries.filter(g=>g.list===list&&!g.done).map(g=>normalize(g.name)));
 const additions=[];
 for(const name of ingredients)if(String(name).trim()&&!known.has(normalize(name))){known.add(normalize(name));additions.push({id:id(),name:String(name).trim(),amount:"",list,category:"Overig",done:false});}
 return {...data,groceries:[...data.groceries,...additions]};
}
function example(s=date()) {
 const people=[{id:"papa",name:"Papa",emoji:"👨",color:0xFF9ACBB8,child:false},{id:"mama",name:"Mama",emoji:"👩",color:0xFFF2B99A,child:false},{id:"mila",name:"Mila",emoji:"👧",color:0xFFB6A0E2,child:true}];
 const recipe={id:"pasta",name:"Pasta met groenten",persons:4,minutes:30,ingredients:["400 g pasta","2 paprika's","1 courgette","400 g tomatenblokjes"],steps:["Kook de pasta volgens de verpakking.","Snijd de paprika en courgette.","Bak de groenten en voeg tomatenblokjes toe.","Meng met de pasta en serveer."],url:""};
 const event=(title,time,end,person,emoji,note="")=>({id:id(),title,date:s,time,end,person,place:"",note,emoji,repeat:"none",until:"",private:false,relatedChild:person==="papa"?"mila":"",dropOff:"",pickUp:person==="papa"?"papa":""});
 return {people,events:[event("School ophalen","15:00","15:30","papa","🏫"),event("Zwemles","17:00","18:00","mila","🏊","Papa brengt en haalt op")],tasks:[{id:id(),title:"Sporttas klaarzetten",person:"mila",date:s,points:5,parentCheck:true,status:"open",emoji:"🎒"},{id:id(),title:"Kamer opruimen",person:"mila",date:"",points:10,parentCheck:true,status:"open",emoji:"🧺"}],groceries:[],staples:["Melk","Brood","Fruit","Koffie"],recipes:[recipe],meals:[{id:id(),date:s,slot:"Diner",title:recipe.name,recipe:recipe.id}],rewards:[{id:id(),title:"Samen een spel spelen",points:10,emoji:"🎲"},{id:id(),title:"Een ijsje kiezen",points:20,emoji:"🍦"}],rewardRequests:[],messages:[],proposals:[],residence:[{id:id(),child:"mila",starts:s,repeatsWeeks:2,days:Array.from({length:14},(_,i)=>i<7?"Papa":"Mama"),switchTime:"18:00",location:"",status:"active"}],settings:{dark:false,homeCards:["residence","agenda","tasks","meal"],quietFrom:"21:00",quietTo:"07:00",notifications:true}};
}
const escapeIcs=s=>String(s||"").replace(/\\/g,"\\\\").replace(/\r?\n/g,"\\n").replace(/;/g,"\\;").replace(/,/g,"\\,");
function ics(events) {
 const lines=["BEGIN:VCALENDAR","VERSION:2.0","PRODID:-//YShott//Planner//NL","CALSCALE:GREGORIAN"];
 const stamp=(date,time)=>date.replace(/-/g,"")+"T"+time.replace(":","")+"00";
 for(const e of events) {
  lines.push("BEGIN:VEVENT","UID:"+escapeIcs(e.id)+"@yshott","DTSTAMP:"+new Date().toISOString().replace(/[-:]/g,"").replace(/\.\d{3}Z$/,"Z"),"DTSTART:"+stamp(e.date,e.time),"DTEND:"+stamp(e.date,e.end),"SUMMARY:"+escapeIcs(e.title),"DESCRIPTION:"+escapeIcs(e.note),"LOCATION:"+escapeIcs(e.place));
  if(e.repeat!=="none"&&e.repeat)lines.push("RRULE:FREQ="+({daily:"DAILY",weekly:"WEEKLY",monthly:"MONTHLY"}[e.repeat])+(e.until?";UNTIL="+stamp(e.until,"23:59"):""));
  lines.push("END:VEVENT");
 }
 lines.push("END:VCALENDAR");
 // Fold by UTF-8 octets without splitting a multibyte code point.
 return lines.map(line=>{let parts=[],part="",bytes=0;for(const c of line){const n=new TextEncoder().encode(c).length;if(bytes+n>75){parts.push(part);part=" ";bytes=1;}part+=c;bytes+=n;}parts.push(part);return parts.join("\r\n");}).join("\r\n")+"\r\n";
}
const api={id,date,parse,add,monday,normalize,occurs,monthCells,validate,residence,points,complete,approveTask,requestReward,approveReward,approveProposal,conflicts,groceriesFrom,example,ics};
if(typeof module!=="undefined"&&module.exports)module.exports=api;else root.YShott=api;
})(globalThis);
