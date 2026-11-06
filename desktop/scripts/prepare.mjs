import {mkdir,copyFile} from 'node:fs/promises';
await mkdir('resources',{recursive:true});
await copyFile('../target/xhlcli-1.0.1.jar','resources/xhlcli.jar');
