'use strict';

const $ = (selector) => document.querySelector(selector);
const state = {config:null, report:null, summary:null, status:null, refreshing:false, startedJob:false, persistentError:null, failedJobIds:new Set(), toastTimer:null, busy: new Set()};
let botSequence = 0;
const viewNames = {overview:'统计概览',settings:'机器人配置',mappings:'渠道与工作流',logs:'运行记录'};
const numberFormat = new Intl.NumberFormat('en-US', {maximumFractionDigits:2});
const escapeHtml = (value) => String(value ?? '').replace(/[&<>"']/g, (c) => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const number = (value) => value == null ? '—' : numberFormat.format(Number(value));
const moneyFormat = new Intl.NumberFormat('en-US', {maximumFractionDigits:12});
const money = (value) => value == null ? '未统计' : moneyFormat.format(Number(value));
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
  for (const input of document.querySelectorAll('input[type="password"]')) {
    if (input.value && input.value.length >= 4) text = text.split(input.value).join('[已隐藏]');
  }
  return text.replace(/\bBearer\s+[^\s,;]+/gi,'Bearer [已隐藏]')
    .replace(/\b\d{5,}:[A-Za-z0-9_-]{15,}\b/g,'[已隐藏]')
    .replace(/\b(?:sk|sess|ghp|github_pat)[-_][A-Za-z0-9_-]{10,}\b/gi,'[已隐藏]')
    .replace(/((?:api[-_]?key|token|authorization|password|secret)\s*["']?\s*[:=]\s*["']?)[^"',;\s&]+/gi,'$1[已隐藏]')
    .slice(0,8000);
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

function showPersistentError(error,context='页面操作') {
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
    message:redactDiagnostic(details.message || error?.message || '操作失败，请复制错误详情反馈。')
  };
  if (details.requestId) diagnostics.requestId = redactDiagnostic(details.requestId).slice(0,128);
  if (details.jobId) diagnostics.jobId = redactDiagnostic(details.jobId).slice(0,128);
  const signature = JSON.stringify([diagnostics.method,diagnostics.path,diagnostics.status,diagnostics.step,diagnostics.message]);
  if (error && typeof error === 'object') error.persistentShown = true;
  if (context === '状态刷新' && state.persistentError?.signature === signature) return;
  const text = JSON.stringify(diagnostics,null,2);
  state.persistentError = {signature,text};
  $('#error-message').textContent = `${diagnostics.step}：${diagnostics.message}`;
  $('#error-details').textContent = text;
  $('#error-panel').classList.remove('hidden');
}

async function copyText(text) {
  if (navigator.clipboard?.writeText) {
    try {await navigator.clipboard.writeText(text);return;} catch { /* HTTP and denied clipboard access use the fallback below. */ }
  }
  const previousFocus = document.activeElement;
  const input = document.createElement('textarea');
  input.value = text;
  input.className = 'visually-hidden position-fixed';
  input.setAttribute('readonly','');
  document.body.append(input);
  try {
    input.focus();input.select();input.setSelectionRange(0,input.value.length);
    if (!document.execCommand('copy')) throw new Error('无法自动复制，请展开详情后手动复制。');
  } finally {input.remove();previousFocus?.focus?.();}
}

async function api(path, options={}) {
  const headers = {'X-Requested-With':'foya-admin',...(options.body ? {'Content-Type':'application/json'}:{}),...options.headers};
  let response;
  try {response = await fetch(path,{...options,headers,cache:'no-store'});}
  catch {throw diagnosticError('无法连接后台服务，请检查服务是否运行。',path,options);}
  let data;
  try {data = await response.json();}
  catch {throw diagnosticError('后台返回格式异常，请复制错误详情反馈。',path,options,response.status);}
  if (!response.ok) throw diagnosticError(data.error || data.message || `请求失败（${response.status}）`,path,options,response.status,data.errorDetails);
  return data;
}

async function withButton(selector, fn, loadingText) {
  const button = $(selector);
  if (state.busy.has(selector)) return;
  const original = button.innerHTML;
  const context = button.textContent.trim() || '页面操作';
  state.busy.add(selector);button.disabled = true;
  if (loadingText) button.innerHTML = `${icon('refresh','spinning')}${loadingText}`;
  try {return await fn();} catch(error) {handleError(error,context);} finally {state.busy.delete(selector);button.innerHTML = original;button.disabled=false;updateActions();}
}

function yesterday(timezone='Asia/Shanghai') {
  const parts = new Intl.DateTimeFormat('en-CA',{timeZone:timezone,year:'numeric',month:'2-digit',day:'2-digit'}).formatToParts(new Date());
  const value = (type) => parts.find((part) => part.type === type).value;
  const date = new Date(`${value('year')}-${value('month')}-${value('day')}T12:00:00Z`);
  date.setUTCDate(date.getUTCDate()-1);
  return date.toISOString().slice(0,10);
}

function setView() {
  const view = location.hash.slice(1) || 'overview';
  const target = viewNames[view] ? view : 'overview';
  document.querySelectorAll('.view').forEach((el) => el.classList.toggle('active',el.id === `view-${target}`));
  document.querySelectorAll('.nav a').forEach((el) => el.classList.toggle('active',el.dataset.view === target));
  $('#breadcrumb-current').textContent = viewNames[target];
  if (target === 'logs') refreshStatus().catch(handleError);
}

function handleError(error,context='页面操作') {showPersistentError(error,context);toast(redactDiagnostic(error.message),true);}
function setConnected(ok) {
  $('#connection-dot').className = `connection-dot ${ok ? 'ok' : 'error'}`;
  $('#connection-text').textContent = ok ? '后台服务已连接' : '后台连接中断';
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
  $('#telegram-config-state').textContent = `${count} 个`;
}

function appendBot(bot={}) {
  const uid = ++botSequence;
  const configured = Boolean(bot.tokenConfigured);
  const card = document.createElement('div');
  card.className = 'border rounded p-3 bg-white';
  card.dataset.botCard = '';
  if (bot.id) card.dataset.botId = bot.id;
  card.innerHTML = `<div class="row g-2 align-items-end">
    <div class="col-md-3"><label class="form-label small mb-1" for="bot-name-${uid}">名称</label><input class="form-control form-control-sm" id="bot-name-${uid}" data-field="name" type="text" required maxlength="100" placeholder="日报机器人" value="${escapeHtml(bot.name || `机器人 ${uid}`)}"></div>
    <div class="col-md-7"><label class="form-label small mb-1" for="bot-token-${uid}">Bot Token</label><input class="form-control form-control-sm" id="bot-token-${uid}" data-field="token" type="password" autocomplete="new-password" placeholder="${configured ? '已配置 · 留空保留' : '从 @BotFather 获取'}"></div>
    <div class="col-md-2"><label class="form-check form-switch small mb-1"><input class="form-check-input" data-field="enabled" type="checkbox" role="switch" ${bot.enabled !== false ? 'checked' : ''}><span class="form-check-label">启用</span></label></div>
    <div class="col-12 col-md-7"><label class="form-check small mt-1 mb-0"><input class="form-check-input" data-field="autoDiscover" type="checkbox" ${bot.autoDiscover !== false ? 'checked' : ''}><span>自动登记群组和私聊</span></label><div class="form-text">加群自动登记，私聊先发 /start；/stop 取消。<span data-bot-subscriptions></span></div></div>
    <div class="col-12 col-md-5 d-flex justify-content-md-end flex-wrap gap-2"><button type="button" class="btn btn-outline-primary btn-sm" data-bot-action="test" id="bot-test-${uid}">测试连接</button><button type="button" class="btn btn-outline-secondary btn-sm" data-bs-toggle="collapse" data-bs-target="#bot-advanced-${uid}" aria-expanded="false" aria-controls="bot-advanced-${uid}">高级</button><button type="button" class="btn btn-outline-danger btn-sm" data-bot-action="remove">删除</button></div>
  </div><div class="collapse" id="bot-advanced-${uid}"><div class="row g-2 mt-1">
    <div class="col-md-5"><label class="form-label small mb-1" for="bot-chat-${uid}">手动 Chat ID（可选）</label><input class="form-control form-control-sm" id="bot-chat-${uid}" data-field="chatId" type="text" placeholder="-100… 或 @channel" value="${escapeHtml(bot.chatId || '')}"></div>
    <div class="col-md-3"><label class="form-label small mb-1" for="bot-thread-${uid}">话题 ID（可选）</label><input class="form-control form-control-sm" id="bot-thread-${uid}" data-field="threadId" type="text" inputmode="numeric" value="${escapeHtml(bot.threadId || '')}"></div>
    <div class="col-md-4"><label class="form-label small mb-1" for="bot-time-${uid}">发送时间（留空用默认）</label><input class="form-control form-control-sm" id="bot-time-${uid}" data-field="sendTime" type="time" value="${escapeHtml(bot.sendTime || '')}"></div>
  </div><label class="form-check small mt-2 mb-0 ${configured ? '' : 'hidden'}"><input class="form-check-input" type="checkbox" data-field="clearToken"><span>清除已保存 Token</span></label></div>`;
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
    card.querySelector('[data-bot-subscriptions]').textContent = subscriptions && card.dataset.botId ? ` 已登记 ${count} 个接收会话。` : '';
  }
  const targets = readyBots();
  const targetIds = new Set(targets.map((bot) => bot.id));
  const count = (subscriptions || []).filter((subscription) => targetIds.has(subscription.botId)).length;
  $('#telegram-target').textContent = targets.length ? `启用 ${targets.length} 个机器人${subscriptions ? ` · 已登记 ${count} 个会话` : ''}` : '暂无可发送的启用机器人';
}

function renderConfig(config) {
  state.config = config;
  const form = $('#config-form');
  for(const name of ['p0Endpoint','p0ApiKeyFile','p0TimeFormat','timezone','sendTime','reportDays','p0MaxAttempts','llmBaseUrl','llmModel','llmTimeoutSeconds']) {
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
  $('#p0-key-hint').textContent = config.p0ApiKeyFromEnvironment ? '当前凭据由环境变量提供，环境变量始终优先；清除操作仅删除后台保存的密钥。' : '密钥不会回显；留空保留当前值。';
  form.elements.llmApiKey.value = '';form.elements.llmApiKey.disabled = false;form.elements.clearLlmApiKey.checked = false;
  $('#clear-llm-wrap').classList.toggle('hidden',!config.llmApiKeyConfigured);
  $('#clear-llm-hint').classList.add('hidden');
  $('#llm-key-hint').textContent = config.llmApiKeyFromEnvironment ? '当前模型密钥由环境变量提供，环境变量始终优先；清除操作仅删除后台保存的密钥。' : '密钥不会回显；留空保留当前值，不包含在导出配置中。';
  form.elements.llmApiKey.placeholder = config.llmApiKeyConfigured ? '已配置 · 留空保持不变' : '输入模型服务的 API Key';
  form.elements.p0ApiKey.placeholder = config.p0ApiKeyConfigured ? '已配置 · 留空保持不变' : '输入 API Key';
  for(const [selector,configured] of [['#p0-config-state',config.p0ApiKeyConfigured]]) {
    $(selector).textContent = configured ? '已配置' : (selector === '#p0-config-state' ? '未设置密钥' : '未配置');$(selector).className = `badge fw-normal ${configured ? 'text-bg-success' : 'text-bg-light border'}`;
  }
  $('#setup-banner').classList.toggle('hidden',Boolean(config.p0ApiKeyConfigured || state.report));
  const days = config.reportDays || 10;
  $('#days-badge').textContent = `${days} 天`;
  $('#metric-total-note').textContent = `近 ${days} 天 · 成功任务`;
  $('#metric-schedule').innerHTML = `${config.scheduleEnabled ? escapeHtml(config.sendTime || '09:00') : '未启用'}<span class="schedule-dot ${config.scheduleEnabled ? 'enabled' : ''}"></span>`;
  $('#metric-schedule-note').textContent = config.scheduleEnabled ? `每日推送 · ${config.timezone || 'Asia/Shanghai'}` : '配置机器人后开启每日推送';
  renderBots(config.telegramBots || []);
  const targets = readyBots();
  updateSubscriptionCounts();
  const selected = $('#send-target').value;
  $('#send-target').innerHTML = '<option value="">所有启用的机器人</option>' + targets.map((bot) => `<option value="${escapeHtml(bot.id)}">${escapeHtml(bot.name || bot.chatId)}</option>`).join('');
  $('#send-target').value = targets.some((bot) => bot.id === selected) ? selected : '';
  $('#send-target').classList.toggle('hidden',targets.length < 2);
  $('#schedule-zone-hint').textContent = `按 ${config.timezone || 'Asia/Shanghai'} 每日生成并发送；AI 不可用仍发统计。`;
  $('#footer-timezone').textContent = config.timezone || 'Asia/Shanghai';
  const maxDate = yesterday(config.timezone || 'Asia/Shanghai');
  $('#end-date').max = maxDate;
  if (!$('#end-date').value || $('#end-date').value > maxDate) $('#end-date').value = maxDate;
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
  if (!report || state.summary?.endDate !== report.endDate || state.summary?.rawText !== report.text) state.summary = null;
  state.report = report;
  const hasData = Boolean(report);
  $('#setup-banner').classList.toggle('hidden',hasData || Boolean(state.config?.p0ApiKeyConfigured));
  $('#daily-empty').classList.toggle('hidden',hasData);
  $('#channel-empty').classList.toggle('hidden',Boolean(report?.channels?.length));
  $('#workflow-empty').classList.toggle('hidden',Boolean(report?.workflows?.length));
  const amountsPaused = report?.amountsEnabled !== true;
  $('#metric-count').textContent = number(report?.latestCount);
  $('#metric-fee').textContent = money(amountsPaused ? null : report?.latestFee);
  $('#metric-fee-note').textContent = amountsPaused ? '金额暂未统计' : (report.latestFeeComplete === false || report.latestFee == null ? '部分任务未配置单价' : '按成功单数 × 单价汇总');
  const byCount = amountsPaused || report?.channelsBy === 'count';
  $('#channel-sort-note').textContent = `最新统计日 · 按${byCount ? '单数' : '金额'}降序`;
  $('#channel-share-heading').textContent = `${byCount ? '单数' : '金额'}占比`;
  const incompleteFees = Boolean(report && (report.feeComplete === false || Number(report.missingFeeCount) > 0));
  $('#fee-notice').classList.toggle('hidden',!amountsPaused && !incompleteFees);
  $('#fee-notice').textContent = amountsPaused ? '金额暂未统计，渠道按单数排名' : (incompleteFees ? `${Number(report.missingFeeCount) > 0 ? `${number(report.missingFeeCount)} 单未配置单价` : '部分任务未配置单价'}，金额未统计，单数统计正常。${byCount ? '渠道按单数排名。' : ''}` : '');
  $('#metric-total').textContent = number(report?.totalCount);
  $('#metric-count-note').textContent = report ? `${displayDate(report.endDate)} · 成功任务` : '等待拉取数据';
  $('#data-period').textContent = report ? `${displayDate(report.startDate)} — ${displayDate(report.endDate)}` : '—';
  $('#daily-rows').innerHTML = (report?.days || []).map((day) => `<tr><td>${escapeHtml(displayDate(day.date))}<span class="small text-body-secondary ms-2">${escapeHtml(day.date?.slice(0,4))}</span></td><td class="text-end">${number(day.count)}</td><td class="text-end">${money(amountsPaused ? null : day.fee)}</td><td class="text-end"><span class="badge text-bg-success fw-normal">已缓存</span></td></tr>`).join('');
  $('#channel-rows').innerHTML = (report?.channels || []).slice(0,10).map((row,index) => `<tr><td title="${escapeHtml(row.name)}"><span class="badge ${index<3?'text-bg-primary':'text-bg-light border'} me-2 fw-normal">${index+1}</span>${escapeHtml(row.name)}</td><td class="text-end">${number(row.count)}</td><td class="text-end">${money(amountsPaused ? null : row.fee)}</td><td class="text-end text-body-secondary">${Number(row.share || 0).toFixed(1)}%</td></tr>`).join('');
  $('#workflow-rows').innerHTML = (report?.workflows || []).slice(0,10).map((row,index) => `<tr><td title="${escapeHtml(row.name)}"><span class="badge ${index<3?'text-bg-primary':'text-bg-light border'} me-2 fw-normal">${index+1}</span>${escapeHtml(row.name)}</td><td class="text-end">${number(row.count)}</td><td class="text-end text-body-secondary">${Number(row.share || 0).toFixed(1)}%</td></tr>`).join('');
  renderAiPreview();
  $('#preview-clock').textContent = report ? new Intl.DateTimeFormat('zh-CN',{timeZone:state.config?.timezone || 'Asia/Shanghai',hour:'2-digit',minute:'2-digit',hour12:false}).format(new Date()) : '预览';
  if (report?.days) $('#days-badge').textContent = `${report.days.length} 天`;
  updateActions();
}


function renderAiPreview() {
  const summary = state.summary;
  const status = summary?.aiStatus || (state.config?.llmEnabled ? 'ready' : 'disabled');
  const labels = {
    ready:'AI 已启用 · 发送时自动生成，可先预览',
    pending:'正在生成 AI 总结 · 原始统计已保留',
    ok:'AI 总结已生成 · 已加入报表预览',
    failed:'AI 总结暂不可用 · 将照常发送原始日报',
    disabled:'AI 总结未启用 · 原始日报可正常发送'
  };
  $('#summary-status').textContent = labels[status] || labels.failed;
  $('#summary-notice').className = `alert ${status === 'ok' ? 'alert-success' : status === 'failed' ? 'alert-warning' : status === 'pending' ? 'alert-primary' : 'alert-light'} border-0 border-bottom rounded-0 m-0 py-2 px-3 small`;
  $('#summary-notice').title = redactDiagnostic(summary?.aiMessage || '');
  $('#summary-dot').className = `tiny-dot me-1 ${status === 'ok' ? 'teal' : status === 'pending' ? 'blue' : ''}`;
  $('#report-preview').textContent = summary?.text || state.report?.text || '等待生成报表\n\n完成数据源配置并刷新统计后，\n这里会显示实际发送内容。\n\n每日统计 · 渠道 TOP10 · 工作流 TOP10';
}

async function loadReport({silent=false}={}) {
  const endDate = $('#end-date').value;
  if (!endDate) return;
  try {renderReport(await api(`/api/report?endDate=${encodeURIComponent(endDate)}`));}
  catch(error) {if (error.status === 409) {renderReport(null);if(!silent) toast('所选日期还没有完整缓存，请点击「刷新统计」。');} else {showPersistentError(error,'读取报表');if(!silent) toast(redactDiagnostic(error.message),true);}}
}

function formatTime(time) {
  if (!time) return '—';
  const date = new Date(time);
  if (Number.isNaN(date.getTime())) return String(time);
  return new Intl.DateTimeFormat('zh-CN',{timeZone:state.config?.timezone || 'Asia/Shanghai',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',second:'2-digit',hour12:false}).format(date);
}

function renderStatus(status) {
  const wasRunning = state.status?.running;
  const previousJob = state.status?.job;
  state.status = status;
  updateSubscriptionCounts();
  const running = Boolean(status.running);
  $('#job-banner').classList.toggle('hidden',!running);
  $('#job-message').textContent = status.job?.message || '正在处理，请稍候…';
  $('#current-job').textContent = running ? (['send','发送报表'].includes(status.job?.kind) ? '正在发送' : (status.job?.kind === '定时报表' ? '正在执行定时报表' : '正在拉取')) : (status.job?.state === 'failed' ? '执行失败' : '空闲');
  const cacheDays = status.cacheDays || [];
  $('#cache-count').textContent = `${cacheDays.length} 天`;
  $('#last-sent').textContent = status.lastSentDate || '尚未发送';
  $('#next-run').textContent = status.nextRun ? formatTime(status.nextRun) : (state.config?.scheduleEnabled ? '等待下次计划' : '未启用');
  $('#cache-dates').innerHTML = cacheDays.length ? [...cacheDays].sort().reverse().map((date) => `<span class="badge text-bg-light border fw-normal">${escapeHtml(date)}</span>`).join('') : '<span class="small text-body-secondary">暂无缓存</span>';
  const logs = status.logs || [];
  $('#logs-empty').classList.toggle('hidden',logs.length > 0);
  $('#log-rows').innerHTML = [...logs].sort((a,b) => String(b.time || '').localeCompare(String(a.time || ''))).map((log) => {
    const level = String(log.level || 'info').toLowerCase();
    const type = level.includes('error') || level === 'failed' ? 'error' : level === 'success' ? 'success' : level === 'warn' ? 'warn' : '';
    const label = type === 'error' ? '失败' : type === 'success' ? '完成' : type === 'warn' ? '提示' : '信息';
    return `<tr><td class="text-nowrap text-body-secondary">${escapeHtml(formatTime(log.time))}</td><td><span class="badge fw-normal ${type === 'error' ? 'text-bg-danger' : type === 'success' ? 'text-bg-success' : type === 'warn' ? 'text-bg-warning' : 'text-bg-light border'}">${label}</span></td><td class="text-break">${escapeHtml(log.message)}</td></tr>`;
  }).join('');
  if ((wasRunning || state.startedJob) && !running && status.job && (status.job !== previousJob || state.startedJob)) {
    state.startedJob = false;
    if (status.job.state === 'failed' || status.job.error) toast(status.job.error || status.job.message || '执行失败，请查看记录。',true);
    else {toast(status.job.message || '任务已完成。');loadReport({silent:true});}
  }
  if (status.job && (status.job.state === 'failed' || status.job.error)) {
    const job = status.job;
    const key = job.id || job.errorDetails?.jobId || `${job.startedAt || ''}:${job.kind || ''}:${job.error || job.message || ''}`;
    if (!state.failedJobIds.has(key)) {
      state.failedJobIds.add(key);
      if (state.failedJobIds.size > 100) state.failedJobIds.delete(state.failedJobIds.values().next().value);
      const error = new Error(job.error || job.message || '任务执行失败。');
      error.diagnostics = {method:'JOB',path:'/api/status',time:job.finishedAt,step:job.kind,message:error.message,...job.errorDetails};
      showPersistentError(error,job.kind || '后台任务');
    }
  }
  updateActions();
}

async function refreshStatus() {
  if (state.refreshing) return;
  state.refreshing = true;
  try {renderStatus(await api('/api/status'));setConnected(true);} catch(error) {setConnected(false);showPersistentError(error,'状态刷新');throw error;} finally {state.refreshing=false;}
}

function configPayload(includeBots=true) {
  const form = $('#config-form');
  const payload = {};
  for(const name of ['p0Endpoint','p0ApiKey','p0ApiKeyFile','p0TimeFormat','timezone','sendTime','llmBaseUrl','llmModel','llmApiKey']) payload[name] = form.elements[name].value.trim();
  payload.reportDays = Number(form.elements.reportDays.value);
  payload.p0MaxAttempts = Number(form.elements.p0MaxAttempts.value);
  if (!Number.isInteger(payload.p0MaxAttempts) || payload.p0MaxAttempts < 1 || payload.p0MaxAttempts > 50) throw new Error('每页最多尝试次数应为 1–50 之间的整数。');
  payload.scheduleEnabled = form.elements.scheduleEnabled.checked;
  payload.llmEnabled = form.elements.llmEnabled.checked;
  payload.llmTimeoutSeconds = Number(form.elements.llmTimeoutSeconds.value);
  if (!Number.isInteger(payload.llmTimeoutSeconds) || payload.llmTimeoutSeconds < 5 || payload.llmTimeoutSeconds > 90) throw new Error('模型请求超时应为 5–90 秒之间的整数。');
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
  try {result=JSON.parse(text);} catch {throw new Error(`${name} JSON 格式不正确，请检查逗号和引号。`);}
  if (!result || Array.isArray(result) || typeof result !== 'object' || Object.values(result).some((value) => typeof value !== 'string')) throw new Error(`${name}应为键和值均为字符串的 JSON 对象。`);
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
  try {result = parsePriceJson(text);} catch {throw new Error('单价映射 JSON 格式不正确，请检查逗号和引号。');}
  if (!result || Array.isArray(result) || typeof result !== 'object') throw new Error('单价映射应为 task_type 到单价的 JSON 对象。');
  const entries = Object.entries(result);
  if (entries.length > 10000) throw new Error('单价映射最多支持 10000 项。');
  for (const [key,value] of entries) {
    if (!key.trim()) throw new Error('单价映射的 task_type 不能为空。');
    if (typeof value === 'number') {
      if (!Number.isFinite(value) || value < 0) throw new Error('单价必须是非负有限数字，允许 0。');
    } else if (typeof value === 'string') {
      const decimal = value.trim();
      if (!decimal || !/^\+?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+-]?\d+)?$/.test(decimal)) throw new Error('单价字符串必须是非负十进制数，不能留空。');
      result[key] = decimal;
    } else throw new Error('单价只能是非负数字或十进制字符串，不能是布尔值、null、数组或对象。');
  }
  return result;
}

function mappingPayload() {
  return {channelNames:parseMap($('#channel-map').value,'渠道映射'),workflowNames:parseMap($('#workflow-map').value,'工作流映射'),taskTypePrices:parsePriceMap($('#price-map').value),amountsEnabled:$('#amounts-enabled').checked};
}

function updatePriceCount() {
  try {$('#price-map-count').textContent = `${Object.keys(parsePriceMap($('#price-map').value)).length} 条`;}
  catch {$('#price-map-count').textContent = '待校验';}
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


for (const [clearName,keyName,hint] of [['clearP0ApiKey','p0ApiKey','#clear-p0-hint'],['clearLlmApiKey','llmApiKey','#clear-llm-hint']]) {
  $('#config-form').elements[clearName].addEventListener('change',(event) => {
    const input = $('#config-form').elements[keyName];
    input.disabled = event.target.checked;
    if (event.target.checked) input.value = '';
    $(hint).classList.toggle('hidden',!event.target.checked);
  });
}

$('#add-bot').addEventListener('click',() => {appendBot({enabled:true});$('#save-state').textContent = '机器人列表已修改，请保存配置。';});
$('#telegram-bots').addEventListener('click',(event) => {
  const button = event.target.closest('[data-bot-action]');
  if (!button) return;
  const card = button.closest('[data-bot-card]');
  if (button.dataset.botAction === 'remove') {card.remove();updateBotCount();$('#save-state').textContent = '删除将在保存配置后生效。';return;}
  withButton(`#${button.id}`,async () => {const result = await api('/api/test/telegram',{method:'POST',body:JSON.stringify({bot:readBot(card)})});toast(result.message || '机器人连接正常。');},'正在测试');
});
$('#telegram-bots').addEventListener('change',(event) => {
  if (event.target.dataset.field !== 'clearToken') return;
  const token = event.target.closest('[data-bot-card]').querySelector('[data-field="token"]');
  token.disabled = event.target.checked;
  if (event.target.checked) token.value = '';
});

$('#config-form').addEventListener('submit',(event) => {
  event.preventDefault();
  withButton('#save-config',async () => {const config=await api('/api/config',{method:'PUT',body:JSON.stringify(configPayload())});renderConfig(config);$('#save-state').textContent='配置已保存 · 密钥已隐藏';toast('配置已保存。');await refreshStatus();await loadReport({silent:true});},'正在保存');
});
$('#mapping-form').addEventListener('submit',(event) => {
  event.preventDefault();
  withButton('#save-mappings',async () => {
    const result=await api('/api/mappings',{method:'PUT',body:JSON.stringify(mappingPayload())});
    renderMappings(result);state.summary=null;
    if (state.config) state.config={...state.config,amountsEnabled:Boolean(result.amountsEnabled)};
    try {const config=await api('/api/config');if(state.config) state.config={...state.config,amountsEnabled:Boolean(config.amountsEnabled)};}
    catch(error) {handleError(error,'刷新金额配置');}
    await loadReport({silent:true});toast('映射配置已保存。');
  },'正在保存');
});
$('#pull-button').addEventListener('click',() => withButton('#pull-button',async () => {
  await api('/api/pull',{method:'POST',body:JSON.stringify({endDate:$('#end-date').value,force:false})});
  state.startedJob = true;toast('已开始拉取，缺失的历史日期会自动补齐。');await refreshStatus();
},'提交任务'));
$('#end-date').addEventListener('change',() => loadReport());
$('#test-p0').addEventListener('click',() => withButton('#test-p0',async () => {const result=await api('/api/test/p0',{method:'POST',body:JSON.stringify(configPayload(false))});toast(result.message || 'P0 接口连接正常。');},'正在测试'));
$('#test-llm').addEventListener('click',() => withButton('#test-llm',async () => {const result=await api('/api/test/llm',{method:'POST',body:JSON.stringify(configPayload(false))});if(result.status === 'failed') showPersistentError(diagnosticError(result.message || 'AI 模型连接失败。','/api/test/llm',{method:'POST'},null,result.errorDetails),'LLM 连接测试');toast(redactDiagnostic(result.message || 'AI 模型服务连接正常。'),result.status === 'failed');},'正在测试'));
$('#summary-button').addEventListener('click',() => withButton('#summary-button',async () => {
  if (!state.report) return;
  const report = state.report;
  const configReference = state.config;
  state.summary = {endDate:report.endDate,rawText:report.text,text:report.text,aiStatus:'pending'};
  renderAiPreview();updateActions();
  try {
    const result = await api('/api/summary',{method:'POST',body:JSON.stringify({endDate:report.endDate})});
    if (state.config !== configReference || state.report?.endDate !== report.endDate || state.report?.text !== report.text) return;
    state.summary = {...result,endDate:report.endDate,rawText:report.text,text:result.aiStatus === 'ok' ? (result.text || report.text) : report.text};
    renderAiPreview();
    if (result.aiStatus === 'failed') showPersistentError(diagnosticError(result.aiMessage || 'AI 总结暂不可用，已保留原始日报。','/api/summary',{method:'POST'},null,result.errorDetails),'AI 总结');
    toast(result.aiStatus === 'ok' ? 'AI 总结已加入预览。' : (result.aiMessage || (result.aiStatus === 'disabled' ? 'AI 总结尚未启用，已保留原始日报。' : 'AI 总结暂不可用，原始日报仍可正常发送。')));
  } catch(error) {
    if (state.config === configReference && state.report?.endDate === report.endDate && state.report?.text === report.text) {
      state.summary = {endDate:report.endDate,rawText:report.text,text:report.text,aiStatus:'failed',aiMessage:error.message};
      renderAiPreview();
    }
    throw error;
  }
},'正在生成'));
$('#send-button').addEventListener('click',() => {
  if (!state.report) return;
  const selected = $('#send-target').value;
  const targets = readyBots().filter((bot) => !selected || bot.id === selected);
  if (!targets.length) {toast('请先保存至少一个已启用且配置完整的机器人。',true);return;}
  $('#confirm-chat').innerHTML = targets.map((bot) => {
    const count = (state.status?.subscriptions || []).filter((subscription) => subscription.botId === bot.id).length;
    const automatic = bot.autoDiscover !== false ? `自动登记的群组/私聊${state.status?.subscriptions ? `（${count} 个）` : ''}` : '';
    const manual = bot.chatId ? `${automatic ? '；手动 ' : ''}${bot.chatId}${bot.threadId ? ` · 话题 ${bot.threadId}` : ''}` : '';
    return `<div class="mb-1">${escapeHtml(bot.name || '机器人')} · ${escapeHtml(automatic + manual)}</div>`;
  }).join('');
  $('#confirm-period').textContent=`${state.report.startDate} — ${state.report.endDate}`;
  showModal('#send-dialog');
});
$('#cancel-send').addEventListener('click',() => hideModal('#send-dialog'));
$('#confirm-send').addEventListener('click',() => withButton('#confirm-send',async () => {
  await api('/api/send',{method:'POST',body:JSON.stringify({endDate:state.report.endDate,...($('#send-target').value ? {botId:$('#send-target').value} : {})})});
  hideModal('#send-dialog');state.startedJob=true;toast('发送任务已提交。');await refreshStatus();
},'正在提交'));
$('#copy-button').addEventListener('click',() => withButton('#copy-button',async () => {await copyText(state.summary?.text || state.report?.text || '');toast('报表已复制。');}));
$('#refresh-status').addEventListener('click',() => withButton('#refresh-status',async () => {await refreshStatus();toast('记录已更新。');},'正在刷新'));
$('#export-config').addEventListener('click',() => {
  if (!state.config) return;
  const {p0ApiKey,telegramBotToken,llmApiKey,p0ApiKeyConfigured,telegramBotTokenConfigured,llmApiKeyConfigured,...safe}=state.config;
  safe.telegramBots = (safe.telegramBots || []).map(({token,tokenConfigured,...bot}) => bot);
  downloadJson('aiapi-config.json',safe);toast('已导出配置，不包含 P0、Telegram 或模型密钥。');
});
$('#export-mappings').addEventListener('click',() => {
  try {downloadJson('aiapi-mappings.json',mappingPayload());} catch(error){handleError(error,'导出映射配置');}
});
for(const [inputId,textareaId,label,key] of [['import-channels','channel-map','渠道映射','channelNames'],['import-workflows','workflow-map','工作流映射','workflowNames']]) {
  $(`#${inputId}`).addEventListener('change',async(event) => {
    const file=event.target.files[0];if(!file) return;
    try {if(file.size>2*1024*1024) throw new Error('映射文件不能超过 2 MB。');const raw=JSON.parse(await file.text());const data=raw[key] || raw;const parsed=parseMap(JSON.stringify(data),label);$(`#${textareaId}`).value=JSON.stringify(parsed,null,2);toast(`已导入 ${Object.keys(parsed).length} 个名称，请保存映射。`);} catch(error){handleError(error instanceof SyntaxError ? new Error('文件不是有效的 JSON。') : error,'导入名称映射');} finally{event.target.value='';}
  });
}
$('#price-map').addEventListener('input',updatePriceCount);
$('#export-prices').addEventListener('click',() => {
  try {downloadJson('aiapi-task-prices.json',{amountsEnabled:$('#amounts-enabled').checked,taskTypePrices:parsePriceMap($('#price-map').value)});}
  catch(error) {handleError(error,'导出单价映射');}
});
$('#import-prices').addEventListener('change',async(event) => {
  const file=event.target.files[0];if(!file) return;
  try {
    if (file.size>2*1024*1024) throw new Error('单价映射文件不能超过 2 MB。');
    const raw=parsePriceJson(await file.text());
    const wrapped=raw && typeof raw==='object' && !Array.isArray(raw) && Object.hasOwn(raw,'taskTypePrices');
    const prices=parsePriceMap(JSON.stringify(wrapped ? raw.taskTypePrices : raw));
    if (wrapped && Object.hasOwn(raw,'amountsEnabled') && typeof raw.amountsEnabled!=='boolean') throw new Error('amountsEnabled 必须是 true 或 false。');
    $('#price-map').value=JSON.stringify(prices,null,2);
    if (wrapped && Object.hasOwn(raw,'amountsEnabled')) $('#amounts-enabled').checked=raw.amountsEnabled;
    updatePriceCount();toast(`已导入 ${Object.keys(prices).length} 条单价，请保存映射配置。`);
  } catch(error) {handleError(error instanceof SyntaxError ? new Error('文件不是有效的 JSON。') : error,'导入单价映射');}
  finally {event.target.value='';}
});
$('#copy-error').addEventListener('click',async () => {
  if (!state.persistentError) return;
  try {await copyText(state.persistentError.text);toast('脱敏错误详情已复制，可直接粘贴反馈。');}
  catch {toast('无法自动复制，请展开详情后手动复制。',true);}
});
$('#clear-error').addEventListener('click',() => {
  state.persistentError = null;
  $('#error-panel').classList.add('hidden');
  $('#error-message').textContent = '';$('#error-details').textContent = '';
});
window.addEventListener('error',() => {
  const error = diagnosticError('页面脚本发生错误，请复制详情反馈。',location.pathname,{method:'BROWSER'});
  showPersistentError(error,'页面脚本');
});
window.addEventListener('unhandledrejection',() => {
  const error = diagnosticError('页面操作未完成，请复制详情反馈。',location.pathname,{method:'BROWSER'});
  showPersistentError(error,'页面操作');
});
window.addEventListener('hashchange',setView);

async function initialize(loadConfig=true) {
  try {
    if(loadConfig) renderConfig(await api('/api/config'));
    const results=await Promise.allSettled([api('/api/mappings'),refreshStatus(),loadReport({silent:true})]);
    if(results[0].status==='fulfilled') renderMappings(results[0].value);
    else handleError(results[0].reason,'加载名称映射');
    if(results[1].status==='rejected') handleError(results[1].reason);
  } catch(error){setConnected(false);handleError(error);}
}

$('#end-date').value=yesterday();
setView();initialize();
setInterval(() => {if(document.visibilityState==='visible') refreshStatus().catch(() => {});},3000);
document.addEventListener('visibilitychange',() => {if(document.visibilityState==='visible') refreshStatus().catch(() => {});});
