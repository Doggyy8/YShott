"use strict";
const test=require("node:test"),assert=require("node:assert/strict"),M=require("../model.js");
const fresh=()=>M.example("2026-10-13");
test("Monday-first month grid includes leap February and adjacent days",()=>{
 assert.equal(M.monthCells("2026-10-13")[0],"2026-09-28");assert.equal(M.monthCells("2026-10-13").length,35);
 assert.ok(M.monthCells("2024-02-10").includes("2024-02-29"));assert.equal(M.monday("2026-10-18"),"2026-10-12");
});
test("recurrence respects start, until, weekday and month day",()=>{
 const e={...fresh().events[0],repeat:"weekly",until:"2026-10-27"};
 assert.equal(M.occurs(e,"2026-10-06"),false);assert.equal(M.occurs(e,"2026-10-20"),true);assert.equal(M.occurs(e,"2026-11-03"),false);
 assert.equal(M.occurs({...e,date:"2026-01-31",repeat:"monthly",until:""},"2026-02-28"),false);
});
test("invalid backup cannot replace valid data",()=>{
 assert.throws(()=>M.parse("2026-02-30"));const d=fresh();d.events[0].end="14:00";assert.throws(()=>M.validate(d));
 const dup=fresh();dup.tasks.push({...dup.tasks[0]});assert.throws(()=>M.validate(dup));
});
test("pickup participant overlap warns; unrelated parent is allowed",()=>{
 const d=fresh(),e={...d.events[0],id:"other",person:"mila",pickUp:"",relatedChild:"",time:"15:15",end:"15:45"};
 assert.equal(M.conflicts(e,d.events).length,1);
 assert.equal(M.conflicts({...e,person:"mama"},d.events).length,0);
});
test("child task needs parent approval, cannot claim another child's task, and does not double points",()=>{
 let d=fresh(),t=d.tasks[0];assert.throws(()=>M.complete(d,t.id,"other-child"));
 d=M.complete(d,t.id,"mila");assert.equal(d.tasks[0].status,"pending");assert.equal(M.points(d,"mila"),0);
 d=M.approveTask(d,t.id);assert.equal(M.points(d,"mila"),5);
 assert.equal(M.points(M.approveTask(d,t.id),"mila"),5);
});
test("rewards recheck points at approval and are idempotent",()=>{
 let d=fresh();d=M.approveTask(M.complete(d,d.tasks[1].id,"mila"),d.tasks[1].id);
 d=M.requestReward(d,d.rewards[0].id,"mila");assert.throws(()=>M.requestReward(d,d.rewards[0].id,"mila"));
 d=M.approveReward(d,d.rewardRequests[0].id);assert.equal(M.points(d,"mila"),0);
 assert.equal(M.points(M.approveReward(d,d.rewardRequests[0].id),"mila"),0);
 assert.throws(()=>M.requestReward(d,d.rewards[1].id,"mila"));
});
test("approved appointment proposal is added once",()=>{
 let d=fresh(),e={...d.events[0],id:"proposal-event",title:"Andere afspraak"};
 d.proposals.push({id:"proposal",event:e,oldEvent:"",status:"pending"});d=M.approveProposal(d,"proposal");d=M.approveProposal(d,"proposal");
 assert.equal(d.events.filter(x=>x.id===e.id).length,1);assert.equal(d.proposals[0].status,"approved");
});
test("groceries deduplicate case, whitespace and previous ingredient additions",()=>{
 let d=fresh();d=M.groceriesFrom(d,[" Melk ","melk","BROOD"]);assert.equal(d.groceries.length,2);
 d=M.groceriesFrom(d,["melk"]);assert.equal(d.groceries.length,2);d.groceries[0].done=true;assert.equal(M.groceriesFrom(d,["melk"]).groceries.length,3);
});
test("calendar export escapes injection and folds UTF-8 lines",()=>{
 const e={...fresh().events[0],title:"Koken, samen; leuk\nBEGIN:VEVENT",note:"é".repeat(100),repeat:"weekly"};
 const ics=M.ics([e]);assert.ok(ics.includes("SUMMARY:Koken\\, samen\\; leuk\\nBEGIN:VEVENT"));assert.ok(ics.includes("RRULE:FREQ=WEEKLY"));
 assert.equal(ics.match(/\r\nBEGIN:VEVENT\r\n/g).length,1);
 for(const line of ics.split("\r\n"))assert.ok(Buffer.byteLength(line)<=75);
});
test("Android-compatible backup keeps all fields and validates after JSON round trip",()=>{
 const d=fresh();assert.deepEqual(M.validate(JSON.parse(JSON.stringify(d))),d);
 assert.ok(d.people[0].color>0xffffffff-0x1000000);assert.equal(d.residence[0].days.length,d.residence[0].repeatsWeeks*7);
});

test("broken recipe data is rejected before import",()=>{const d=fresh();d.recipes[0].steps=null;assert.throws(()=>M.validate(d));});
