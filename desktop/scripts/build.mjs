import {build} from 'esbuild';
import {mkdir,copyFile} from 'node:fs/promises';
await mkdir('dist',{recursive:true});
await build({entryPoints:['src/app.jsx'],bundle:true,outfile:'dist/app.js',minify:true,format:'iife',platform:'browser',target:'chrome140',define:{'process.env.NODE_ENV':'"production"'},loader:{'.css':'css'}});
await copyFile('src/index.html','dist/index.html');
