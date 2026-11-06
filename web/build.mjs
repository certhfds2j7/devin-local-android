import {build} from "esbuild";
import {mkdir,writeFile,copyFile} from "node:fs/promises";
const out="../app/src/main/assets/web";
await mkdir(out,{recursive:true});
await build({entryPoints:["editor.js","terminal.js"],bundle:true,minify:true,outdir:out,format:"iife",target:"chrome100",legalComments:"eof"});
for(const name of ["editor","terminal"]){
  await writeFile(out+"/"+name+".html",'<!doctype html><html><head><meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1"><meta charset="utf-8"><meta http-equiv="Content-Security-Policy" content="default-src \'none\'; script-src \'self\'; style-src \'self\' \'unsafe-inline\'; font-src \'self\' data:"><link rel="stylesheet" href="'+name+'.css"><style>html,body{height:100%;margin:0;overflow:hidden}#'+name+'{height:100%}</style></head><body><div id="'+name+'"></div><script src="'+name+'.js"></script></body></html>');
}
await writeFile(out+"/editor.css","body{color-scheme:light dark;background:transparent}.cm-editor{height:100%}");
