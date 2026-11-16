'use strict';

const $ = (selector) => document.querySelector(selector);
const appText = (zh, en) => window.AppLanguage.t(zh, en);
const state = {view:'home', config:null, report:null, summary:null, status:null, connected:null, saveState:null, summaryRemaining:null, refreshing:false, startedJob:false, persistentError:null, cacheErrorSignature:null, failedJobIds:new Set(), toastTimer:null, busy: new Set()};
let botSequence = 0;
const viewNames = {
  home:['首页','Home'],
  overview:['日统计','Daily stats'],
  tasks:['P0 任务','P0 tasks'],
  'task-lookup':['单条任务查询','Task lookup'],
  settings:['配置','Settings'],
  mappings:['映射','Mappings'],
  logs:['记录','Logs']
};
let reportAiController = null;
let overviewOpened = false;
let reportSequence = 0;
let mappingsLoaded = false;
let mappingsLoading = false;
const numberFormat = new Intl.NumberFormat('en-US', {maximumFractionDigits:2});
const escapeHtml = (value) => String(value ?? '').replace(/[&<>"']/g, (c) => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const number = (value) => value == null ? '—' : numberFormat.format(Number(value));
const moneyFormat = new Intl.NumberFormat('en-US', {maximumFractionDigits:12});
const money = (value) => value == null ? appText('未统计','Unavailable') : moneyFormat.format(Number(value));
const displayDate = (date) => typeof date === 'string' ? date.slice(5).replace('-', '/') : '—';
const icon = (name, className='') => `<svg class="${className}"><use href="#i-${name}"/></svg>`;
const showModal = (selector) => bootstrap.Modal.getOrCreateInstance($(selector)).show();
const hideModal = (selector) => bootstrap.Modal.getInstance($(selector))?.hide();

function toast(message, error=false) {
  clearTimeout(state.toastTimer);
  $('#toast').textContent = redactDiagnostic(message);
  $('#toast').classList.toggle('alert-danger',error);
  $('#toast').classList.toggle('alert-dark',!error);
  $('#toast').classList.remove('hidden');
  state.toastTimer = setTimeout(() => $('#toast').classList.add('hidden'), error ? 7000 : 4500);
}

function redactDiagnostic(value) {
  let text = String(value ?? '');
  const hidden = appText('[已隐藏]','[redacted]');
  for (const input of document.querySelectorAll('input[type="password"]')) {
    if (input.value && input.value.length >= 4) text = text.split(input.value).join(hidden);
  }
  return text.replace(/\bBearer\s+[^\s,;]+/gi,`Bearer ${hidden}`)
    .replace(/\b\d{5,}:[A-Za-z0-9_-]{15,}\b/g,hidden)
    .replace(/\b(?:sk|sess|ghp|github_pat)[-_][A-Za-z0-9_-]{10,}\b/gi,hidden)
    .replace(/((?:api[-_]?key|token|authorization|password|secret)\s*["']?\s*[:=]\s*["']?)[^"',;\s&]+/gi,`$1${hidden}`)
    .slice(0,8000);
}

function displayServerMessage(message,englishFallback) {
  const text = redactDiagnostic(message);
  if (!text) return englishFallback;
  return appText(text,/\p{Script=Han}/u.test(text) ? englishFallback : text);
}

function diagnosticPath(value) {
  try {return redactDiagnostic(new URL(String(value || location.pathname),location.origin).pathname);} catch {return '/';}
}

function diagnosticError(message,path,options={},status=null,details=null) {
  const error = new Error(redactDiagnostic(message));
  error.status = status;
  error.diagnostics = details && typeof details === 'object' ? details : {time:new Date().toISOString(),method:options.method || 'GET',path:diagnosticPath(path),status,message:error.message};
  return error;
}

function showPersistentError(error,context=appText('页面操作','Page action')) {
  if (error?.persistentShown) return;
  const details = error?.diagnostics && typeof error.diagnostics === 'object' ? error.diagnostics : {};
  const method = String(details.method || 'LOCAL').toUpperCase();
  const parsedTime = new Date(details.time || Date.now());
  const diagnostics = {
    time:Number.isNaN(parsedTime.getTime()) ? new Date().toISOString() : parsedTime.toISOString(),
    method:['GET','POST','PUT','PATCH','DELETE','HEAD','OPTIONS','LOCAL','JOB','BROWSER'].includes(method) ? method : 'LOCAL',
    path:diagnosticPath(details.path),
    status:Number.isInteger(details.status) && details.status >= 100 && details.status <= 599 ? details.status : null,
    step:redactDiagnostic(details.step || context),
    message:redactDiagnostic(details.message || error?.message || appText('操作失败，请复制错误详情反馈。','This action failed. Copy the error details to report it.'))
  };
  if (details.requestId) diagnostics.requestId = redactDiagnostic(details.requestId).slice(0,128);
  if (details.jobId) diagnostics.jobId = redactDiagnostic(details.jobId).slice(0,128);
  const signature = JSON.stringify([diagnostics.method,diagnostics.path,diagnostics.status,diagnostics.step,diagnostics.message]);
  if (error && typeof error === 'object') error.persistentShown = true;
  if (state.persistentError?.signature === signature) return;
  const text = JSON.stringify(diagnostics,null,2);
  state.persistentError = {signature,text,diagnostics,translatedMessage:error?.translatedMessage};
  renderErrorMessage();
  $('#error-details').textContent = text;
  $('#error-panel').classList.remove('hidden');
}

function renderErrorMessage() {
  const details = state.persistentError?.diagnostics;
  if (!details) return;
  const fallback = details.status ? `The server returned HTTP ${details.status}. See details below.` : 'This action failed. See details below.';
  const translations = state.persistentError.translatedMessage;
  const message = translations ? appText(...translations) : displayServerMessage(details.message,fallback);
  $('#error-message').textContent = appText(
    `${details.step}：${message}`,
    message
  );
}

async function copyText(text) {
  if (navigator.clipboard?.writeText) {
    try {await navigator.clipboard.writeText(text);return;} catch { /* HTTP and denied clipboard access use the fallback below. */ }
  }
  const previousFocus = document.activeElement;
  const scrollers = new Set([document.scrollingElement]);
  for (let element = previousFocus; element; element = element.parentElement) {
    if (element.scrollWidth > element.clientWidth || element.scrollHeight > element.clientHeight) scrollers.add(element);
  }
  const scrollPositions = [...scrollers].filter(Boolean).map((element) => [element, element.scrollLeft, element.scrollTop]);
  const input = document.createElement('textarea');
  input.value = text;
  input.className = 'clipboard-proxy';
  input.readOnly = true;
  input.tabIndex = -1;
  // Keep the copy field inside the Bootstrap focus trap when a modal is open.
  (document.querySelector('.modal.show') || document.body).append(input);
  try {
    input.focus({preventScroll:true});
    input.setSelectionRange(0,input.value.length);
    if (!document.execCommand('copy')) throw new Error(appText('无法自动复制，请展开详情后手动复制。','Could not copy automatically. Open the details and copy the text manually.'));
  } finally {
    input.remove();
    if (previousFocus?.isConnected) previousFocus.focus?.({preventScroll:true});
    for (const [element, left, top] of scrollPositions) {
      if (element.scrollLeft !== left) element.scrollLeft = left;
      if (element.scrollTop !== top) element.scrollTop = top;
    }
  }
}

async function api(path, options={}) {
  const {timeoutMs=30000,signal,...requestOptions} = options;
  const controller = new AbortController();
  const abort = () => controller.abort();
  let timedOut = false;
  if (signal?.aborted) throw new DOMException(appText('已取消请求。','Request canceled.'),'AbortError');
  signal?.addEventListener('abort',abort,{once:true});
  const timer = setTimeout(() => {timedOut=true;controller.abort();},timeoutMs);
  const headers = {'X-Requested-With':'foya-admin',...(options.body ? {'Content-Type':'application/json'}:{}),...options.headers};
  try {
    let response;
    try {response = await fetch(path,{...requestOptions,headers,signal:controller.signal,cache:'no-store'});}
    catch(error) {
      if (controller.signal.aborted) throw error;
      throw diagnosticError(appText('无法连接后台服务，请检查服务是否运行。','Could not connect to the server. Check that the service is running.'),path,options);
    }
    let data;
    try {data = await response.json();}
    catch(error) {
      if (controller.signal.aborted) throw error;
      throw diagnosticError(appText('后台返回格式异常，请复制错误详情反馈。','The server returned an unexpected response. Copy the error details to report it.'),path,options,response.status);
    }
    if (!response.ok) throw diagnosticError(data.error || data.message || appText(`请求失败（${response.status}）`,`Request failed (HTTP ${response.status}).`),path,options,response.status,data.errorDetails);
    return data;
  } catch(error) {
    if (timedOut) {
      const seconds = Math.round(timeoutMs / 1000);
      throw diagnosticError(appText(`请求超过 ${seconds} 秒，已停止等待，请稍后重试。`,`The request timed out after ${seconds}s. Try again.`),path,options);
    }
    if (signal?.aborted) throw new DOMException(appText('已取消请求。','Request canceled.'),'AbortError');
    throw error;
  } finally {clearTimeout(timer);signal?.removeEventListener('abort',abort);}
}

async function runAiTask(input,{timeoutSeconds=120,signal,onProgress}={}) {
  const budget = Math.min(300,Math.max(5,Number(timeoutSeconds) || 120));
  const started = performance.now();
  let id;
  let complete = false;
  const timeoutMessage = () => appText(`AI 总结超过 ${budget} 秒，已中止生成。`,`AI generation timed out after ${budget}s.`);
  const aborted = () => {if(signal?.aborted) throw new DOMException(appText('AI 总结已取消。','AI summary canceled.'),'AbortError');};
  const progress = () => {
    const elapsedSeconds = Math.floor((performance.now()-started)/1000);
    onProgress?.({elapsedSeconds,remainingSeconds:Math.max(0,budget-elapsedSeconds),timeoutSeconds:budget});
  };
  const timer = setInterval(progress,1000);
  try {
    aborted();progress();
    // Always read the start response so an immediately cancelled request can still cancel its server job.
    const job = await api('/api/ai/start',{method:'POST',body:JSON.stringify({...input,timeoutSeconds:budget}),timeoutMs:10000});
    id = job.id;
    if (!id) throw new Error(appText('AI 任务未返回编号，请重试。','No AI job ID was returned. Try again.'));
    while (true) {
      aborted();progress();
      const remaining = budget*1000-(performance.now()-started);
      if (remaining <= 0) return {aiStatus:'failed',aiMessage:timeoutMessage()};
      const current = await api(`/api/ai/status?id=${encodeURIComponent(id)}`,{signal,timeoutMs:Math.min(10000,Math.max(1,remaining))});
      if (current.state === 'complete') {complete=true;return current.result || {aiStatus:'failed',aiMessage:appText('AI 未返回总结。','No AI summary was returned.')};}
      if (current.state === 'cancelled') throw new DOMException(appText('AI 总结已取消。','AI summary canceled.'),'AbortError');
      if (current.state === 'timed_out') {complete=true;return current.result || {aiStatus:'failed',aiMessage:timeoutMessage()};}
      if (current.state !== 'running') {complete=true;return current.result || {aiStatus:'failed',aiMessage:appText('AI 生成失败，请稍后重试。','AI generation failed. Try again later.')};}
      await new Promise((resolve,reject) => {
        const stop = () => {clearTimeout(pause);reject(new DOMException(appText('AI 总结已取消。','AI summary canceled.'),'AbortError'));};
        const pause = setTimeout(() => {signal?.removeEventListener('abort',stop);resolve();},Math.min(800,remaining));
        signal?.addEventListener('abort',stop,{once:true});
      });
    }
  } finally {
    clearInterval(timer);
    if (id && !complete) await api('/api/ai/cancel',{method:'POST',body:JSON.stringify({id}),timeoutMs:5000}).catch(() => {});
  }
}

async function withButton(selector, fn, loadingText) {
  const button = $(selector);
  if (state.busy.has(selector)) return;
  const original = document.createElement('span');
  const loading = document.createElement('span');
  const renderLoading = () => {loading.textContent = appText(...loadingText);};
  state.busy.add(selector);
  button.disabled = true;
  if (loadingText) {
    original.hidden = true;
    original.append(...button.childNodes);
    button.append(original);
    button.insertAdjacentHTML('beforeend',icon('refresh','spinning'));
    button.append(loading);
    renderLoading();
    window.addEventListener('app-language-change',renderLoading);
  }
  try {return await fn();}
  catch(error) {handleError(error,(loadingText ? original : button).textContent.trim());}
  finally {
    state.busy.delete(selector);
    if (loadingText) {
      window.removeEventListener('app-language-change',renderLoading);
      button.replaceChildren(...original.childNodes);
    }
    button.disabled = false;
    updateActions();
  }
}

function yesterday(timezone='Asia/Shanghai') {
  const parts = new Intl.DateTimeFormat('en-CA',{timeZone:timezone,year:'numeric',month:'2-digit',day:'2-digit'}).formatToParts(new Date());
  const value = (type) => parts.find((part) => part.type === type).value;
  const date = new Date(`${value('year')}-${value('month')}-${value('day')}T12:00:00Z`);
  date.setUTCDate(date.getUTCDate()-1);
  return date.toISOString().slice(0,10);
}

function renderViewName() {
  const view = location.hash.slice(1);
  const target = Object.hasOwn(viewNames,view) ? view : 'home';
  const name = appText(...viewNames[target]);
  $('#breadcrumb-current').textContent = name;
  document.title = `${name} · AIAPI`;
  return target;
}

function setView(event) {
  const target = renderViewName();
  const switched = state.view !== target;
  state.view = target;
  document.body.dataset.view = target;
  document.querySelectorAll('.view').forEach((el) => el.classList.toggle('active',el.id === `view-${target}`));
  if (event && switched) {
    window.scrollTo(0,0);
    const heading = $(`#view-${target} h1`);
    heading.tabIndex = -1;
    heading.focus({preventScroll:true});
  }
  if (target === 'logs' && state.config) refreshStatus().catch(handleError);
  if (target === 'tasks') window.P0Tasks?.activate();
  loadModule(target);
}

function loadModule(view) {
  if (view === 'overview' && state.config && !overviewOpened) {
    void loadReport({silent:true});
  }
  if (view === 'mappings') void loadMappings();
}

function handleError(error,context=appText('页面操作','Page action')) {
  showPersistentError(error,context);
  const message = error?.translatedMessage ? appText(...error.translatedMessage)
    : displayServerMessage(error?.message || '操作失败，请查看详情。','This action failed. See error details.');
  toast(message,true);
}
function setConnected(ok) {
  state.connected = ok;
  $('#connection-dot').className = `connection-dot ${ok ? 'ok' : 'error'}`;
  $('#connection-text').textContent = ok ? appText('后台服务已连接','Connected')
    : appText('后台连接中断','Disconnected');
}

function readyBots() {
  return (state.config?.telegramBots || []).filter((bot) => bot.enabled && bot.tokenConfigured && (bot.autoDiscover !== false || bot.chatId));
}

function readBot(card) {
  const value = (field) => card.querySelector(`[data-field="${field}"]`).value.trim();
  const bot = {name:value('name'),chatId:value('chatId'),threadId:value('threadId'),sendTime:value('sendTime'),autoDiscover:card.querySelector('[data-field="autoDiscover"]').checked,enabled:card.querySelector('[data-field="enabled"]').checked};
  if (card.dataset.botId) bot.id = card.dataset.botId;
  if (value('token')) bot.token = value('token');
  if (card.querySelector('[data-field="clearToken"]').checked) {bot.clearToken = true;delete bot.token;}
  return bot;
}

function updateBotCount() {
  const count = document.querySelectorAll('[data-bot-card]').length;
  $('#bots-empty').classList.toggle('hidden',count > 0);
  $('#telegram-config-state').textContent = appText(`${count} 个`,`${count} ${count === 1 ? 'bot' : 'bots'}`);
}

const botCopy = {
  name:['名称','Name'],
  namePlaceholder:['日报机器人','Report bot'],
  configured:['已配置 · 留空保留','Configured · leave blank to keep'],
  tokenPlaceholder:['从 @BotFather 获取','Get a token from @BotFather'],
  enabled:['启用','Enabled'],
  discovery:['自动登记群组和私聊','Auto-register groups and direct chats'],
  discoveryHelp:['加群自动登记，私聊先发 /start；/stop 取消。','Groups register automatically. Send /start or /stop in a direct chat.'],
  test:['测试连接','Test connection'],
  advanced:['高级','Advanced'],
  remove:['删除','Remove'],
  chat:['手动 Chat ID（可选）','Manual chat ID (optional)'],
  chatPlaceholder:['-100… 或 @channel','-100… or @channel'],
  thread:['话题 ID（可选）','Topic ID (optional)'],
  time:['发送时间（留空用默认）','Send time (blank uses default)'],
  clear:['清除已保存 Token','Clear saved token']
};
const botText = (key) => appText(...botCopy[key]);
const botLabel = (key) => `<span data-bot-copy="${key}">${escapeHtml(botText(key))}</span>`;
const botPlaceholder = (key) => `data-bot-placeholder="${key}" placeholder="${escapeHtml(botText(key))}"`;

function renderBotLanguage() {
  for (const element of document.querySelectorAll('[data-bot-copy]')) element.textContent = botText(element.dataset.botCopy);
  for (const element of document.querySelectorAll('[data-bot-placeholder]')) element.placeholder = botText(element.dataset.botPlaceholder);
  updateBotCount();
  updateSubscriptionCounts();
}

function appendBot(bot={}) {
  const uid = ++botSequence;
  const configured = Boolean(bot.tokenConfigured);
  const card = document.createElement('div');
  card.className = 'border rounded p-3 bg-white';
  card.dataset.botCard = '';
  if (bot.id) card.dataset.botId = bot.id;
  card.innerHTML = `<div class="row g-2 align-items-end">
    <div class="col-md-3"><label class="form-label small mb-1" for="bot-name-${uid}">${botLabel('name')}</label><input class="form-control form-control-sm" id="bot-name-${uid}" data-field="name" type="text" required maxlength="100" ${botPlaceholder('namePlaceholder')} value="${escapeHtml(bot.name || appText(`机器人 ${uid}`,`Bot ${uid}`))}"></div>
    <div class="col-md-7"><label class="form-label small mb-1" for="bot-token-${uid}">Bot Token</label><input class="form-control form-control-sm" id="bot-token-${uid}" data-field="token" type="password" autocomplete="new-password" ${botPlaceholder(configured ? 'configured' : 'tokenPlaceholder')}></div>
    <div class="col-md-2"><label class="form-check form-switch small mb-1"><input class="form-check-input" data-field="enabled" type="checkbox" role="switch" ${bot.enabled !== false ? 'checked' : ''}>${botLabel('enabled')}</label></div>
    <div class="col-12 col-md-7"><label class="form-check small mt-1 mb-0"><input class="form-check-input" data-field="autoDiscover" type="checkbox" ${bot.autoDiscover !== false ? 'checked' : ''}>${botLabel('discovery')}</label><div class="form-text">${botLabel('discoveryHelp')}<span data-bot-subscriptions></span></div></div>
    <div class="col-12 col-md-5 d-flex justify-content-md-end flex-wrap gap-2"><button type="button" class="btn btn-outline-primary btn-sm" data-bot-action="test" id="bot-test-${uid}">${botLabel('test')}</button><button type="button" class="btn btn-outline-secondary btn-sm" data-bs-toggle="collapse" data-bs-target="#bot-advanced-${uid}" aria-expanded="false" aria-controls="bot-advanced-${uid}">${botLabel('advanced')}</button><button type="button" class="btn btn-outline-danger btn-sm" data-bot-action="remove">${botLabel('remove')}</button></div>
  </div><div class="collapse" id="bot-advanced-${uid}"><div class="row g-2 mt-1">
    <div class="col-md-5"><label class="form-label small mb-1" for="bot-chat-${uid}">${botLabel('chat')}</label><input class="form-control form-control-sm" id="bot-chat-${uid}" data-field="chatId" type="text" ${botPlaceholder('chatPlaceholder')} value="${escapeHtml(bot.chatId || '')}"></div>
    <div class="col-md-3"><label class="form-label small mb-1" for="bot-thread-${uid}">${botLabel('thread')}</label><input class="form-control form-control-sm" id="bot-thread-${uid}" data-field="threadId" type="text" inputmode="numeric" value="${escapeHtml(bot.threadId || '')}"></div>
    <div class="col-md-4"><label class="form-label small mb-1" for="bot-time-${uid}">${botLabel('time')}</label><input class="form-control form-control-sm" id="bot-time-${uid}" data-field="sendTime" type="time" value="${escapeHtml(bot.sendTime || '')}"></div>
  </div><label class="form-check small mt-2 mb-0 ${configured ? '' : 'hidden'}"><input class="form-check-input" type="checkbox" data-field="clearToken">${botLabel('clear')}</label></div>`;
  $('#telegram-bots').append(card);
  updateBotCount();
  updateSubscriptionCounts();
}

function renderBots(bots) {
  $('#telegram-bots').replaceChildren();
  bots.forEach(appendBot);
  updateBotCount();
}

function updateSubscriptionCounts() {
  const subscriptions = state.status?.subscriptions;
  for (const card of document.querySelectorAll('[data-bot-card]')) {
    const count = (subscriptions || []).filter((subscription) => subscription.botId === card.dataset.botId).length;
    card.querySelector('[data-bot-subscriptions]').textContent = subscriptions && card.dataset.botId
      ? appText(` 已登记 ${count} 个接收会话。`,` ${count} registered ${count === 1 ? 'chat' : 'chats'}.`) : '';
  }
  const targets = readyBots();
  const targetIds = new Set(targets.map((bot) => bot.id));
  const count = (subscriptions || []).filter((subscription) => targetIds.has(subscription.botId)).length;
  $('#telegram-target').textContent = targets.length ? appText(
    `启用 ${targets.length} 个机器人${subscriptions ? ` · 已登记 ${count} 个会话` : ''}`,
    `${targets.length} enabled ${targets.length === 1 ? 'bot' : 'bots'}${subscriptions ? ` · ${count} registered ${count === 1 ? 'chat' : 'chats'}` : ''}`
  ) : appText('暂无可发送的启用机器人','No enabled bots can send reports');
}

function renderConfigLabels(config) {
  const form = $('#config-form');
  const configured = appText('已配置 · 留空保持不变','Configured · leave blank to keep');
  $('#p0-key-hint').textContent = config.p0ApiKeyFromEnvironment
    ? appText('当前凭据由环境变量提供，环境变量始终优先；清除操作仅删除后台保存的密钥。','The environment variable takes precedence. Clearing removes only the saved key.')
    : appText('密钥不会回显；留空保留当前值。','Leave blank to keep the saved key.');
  $('#llm-key-hint').textContent = config.llmApiKeyFromEnvironment
    ? appText('当前模型密钥由环境变量提供，环境变量始终优先；清除操作仅删除后台保存的密钥。','The environment variable takes precedence. Clearing removes only the saved key.')
    : appText('密钥不会回显；留空保留当前值，不包含在导出配置中。','Leave blank to keep the saved key. Exports omit the key.');
  form.elements.llmApiKey.placeholder = config.llmApiKeyConfigured ? configured : appText('输入模型服务的 API Key','Enter the model API key');
  form.elements.p0ApiKey.placeholder = config.p0ApiKeyConfigured ? configured : appText('输入 API Key','Enter API key');
  $('#p0-config-state').textContent = config.p0ApiKeyConfigured ? appText('已配置','Configured') : appText('未设置密钥','No key set');
  const days = config.reportDays || 10;
  const reportDays = state.report?.days?.length || days;
  $('#days-badge').textContent = appText(`${reportDays} 天`,`${reportDays} ${reportDays === 1 ? 'day' : 'days'}`);
  $('#metric-total-note').textContent = appText(`近 ${days} 天 · 成功任务`,`Successful tasks · last ${days} ${days === 1 ? 'day' : 'days'}`);
  $('#metric-schedule').innerHTML = `${config.scheduleEnabled ? escapeHtml(config.sendTime || '09:00') : appText('未启用','Disabled')}<span class="schedule-dot ${config.scheduleEnabled ? 'enabled' : ''}"></span>`;
  const timezone = config.timezone || 'Asia/Shanghai';
  $('#metric-schedule-note').textContent = config.scheduleEnabled ? appText(`每日推送 · ${timezone}`,`Daily delivery · ${timezone}`)
    : appText('配置机器人后开启每日推送','Configure a bot to enable daily delivery');
  $('#schedule-zone-hint').textContent = appText(`按 ${timezone} 每日生成并发送；AI 不可用仍发统计。`,`Reports are delivered daily in ${timezone}, including when AI is unavailable.`);
  const allTargets = $('#send-target').options[0];
  if (allTargets) allTargets.textContent = appText('所有启用的机器人','All enabled bots');
}

function renderConfig(config) {
  reportAiController?.abort();
  state.config = config;
  const form = $('#config-form');
  for(const name of ['p0Endpoint','cacheUploadUrl','p0ApiKeyFile','p0TimeFormat','timezone','sendTime','reportDays','p0MaxAttempts','llmBaseUrl','llmModel','llmTimeoutSeconds']) {
    if (config[name] != null && form.elements[name]) form.elements[name].value = config[name];
  }
  form.elements.scheduleEnabled.checked = Boolean(config.scheduleEnabled);
  form.elements.llmEnabled.checked = Boolean(config.llmEnabled);
  state.summary = null;
  form.elements.p0ApiKey.value = '';
  form.elements.clearP0ApiKey.checked = false;
  form.elements.p0ApiKey.disabled = false;
  $('#clear-p0-wrap').classList.toggle('hidden',!config.p0ApiKeyConfigured);
  $('#clear-p0-hint').classList.add('hidden');
  form.elements.llmApiKey.value = '';form.elements.llmApiKey.disabled = false;form.elements.clearLlmApiKey.checked = false;
  $('#clear-llm-wrap').classList.toggle('hidden',!config.llmApiKeyConfigured);
  $('#clear-llm-hint').classList.add('hidden');
  $('#p0-config-state').className = `badge fw-normal ${config.p0ApiKeyConfigured ? 'text-bg-success' : 'text-bg-light border'}`;
  $('#setup-banner').classList.toggle('hidden',Boolean(config.p0ApiKeyConfigured || state.report));
  renderBots(config.telegramBots || []);
  const targets = readyBots();
  updateSubscriptionCounts();
  const selected = $('#send-target').value;
  $('#send-target').innerHTML = '<option value=""></option>' + targets.map((bot) => `<option value="${escapeHtml(bot.id)}">${escapeHtml(bot.name || bot.chatId)}</option>`).join('');
  $('#send-target').value = targets.some((bot) => bot.id === selected) ? selected : '';
  $('#send-target').classList.toggle('hidden',targets.length < 2);
  renderConfigLabels(config);
  $('#footer-timezone').textContent = config.timezone || 'Asia/Shanghai';
  const maxDate = yesterday(config.timezone || 'Asia/Shanghai');
  $('#end-date').max = maxDate;
  if (!$('#end-date').value || $('#end-date').value > maxDate) $('#end-date').value = maxDate;
  $('#summary-timeout').value = config.llmTimeoutSeconds || 120;
  window.P0Tasks?.configure(config);
  renderAiPreview();
  updateActions();
}

function updateActions() {
  const running = Boolean(state.status?.running);
  $('#pull-button').disabled = running || state.busy.has('#pull-button');
  $('#send-button').disabled = running || state.busy.has('#send-button') || state.busy.has('#summary-button') || !state.report || readyBots().length === 0;
  $('#copy-button').disabled = !state.report;
  $('#summary-button').disabled = running || !state.report || state.busy.has('#summary-button');
}

function renderReport(report) {
  if (state.report?.endDate !== report?.endDate || state.report?.text !== report?.text) reportAiController?.abort();
  if (!report || state.summary?.endDate !== report.endDate || state.summary?.rawText !== report.text) state.summary = null;
  state.report = report;
  renderReportView(report);
}

function renderReportView(report) {
  const hasData = Boolean(report);
  $('#setup-banner').classList.toggle('hidden',hasData || Boolean(state.config?.p0ApiKeyConfigured));
  $('#daily-empty').classList.toggle('hidden',hasData);
  $('#channel-empty').classList.toggle('hidden',Boolean(report?.channels?.length));
  $('#workflow-empty').classList.toggle('hidden',Boolean(report?.workflows?.length));
  const amountsPaused = report?.amountsEnabled !== true;
  $('#metric-count').textContent = number(report?.latestCount);
  $('#metric-fee').textContent = money(amountsPaused ? null : report?.latestFee);
  $('#metric-fee-note').textContent = amountsPaused ? appText('金额暂未统计','Fee tracking is off')
    : report.latestFeeComplete === false || report.latestFee == null ? appText('部分任务未配置单价','Some tasks have no unit price')
    : appText('按成功单数 × 单价汇总','Successful tasks × unit price');
  const byCount = amountsPaused || report?.channelsBy === 'count';
  $('#channel-sort-note').textContent = byCount ? appText('最新统计日 · 按单数降序','Latest day · by task count')
    : appText('最新统计日 · 按金额降序','Latest day · by fee');
  $('#channel-share-heading').textContent = byCount ? appText('单数占比','Task share') : appText('金额占比','Fee share');
  const incompleteFees = Boolean(report && (report.feeComplete === false || Number(report.missingFeeCount) > 0));
  $('#fee-notice').classList.toggle('hidden',!amountsPaused && !incompleteFees);
  const missingPrices = number(report?.missingFeeCount);
  $('#fee-notice').textContent = amountsPaused ? appText('金额暂未统计，渠道按单数排名','Fee tracking is off. Channels are ranked by task count.')
    : incompleteFees ? appText(
      `${Number(report.missingFeeCount) > 0 ? `${missingPrices} 单未配置单价` : '部分任务未配置单价'}，金额未统计，单数统计正常。${byCount ? '渠道按单数排名。' : ''}`,
      `${Number(report.missingFeeCount) > 0 ? `Tasks without a unit price: ${missingPrices}.` : 'Some tasks have no unit price.'} Fee totals are unavailable.${byCount ? ' Channels are ranked by task count.' : ''}`
    ) : '';
  $('#metric-total').textContent = number(report?.totalCount);
  $('#metric-count-note').textContent = report ? appText(`${displayDate(report.endDate)} · 成功任务`,`${displayDate(report.endDate)} · Successful tasks`)
    : appText('等待拉取数据','Waiting for data');
  $('#data-period').textContent = report ? `${displayDate(report.startDate)} — ${displayDate(report.endDate)}` : '—';
  $('#daily-rows').innerHTML = (report?.days || []).map((day) => `<tr><td>${escapeHtml(displayDate(day.date))}<span class="small text-body-secondary ms-2">${escapeHtml(day.date?.slice(0,4))}</span></td><td class="text-end">${number(day.count)}</td><td class="text-end">${money(amountsPaused ? null : day.fee)}</td><td class="text-end"><span class="badge text-bg-success fw-normal">${appText('远程缓存','Cached')}</span></td></tr>`).join('');
  $('#channel-rows').innerHTML = (report?.channels || []).slice(0,10).map((row,index) => `<tr><td title="${escapeHtml(row.name)}"><span class="badge ${index<3?'text-bg-primary':'text-bg-light border'} me-2 fw-normal">${index+1}</span>${escapeHtml(row.name)}</td><td class="text-end">${number(row.count)}</td><td class="text-end">${money(amountsPaused ? null : row.fee)}</td><td class="text-end text-body-secondary">${Number(row.share || 0).toFixed(1)}%</td></tr>`).join('');
  $('#workflow-rows').innerHTML = (report?.workflows || []).slice(0,10).map((row,index) => `<tr><td title="${escapeHtml(row.name)}"><span class="badge ${index<3?'text-bg-primary':'text-bg-light border'} me-2 fw-normal">${index+1}</span>${escapeHtml(row.name)}</td><td class="text-end">${number(row.count)}</td><td class="text-end text-body-secondary">${Number(row.share || 0).toFixed(1)}%</td></tr>`).join('');
  renderAiPreview();
  $('#preview-clock').textContent = report ? new Intl.DateTimeFormat(window.AppLanguage.locale(),{timeZone:state.config?.timezone || 'Asia/Shanghai',hour:'2-digit',minute:'2-digit',hour12:false}).format(new Date())
    : appText('预览','Preview');
  const days = report?.days?.length || state.config?.reportDays || 10;
  $('#days-badge').textContent = appText(`${days} 天`,`${days} ${days === 1 ? 'day' : 'days'}`);
  updateActions();
}


function renderAiPreview() {
  const summary = state.summary;
  const status = summary?.aiStatus || (state.config?.llmEnabled ? 'ready' : 'disabled');
  const labels = {
    ready:['AI 已启用 · 发送时自动生成，可先预览','AI summaries are enabled. Generate a preview or wait for delivery.'],
    pending:['正在生成 AI 总结','Generating AI summary'],
    ok:['AI 总结已加入报表预览','AI summary added to preview'],
    failed:['AI 总结暂不可用 · 将照常发送原始日报','AI summary unavailable. The original report can still be sent.'],
    cancelled:['AI 总结已取消','AI summary canceled'],
    disabled:['AI 总结未启用','AI summary is off']
  };
  $('#summary-status').textContent = appText(...(labels[status] || labels.failed));
  $('#summary-notice').className = `alert ${status === 'ok' ? 'alert-success' : status === 'failed' ? 'alert-warning' : status === 'pending' ? 'alert-primary' : 'alert-light'} border-0 border-bottom rounded-0 m-0 py-2 px-3 small`;
  $('#summary-notice').title = summary?.aiMessage ? displayServerMessage(summary.aiMessage,'See the AI summary status.') : '';
  $('#summary-dot').className = `tiny-dot me-1 ${status === 'ok' ? 'teal' : status === 'pending' ? 'blue' : ''}`;
  const reportText = summary?.text || state.report?.text;
  $('#report-preview').textContent = reportText || appText('刷新统计后，这里会显示实际发送的报表。','Refresh statistics to preview the report that will be sent.');
  $('#report-preview').lang = reportText ? 'zh-CN' : window.AppLanguage.locale();
  renderSummaryProgress();
}

function renderSummaryProgress() {
  const remaining = state.summaryRemaining;
  $('#summary-progress').textContent = remaining == null ? '' : appText(`剩余 ${remaining} 秒`,`${remaining}s remaining`);
}

async function loadReport({silent=false}={}) {
  const endDate = $('#end-date').value;
  const sequence = ++reportSequence;
  if (!endDate) return;
  overviewOpened = true;
  try {
    const report = await api(`/api/report?endDate=${encodeURIComponent(endDate)}`);
    if (sequence === reportSequence) renderReport(report);
  } catch(error) {
    if (sequence !== reportSequence) return;
    if (error.status === 409) {
      renderReport(null);
      if (!silent) toast(appText('所选日期还没有完整远程缓存，请点击「刷新统计」。','The selected date is missing cached data. Refresh statistics and try again.'));
    } else {
      showPersistentError(error,appText('读取报表','Load report'));
      if (!silent) toast(displayServerMessage(error.message,'Could not load the report. See error details.'),true);
    }
  }
}

function formatTime(time) {
  if (!time) return '—';
  const date = new Date(time);
  if (Number.isNaN(date.getTime())) return String(time);
  return new Intl.DateTimeFormat(window.AppLanguage.locale(),{timeZone:state.config?.timezone || 'Asia/Shanghai',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',second:'2-digit',hour12:false}).format(date);
}

function currentJobLabel(status) {
  if (!status.running) return status.job?.state === 'failed' ? appText('执行失败','Failed') : appText('空闲','Idle');
  if (['send','发送报表'].includes(status.job?.kind)) return appText('正在发送','Sending report');
  if (status.job?.kind === '定时报表') return appText('正在执行定时报表','Running scheduled report');
  return appText('正在拉取','Refreshing statistics');
}

function englishJobMessage(job, fallback) {
  const progress = job?.progress;
  if (progress?.date && progress.completedHours != null && progress.totalHours != null)
    return `${progress.date} · ${progress.completedHours}/${progress.totalHours} hours complete · ${progress.requests} requests`;
  if (progress?.date) return `Checking cache for ${progress.date}`;
  if (job?.message?.startsWith('正在生成 AI')) return 'Generating AI summary';
  if (job?.message?.startsWith('正在发送：')) return `Sending to ${job.message.slice('正在发送：'.length)}`;
  return fallback;
}

function logMessage(message) {
  if (window.AppLanguage.locale() !== 'en') return message;
  const labels = {
    '拉取统计任务开始':'Statistics refresh started',
    '发送报表任务开始':'Report delivery started',
    '定时报表任务开始':'Scheduled report started',
    '任务已完成':'Job completed',
    '任务失败':'Job failed'
  };
  if (Object.hasOwn(labels,message)) return labels[message];
  const cached = /^统计缓存已就绪：(\d{4}-\d{2}-\d{2}) 至 (\d{4}-\d{2}-\d{2})$/.exec(message);
  return cached ? `Statistics cached for ${cached[1]} to ${cached[2]}` : message;
}

function renderStatusView(status) {
  const running = Boolean(status.running);
  $('#job-banner').classList.toggle('hidden',!running);
  const currentJob = currentJobLabel(status);
  $('#job-message').textContent = appText(status.job?.message || '正在处理，请稍候…',englishJobMessage(status.job,currentJob));
  $('#current-job').textContent = currentJob;
  const cacheDays = status.cacheDays || [];
  const cacheError = status.cacheError;
  $('#cache-count').textContent = cacheError ? appText('读取失败','Unavailable')
    : appText(`${cacheDays.length} 天`,`${cacheDays.length} ${cacheDays.length === 1 ? 'day' : 'days'}`);
  $('#cache-count').classList.toggle('text-danger',Boolean(cacheError));
  $('#last-sent').textContent = status.lastSentDate || appText('尚未发送','No reports sent');
  $('#next-run').textContent = status.nextRun ? formatTime(status.nextRun) : state.config?.scheduleEnabled
    ? appText('等待下次计划','Waiting for the next run') : appText('未启用','Disabled');
  $('#cache-dates').classList.toggle('text-danger',Boolean(cacheError));
  if (cacheError) $('#cache-dates').textContent = appText('远程缓存目录读取失败，请查看错误详情。','Could not read cache dates. See error details.');
  else $('#cache-dates').innerHTML = cacheDays.length
    ? [...cacheDays].sort().reverse().map((date) => `<span class="badge text-bg-light border fw-normal">${escapeHtml(date)}</span>`).join('')
    : `<span class="small text-body-secondary">${appText('暂无远程缓存','No cached dates')}</span>`;
  const logs = status.logs || [];
  $('#logs-empty').classList.toggle('hidden',logs.length > 0);
  $('#log-rows').innerHTML = [...logs].sort((a,b) => String(b.time || '').localeCompare(String(a.time || ''))).map((log) => {
    const level = String(log.level || 'info').toLowerCase();
    const type = level.includes('error') || level === 'failed' ? 'error' : level === 'success' ? 'success' : level === 'warn' ? 'warn' : '';
    const label = type === 'error' ? appText('失败','Error') : type === 'success' ? appText('完成','Done') : type === 'warn' ? appText('提示','Warning') : appText('信息','Info');
    return `<tr><td class="text-nowrap text-body-secondary">${escapeHtml(formatTime(log.time))}</td><td><span class="badge fw-normal ${type === 'error' ? 'text-bg-danger' : type === 'success' ? 'text-bg-success' : type === 'warn' ? 'text-bg-warning' : 'text-bg-light border'}">${label}</span></td><td class="text-break">${escapeHtml(logMessage(log.message))}</td></tr>`;
  }).join('');
}

function renderStatus(status) {
  const wasRunning = state.status?.running;
  const previousJob = state.status?.job;
  state.status = status;
  updateSubscriptionCounts();
  renderStatusView(status);
  const running = Boolean(status.running);
  const cacheError = status.cacheError ? redactDiagnostic(status.cacheError) : '';
  if (cacheError && state.cacheErrorSignature !== cacheError) {
    state.cacheErrorSignature = cacheError;
    const error = diagnosticError(cacheError,'/api/status',{method:'GET'});
    const context = appText('读取远程缓存','Load cache dates');
    error.diagnostics.step = context;
    showPersistentError(error,context);
  } else if (!cacheError) state.cacheErrorSignature = null;
  if ((wasRunning || state.startedJob) && !running && status.job && (status.job !== previousJob || state.startedJob)) {
    state.startedJob = false;
    if (status.job.state === 'failed' || status.job.error) toast(displayServerMessage(status.job.error || status.job.message || '执行失败，请查看记录。','Job failed. See activity for details.'),true);
    else {
      toast(appText('任务已完成。','Job completed.'));
      if (overviewOpened) void loadReport({silent:true});
    }
  }
  if (status.job && (status.job.state === 'failed' || status.job.error)) {
    const job = status.job;
    const key = job.id || job.errorDetails?.jobId || `${job.startedAt || ''}:${job.kind || ''}:${job.error || job.message || ''}`;
    if (!state.failedJobIds.has(key)) {
      state.failedJobIds.add(key);
      if (state.failedJobIds.size > 100) state.failedJobIds.delete(state.failedJobIds.values().next().value);
      const error = new Error(job.error || job.message || appText('任务执行失败。','Job failed.'));
      error.diagnostics = {method:'JOB',path:'/api/status',time:job.finishedAt,step:job.kind,message:error.message,...job.errorDetails};
      showPersistentError(error,job.kind || appText('后台任务','Background job'));
    }
  }
  updateActions();
}

async function refreshStatus() {
  if (state.refreshing) return;
  state.refreshing = true;
  try {renderStatus(await api('/api/status'));setConnected(true);} catch(error) {setConnected(false);showPersistentError(error,appText('状态刷新','Refresh status'));throw error;} finally {state.refreshing=false;}
}

function configPayload(includeBots=true) {
  const form = $('#config-form');
  const payload = {};
  for(const name of ['p0Endpoint','cacheUploadUrl','p0ApiKey','p0ApiKeyFile','p0TimeFormat','timezone','sendTime','llmBaseUrl','llmModel','llmApiKey']) payload[name] = form.elements[name].value.trim();
  payload.reportDays = Number(form.elements.reportDays.value);
  payload.p0MaxAttempts = Number(form.elements.p0MaxAttempts.value);
  if (!Number.isInteger(payload.p0MaxAttempts) || payload.p0MaxAttempts < 1 || payload.p0MaxAttempts > 50) throw new Error(appText('每页最多尝试次数应为 1–50 之间的整数。','Enter a whole number from 1 to 50 for page attempts.'));
  payload.scheduleEnabled = form.elements.scheduleEnabled.checked;
  payload.llmEnabled = form.elements.llmEnabled.checked;
  payload.llmTimeoutSeconds = Number(form.elements.llmTimeoutSeconds.value);
  if (!Number.isInteger(payload.llmTimeoutSeconds) || payload.llmTimeoutSeconds < 5 || payload.llmTimeoutSeconds > 300) throw new Error(appText('模型生成时限应为 5–300 秒之间的整数。','Enter a whole number from 5 to 300 seconds for the model timeout.'));
  if (!payload.llmApiKey) delete payload.llmApiKey;
  payload.clearLlmApiKey = form.elements.clearLlmApiKey.checked;
  if (payload.clearLlmApiKey) delete payload.llmApiKey;
  if (!payload.p0ApiKey) delete payload.p0ApiKey;
  payload.clearP0ApiKey = form.elements.clearP0ApiKey.checked;
  if (payload.clearP0ApiKey) delete payload.p0ApiKey;
  if (includeBots) payload.telegramBots = [...document.querySelectorAll('[data-bot-card]')].map(readBot);
  return payload;
}

function downloadJson(filename, data) {
  const url = URL.createObjectURL(new Blob([JSON.stringify(data,null,2)+'\n'],{type:'application/json'}));
  const link = document.createElement('a');link.href = url;link.download=filename;link.click();
  setTimeout(() => URL.revokeObjectURL(url),1000);
}

function parseMap(text,name) {
  let result;
  try {result=JSON.parse(text);}
  catch {throw new Error(appText(`${name} JSON 格式不正确，请检查逗号和引号。`,`${name}: invalid JSON. Check commas and quotation marks.`));}
  if (!result || Array.isArray(result) || typeof result !== 'object' || Object.values(result).some((value) => typeof value !== 'string'))
    throw new Error(appText(`${name}应为键和值均为字符串的 JSON 对象。`,`${name}: use a JSON object with string keys and values.`));
  return result;
}
// Keep JSON number literals exact before they reach JavaScript's binary Number representation.
function parsePriceJson(text) {
  let output='',quoted=false,escaped=false,previous='';
  for (let index=0;index<text.length;index++) {
    const character=text[index];
    if (quoted) {
      output+=character;
      if (escaped) escaped=false;
      else if (character==='\\') escaped=true;
      else if (character==='"') {quoted=false;previous='"';}
      continue;
    }
    if (character==='"') {quoted=true;output+=character;continue;}
    if (previous===':' && /[\d-]/.test(character)) {
      const literal=/^-?(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?/.exec(text.slice(index));
      if (literal) {output+=JSON.stringify(literal[0]);index+=literal[0].length-1;previous='n';continue;}
    }
    output+=character;
    if (!/\s/.test(character)) previous=character;
  }
  return JSON.parse(output);
}

function parsePriceMap(text) {
  let result;
  try {result = parsePriceJson(text);} catch {throw new Error(appText('单价映射 JSON 格式不正确，请检查逗号和引号。','Price mapping JSON is invalid. Check commas and quotation marks.'));}
  if (!result || Array.isArray(result) || typeof result !== 'object') throw new Error(appText('单价映射应为 task_type 到单价的 JSON 对象。','Use a JSON object mapping task_type values to unit prices.'));
  const entries = Object.entries(result);
  if (entries.length > 10000) throw new Error(appText('单价映射最多支持 10000 项。','Use at most 10,000 unit prices.'));
  for (const [key,value] of entries) {
    if (!key.trim()) throw new Error(appText('单价映射的 task_type 不能为空。','A task_type key cannot be empty.'));
    if (typeof value === 'number') {
      if (!Number.isFinite(value) || value < 0) throw new Error(appText('单价必须是非负有限数字，允许 0。','A unit price must be a finite number of zero or more.'));
    } else if (typeof value === 'string') {
      const decimal = value.trim();
      if (!decimal || !/^\+?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+-]?\d+)?$/.test(decimal)) throw new Error(appText('单价字符串必须是非负十进制数，不能留空。','A unit price string must be a nonempty decimal of zero or more.'));
      result[key] = decimal;
    } else throw new Error(appText('单价只能是非负数字或十进制字符串，不能是布尔值、null、数组或对象。','Use a nonnegative number or decimal string for each unit price.'));
  }
  return result;
}

function mappingPayload() {
  return {channelNames:parseMap($('#channel-map').value,appText('渠道映射','Channel mappings')),workflowNames:parseMap($('#workflow-map').value,appText('工作流映射','Workflow mappings')),taskTypePrices:parsePriceMap($('#price-map').value),amountsEnabled:$('#amounts-enabled').checked};
}

function updatePriceCount() {
  try {
    const count = Object.keys(parsePriceMap($('#price-map').value)).length;
    $('#price-map-count').textContent = appText(`${count} 条`,`${count} ${count === 1 ? 'entry' : 'entries'}`);
  }
  catch {$('#price-map-count').textContent = appText('待校验','Check JSON');}
}

function renderMappings(mappings) {
  $('#channel-map').value=JSON.stringify(mappings.channelNames || {},null,2);
  $('#workflow-map').value=JSON.stringify(mappings.workflowNames || {},null,2);
  $('#channel-map-count').textContent=Object.keys(mappings.channelNames || {}).length;
  $('#workflow-map-count').textContent=Object.keys(mappings.workflowNames || {}).length;
  $('#price-map').value=JSON.stringify(mappings.taskTypePrices || {},null,2);
  $('#amounts-enabled').checked=Boolean(mappings.amountsEnabled);
  updatePriceCount();
}

async function loadMappings() {
  if (mappingsLoaded || mappingsLoading) return;
  const form = $('#mapping-form');
  mappingsLoading = true;
  form.inert = true;
  form.setAttribute('aria-busy','true');
  try {
    renderMappings(await api('/api/mappings'));
    mappingsLoaded = true;
  } catch(error) {
    handleError(error,appText('加载名称映射','Load name mappings'));
  } finally {
    mappingsLoading = false;
    form.inert = !mappingsLoaded;
    form.removeAttribute('aria-busy');
  }
}

for (const [clearName,keyName,hint] of [['clearP0ApiKey','p0ApiKey','#clear-p0-hint'],['clearLlmApiKey','llmApiKey','#clear-llm-hint']]) {
  $('#config-form').elements[clearName].addEventListener('change',(event) => {
    const input = $('#config-form').elements[keyName];
    input.disabled = event.target.checked;
    if (event.target.checked) input.value = '';
    $(hint).classList.toggle('hidden',!event.target.checked);
  });
}

function renderSaveState() {
  const labels = {
    changed:['机器人列表已修改，请保存配置。','Bot list edited. Save to apply changes.'],
    removed:['删除将在保存配置后生效。','Save settings to remove the bot.'],
    saved:['配置已保存 · 密钥已隐藏','Settings saved. Keys remain hidden.']
  };
  if (state.saveState) $('#save-state').textContent = appText(...labels[state.saveState]);
}

function setSaveState(status) {
  state.saveState = status;
  renderSaveState();
}

$('#add-bot').addEventListener('click',() => {appendBot({enabled:true});setSaveState('changed');});
$('#telegram-bots').addEventListener('click',(event) => {
  const button = event.target.closest('[data-bot-action]');
  if (!button) return;
  const card = button.closest('[data-bot-card]');
  if (button.dataset.botAction === 'remove') {card.remove();updateBotCount();setSaveState('removed');return;}
  withButton(`#${button.id}`,async () => {
    const result = await api('/api/test/telegram',{method:'POST',body:JSON.stringify({bot:readBot(card)})});
    toast(displayServerMessage(result.message || '机器人连接正常。','Bot connection successful.'));
  },['正在测试','Testing…']);
});
$('#telegram-bots').addEventListener('change',(event) => {
  if (event.target.dataset.field !== 'clearToken') return;
  const token = event.target.closest('[data-bot-card]').querySelector('[data-field="token"]');
  token.disabled = event.target.checked;
  if (event.target.checked) token.value = '';
});

$('#config-form').addEventListener('submit',(event) => {
  event.preventDefault();
  withButton('#save-config',async () => {
    const config = await api('/api/config',{method:'PUT',body:JSON.stringify(configPayload())});
    renderConfig(config);
    setSaveState('saved');
    toast(appText('配置已保存。','Settings saved.'));
    await refreshStatus();
    if (overviewOpened) await loadReport({silent:true});
  },['正在保存','Saving…']);
});
$('#mapping-form').addEventListener('submit',(event) => {
  event.preventDefault();
  withButton('#save-mappings',async () => {
    const result=await api('/api/mappings',{method:'PUT',body:JSON.stringify(mappingPayload())});
    renderMappings(result);state.summary=null;
    if (state.config) state.config={...state.config,amountsEnabled:Boolean(result.amountsEnabled)};
    try {const config=await api('/api/config');if(state.config) state.config={...state.config,amountsEnabled:Boolean(config.amountsEnabled)};}
    catch(error) {handleError(error,appText('刷新金额配置','Refresh price settings'));}
    if (overviewOpened) await loadReport({silent:true});
    toast(appText('映射配置已保存。','Mappings saved.'));
  },['正在保存','Saving…']);
});
$('#pull-button').addEventListener('click',() => withButton('#pull-button',async () => {
  await api('/api/pull',{method:'POST',body:JSON.stringify({endDate:$('#end-date').value,force:false})});
  state.startedJob = true;toast(appText('已开始拉取，缺失的历史日期会自动补齐。','Refresh started. Missing report days will be filled in.'));await refreshStatus();
},['提交任务','Starting…']));
$('#end-date').addEventListener('change',() => loadReport());
$('#test-p0').addEventListener('click',() => withButton('#test-p0',async () => {
  const result = await api('/api/test/p0',{method:'POST',body:JSON.stringify(configPayload(false))});
  toast(displayServerMessage(result.message || 'P0 接口连接正常。','P0 connection successful.'));
},['正在测试','Testing…']));
$('#test-llm').addEventListener('click',() => withButton('#test-llm',async () => {
  const result = await api('/api/test/llm',{method:'POST',body:JSON.stringify(configPayload(false))});
  const failed = result.status === 'failed';
  if (failed) showPersistentError(diagnosticError(result.message || 'AI 模型连接失败。','/api/test/llm',{method:'POST'},null,result.errorDetails),appText('LLM 连接测试','Test AI connection'));
  const defaultMessage = failed ? 'AI 模型连接失败。' : 'AI 模型服务连接正常。';
  toast(displayServerMessage(result.message || defaultMessage,failed ? 'AI connection failed. See error details.' : 'AI connection successful.'),failed);
},['正在测试','Testing…']));
$('#summary-cancel').addEventListener('click',() => reportAiController?.abort());
$('#summary-button').addEventListener('click',() => withButton('#summary-button',async () => {
  if (!state.report) return;
  const timeoutSeconds = Number($('#summary-timeout').value);
  if (!Number.isInteger(timeoutSeconds) || timeoutSeconds < 5 || timeoutSeconds > 300) throw new Error(appText('AI 时限应为 5–300 秒。','The AI timeout must be between 5 and 300 seconds.'));
  const report = state.report;
  const configReference = state.config;
  const controller = new AbortController();
  reportAiController = controller;
  state.summary = {endDate:report.endDate,rawText:report.text,text:report.text,aiStatus:'pending'};
  $('#summary-cancel').classList.remove('hidden');
  $('#summary-timeout').disabled = true;
  renderAiPreview();updateActions();
  try {
    const result = await runAiTask({kind:'report',endDate:report.endDate},{timeoutSeconds,signal:controller.signal,onProgress:({remainingSeconds}) => {
      state.summaryRemaining = remainingSeconds;
      renderSummaryProgress();
    }});
    if (state.config !== configReference || state.report?.endDate !== report.endDate || state.report?.text !== report.text) return;
    state.summary = {...result,endDate:report.endDate,rawText:report.text,text:result.aiStatus === 'ok' ? (result.text || report.text) : report.text};
    renderAiPreview();
    if (result.aiStatus === 'failed') showPersistentError(diagnosticError(result.aiMessage || 'AI 总结暂不可用，已保留原始日报。','/api/ai/status',{method:'GET'},null,result.errorDetails),appText('AI 总结','AI summary'));
    toast(result.aiStatus === 'ok' ? appText('AI 总结已加入预览。','AI summary added to preview.')
      : displayServerMessage(result.aiMessage || 'AI 总结未生成，已保留原始日报。','AI summary was not generated. The original report is available.'));
  } catch(error) {
    if (state.config === configReference && state.report?.endDate === report.endDate && state.report?.text === report.text) {
      state.summary = {endDate:report.endDate,rawText:report.text,text:report.text,aiStatus:error.name === 'AbortError' ? 'cancelled' : 'failed',aiMessage:error.message};
      renderAiPreview();
    }
    if (error.name === 'AbortError') {toast(appText('AI 生成已取消。','AI generation canceled.'));return;}
    throw error;
  } finally {
    if (reportAiController === controller) reportAiController = null;
    $('#summary-cancel').classList.add('hidden');
    $('#summary-timeout').disabled = false;
    state.summaryRemaining = null;
    renderSummaryProgress();
  }
},['正在生成','Generating…']));

function selectedTargets() {
  const selected = $('#send-target').value;
  return readyBots().filter((bot) => !selected || bot.id === selected);
}

function renderSendDialog(targets) {
  $('#confirm-chat').innerHTML = targets.map((bot) => {
    const subscriptions = state.status?.subscriptions;
    const count = (subscriptions || []).filter((subscription) => subscription.botId === bot.id).length;
    const automatic = bot.autoDiscover !== false ? appText(
      `自动登记群组与私聊${subscriptions ? `（${count} 个）` : ''}`,
      `Auto-registered chats${subscriptions ? ` (${count})` : ''}`
    ) : '';
    const manual = bot.chatId ? appText(
      `手动会话 ${bot.chatId}${bot.threadId ? ` · 话题 ${bot.threadId}` : ''}`,
      `Manual chat ${bot.chatId}${bot.threadId ? ` · Topic ${bot.threadId}` : ''}`
    ) : '';
    const label = [bot.name || appText('机器人','Bot'),automatic,manual].filter(Boolean).join(' · ');
    return `<div class="mb-1">${escapeHtml(label)}</div>`;
  }).join('');
  $('#confirm-period').textContent = `${state.report.startDate} — ${state.report.endDate}`;
}

$('#send-button').addEventListener('click',() => {
  if (!state.report) return;
  const targets = selectedTargets();
  if (!targets.length) {toast(appText('请先保存至少一个已启用且配置完整的机器人。','Save at least one configured, enabled bot.'),true);return;}
  renderSendDialog(targets);
  showModal('#send-dialog');
});
$('#cancel-send').addEventListener('click',() => hideModal('#send-dialog'));
$('#confirm-send').addEventListener('click',() => withButton('#confirm-send',async () => {
  await api('/api/send',{method:'POST',body:JSON.stringify({endDate:state.report.endDate,...($('#send-target').value ? {botId:$('#send-target').value} : {})})});
  hideModal('#send-dialog');state.startedJob=true;toast(appText('发送任务已提交。','Report delivery started.'));await refreshStatus();
},['正在提交','Submitting…']));
$('#copy-button').addEventListener('click',() => withButton('#copy-button',async () => {await copyText(state.summary?.text || state.report?.text || '');toast(appText('报表已复制。','Report copied.'));}));
$('#refresh-status').addEventListener('click',() => withButton('#refresh-status',async () => {await refreshStatus();toast(appText('记录已更新。','Activity refreshed.'));},['正在刷新','Refreshing…']));
$('#export-config').addEventListener('click',() => {
  if (!state.config) return;
  const {p0ApiKey,telegramBotToken,llmApiKey,p0ApiKeyConfigured,telegramBotTokenConfigured,llmApiKeyConfigured,...safe}=state.config;
  safe.telegramBots = (safe.telegramBots || []).map(({token,tokenConfigured,...bot}) => bot);
  downloadJson('aiapi-config.json',safe);toast(appText('已导出配置，不包含 P0、Telegram 或模型密钥。','Exported settings with API keys and bot tokens removed.'));
});
$('#export-mappings').addEventListener('click',() => {
  try {downloadJson('aiapi-mappings.json',mappingPayload());} catch(error){handleError(error,appText('导出映射配置','Export mappings'));}
});
for(const [inputId,textareaId,labels,key] of [['import-channels','channel-map',['渠道映射','Channel mappings'],'channelNames'],['import-workflows','workflow-map',['工作流映射','Workflow mappings'],'workflowNames']]) {
  $(`#${inputId}`).addEventListener('change',async(event) => {
    const file=event.target.files[0];if(!file) return;
    try {
      if (file.size>2*1024*1024) throw new Error(appText('映射文件不能超过 2 MB。','Mapping files must be 2 MB or smaller.'));
      const raw = JSON.parse(await file.text());
      const parsed = parseMap(JSON.stringify(raw[key] || raw),appText(...labels));
      $(`#${textareaId}`).value = JSON.stringify(parsed,null,2);
      const count = Object.keys(parsed).length;
      toast(appText(`已导入 ${count} 个名称，请保存映射。`,`Imported ${count} ${count === 1 ? 'name' : 'names'}. Save the mappings to apply them.`));
    } catch(error) {
      handleError(error instanceof SyntaxError ? new Error(appText('文件不是有效的 JSON。','The file is not valid JSON.')) : error,appText('导入名称映射','Import name mappings'));
    } finally {event.target.value = '';}
  });
}
$('#price-map').addEventListener('input',updatePriceCount);
$('#export-prices').addEventListener('click',() => {
  try {downloadJson('aiapi-task-prices.json',{amountsEnabled:$('#amounts-enabled').checked,taskTypePrices:parsePriceMap($('#price-map').value)});}
  catch(error) {handleError(error,appText('导出单价映射','Export unit prices'));}
});
$('#import-prices').addEventListener('change',async(event) => {
  const file=event.target.files[0];if(!file) return;
  try {
    if (file.size>2*1024*1024) throw new Error(appText('单价映射文件不能超过 2 MB。','Unit price files must be 2 MB or smaller.'));
    const raw=parsePriceJson(await file.text());
    const wrapped=raw && typeof raw==='object' && !Array.isArray(raw) && Object.hasOwn(raw,'taskTypePrices');
    const prices=parsePriceMap(JSON.stringify(wrapped ? raw.taskTypePrices : raw));
    if (wrapped && Object.hasOwn(raw,'amountsEnabled') && typeof raw.amountsEnabled!=='boolean') throw new Error(appText('amountsEnabled 必须是 true 或 false。','amountsEnabled must be true or false.'));
    $('#price-map').value=JSON.stringify(prices,null,2);
    if (wrapped && Object.hasOwn(raw,'amountsEnabled')) $('#amounts-enabled').checked=raw.amountsEnabled;
    updatePriceCount();
    const count = Object.keys(prices).length;
    toast(appText(`已导入 ${count} 条单价，请保存映射配置。`,`Imported ${count} unit ${count === 1 ? 'price' : 'prices'}. Save the mappings to apply them.`));
  } catch(error) {handleError(error instanceof SyntaxError ? new Error(appText('文件不是有效的 JSON。','The file is not valid JSON.')) : error,appText('导入单价映射','Import unit prices'));}
  finally {event.target.value='';}
});
$('#copy-error').addEventListener('click',async () => {
  if (!state.persistentError) return;
  try {await copyText(state.persistentError.text);toast(appText('脱敏错误详情已复制，可直接粘贴反馈。','Redacted error details copied.'));}
  catch {toast(appText('无法自动复制，请展开详情后手动复制。','Could not copy automatically. Open the details and copy the text manually.'),true);}
});
$('#clear-error').addEventListener('click',() => {
  state.persistentError = null;
  $('#error-panel').classList.add('hidden');
  $('#error-message').textContent = '';$('#error-details').textContent = '';
});
window.addEventListener('error',() => {
  const error = diagnosticError(appText('页面脚本发生错误，请复制详情反馈。','A page script failed. Copy the error details to report it.'),location.pathname,{method:'BROWSER'});
  showPersistentError(error,appText('页面脚本','Page script'));
});
window.addEventListener('unhandledrejection',() => {
  const error = diagnosticError(appText('页面操作未完成，请复制详情反馈。','The page action did not finish. Copy the error details to report it.'),location.pathname,{method:'BROWSER'});
  showPersistentError(error,appText('页面操作','Page action'));
});
window.addEventListener('hashchange',setView);
window.addEventListener('app-language-change',() => {
  renderViewName();
  if (state.connected != null) setConnected(state.connected);
  if (state.config) renderConfigLabels(state.config);
  renderBotLanguage();
  renderSaveState();
  renderReportView(state.report);
  if (state.status) renderStatusView(state.status);
  if (state.report && $('#send-dialog').classList.contains('show')) renderSendDialog(selectedTargets());
  updatePriceCount();
  renderErrorMessage();
  $('#toast').classList.add('hidden');
});

async function initialize() {
  try {
    renderConfig(await api('/api/config'));
    loadModule(state.view);
    await refreshStatus();
  } catch(error){setConnected(false);handleError(error);}
}

$('#end-date').value=yesterday();
setView();initialize();
setInterval(() => {if(document.visibilityState==='visible') refreshStatus().catch(() => {});},3000);
document.addEventListener('visibilitychange',() => {if(document.visibilityState==='visible') refreshStatus().catch(() => {});});
