// Pure JS regression tests; no browser, network, media, npm dependencies or user profile.
const {test}=require('node:test');
const assert=require('node:assert/strict');
const vm=require('node:vm');
const fs=require('node:fs');
const path=require('node:path');
const code=fs.readFileSync(path.join(__dirname,'../resources/spike/ui/app.js'),'utf8');
const tick=()=>new Promise(resolve=>setImmediate(resolve));
test('preview budgets and compressed manifest exclusions have explicit explanations',async()=>{
 const f=fixture('#valid');await tick();
 Object.assign(f.state,{phase:'AWAITING_CONFIRMATION',previewError:'PREVIEW_REQUEST_LIMIT'});f.emit();
 assert.match(f.element('preview-status').textContent,/бюджет запитів/);
 assert.match(f.element('preview-status').textContent,/коротший фрагмент/);
 f.state.previewError='PREVIEW_BYTE_LIMIT';f.emit();assert.match(f.element('preview-status').textContent,/128 MiB/);
 f.state.previewError='PREVIEW_TIME_LIMIT';f.emit();assert.match(f.element('preview-status').textContent,/2 хвилини/);
 Object.assign(f.state,{phase:'DISCOVERING',discoveryActivity:{state:'WAITING_FOR_ACTION',skippedEncodedManifests:2}});f.emit();
 assert.match(f.element('discovery-hint').textContent,/стиснених маніфестів: 2/);
 assert.match(f.element('discovery-hint').textContent,/не додано/);
});
function fixture(hash='',stored='',storageBlocked=false){
 const elements=new Map(),listeners={},requests=[],streams=[];
 function newElement(){return {value:'',hidden:false,textContent:'',disabled:false,children:[],events:{},setAttribute(name,value){this[name]=value;},getAttribute(name){return this[name]||null;},removeAttribute(name){delete this[name];},pause(){this.paused=true;},load(){},replaceChildren(...items){this.value='';this.children=items;},add(item){this.children.push(item);},append(...items){this.children.push(...items);},addEventListener(type,fn){this.events[type]=fn;}};}
 function element(id){if(!elements.has(id))elements.set(id,newElement());return elements.get(id);}
 const location={hash,pathname:'/'};
 const state={version:0,phase:'IDLE',status:'IDLE',workerAlive:false,catalog:{revision:0,candidates:[]}};
 const context={document:{getElementById:element,createElement:newElement},location,
  history:{replaceState(){location.hash='';}},sessionStorage:{getItem(){if(storageBlocked)throw Error('blocked');return stored;},setItem(_,value){if(storageBlocked)throw Error('blocked');stored=value;}},
  window:{addEventListener(type,fn){listeners[type]=fn;}},Option:function(text,value){this.textContent=text;this.value=value;},AbortSignal,
  fetch:async(url,options)=>{requests.push({url,options});if(url==='/api/pin'&&!state.rejectPin){const data=JSON.parse(options.body);state.catalog={...state.catalog,revision:state.catalog.revision+1,pinnedCandidateId:data.candidate};}return{ok:options.headers['X-StreamBridge-Key']==='valid'&&!(url==='/api/pin'&&state.rejectPin),status:403,json:async()=>JSON.parse(JSON.stringify(state))};},
  EventSource:class{constructor(){this.listeners={};streams.push(this);}close(){this.closed=true;}addEventListener(type,fn){this.listeners[type]=fn;}},
 };
 vm.runInNewContext(code,context);
 return {element,location,listeners,requests,streams,state,change(value){location.hash='#'+value;listeners.hashchange();},emit(stream=streams.at(-1)){stream.listeners.state({data:JSON.stringify(state)});}};
}
test('missing key explains disabled start beside controls',async()=>{
 const f=fixture();await tick();assert.equal(f.element('start').disabled,true);
 assert.match(f.element('connection-help').textContent,/Немає ключа/);assert.equal(f.requests.length,0);
});
test('preview is explicit, same-origin, and released when confirmation ends',async()=>{
 const f=fixture('#valid');await tick();Object.assign(f.state,{discoveryId:'job',status:'RUNNING',phase:'AWAITING_CONFIRMATION',previewRevision:1,review:{token:'token',previewAvailable:true,previewMaximumMillis:8000}});f.emit();
 assert.equal(f.element('preview').disabled,false);assert.equal(f.element('preview-video').hidden,true);
 f.element('preview').events.click();await tick();assert.equal(f.requests.at(-1).url,'/api/preview');
 Object.assign(f.state,{previewRequested:true,previewReady:true});f.emit();
 assert.equal(f.element('preview-video').src,'/api/preview/job?v=1');assert.equal(f.element('preview').disabled,false);
 Object.assign(f.state,{phase:'FETCHING',previewReady:false});f.emit();assert.equal(f.element('preview-video').src,undefined);assert.equal(f.element('preview-video').paused,true);
});
test('duration defaults, shortest-track ceiling, validation and edits survive SSE',async()=>{
 const f=fixture('#valid');await tick();Object.assign(f.state,{discoveryId:'job',status:'RUNNING',phase:'AWAITING_CONFIRMATION',review:{token:'one',previewAvailable:true,previewMaximumMillis:12000}});f.emit();
 assert.equal(f.element('preview-settings').open,false);assert.equal(f.element('preview-seconds').value,'5');
 f.element('preview-seconds').value='10';f.element('preview-seconds').events.input();f.emit();assert.equal(f.element('preview-seconds').value,'10');
 f.element('preview').events.click();await tick();assert.equal(JSON.parse(f.requests.at(-1).options.body).durationMillis,10000);
 for(const value of ['','-1','0','NaN','12.001','0.0001']){f.element('preview-seconds').value=value;f.element('preview-seconds').events.input();assert.equal(f.element('preview').disabled,true);}
 f.element('preview-default').events.click();assert.equal(f.element('preview-seconds').value,'5');
 f.state.review={token:'two',previewAvailable:true,previewMaximumMillis:3456};f.emit();assert.equal(f.element('preview-seconds').value,'3.456');assert.equal(f.element('preview-seconds').max,'3.456');
 assert.equal(f.element('preview').disabled,false);
});
test('folder selection blocks confirmation and saving blocks another job',async()=>{
 const f=fixture('#valid');await tick();Object.assign(f.state,{status:'RUNNING',phase:'AWAITING_CONFIRMATION',choosingFolder:true,outputDirectory:'D:/Videos'});f.emit();
 assert.equal(f.element('confirm').disabled,true);assert.equal(f.element('choose-folder').disabled,true);assert.equal(f.element('destination-path').textContent,'D:/Videos');
 Object.assign(f.state,{phase:'SAVING',workerAlive:false,choosingFolder:false});f.emit();assert.equal(f.element('start').disabled,true);assert.equal(f.element('choose-folder').disabled,true);
});
test('folder persistence warnings distinguish unavailable preference from failed save',async()=>{
 const f=fixture('#valid');await tick();Object.assign(f.state,{folderNotice:'SAVED_OUTPUT_UNAVAILABLE',outputDirectory:'C:/Downloads'});f.emit();
 assert.equal(f.element('folder-notice').hidden,false);assert.match(f.element('folder-notice').textContent,/Попередній збережений вибір не змінено/);
 f.state.folderNotice='OUTPUT_PREFERENCE_NOT_SAVED';f.emit();assert.match(f.element('folder-notice').textContent,/Не вдалося запам’ятати/);
 f.state.folderNotice='';f.emit();assert.equal(f.element('folder-notice').hidden,true);
});

test('handled network failure shows detail without a false cleanup warning',async()=>{
 const f=fixture('#valid');await tick();
 Object.assign(f.state,{status:'FAILED',phase:'FINISHED',error:'PLAYLIST_SELECTED_VARIANT_IO_TLS_CERTIFICATE',cleanupUncertain:false});
 f.emit();assert.match(f.element('error').textContent,/IO_TLS_CERTIFICATE/);
 assert.equal(f.element('cleanup').hidden,true);
});

test('uncertain cleanup warning stays independent from transfer failure detail',async()=>{
 const f=fixture('#valid');await tick();
 Object.assign(f.state,{status:'FAILED',phase:'FINISHED',error:'PLAYLIST_SELECTED_VARIANT_HTTP_STATUS_403',cleanupUncertain:true});
 f.emit();assert.match(f.element('error').textContent,/HTTP_STATUS_403/);
 assert.equal(f.element('cleanup').hidden,false);
});
test('hash-only navigation recovers from stale key without reload or losing URL input',async()=>{
 const f=fixture('', 'expired');await tick();assert.match(f.element('connection-help').textContent,/403/);
 f.element('page-url').value='https://example.org/video';f.change('valid');await tick();f.emit();
 assert.equal(f.element('start').disabled,false);assert.equal(f.location.hash,'');
 assert.equal(f.element('page-url').value,'https://example.org/video');assert.equal(f.element('connection-help').hidden,true);
});
test('reconnect closes old SSE and ignores its late callbacks',async()=>{
 const f=fixture('#valid');await tick();f.emit();const old=f.streams[0];
 f.change('bad');await tick();assert.equal(old.closed,true);f.emit(old);
 assert.equal(f.element('start').disabled,true);assert.match(f.element('connection-help').textContent,/403/);
 f.change('valid');await tick();f.emit();assert.equal(f.element('start').disabled,false);
});
test('storage denial does not crash startup when fragment supplies key',async()=>{
 const f=fixture('#valid','',true);await tick();f.emit();assert.equal(f.element('start').disabled,false);
});
test('SSE failure disables actions and reconnect button establishes a new stream',async()=>{
 const f=fixture('#valid');await tick();f.emit();f.streams[0].onerror();
 assert.equal(f.element('start').disabled,true);assert.equal(f.element('reconnect').hidden,false);
 f.element('reconnect').events.click();await tick();f.emit();assert.equal(f.element('start').disabled,false);
 assert.equal(f.streams.length,2);
});

function catalogFixture(){return {revision:1,candidates:[
 {id:'C1',selectionRevision:11,kind:'MASTER',resource:'redacted',metadataUsable:true,players:[{id:'F1',resource:'https://player.example/resource-123'}],variants:[
  {id:'V1',resolution:'1920x1080',bandwidth:2000000,durationSeconds:1444.02,audio:[{id:'A1',name:'<img src=x onerror=alert(1)>',language:'uk',source:'SEPARATE_PLAYLIST'}]},
  {id:'V2',resolution:'1280x720',bandwidth:1000000,durationSeconds:null,audio:[]}]},
 {id:'C2',selectionRevision:22,kind:'MASTER',resource:'other',frames:[],variants:[{id:'V1',resolution:'unknown',bandwidth:1000,audio:[]}]}
]};}
function treeText(el){return [el.textContent,...(el.children||[]).map(treeText)].join(' ');}
function radio(f,index=0){return f.element('source-cards').children[index].children[0].children[0];}
test('cards show attributed metadata and explicit unknowns; audio stays scoped to selected variant',async()=>{
 const f=fixture('#valid');await tick();Object.assign(f.state,{phase:'DISCOVERING',catalog:catalogFixture(),discoveryId:'job'});f.emit();
 const cards=f.element('source-cards').children;assert.equal(cards.length,2);
 assert.match(treeText(cards[0]),/F1 · https:\/\/player.example/);assert.match(treeText(cards[0]),/24:04.*1\/2/);
 assert.match(treeText(cards[0]),/1920×1080, 1280×720/);assert.match(treeText(cards[0]),/<img src=x onerror=alert\(1\)>/);
 assert.match(treeText(cards[1]),/Ще невідома/);assert.match(treeText(cards[1]),/звук може бути/);assert.match(treeText(cards[1]),/Роздільність невідома/);
 const input=radio(f);input.checked=true;input.events.change();await tick();assert.equal(f.element('prepare').disabled,true);
 f.element('variant').value='V1';f.element('variant').events.change();assert.equal(f.element('audio').children.length,2);
 f.element('audio').value='A1';f.element('audio').events.change();assert.equal(f.element('prepare').disabled,false);
 f.element('prepare').events.click();await tick();assert.equal(JSON.parse(f.requests.at(-1).options.body).candidate,'C1');
 f.element('variant').value='V2';f.element('variant').events.change();assert.equal(f.element('audio').children.length,1);assert.equal(f.element('audio').disabled,true);
 // An unrelated catalog update preserves choice; only this source's version invalidates parameters.
 f.emit();assert.equal(radio(f).checked,true);f.state.catalog.revision++;f.emit();assert.equal(radio(f).checked,true);assert.equal(f.element('prepare').disabled,false);
 f.state.catalog.candidates[0].selectionRevision++;f.state.catalog.revision++;f.emit();assert.equal(radio(f).checked,true);assert.equal(f.element('prepare').disabled,true);
 assert.match(f.element('selection-update').textContent,/повторно/);
});

test('unrelated ads and duration updates preserve pinned source, quality and audio; prepare uses source revision',async()=>{
 const f=fixture('#valid');await tick();Object.assign(f.state,{phase:'DISCOVERING',catalog:catalogFixture(),discoveryId:'job'});f.emit();
 radio(f).checked=true;radio(f).events.change();await tick();
 assert.deepEqual(JSON.parse(f.requests.at(-1).options.body),{id:'job',revision:11,candidate:'C1'});
 f.element('variant').value='V1';f.element('variant').events.change();f.element('audio').value='A1';
 for(let i=0;i<150;i++){f.state.catalog.revision++;f.state.catalog.candidates[0].variants[0].durationSeconds=1500+i;f.emit();}
 assert.equal(radio(f).checked,true);assert.equal(f.element('variant').value,'V1');assert.equal(f.element('audio').value,'A1');assert.equal(f.element('prepare').disabled,false);
 assert.match(f.element('pin-status').textContent,/закріплено/);
 f.element('prepare').events.click();await tick();assert.equal(JSON.parse(f.requests.at(-1).options.body).revision,11);
 radio(f,1).checked=true;radio(f,1).events.change();await tick();assert.equal(f.state.catalog.pinnedCandidateId,'C2');assert.equal(f.element('variant').value,'');
 f.state.catalog={revision:f.state.catalog.revision+1,candidates:[],pinnedCandidateId:''};f.emit();assert.equal(f.element('prepare').disabled,true);assert.match(f.element('selection-update').textContent,/більше недоступне/);
});

test('failed pin is visible, blocks prepare, and can be retried without losing parameters',async()=>{
 const f=fixture('#valid');await tick();Object.assign(f.state,{phase:'DISCOVERING',catalog:catalogFixture(),discoveryId:'job',rejectPin:true});f.emit();
 radio(f).checked=true;radio(f).events.change();await tick();f.element('variant').value='V2';f.element('variant').events.change();
 assert.equal(f.element('prepare').disabled,true);assert.equal(f.element('pin-retry').hidden,false);assert.match(f.element('pin-status').textContent,/не підтверджено/);
 f.state.rejectPin=false;f.element('pin-retry').events.click();await tick();assert.equal(f.element('prepare').disabled,false);assert.equal(f.element('variant').value,'V2');
 f.state.discoveryId='another-job';f.emit();assert.equal(radio(f).checked,false);assert.equal(f.element('variant').value,'');
});
test('protected, invalid and unassociated sources cannot be selected; lost connection disables cards',async()=>{
 const f=fixture('#valid');await tick();const catalog=catalogFixture();catalog.candidates[0].keyTagObserved=true;catalog.candidates[1].kind='MEDIA';
 Object.assign(f.state,{phase:'DISCOVERING',catalog});f.emit();assert.equal(radio(f).disabled,true);assert.equal(radio(f,1).disabled,true);
 catalog.candidates[0].keyTagObserved=false;catalog.candidates[0].metadataUsable=false;catalog.revision++;f.emit();assert.equal(radio(f).disabled,true);
 catalog.candidates[0].metadataUsable=true;catalog.revision++;f.emit();assert.equal(radio(f).disabled,false);
 f.streams.at(-1).onerror();assert.equal(radio(f).disabled,true);
});
test('browser launch failure explains the required sandbox without claiming it caused the failure',async()=>{
 const f=fixture('#valid');await tick();
 Object.assign(f.state,{phase:'FINISHED',status:'FAILED',error:'DISCOVERY_BROWSER_START_FAILED',workerAlive:false});f.emit();
 assert.match(f.element('error').textContent,/обов’язковою пісочницею/);
 assert.match(f.element('error').textContent,/Захист не вимикався/);
 assert.equal(f.element('prepare').disabled,true);
});

test('different observed variant durations are shown as a range, not a fabricated single duration',async()=>{
 const f=fixture('#valid');await tick();const catalog=catalogFixture();catalog.candidates[0].variants[1].durationSeconds=1450;
 Object.assign(f.state,{phase:'DISCOVERING',catalog});f.emit();assert.match(treeText(f.element('source-cards').children[0]),/24:04–24:10.*2\/2/);
});
test('search activity distinguishes pending, waiting, retention, disconnect and completion without resetting choice',async()=>{
 const f=fixture('#valid');await tick();Object.assign(f.state,{phase:'DISCOVERING',catalog:catalogFixture(),discoveryActivity:{state:'LOADING_PAGE',remainingSeconds:180,lastChangeAgoSeconds:-1}});f.emit();
 assert.equal(f.element('discovery-activity').hidden,false);assert.match(f.element('discovery-state').textContent,/Завантажуємо сторінку/);
 const input=radio(f);input.checked=true;input.events.change();
 f.state.discoveryActivity={state:'READING_PLAYLIST',remainingSeconds:170,lastChangeAgoSeconds:5,pendingPlaylists:2};f.emit();
 assert.match(f.element('discovery-state').textContent,/плейлисти: 2/);assert.equal(radio(f).checked,true);
 f.state.discoveryActivity={state:'WAITING_FOR_ACTION',remainingSeconds:150,lastChangeAgoSeconds:12,unsupportedMediaSeen:true,timedOutRequests:1,closedExtraTabs:2};f.emit();
 assert.match(f.element('discovery-state').textContent,/Очікуємо нових дій/);assert.match(f.element('discovery-clock').textContent,/чекати його завершення не потрібно/);
 assert.match(f.element('discovery-hint').textContent,/таймаутом: 1/);assert.match(f.element('discovery-hint').textContent,/вкладок закрито: 2/);
 assert.match(f.element('discovery-hint').textContent,/не означає, що знайдено все/);assert.match(f.element('discovery-hint').textContent,/поза підтримуваним/);
 assert.match(f.element('discovery-clock').textContent,/150 с/);
 f.state.discoveryActivity.remainingSeconds=-1;f.emit();
 assert.match(f.element('discovery-clock').textContent,/Без обмеження часу/);
 assert.doesNotMatch(f.element('discovery-clock').textContent,/автоматично закриється|-1 с/);
 assert.equal(radio(f).checked,true);
 f.state.catalog.evictedManifests=30;f.emit();assert.match(f.element('catalog-note').textContent,/витіснено плейлистів: 30/);
 assert.match(f.element('catalog-note').textContent,/Пошук продовжується/);
 f.streams.at(-1).onerror();assert.match(f.element('discovery-state').textContent,/стан пошуку невідомий/);assert.equal(f.element('discovery-clock').textContent,'');
 f.state.phase='FINISHED';f.emit();assert.equal(f.element('discovery-activity').hidden,true);
});
