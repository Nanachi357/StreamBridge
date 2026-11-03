'use strict';
const $ = id => document.getElementById(id);
let key = '', connectionAttempt=0;
function readKey(){
 const fragment=location.hash.slice(1);
 try{key=fragment||sessionStorage.getItem('streambridge-key')||key;if(key)sessionStorage.setItem('streambridge-key',key);}catch{key=fragment||key;}
 if(fragment)history.replaceState(null,'',location.pathname);
}
readKey();
let state, catalogRevision=-1, choices=[], events, sending=false, online=false;
let selectedCandidateId='', selectedRevision=0, sourceInputs=[];
let previewToken='';
function previewMillis(){return Number($('preview-seconds').value)*1000;}
function validPreviewDuration(){const n=previewMillis();return Number.isFinite(n)&&Math.abs(n-Math.round(n))<0.000001&&n>=1&&Math.round(n)<=state?.review?.previewMaximumMillis;}
function previewControls(){
 const maximum=state?.review?.previewMaximumMillis||0, enabled=online&&!sending&&state?.phase==='AWAITING_CONFIRMATION'&&!state?.choosingFolder;
 $('preview-seconds').disabled=!enabled;$('preview-default').disabled=!enabled;
 $('preview').disabled=!enabled||!state?.review?.previewAvailable||!validPreviewDuration();
 $('preview').textContent=(state?.previewReady?'Оновити фрагмент':'Передперегляд')+(validPreviewDuration()?` · ${Math.round(previewMillis())/1000} с`:'');
 $('preview-validation').textContent=maximum&&!validPreviewDuration()?`Вкажи від 0.001 до ${maximum/1000} секунд (до трьох знаків після крапки).`:'';
}
function connectionMessage(message){$('connection').textContent=message;$('connection-help').textContent=message;$('connection-help').hidden=online;$('reconnect').hidden=online;}
const names={IDLE:'Готовий до роботи',STARTING:'Запускаємо ізольований браузер…',DISCOVERING:'Запусти потрібне відео в окремому браузері. Спостерігаємо за потоками…',PREFLIGHT:'Перевіряємо плейлисти та обсяг…',AWAITING_CONFIRMATION:'Очікуємо твого підтвердження',FETCHING:'Отримуємо сегменти',PROBING:'Перевіряємо доріжки',REMUXING:'Збираємо MP4',VERIFYING:'Перевіряємо декодування та тривалість',CANCELLING:'Скасовуємо операцію та закриваємо ресурси…'};
const endings={SUCCEEDED:'Готово: повне декодування й перевірка тривалості пройшли',FAILED:'Операція завершилась помилкою',CANCELLED:'Операцію скасовано',EXPIRED:'Час пошуку завершився. За потреби відкрий сторінку знову.'};
names.PREVIEWING='Готуємо короткий передперегляд…';names.SAVING='Зберігаємо перевірений MP4 у вибрану папку…';
function options(element,placeholder,values){element.replaceChildren(new Option(placeholder,''));for(const [value,label] of values)element.add(new Option(label,value));}
function variant(){return choices.find(c=>c.id===selectedCandidateId)?.variants.find(v=>v.id===$('variant').value);}
function resolution(v){return v.resolution&&v.resolution!=='unknown'?v.resolution.replace('x','×'):'Роздільність невідома';}
function audioLabel(a){return `${a.name?`${a.name} · `:''}${a.language&&a.language!=='und'?a.language:'Мова невідома'} · ${a.source==='IN_BAND_DECLARED'?'у відео':'окрема доріжка'}`;}
function durationLabel(seconds){const n=Math.round(seconds);return `${Math.floor(n/3600)?Math.floor(n/3600)+':':''}${String(Math.floor(n/60)%60).padStart(2,'0')}:${String(n%60).padStart(2,'0')}`;}
function sourceDuration(c){
 const known=c.variants.filter(v=>Number.isFinite(v.durationSeconds)&&v.durationSeconds>0);
 if(!c.variants.length&&Number.isFinite(c.durationSeconds)&&c.durationSeconds>0)return durationLabel(c.durationSeconds)+' · за плейлистом';
 if(!known.length)return 'Ще невідома — немає завершеного плейлиста';
 const values=known.map(v=>v.durationSeconds),min=Math.min(...values),max=Math.max(...values);
 return (Math.abs(max-min)<0.5?durationLabel(min):`${durationLabel(min)}–${durationLabel(max)}`)+` · за плейлистами якостей (${known.length}/${c.variants.length})`;
}
function node(tag,text,className){const el=document.createElement(tag);if(text!==undefined)el.textContent=text;if(className)el.className=className;return el;}
function sourceCards(){
 $('source-cards').replaceChildren();sourceInputs=[];
 for(const c of choices){
  const card=node('article',undefined,'source-card'),head=node('label',undefined,'source-heading'),input=node('input');
  input.type='radio';input.name='source';input.value=c.id;input.id='source-'+c.id;input.setAttribute('aria-label','Обрати джерело '+c.id);
  input.addEventListener('change',()=>{if(input.checked){selectedCandidateId=c.id;selectedRevision=c.selectionRevision;$('selection-update').textContent='';candidateChanged();pinChoice();}});
  const blocked=c.metadataUsable===false||c.keyTagObserved||c.kind!=='MASTER';sourceInputs.push({input,blocked});
  head.append(input,node('strong','Джерело '+c.id));card.append(head);
  const info=node('dl');info.id='source-info-'+c.id;input.setAttribute('aria-describedby',info.id);
  const players=(c.players?.length?c.players.map(p=>[p.id,p.resource].filter(Boolean).join(' · ')):c.frames||[]);
  const tracks=new Map();for(const v of c.variants)for(const a of v.audio||[])tracks.set(a.id,a);
  const audio=tracks.size?[...tracks.values()].map(audioLabel).join('; ')+(c.variants.some(v=>!v.audio?.length)?'; для частини якостей звук не описано':''):'Окремі доріжки не оголошені; звук може бути у відео';
  for(const [label,value] of [['Плеєр (фрейм)',players.join('; ')||'Не визначено'],['Тривалість',sourceDuration(c)],['Якості (заявлені)',[...new Set(c.variants.map(resolution))].join(', ')||'Не оголошені'],['Аудіо',audio]])info.append(node('dt',label),node('dd',value));
  card.append(info);
  const detail=node('details'),summary=node('summary','Про джерело');detail.append(summary,node('p',c.resource,'muted'),node('p','Назви аудіо взято з відповідної групи HLS. Домени та F-номери позначають фрейми браузера, а не гарантують назву плеєра чи відсутність реклами.','muted'));card.append(detail);
  if(blocked)card.append(node('p',c.keyTagObserved?'Захищений потік: вибір недоступний.':c.metadataUsable===false?'Метадані некоректні: очікуємо оновлення.':'Плейлист ще не пов’язано зі списком якостей.','muted'));
  $('source-cards').append(card);
 }
}
function candidateChanged(){const c=choices.find(c=>c.id===selectedCandidateId);options($('variant'),'Обери якість',(c?.variants||[]).map(v=>[v.id,`${resolution(v)} · ${Math.round(v.bandwidth/1000)} кбіт/с${v.durationSeconds>0?' · '+durationLabel(v.durationSeconds):''}`]));variantChanged();}
function variantChanged(){const v=variant();options($('audio'),v?.audio?.length?'Обери звук':'Окрему доріжку не оголошено',(v?.audio||[]).map(a=>[a.id,audioLabel(a)]));$('audio-note').textContent=v&&!v.audio.length?'Звук може бути всередині відеосегментів; його наявність буде перевірено.':'';controls();}
function controls(){
 const discovering=online&&!sending&&state?.phase==='DISCOVERING',v=variant();
 const pinned=!!selectedCandidateId&&state?.catalog?.pinnedCandidateId===selectedCandidateId;
 for(const {input,blocked} of sourceInputs){input.disabled=!discovering||blocked;input.checked=input.value===selectedCandidateId;}
 $('variant').disabled=!discovering||!selectedCandidateId;$('audio').disabled=!discovering||!v?.audio.length;
 $('prepare').disabled=!discovering||!pinned||!v||!!(v.audio.length&&!$('audio').value);
 $('pin-retry').hidden=!selectedCandidateId||pinned||state?.phase!=='DISCOVERING';$('pin-retry').disabled=!discovering;
 $('pin-status').textContent=selectedCandidateId&&state?.phase==='DISCOVERING'?(pinned?'Джерело закріплено в кеші. Нові сторонні потоки не скинуть вибір.':sending?'Закріплюємо вибране джерело…':'Закріплення не підтверджено. Повтори спробу перед перевіркою вибору.'):'';
 $('start').disabled=!online||sending||!!state?.workerAlive||state?.status==='RUNNING'||!!state?.choosingFolder;$('page-url').disabled=!!state?.workerAlive;
 $('confirm').disabled=!online||sending||!!state?.choosingFolder||state?.phase!=='AWAITING_CONFIRMATION';$('cancel').disabled=!online||sending||state?.phase==='CANCELLING';
 $('choose-folder').disabled=!online||sending||!!state?.choosingFolder||(state?.status==='RUNNING'&&state?.phase!=='AWAITING_CONFIRMATION');previewControls();
}
function clearPreview(){const video=$('preview-video');if(video.getAttribute('src')){video.pause();video.removeAttribute('src');video.load();}video.hidden=true;$('preview-metadata').textContent='';}
function discoveryActivity(){
 const active=state?.phase==='DISCOVERING',a=state?.discoveryActivity||{};
 $('discovery-activity').hidden=!active;
 if(!active)return;
 const labels={LOADING_PAGE:'Завантажуємо сторінку…',READING_PLAYLIST:`Отримуємо плейлисти: ${a.pendingPlaylists||0}. Очікування — до 15 с на запит.`,WAITING_FOR_ACTION:'Зараз немає плейлистів в обробці. Очікуємо нових дій у плеєрі.'};
 $('discovery-state').textContent=!online?'Зв’язок втрачено — актуальний стан пошуку невідомий.':labels[a.state]||'Очікуємо стан пошуку…';
 $('discovery-clock').textContent=online&&Number.isFinite(a.remainingSeconds)?`${a.remainingSeconds<0?'Без обмеження часу — обери потік і натисни «Перевірити вибір» або скасуй пошук.':`Сеанс автоматично закриється через ${a.remainingSeconds} с — чекати його завершення не потрібно.`}${a.lastChangeAgoSeconds>=0?` Останнє оновлення джерел: ${a.lastChangeAgoSeconds} с тому.`:' Джерела ще не оновлювались.'}`:'';
 $('discovery-hint').textContent=a.state==='WAITING_FOR_ACTION'?'Можна обирати вже знайдене. Для інших джерел запусти або зміни плеєр, серію чи якість. Нові запити можуть з’явитися й пізніше; це не означає, що знайдено все.':a.state==='READING_PLAYLIST'?'Список ще може оновитися після отримання відповідей. Завислі запити не блокуватимуть очікування безстроково.':'Запусти потрібне відео в окремому браузері. Пошук не перебирає всі серії та якості автоматично.';
 if(a.timedOutRequests)$('discovery-hint').textContent+=` Плейлистів із таймаутом: ${a.timedOutRequests}. Їхні запізнілі відповіді не додаються; повтори дію в плеєрі для нового запиту.`;
 if(a.failedRequests)$('discovery-hint').textContent+=` Невдалих мережевих запитів: ${a.failedRequests} (не обов’язково запитів відео).`;
 if(a.closedExtraTabs)$('discovery-hint').textContent+=` Додаткових вкладок закрито: ${a.closedExtraTabs}. У цьому вікні підтримується одна вкладка пошуку.`;
 if(a.skippedEncodedManifests)$('discovery-hint').textContent+=` Пропущено стиснених маніфестів: ${a.skippedEncodedManifests}. Безпечне читання стиснених відповідей браузера поки не підтримується; ці джерела не додано. Спробуй інший плеєр.`;
 if(a.unsupportedMediaSeen)$('discovery-hint').textContent+=' Також помічено медіазапити поза підтримуваним HLS-каталогом; не кожен такий запит можна завантажити поточним рішенням.';
}
function display(next){
 if(state&&next.version<state.version)return;const newJob=state?.discoveryId!==next.discoveryId;state=next;
 const catalog=state.catalog||{revision:0,candidates:[]};
 if(newJob||catalog.revision!==catalogRevision){
  const hadSelection=!!selectedCandidateId,oldVariant=$('variant').value,oldAudio=$('audio').value;
  catalogRevision=catalog.revision;choices=catalog.candidates||[];
  const current=!newJob&&choices.find(c=>c.id===selectedCandidateId&&c.metadataUsable!==false&&!c.keyTagObserved&&c.kind==='MASTER');
  const unchanged=current&&current.selectionRevision===selectedRevision;
  if(!current){selectedCandidateId='';selectedRevision=0;if(hadSelection)$('selection-update').textContent=catalog.pinNotice==='PIN_METADATA_TOO_LARGE'?'Метадані вибраного джерела перевищили безпечний розмір. Закріплення знято; вибери інше джерело.':'Вибране джерело більше недоступне або сторінка змінилась. Обери джерело повторно.';}
  else if(!unchanged){selectedRevision=current.selectionRevision;$('selection-update').textContent='Змінилися параметри саме вибраного джерела. Обери якість і аудіо повторно.';}
  if(hadSelection&&catalog.pinNotice==='PIN_METADATA_TOO_LARGE'&&!catalog.pinnedCandidateId)$('selection-update').textContent='Метадані вибраного джерела перевищили безпечний розмір. Закріплення знято; вибери інше джерело.';
  sourceCards();candidateChanged();
  if(unchanged&&current.variants.some(v=>v.id===oldVariant)){$('variant').value=oldVariant;variantChanged();if(variant()?.audio.some(a=>a.id===oldAudio))$('audio').value=oldAudio;}
 }
 $('catalog-note').textContent=choices.length?`Знайдено джерел: ${choices.length}. Версія списку: ${catalogRevision}.`:'HLS-джерел ще немає. Запусти плеєр; DASH та прямі MP4 наразі не потрапляють до цього списку.';
 if(catalog.evictedManifests>0)$('catalog-note').textContent+=` Пошук продовжується. Для обмеження пам’яті з кешу витіснено плейлистів: ${catalog.evictedManifests}; зберігається до 100 записів, залежно від розміру метаданих. Закріплене джерело та його доріжки захищено від витіснення сторонніми потоками. Для іншого старого джерела повторно запусти потрібний плеєр.`;
 $('phase').textContent=state.phase==='FINISHED'?(endings[state.status]||state.status):(names[state.phase]||state.phase);
 const discoveryErrors={DISCOVERY_PAGE_CLOSED:'Вкладку пошуку закрито. Для нового пошуку знову відкрий сторінку.',DISCOVERY_PAGE_CRASHED:'Вкладка аварійно завершилась. Почни новий пошук.',DISCOVERY_PAGE_UNRESPONSIVE:'Сторінка не відповідає. Сеанс пошуку завершено; можна спробувати знову.',DISCOVERY_BROWSER_DISCONNECTED:'Зв’язок із браузером втрачено. Почни новий пошук.',DISCOVERY_WORKER_UNRESPONSIVE:'Процес пошуку перевищив граничний час або не відповідав 45 с і був зупинений.',DISCOVERY_BROWSER_CLEANUP_FAILED:'Не вдалося підтвердити закриття браузера. Завантаження не розпочато.',DISCOVERY_NAVIGATION_TIMEOUT:'Сторінка не завантажилась за 15 с. Перевір з’єднання й повтори пошук.',DISCOVERY_NAVIGATION_FAILED:'Не вдалося відкрити сторінку. Перевір адресу та з’єднання.'};
 discoveryErrors.DISCOVERY_BROWSER_START_FAILED='Не вдалося запустити браузер пошуку з обов’язковою пісочницею. Захист не вимикався. Перевір встановлення Chromium і технічний звіт.';
 $('error').textContent=state.error?(discoveryErrors[state.error]||`Код: ${state.error}. Якщо проблема повторюється, збережи технічний звіт.`):'';
 $('cleanup').hidden=!state.cleanupUncertain;$('cancel').hidden=!state.workerAlive;$('result').hidden=!state.fileReady;
 if(state.fileReady){$('output-path').textContent=state.outputPath;$('file').href='/api/file/'+encodeURIComponent(state.discoveryId);}
 const p=state.progress||{};$('progress').hidden=state.phase!=='FETCHING'||!p.total;
 $('progress').value=p.total?100*p.completed/p.total:0;
 $('numbers').textContent=p.total?`${p.completed} / ${p.total} сегментів · ${(p.bytes/1024/1024).toFixed(1)} MiB отримано.${state.fileReady?'':' 100% сегментів ще не означає готовий файл.'}`:'';
 $('destination-path').textContent=state.outputDirectory||'';
 $('folder-status').textContent=state.choosingFolder?'Вибери папку в окремому вікні Windows.':state.folderError?'Не вдалося вибрати папку. Перевір доступ до неї та спробуй ще раз.':'';
 $('folder-notice').hidden=!state.folderNotice;
 $('folder-notice').textContent=state.folderNotice==='SAVED_OUTPUT_UNAVAILABLE'?'Збережена папка або її налаштування недоступні. Зараз вибрано системну папку «Завантаження». Перевір шлях вище або вибери іншу папку. Попередній збережений вибір не змінено.':state.folderNotice==='OUTPUT_PREFERENCE_NOT_SAVED'?'Не вдалося запам’ятати папку між запусками. Цього разу відео зберігатиметься за шляхом вище; вибери папку ще раз, щоб повторити збереження налаштування.':'';
 const video=$('preview-video');
 if(state.previewReady){const src='/api/preview/'+encodeURIComponent(state.discoveryId)+'?v='+state.previewRevision;if(video.getAttribute('src')!==src){clearPreview();video.src=src;}video.hidden=false;}else clearPreview();
 const previewLimits={PREVIEW_REQUEST_LIMIT:'Перевищено бюджет запитів передперегляду.',PREVIEW_BYTE_LIMIT:'Перевищено бюджет даних передперегляду (128 MiB).',PREVIEW_TIME_LIMIT:'Вичерпано час передперегляду (2 хвилини).'};
 $('preview-status').textContent=state.phase==='PREVIEWING'?'Отримуємо початкові сегменти та готуємо фрагмент…':state.previewError?`${previewLimits[state.previewError]||`Передперегляд недоступний (${state.previewError}).`} Спробуй коротший фрагмент. Повне завантаження можна підтвердити окремо.`:state.previewReady?'Фрагмент готовий. Натисни ▶ для перегляду.':'';
 const r=state.review||{};$('review-panel').hidden=!['AWAITING_CONFIRMATION','PREVIEWING'].includes(state.phase);
 if(r.token&&r.token!==previewToken){previewToken=r.token;$('preview-seconds').value=String(Math.min(5000,r.previewMaximumMillis||5000)/1000);$('preview-settings').open=false;}
 $('preview-settings').hidden=!r.previewAvailable;
 $('preview-seconds').max=String((r.previewMaximumMillis||0)/1000);
 $('preview-limit').textContent=`Максимум: ${(r.previewMaximumMillis||0)/1000} с — за тривалістю найкоротшої потрібної доріжки у плейлистах. За замовчуванням — 5 с або весь потік, якщо він коротший.`;
 if(r.token){$('review').replaceChildren();for(const [label,value] of [['Тривалість',`${(r.seconds/60).toFixed(1)} хв`],['Сегменти',String(r.segments)],['Орієнтовний розмір',r.estimatedBytes<0?'Невідомий':`${(r.estimatedBytes/1024/1024).toFixed(1)} MiB · приблизно`],['Вже отримано',`${(r.transferredBytes/1024/1024).toFixed(1)} MiB`]]){const dt=document.createElement('dt'),dd=document.createElement('dd');dt.textContent=label;dd.textContent=value;$('review').append(dt,dd);}$('warning').hidden=!r.warning;}
 controls();
 discoveryActivity();
}
async function request(path,data){const response=await fetch(path,{signal:AbortSignal.timeout(10000),method:data===undefined?'GET':'POST',headers:{'X-StreamBridge-Key':key,...(data===undefined?{}:{'Content-Type':'application/json'})},...(data===undefined?{}:{body:JSON.stringify(data)})});if(!response.ok)throw new Error(response.status===403?'Доступ відхилено (403). Відкрий актуальне посилання з вікна запуску StreamBridge.':response.status===409?'Стан змінився. Онови вибір і спробуй ще раз.':`Запит не виконано (${response.status}).`);return response.json();}
async function command(path,data){if(sending||!online)return;const attempt=connectionAttempt;sending=true;controls();try{const next=await request(path,data);if(attempt===connectionAttempt)display(next);}catch(error){if(attempt===connectionAttempt)$('error').textContent=error.message;}finally{sending=false;controls();}}
$('start-form').addEventListener('submit',event=>{event.preventDefault();if(!state?.workerAlive){catalogRevision=-1;command('/api/start',{url:$('page-url').value.trim()});}});
$('variant').addEventListener('change',variantChanged);$('audio').addEventListener('change',controls);
function pinChoice(){return command('/api/pin',{id:state.discoveryId,revision:selectedRevision,candidate:selectedCandidateId});}
$('pin-retry').addEventListener('click',pinChoice);
$('prepare').addEventListener('click',()=>command('/api/prepare',{id:state.discoveryId,revision:selectedRevision,candidate:selectedCandidateId,variant:$('variant').value,audio:variant()?.audio.length?$('audio').value:'unknown'}));
$('preview').addEventListener('click',()=>{if(validPreviewDuration()){clearPreview();command('/api/preview',{id:state.discoveryId,token:state.review.token,durationMillis:Math.round(previewMillis())});}});
$('preview-seconds').addEventListener('input',controls);
$('preview-default').addEventListener('click',()=>{$('preview-seconds').value=String(Math.min(5000,state.review.previewMaximumMillis)/1000);controls();});
$('preview-video').addEventListener('loadedmetadata',()=>{const v=$('preview-video');if(v.getAttribute('src'))$('preview-metadata').textContent=`Фактичний фрагмент: ${v.videoWidth}×${v.videoHeight} · ${v.duration.toFixed(3)} с. Без перекодування.`;});
$('preview-video').addEventListener('error',()=>{if($('preview-video').getAttribute('src'))$('preview-metadata').textContent='Браузер не зміг відтворити фрагмент. Можливо, він не підтримує оригінальний кодек. Якість автоматично не змінюється; повне завантаження можна підтвердити окремо.';});
$('choose-folder').addEventListener('click',()=>command('/api/output-folder',{}));
$('confirm').addEventListener('click',()=>{clearPreview();command('/api/confirm',{id:state.discoveryId,token:state.review.token});});
$('cancel').addEventListener('click',()=>{clearPreview();command('/api/cancel',{id:state.discoveryId});});
async function report(){try{$('report').value=JSON.stringify(await request('/api/report'),null,2);}catch(error){$('error').textContent=error.message;}}
$('refresh-report').addEventListener('click',report);$('report-details').addEventListener('toggle',()=>{if($('report-details').open)report();});
async function connect(){
 const attempt=++connectionAttempt;events?.close();online=false;connectionMessage('Підключення…');controls();
 try{if(!key)throw new Error('Немає ключа сеансу. Відкрий UI через вікно запуску StreamBridge або його повне стартове посилання.');
 await request('/api/session',{});if(attempt!==connectionAttempt)return;
 const next=await request('/api/state');if(attempt!==connectionAttempt)return;
 display(next);
 events=new EventSource('/api/events');events.addEventListener('state',event=>{if(attempt!==connectionAttempt)return;online=true;connectionMessage('Підключено · лише цей комп’ютер');display(JSON.parse(event.data));});
 events.onerror=()=>{if(attempt!==connectionAttempt)return;online=false;connectionMessage('Зв’язок втрачено · перепідключення… Перевір, чи працює StreamBridge.');controls();discoveryActivity();};
 }catch(error){if(attempt!==connectionAttempt)return;online=false;connectionMessage(error.message);$('error').textContent=error.message;controls();}
}
$('reconnect').addEventListener('click',()=>{readKey();connect();});
window.addEventListener('hashchange',()=>{if(location.hash.length>1){readKey();connect();}});
connect();
