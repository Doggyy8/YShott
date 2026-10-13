"use strict";
const fs=require("node:fs"),path=require("node:path");
const source=path.resolve(__dirname,".."),destination=path.resolve("web-dist");
fs.mkdirSync(destination,{recursive:true});
for(const item of ["index.html","style.css","model.js","storage.js","app.js","manifest.webmanifest","sw.js","README.md","assets"])
 fs.cpSync(path.join(source,item),path.join(destination,item),{recursive:true});
fs.writeFileSync(path.join(destination,".nojekyll"),"");
console.log("Packaged browser app at web-dist");
