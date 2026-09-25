// Offline DOM tests: no browser navigation, network resources or rendering.
// npm install --prefix build/trace-ui-test --no-save --no-audit --no-fund jsdom@26.1.0
// node scripts/test-trace-viewer.cjs [actual-session/events.jsonl]
const {createRequire}=require('node:module');
const {resolve}=require('node:path');
const {readFileSync}=require('node:fs');
const assert=require('node:assert/strict');
const {JSDOM,VirtualConsole}=createRequire(resolve('build/trace-ui-test/package.json'))('jsdom');
const template=readFileSync('src/main/resources/jev-trace/viewer.html','utf8');
let seq=0;
function event(span,parent,kind,phase,data={},tags={}){return {schema:1,session:'test',seq:++seq,time:'2026-09-25T09:00:00Z',span,parent,kind,phase,data,tags:{npc:'npc-1',...tags}}}
const rows=[
 event('n','','npc','start'),event('d1','n','dialogue','start',{text:'<script>window.pwned=true</script>'},{dialogue:1}),
 event('j1','d1','jev','start',{}, {dialogue:1}),
 event('j1','d1','jev','request',{input:{questions:{next_action:{instructions:'Choose a tool',criteria:{mine:'挖石头',wait:'等待',unknown:'缺少概率'}}}}},{dialogue:1}),
 event('j1','d1','jev','response',{elapsed_ms:123,output:{answers:{next_action:{choice:'mine',confidence:0.5,probabilities:{mine:0.75,wait:0.25}}}}},{dialogue:1}),
 event('g1','j1','goal','start',{request:'采集十二块'},{goal:'g1',dialogue:1}),
 event('m1','g1','method','start',{action:{id:'mine-old',description:'旧目标动作'}},{goal:'g1',dialogue:1}),
 event('m1','g1','method','result',{success:true,progress:3},{goal:'g1',dialogue:1}),
 event('d2','g1','dialogue','start',{text:'改成新目标'},{goal:'g1',dialogue:2}),
 event('j2','d2','jev','start',{}, {goal:'g1',dialogue:2}),
 event('l2','j2','deepseek','start',{}, {goal:'g1',dialogue:2}),
 event('l2','j2','deepseek','request',{input:{messages:[{role:'user',content:'改成新目标'}]}},{goal:'g1',dialogue:2}),
 event('l2','j2','deepseek','response',{output:{choices:[{message:{content:'{"reply":"新目标"}'}}]}},{goal:'g1',dialogue:2}),
 event('r2','d2','jev','start',{}, {goal:'g1',dialogue:2}),
 event('g2','r2','goal','start',{request:'新目标'},{goal:'g2',dialogue:2}),
 event('m2','g2','method','start',{action:{description:'新目标动作'}},{goal:'g2',dialogue:2}),
 event('m2','g2','method','failed',{error:'unreachable'},{goal:'g2',dialogue:2})
];
function load(events){const errors=[];const vc=new VirtualConsole();vc.on('jsdomError',e=>errors.push(e.message));
 const data=JSON.stringify({session:'test',part:1,live:false,dropped:0,events}).replaceAll('<','\\u003c').replaceAll('>','\\u003e');
 const dom=new JSDOM(template.replace('/*TRACE_DATA*/{}',data),{runScripts:'dangerously',url:'https://trace-tests.invalid/',virtualConsole:vc});
 return {dom,w:dom.window,d:dom.window.document,errors};}
function input(w,d,id,value){const el=d.getElementById(id);if(el.type==='checkbox')el.checked=value;else el.value=value;el.dispatchEvent(new w.Event('input'))}
function visible(d){return [...d.querySelectorAll('.event')].map(el=>Number(el.dataset.seq))}
(async()=>{
 const {dom,w,d,errors}=load(rows);
 assert.equal(w.pwned,undefined);assert.equal(d.querySelectorAll('img').length,0);
 // Actual choice probabilities stay separate from the aggregate confidence; missing probability isn't invented.
 d.querySelector('[data-seq="5"]').click();
 assert.match(d.getElementById('details').textContent,/75\.00%/);assert.match(d.getElementById('details').textContent,/置信度：0.5/);
 assert.match(d.getElementById('details').textContent,/未返回概率/);
 // Goal replacement must not pull the successor's execution into the old goal filter.
 input(w,d,'goal','g1');assert(!visible(d).includes(16));assert(!visible(d).includes(17));assert(visible(d).includes(12));
 input(w,d,'goal','g2');assert(visible(d).includes(12));assert(visible(d).includes(17));assert(!visible(d).includes(7));
 input(w,d,'goal','');input(w,d,'kind','deepseek');assert.equal(visible(d).length,3);
 input(w,d,'kind','');input(w,d,'search','unreachable');assert.deepEqual(visible(d),[17]);
 input(w,d,'search','');input(w,d,'errors',true);assert.deepEqual(visible(d),[17]);input(w,d,'errors',false);
 d.querySelector('[data-seq="17"]').click();[...d.querySelectorAll('#details button')].find(b=>b.textContent==='↑ 父事件').click();
 assert.match(d.querySelector('#details h2').textContent,/goal · start/);
 // Export the filter without uploading; only a local Blob download is requested.
 let exported;w.URL.createObjectURL=blob=>{exported=blob;return 'blob:offline-test'};w.URL.revokeObjectURL=()=>{};w.HTMLAnchorElement.prototype.click=function(){};
 input(w,d,'kind','deepseek');d.getElementById('export').click();assert(exported instanceof w.Blob);assert(exported.size>0);
 // Import while a writer is appending: only an incomplete final line is skipped, with a visible warning.
 const file={name:'events.jsonl',text:async()=>rows.map(e=>JSON.stringify(e)).join('\n')+'\n{"schema":'};
 Object.defineProperty(d.getElementById('import'),'files',{value:[file]});await d.getElementById('import').onchange();
 assert.match(d.getElementById('notice').textContent,/末行尚未写完/);assert.equal(w.pwned,undefined);
 assert.deepEqual(errors,[]);dom.window.close();
 if(process.argv[2]){
   const actual=readFileSync(process.argv[2],'utf8').trim().split('\n').map(JSON.parse);
   const page=load(actual);assert(page.d.querySelectorAll('.event').length>0);
   const response=actual.find(e=>e.kind==='jev'&&e.phase==='response'&&e.data.output?.answers?.next_action);
   input(page.w,page.d,'search',response.span);
   page.d.querySelector(`[data-seq="${response.seq}"]`).click();
   assert.match(page.d.getElementById('details').textContent,/整次判断置信度/);
   assert(page.d.querySelectorAll('.candidate').length>0);assert.deepEqual(page.errors,[]);page.dom.window.close();
 }
 console.log('PASS: XSS text handling, probability/confidence, goal boundaries, filtering, parent links, local export, partial import'+(process.argv[2]?', actual live records':''));
})().catch(error=>{console.error(error);process.exitCode=1});
