"use strict";
const {chromium}=require("playwright"),http=require("node:http"),fs=require("node:fs"),path=require("node:path"),assert=require("node:assert/strict");
const root=path.resolve(__dirname,".."),out=path.resolve("web-test-results");fs.mkdirSync(out,{recursive:true});
const mime={".html":"text/html; charset=utf-8",".js":"text/javascript; charset=utf-8",".css":"text/css; charset=utf-8",".svg":"image/svg+xml",".png":"image/png",".ttf":"font/ttf",".webmanifest":"application/manifest+json"};
const server=http.createServer((req,res)=>{
 const url=new URL(req.url,"http://localhost"),file=path.resolve(root,"."+decodeURIComponent(url.pathname==="/"?"/index.html":url.pathname));
 if(file!==root&&!file.startsWith(root+path.sep)){res.writeHead(403).end();return;}
 if(!fs.existsSync(file)||!fs.statSync(file).isFile()){res.writeHead(404).end();return;}
 res.writeHead(200,{"Content-Type":mime[path.extname(file)]||"application/octet-stream","Cache-Control":"no-store"});fs.createReadStream(file).pipe(res);
});
const errors=[];
async function shot(page,name){await page.evaluate(()=>document.fonts.ready);await page.screenshot({path:path.join(out,name+".png"),fullPage:false});console.log("YSHOTT_WEB_PREVIEW:"+name+":"+fs.readFileSync(path.join(out,name+".png")).toString("base64"));}
(async()=>{
 await new Promise(r=>server.listen(8765,"127.0.0.1",r));
 const browser=await chromium.launch({headless:true}),context=await browser.newContext({viewport:{width:412,height:915},acceptDownloads:true});
 const page=await context.newPage();page.on("pageerror",e=>errors.push(e.message));
 try {
  await page.goto("http://127.0.0.1:8765");await page.getByRole("button",{name:"Voorbeeld bekijken"}).click();await page.getByRole("heading",{name:"Onze dag"}).waitFor();
  await shot(page,"vandaag");
  await page.getByRole("button",{name:"Agenda",exact:true}).click();await page.getByRole("heading",{name:"Agenda",exact:true}).waitFor();
  const cells=page.locator(".day-cell");assert.ok([28,35,42].includes(await cells.count()));await shot(page,"maandagenda");
  await page.getByRole("button",{name:"Week",exact:true}).click();assert.equal(await page.locator(".day-cell").count(),7);await shot(page,"weekagenda");
  await page.getByRole("button",{name:"Afspraak toevoegen"}).click();await page.getByLabel("Naam",{exact:true}).fill("Web opslagcontrole");
  await page.getByRole("button",{name:"Bewaren",exact:true}).click();await page.getByRole("button",{name:"Web opslagcontrole",exact:true}).waitFor();
  await page.reload();await page.getByRole("button",{name:"Mijn lokale planner openen"}).click();await page.getByRole("button",{name:"Agenda",exact:true}).click();await page.getByRole("button",{name:"Web opslagcontrole",exact:true}).waitFor();
  const vault=await page.evaluate(()=>localStorage.getItem("yshott-vault-v1"));assert.ok(vault&&!vault.includes("Web opslagcontrole"));
  await page.getByRole("button",{name:"Meer",exact:true}).click();await page.getByRole("button",{name:"Recepten",exact:true}).click();await page.getByRole("button",{name:"Boodschappen",exact:true}).click();await page.getByRole("button",{name:"Toevoegen",exact:true}).click();
  await page.getByRole("button",{name:"Meer",exact:true}).click();await page.getByRole("button",{name:"Boodschappen",exact:true}).click();await page.locator("#main").getByText("400 g pasta",{exact:true}).waitFor();
  await page.getByRole("button",{name:"Meer",exact:true}).click();await page.getByRole("button",{name:"Instellingen",exact:true}).click();await page.getByRole("button",{name:"Ouderpincode instellen",exact:true}).click();await page.getByLabel("Vier cijfers",{exact:true}).fill("2468");await page.getByRole("button",{name:"Bewaren",exact:true}).click();await page.locator("dialog").waitFor({state:"hidden"});
  await page.getByRole("button",{name:/^Bij /}).click();await page.getByRole("button",{name:"Kindmodus: Mila",exact:true}).click();await page.getByLabel("Vier cijfers",{exact:true}).fill("2468");await page.getByRole("button",{name:"Verder",exact:true}).click();await page.locator("dialog").waitFor({state:"hidden"});await page.getByRole("heading",{name:"Mijn dag",exact:true}).waitFor();
  await shot(page,"kindmodus");
  await page.getByRole("button",{name:"Klusje klaar",exact:true}).click();await page.getByRole("button",{name:"Klusjes",exact:true}).click();await page.getByText("Wacht op je ouder",{exact:true}).waitFor();
  await page.getByRole("button",{name:"Ouderinstellingen openen met pincode"}).click();await page.getByLabel("Vier cijfers",{exact:true}).fill("9999");await page.getByRole("button",{name:"Verder",exact:true}).click();await page.getByText("De pincode klopt niet.",{exact:true}).waitFor();
  await page.getByLabel("Vier cijfers",{exact:true}).fill("2468");await page.getByRole("button",{name:"Verder",exact:true}).click();await page.locator("dialog").waitFor({state:"hidden"});
  await page.getByRole("button",{name:"Meer",exact:true}).click();await page.getByRole("button",{name:"Taken & klusjes",exact:true}).click();await page.getByRole("button",{name:"Goedkeuren",exact:true}).click();
  await page.getByRole("button",{name:"Meer",exact:true}).click();await page.getByRole("button",{name:"Beloningen",exact:true}).click();await page.getByRole("heading",{name:"Mila · 5 punten",exact:true}).waitFor();
  await page.getByRole("button",{name:"Vandaag",exact:true}).click();await page.setViewportSize({width:1440,height:1000});await shot(page,"desktop");
  for(const size of [{width:320,height:640},{width:412,height:915},{width:1440,height:1000}]){
   await page.setViewportSize(size);const overflow=await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth+1);assert.equal(overflow,false,"No horizontal overflow at "+size.width);
  }
  assert.deepEqual(errors,[]);
  fs.writeFileSync(path.join(out,"summary.json"),JSON.stringify({passed:true,checks:["mobile calendar views","event survives reload","encrypted storage","recipe ingredients","child PIN gate","parent approval points","responsive layouts"],pageErrors:errors},null,2));
  console.log("Browser checks passed: mobile/desktop, encrypted reload, groceries, child gate and parent-approved points.");

 }finally{await browser.close();await new Promise(r=>server.close(r));}
})().catch(e=>{console.error(e);process.exitCode=1;server.close();});
