'use strict';

// The explorer keeps edited inputs separate from the query currently shown on screen.
window.P0Tasks = (() => {
  const el = (id) => document.getElementById(id);
  const esc = (value) => escapeHtml(value);
  const fmt = (value) => value == null ? '—' : number(value);
  const t = (zh,en) => window.AppLanguage.t(zh,en);
  const locale = () => window.AppLanguage.locale();
  const english = () => locale() === 'en';
  const codes = ['0','1','2','3','-1'];
  const rollingRangeDays = {'24h':1,'7d':7,'30d':30};
  // Leave time to receive the response after the server's six-minute range query deadline.
  const rangeRequestTimeoutMs = 375000;
  const aiTimeoutOptions = new Set([30,60,120,180,300]);
  const lookupLanguages = {
    'zh-CN': {
      title:'单条任务查询', identifier:'标识类型', taskId:'任务 ID',
      placeholder:(id) => `输入${id === 'UUID' ? ' ' : ''}${id}`, search:'查询', searching:'查询中…',
      details:'任务详情', json:'完整数据', copy:'复制 JSON', copied:'已复制 JSON。', copyFailed:'复制失败，请手动复制 JSON。',
      missing:(id) => `请输入${id === 'UUID' ? ' ' : ''}${id}。`, notFound:'未找到匹配的任务。', found:'已找到任务。',
      invalid:'请检查任务标识的格式。', unauthorized:'P0 拒绝了此查询（HTTP 401）。', forbidden:'P0 拒绝了此查询（HTTP 403）。',
      failed:'查询失败，请稍后重试。'
    },
    en: {
      title:'Task lookup', identifier:'Identifier type', taskId:'Task ID',
      placeholder:(id) => `Enter ${id}`, search:'Look up', searching:'Looking up…',
      details:'Task details', json:'Raw JSON', copy:'Copy JSON', copied:'JSON copied.', copyFailed:'Could not copy. Select and copy the JSON manually.',
      missing:(id) => `Enter a ${id}.`, notFound:'No matching task found.', found:'Task found.',
      invalid:'Check the identifier and try again.', unauthorized:'P0 declined the request (HTTP 401).', forbidden:'P0 declined the request (HTTP 403).',
      failed:'Could not look up the task. Try again.'
    }
  };
  let config = null;
  let applied = null;
  let queryChanged = false;
  let pageData = null;
  let listError = null;
  let statistics = null;
  let statsQuery = null;
  let statsError = null;
  let quickRange = null;
  let listController = null;
  let statsController = null;
  let failureController = null;
  let failureBreakdown = null;
  let failureError = null;
  let aiController = null;
  let aiTimeoutSeconds = 120;
  let listSequence = 0;
  let statsSequence = 0;
  let aiSequence = 0;
  let listBusy = false;
  let statsBusy = false;
  let aiDisplay = null;
  let detailRow = null;
  let lookupController = null;
  let lookupResult = null;
  let lookupMessageKey = '';

  const first = (row, names) => {
    for (const name of names) {
      const value = row?.[name];
      if (value !== undefined && value !== null && value !== '') return value;
    }
    return '';
  };
  const scalar = (value) => typeof value === 'object' ? JSON.stringify(value) : String(value ?? '');
  const taskId = (row) => scalar(first(row,['task_id','taskId','uuid','id']));
  const taskType = (row) => scalar(first(row,['task_type','taskType','type']));
  const workflowName = (row) => scalar(first(row,['workflow_name','workflowName']));
  const channel = (row) => scalar(first(row,['hash_key','hashKey','user_hash','userHash']));
  const statuses = {'-1':['失败','Failed'],'0':['处理中','Processing'],'1':['旧状态','Legacy status'],
    '2':['成功','Succeeded'],'3':['加水印中','Watermarking']};
  const statusLabel = (value) => Object.hasOwn(statuses,value)
    ? `${t(...statuses[value])} · ${value}` : t(`状态 ${value || '未知'}`,`Status ${value || 'unknown'}`);
  const filterCaption = (query) => [[t('工作流','Workflow'),query.task_type],['HashKey',query.hash_key],
    [t('任务 ID','Task ID'),query.task_id]].filter(([,value]) => value).map(([name,value]) => `${name} ${compact(value,32)}`).join(' · ');
  const displayTime = (value) => {
    if (value === '' || value == null) return '—';
    if (typeof value === 'number') {
      const date = new Date(value < 100000000000 ? value * 1000 : value);
      if (!Number.isNaN(date.getTime())) return new Intl.DateTimeFormat('sv-SE',{timeZone:config?.timezone || 'Asia/Shanghai',dateStyle:'short',timeStyle:'medium'}).format(date);
    }
    return scalar(value).replace('T',' ').replace(/\.\d+(?:Z)?$/,'').replace(/Z$/,'');
  };
  const compact = (value, max=25) => value.length > max ? `${value.slice(0,12)}…${value.slice(-8)}` : value;

  function nowLocal(instant=new Date()) {
    const parts = new Intl.DateTimeFormat('en-CA',{timeZone:config?.timezone || 'Asia/Shanghai',year:'numeric',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',second:'2-digit',hourCycle:'h23'}).formatToParts(instant);
    const part = (name) => parts.find((entry) => entry.type === name).value;
    return `${part('year')}-${part('month')}-${part('day')}T${part('hour')}:${part('minute')}:${part('second')}`;
  }

  function setRange(kind) {
    quickRange = kind;
    const now = new Date();
    const end = nowLocal(now);
    let start = `${end.slice(0,10)}T00:00:00`;
    let until = end;
    if (kind === 'yesterday') {
      const previous = new Date(`${end.slice(0,10)}T12:00:00Z`);
      previous.setUTCDate(previous.getUTCDate() - 1);
      start = `${previous.toISOString().slice(0,10)}T00:00:00`;
      until = `${start.slice(0,10)}T23:59:59`;
    } else if (Object.hasOwn(rollingRangeDays,kind)) {
      start = nowLocal(new Date(now.getTime() - rollingRangeDays[kind] * 86400000));
    }
    el('tasks-start').value = start;
    el('tasks-end').value = until;
    document.querySelectorAll('[data-task-range]').forEach((button) => button.classList.toggle('active',button.dataset.taskRange === kind));
    edited();
  }

  function inputQuery() {
    const start = el('tasks-start').value;
    const end = el('tasks-end').value;
    if (!start || !end) throw taskError('请选择完整的开始和结束时间。','Choose both a start and end time.');
    const seconds = (value) => value.length === 16 ? `${value}:00` : value;
    if (seconds(start) > seconds(end)) throw taskError('开始时间不能晚于结束时间。','Start time cannot be after end time.');
    return {start_time:`${seconds(start)}.000000`,end_time:`${seconds(end)}.999999`,status:el('tasks-status').value,task_type:el('tasks-type').value.trim(),hash_key:el('tasks-hash').value.trim(),task_id:el('tasks-id').value.trim(),page:1,page_size:Number(el('tasks-page-size').value)};
  }

  function queryNote() {
    if (queryChanged) return t('条件已修改，点击「查询」后应用。','Search to apply the changed filters.');
    if (quickRange === 'yesterday') return t('按当前日期查询昨天完整一天','Search the full previous day.');
    if (quickRange) return t('查询时自动更新至当前时间','The time range updates when you search.');
    return t('固定起止时间，按任务创建时间筛选','Fixed time range based on task creation time.');
  }

  function edited(event) {
    if (['tasks-start','tasks-end'].includes(event?.target?.id)) {
      quickRange = null;
      document.querySelectorAll('[data-task-range]').forEach((button) => button.classList.remove('active'));
    }
    queryChanged = Boolean(applied);
    el('tasks-query-note').textContent = queryNote();
  }

  async function get(path, signal) {
    const controller = new AbortController();
    const abort = () => controller.abort();
    if (signal?.aborted) controller.abort();
    signal?.addEventListener('abort',abort,{once:true});
    let timedOut = false;
    const timer = setTimeout(() => {timedOut=true;controller.abort();},26000);
    try {
      return await api(path,{signal:controller.signal});
    } catch (error) {
      if (signal?.aborted) throw new DOMException('查询已取消','AbortError');
      if (timedOut) throw taskError('P0 查询超时，请缩小时间范围后重试。','P0 timed out. Shorten the date range and try again.');
      throw error;
    } finally {
      clearTimeout(timer);
      signal?.removeEventListener('abort',abort);
    }
  }

  function queryParams(query, stats=false) {
    const params = new URLSearchParams({start_time:query.start_time,end_time:query.end_time});
    for (const name of ['task_type','hash_key','task_id']) if (query[name]) params.set(name,query[name]);
    if (!stats) {
      if (query.status !== '') params.set('status',query.status);
      params.set('page',String(query.page));
      params.set('page_size',String(query.page_size));
    }
    return params;
  }

  function taskError(zh, en) {
    return Object.assign(new Error(zh),{translatedMessage:[zh,en]});
  }

  function errorText(error) {
    if (error?.translatedMessage) return t(...error.translatedMessage);
    const detail = redactDiagnostic(error?.message || '');
    if (detail && (!english() || !/\p{Script=Han}/u.test(detail))) return detail;
    const status = detail.match(/\bHTTP (\d{3})\b/)?.[1] || error?.status;
    return t('操作失败，请重试。',status ? `Request failed (HTTP ${status}). Try again.` : 'Request failed. Try again.');
  }

  function notifyError(error, context) {
    if (typeof handleError === 'function') handleError(error,context);
  }

  function stopAi(showMessage=false) {
    aiSequence++;
    aiController?.abort();
    aiController = null;
    el('tasks-ai-cancel').classList.add('hidden');
    if (showMessage) {
      aiDisplay = {state:'cancelled'};
      renderAi();
    }
    updateActions();
  }

  function updateActions() {
    el('tasks-search').disabled = listBusy;
    el('tasks-search').querySelector('span').textContent = listBusy ? t('查询中','Searching…') : t('查询','Search');
    el('tasks-search').querySelector('svg').classList.toggle('spinning',listBusy);
    el('tasks-reset').disabled = listBusy;
    el('tasks-prev').disabled = listBusy || !applied || applied.page <= 1;
    el('tasks-next').disabled = listBusy || !pageData?.hasMore;
    el('tasks-page-size').disabled = listBusy;
    el('tasks-export').disabled = listBusy || !pageData?.items?.length;
    el('tasks-failure-load').disabled = !applied || !!failureController;
    el('tasks-failure-load').textContent = failureController ? t('正在分类','Grouping…') : failureBreakdown
      ? t('刷新失败分类','Refresh failures') : t('查看失败分类','Show failures');
    el('tasks-ai-generate').disabled = !statistics || statistics.complete === false || statsBusy || !!aiController || !config?.llmEnabled || !config?.llmApiKeyConfigured;
    el('tasks-ai-generate').textContent = aiController ? t('正在生成','Generating…') : t('重新生成','Regenerate');
    const timeoutToggle = el('tasks-ai-timeout-toggle');
    if (aiController && timeoutToggle.getAttribute('aria-expanded') === 'true') {
      const focused = el('tasks-ai-timeout-menu').contains(document.activeElement) || document.activeElement === timeoutToggle;
      bootstrap.Dropdown.getOrCreateInstance(timeoutToggle).hide();
      if (focused) el('tasks-ai-cancel').focus({preventScroll:true});
    }
    timeoutToggle.disabled = Boolean(aiController);
  }

  function renderStats() {
    el('tasks-count-all').textContent = fmt(statistics?.total);
    for (const code of codes) {
      const count = statistics?.counts?.[code];
      el(`tasks-count-${code}`).textContent = fmt(count);
      let caption = t('点击筛选','Select to filter');
      if (count == null && statistics?.complete === false) caption = t('统计未完成','Count unavailable');
      else if (count != null && statistics?.consistent === false) caption = t('独立查询计数','Counted separately');
      else if (count != null && statistics?.total > 0) {
        const ratio = (Number(count) / Number(statistics.total) * 100).toFixed(1);
        caption = t(`占比 ${ratio}%`,`${ratio}% of total`);
      } else if (statistics?.total === 0) caption = t('该时间段暂无任务','No tasks in this period');
      el(`tasks-ratio-${code}`).textContent = caption;
    }
    document.querySelectorAll('[data-task-status]').forEach((button) => {
      button.classList.toggle('active',button.dataset.taskStatus === (applied?.status ?? ''));
      button.setAttribute('aria-pressed',String(button.dataset.taskStatus === (applied?.status ?? '')));
    });
    if (pageData && pageData.total == null) renderPageCounts();
    renderStatsCaption();
    updateActions();
  }

  function renderStatsCaption() {
    let caption = t('时间段统计 · 全部状态','Time range · All statuses');
    if (statistics) {
      const difference = statistics.snapshotDifference;
      const scope = statsQuery && filterCaption(statsQuery) ? t('匹配条件的全部状态','All matching statuses') : t('全部状态','All statuses');
      const parts = [t('时间段统计','Time range'),scope];
      if (statistics.complete === false) parts.push(t('部分状态统计未完成，请缩短时间段或重试','Some counts are unavailable. Shorten the time range or retry.'));
      else if (difference != null && Number(difference) !== 0) parts.push(t(
        `状态统计与总数差 ${fmt(difference)}，任务状态可能在查询期间变化`,
        `Status counts differ from the total by ${fmt(difference)}. Some statuses may have changed while loading.`));
      parts.push(config?.timezone || statistics.timezone);
      caption = parts.filter(Boolean).join(' · ');
    } else if (statsError) caption = t(`统计未完成：${errorText(statsError)}`,`Could not count tasks. ${errorText(statsError)}`);
    else if (statsBusy) caption = statsQuery.start_time.slice(0,10) === statsQuery.end_time.slice(0,10)
      ? t('正在统计完整时间段…','Counting tasks across the time range…')
      : t('正在统计，超过 24 小时的范围会按天汇总…','Counting tasks. Ranges over 24 hours are counted in daily segments…');
    el('tasks-stats-caption').textContent = caption;
    el('tasks-stats-retry').classList.toggle('hidden',!statsError && statistics?.complete !== false && statistics?.consistent !== false);
    renderAiScope();
  }

  async function loadStatistics(query) {
    statsController?.abort();
    statsController = new AbortController();
    const sequence = ++statsSequence;
    stopAi();
    statistics = null;
    statsQuery = query;
    statsError = null;
    statsBusy = true;
    aiDisplay = null;
    renderAi();
    renderStats();
    try {
      const response = await api(`/api/tasks/statistics?${queryParams(query,true)}`,{signal:statsController.signal,timeoutMs:rangeRequestTimeoutMs});
      if (sequence !== statsSequence) return;
      statistics = response;
    } catch (error) {
      if (sequence !== statsSequence || error.name === 'AbortError') return;
      statsError = error;
      notifyError(error,t('P0 时间段统计','P0 status counts'));
    } finally {
      if (sequence === statsSequence) {
        statsBusy=false;
        renderStats();
        void generateAi();
      }
    }
  }

  async function loadList(query) {
    listController?.abort();
    listController = new AbortController();
    const sequence = ++listSequence;
    applied = {...query};
    pageData = null;
    listError = null;
    queryChanged = false;
    listBusy = true;
    renderListState();
    renderStats();
    try {
      const response = await api(`/api/tasks?${queryParams(query)}`,{signal:listController.signal,timeoutMs:rangeRequestTimeoutMs});
      if (sequence !== listSequence) return;
      pageData = response;
      if (Number.isInteger(response.pageSize) && response.pageSize > 0) applied.page_size = response.pageSize;
    } catch (error) {
      if (sequence !== listSequence || error.name === 'AbortError') return;
      listError = error;
      notifyError(error,t('P0 任务查询','P0 task search'));
    } finally {
      if (sequence === listSequence) {
        listBusy = false;
        renderListState();
        updateActions();
      }
    }
  }

  function renderListState() {
    el('tasks-query-note').textContent = queryNote();
    if (applied) {
      const status = applied.status === '' ? t('全部状态','All statuses') : statusLabel(applied.status);
      const filters = filterCaption(applied);
      el('tasks-applied').textContent = `${displayTime(applied.start_time)} — ${displayTime(applied.end_time)} · ${status}${filters ? ' · ' + filters : ''}`;
    } else {
      el('tasks-applied').textContent = t('查询后显示任务信息。','Search to see tasks.');
    }
    el('tasks-list-error').classList.toggle('hidden',!listError);
    if (listError) el('tasks-list-error').textContent = errorText(listError);
    if (pageData) {
      renderRows();
      return;
    }
    el('tasks-rows').innerHTML = '';
    el('tasks-empty').classList.remove('hidden');
    el('tasks-result-count').textContent = listBusy ? t('查询中','Searching…') : listError ? t('查询失败','Search failed') : t('待查询','Awaiting search');
    el('tasks-empty').querySelector('p').textContent = listBusy ? t('正在加载任务…','Loading tasks…')
      : listError ? t('任务未加载，请调整条件或重新查询。','Tasks could not load. Change your filters or retry.')
        : t('选择条件后查询任务。','Choose filters to search for tasks.');
    el('tasks-page-info').textContent = listBusy ? t('正在查询 P0','Searching P0') : '—';
  }

  async function search() {
    try {
      if (quickRange) setRange(quickRange);
      const query = inputQuery();
      el('tasks-keyword').value = '';
      resetFailureBreakdown();
      await Promise.allSettled([loadList(query),loadStatistics(query)]);
    } catch (error) {notifyError(error,t('P0 查询条件','P0 search filters'));}
  }

  function resetFailureBreakdown() {
    failureController?.abort();
    failureController = null;
    failureBreakdown = null;
    failureError = null;
    renderFailureBreakdown();
  }

  function renderFailureBreakdown() {
    const summary = el('tasks-failure-summary');
    const result = failureBreakdown;
    if (failureError) summary.textContent = errorText(failureError);
    else if (result) {
      const count = fmt(result.observedCount);
      if (result.complete) summary.textContent = result.observedCount
        ? t(`已分类 ${count} 条失败任务，按失败单数降序排列。`,`Grouped ${count} failed tasks by count, highest first.`)
        : t('当前查询范围内没有符合条件的失败任务。','No failed tasks match these filters.');
      else {
        const reported = result.expectedCount == null ? '' : t(`（上游总数 ${fmt(result.expectedCount)} 条）`,` of ${fmt(result.expectedCount)} reported upstream`);
        const reason = result.requestFailed ? t('明细读取中断','The request was interrupted') : result.truncated
          ? t('已达到读取上限','The read limit was reached') : t('明细尚未读全','Some records could not be read');
        summary.textContent = t(`${reason}，已读取 ${count} 条失败任务${reported}。占比按已读取记录计算，可缩短时间范围后重试。`,
          `${reason}. Read ${count} failed tasks${reported}. Percentages use the records read. Shorten the time range to retry.`);
      }
    } else summary.textContent = failureController ? t('正在读取失败任务明细…','Loading failed tasks…')
      : t('按当前查询条件查看失败任务（状态 -1）的分布。','Group failed tasks (status -1) using the current filters.');
    const rows = failureBreakdown?.rows || [];
    el('tasks-failure-table').classList.toggle('hidden',!rows.length);
    el('tasks-failure-group').classList.toggle('hidden',!rows.length);
    if (!rows.length) return;
    const by = el('tasks-failure-group').value;
    const axes = by === 'both' ? ['workflowName','taskType'] : [by];
    const labels = {workflowName:t('工作流名称','Workflow name'),taskType:t('任务类型','Task type')};
    const missingLabels = {'未提供名称':t('未提供名称','Unnamed workflow'),'未提供类型':t('未提供类型','Unknown task type')};
    const groups = new Map();
    for (const row of rows) {
      const rawValues = axes.map((axis) => row[axis]);
      const key = JSON.stringify(rawValues);
      const values = rawValues.map((value) => Object.hasOwn(missingLabels,value) ? missingLabels[value] : value);
      const group = groups.get(key) || {values,count:0};
      group.count += row.count;
      groups.set(key,group);
    }
    const ranked = [...groups.values()].sort((a,b) => b.count - a.count || a.values.join(' ').localeCompare(b.values.join(' ')));
    const observed = failureBreakdown.observedCount;
    el('tasks-failure-head').innerHTML = axes.map((axis) => `<th scope="col">${labels[axis]}</th>`).join('')
      + `<th scope="col" class="text-end text-nowrap">${t('失败单数','Failed tasks')}</th><th scope="col" class="text-end text-nowrap">${t('占比','Share')}</th>`;
    el('tasks-failure-rows').innerHTML = ranked.map(({values,count}) => `<tr>${values.map((value) => `<td class="text-break">${esc(value)}</td>`).join('')}<td class="text-end text-nowrap">${fmt(count)}</td><td class="text-end text-nowrap">${(count / observed * 100).toFixed(1)}%</td></tr>`).join('');
  }

  async function loadFailureBreakdown() {
    if (!applied || failureController) return;
    const params = queryParams(applied,true);
    params.set('status','-1');
    const controller = failureController = new AbortController();
    failureBreakdown = null;
    failureError = null;
    renderFailureBreakdown();
    updateActions();
    try {
      const response = await get(`/api/tasks/diagnostics?${params}`,controller.signal);
      if (failureController !== controller) return;
      const result = response.diagnostics?.failureBreakdown;
      if (!Array.isArray(result?.rows)) throw taskError('P0 未返回失败任务分类，请稍后重试。','P0 did not return a failure breakdown. Try again.');
      failureBreakdown = result;
    } catch (error) {
      if (failureController !== controller || error.name === 'AbortError') return;
      failureError = error;
      notifyError(error,t('P0 失败任务分类','P0 failure breakdown'));
    } finally {
      if (failureController === controller) {
        failureController = null;
        renderFailureBreakdown();
        updateActions();
      }
    }
  }

  function contentSummary(row) {
    const text = first(row,['prompt','description','content','text','error_message','error','message']);
    let summary = text ? scalar(text) : '';
    const style = first(row,['style','style_name']);
    if (style) summary = `${scalar(style)}${summary ? ' · ' + summary : ''}`;
    if (!summary) {
      for (const name of ['data','input','params','parameters','request']) {
        const nested = row[name];
        if (nested && typeof nested === 'object') {
          const prompt = first(nested,['prompt','description','content','text','positive_prompt']);
          if (prompt) {summary=scalar(prompt);break;}
        }
      }
    }
    return summary || t('查看详情了解任务输入与结果','View details to see inputs and results.');
  }

  function statusBadge(row) {
    const value = first(row,['status','task_status','taskStatus']);
    const color = String(value) === '-1' ? 'text-danger bg-danger-subtle border-danger-subtle' : String(value) === '2' ? 'text-success bg-success-subtle border-success-subtle' : 'text-body-secondary bg-body-tertiary';
    return `<span class="badge border fw-normal ${color}">${esc(statusLabel(value))}</span>`;
  }

  function copyableValue(value,index,field,label) {
    const caption = esc(t(`复制 ${label}`,`Copy ${label}`));
    const mono = ['taskType','hashKey','userId'].includes(field) ? ' font-monospace text-body-secondary' : '';
    const button = value ? `<button type="button" class="btn btn-link text-body-secondary tasks-copy flex-shrink-0"
      data-task-copy="${index}" data-task-copy-field="${field}" aria-label="${caption}" title="${caption}"><svg><use href="#i-copy"/></svg></button>` : '';
    return `<div class="d-flex align-items-start gap-1"><span class="tasks-full-value flex-grow-1${mono}">${esc(value || t('未提供','Not provided'))}</span>${button}</div>`;
  }

  function channelName(row) {
    const hash = channel(row);
    return scalar(!hash && (!row.channelName || row.channelName === '未分類') ? t('未分類','Unclassified') : row.channelName || hash);
  }

  function renderRows() {
    if (!pageData) return;
    const items = Array.isArray(pageData?.items) ? pageData.items : [];
    const keyword = el('tasks-keyword').value.trim().toLocaleLowerCase();
    const rows = items.map((row,index) => ({row,index})).filter(({row}) => !keyword || JSON.stringify(row).toLocaleLowerCase().includes(keyword));
    el('tasks-rows').innerHTML = rows.map(({row,index}) => {
      const id = taskId(row);
      const type = taskType(row);
      const hash = channel(row);
      const name = channelName(row);
      const created = first(row,['created_at','createdAt','create_time','createTime','creation_time','created_time']);
      const updated = first(row,['updated_at','updatedAt','update_time','finished_at','completed_at']);
      const user = scalar(first(row,['user_id','userId','user_identifier','userIdentifier']));
      const copyId = id ? `<button type="button" class="btn btn-link text-body-secondary tasks-copy"
        data-task-copy="${index}" aria-label="${t('复制任务 ID','Copy task ID')}"><svg><use href="#i-copy"/></svg></button>` : '';
      const nameCell = name !== hash ? copyableValue(name,index,'channelName',t('渠道名称','channel name')) : '';
      const hashCell = hash ? `<div${name !== hash ? ' class="mt-1"' : ''}>${copyableValue(hash,index,'hashKey','HashKey')}</div>` : '';
      const userLabel = t('用户标识','User ID');
      const userCell = user ? `<div class="mt-1"><span class="small text-body-secondary">${userLabel}</span>${copyableValue(user,index,'userId',userLabel)}</div>` : '';
      const updatedCell = updated
        ? `<span class="d-block text-body-secondary mt-1 text-nowrap" title="${t('更新时间','Updated time')}">${t('更新','Updated')} ${esc(displayTime(updated))}</span>`
        : `<span class="text-body-secondary small">${t('创建时间','Created time')}</span>`;
      return `<tr><td><div class="d-flex align-items-center gap-1 mb-1"><span class="font-monospace tasks-task-id tasks-clip" title="${esc(id)}">${esc(compact(id)) || t('未提供任务 ID','No task ID')}</span>${copyId}</div>
        <span class="tasks-content text-body-secondary">${esc(contentSummary(row))}</span></td>
        <td>${copyableValue(workflowName(row),index,'workflowName',t('工作流名称','workflow name'))}</td>
        <td>${copyableValue(type,index,'taskType',t('任务类型','task type'))}</td><td>${statusBadge(row)}</td>
        <td>${nameCell}${hashCell}${userCell}</td><td><span class="d-block text-nowrap">${esc(displayTime(created))}</span>${updatedCell}</td>
        <td class="text-end"><button type="button" class="btn btn-link btn-sm p-0" data-task-detail="${index}">${t('查看','View')}</button></td></tr>`;
    }).join('');
    el('tasks-empty').classList.toggle('hidden',rows.length > 0);
    el('tasks-empty').querySelector('p').textContent = keyword
      ? t('当前页没有匹配的任务，请修改关键词或查看其他页。','No matching tasks on this page. Change the keyword or check other pages.')
      : t('该条件下暂无任务，可调整时间或状态。','No tasks match. Change the time range or status.');
    renderPageCounts();
    updateActions();
  }

  function renderPageCounts() {
    const itemCount = pageData.items?.length || 0;
    let total = pageData.total;
    if (total == null && statistics?.complete && statsQuery &&
        queryParams(statsQuery,true).toString() === queryParams(applied,true).toString()) {
      total = applied.status === '' ? statistics.total : statistics.counts?.[applied.status];
    }
    el('tasks-result-count').textContent = total != null ? t(`${fmt(total)} 条`,`${fmt(total)} total`)
      : t(`本页 ${fmt(itemCount)} 条`,`${fmt(itemCount)} on this page`);
    const page = applied?.page || 1;
    const totalPages = total != null ? Math.max(1,Math.ceil(total/(pageData.pageSize || applied.page_size))) : null;
    const totalLabel = total != null ? t(`共 ${fmt(total)} 条`,`${fmt(total)} total`) : t('总数未提供','Total unavailable');
    const pages = `${page}${totalPages == null ? '' : ` / ${fmt(totalPages)}`}`;
    const parts = [totalLabel,t(`第 ${pages} 页`,`Page ${pages}`),t(`本页 ${fmt(itemCount)} 条`,`${fmt(itemCount)} on this page`)];
    if (el('tasks-keyword').value.trim()) {
      const matches = el('tasks-rows').childElementCount;
      parts.push(t(`匹配 ${fmt(matches)} 条`,`${fmt(matches)} matched`));
    }
    el('tasks-page-info').textContent = parts.join(' · ');
  }

  function linkValue(value) {
    const text = scalar(value);
    try {
      const url = new URL(text);
      if (['http:','https:'].includes(url.protocol) && !url.username && !url.password) return `<a href="${esc(url.href)}" target="_blank" rel="noreferrer noopener">${esc(text)}</a>`;
    } catch { /* Ordinary values render as text. */ }
    return esc(text || '—');
  }

  function renderDetail(row, prefix) {
    const status = first(row,['status','task_status','taskStatus']);
    const fields = [
      ['任务 ID','Task ID',taskId(row)],['记录 UUID','Record UUID',row.uuid],
      ['工作流名称','Workflow name',workflowName(row)],['任务类型','Task type',taskType(row)],
      ['状态','Status',statusLabel(status)],['错误码','Error code',first(row,['error_code','errorCode'])],
      ['场景','Scene',first(row,['scene_name','sceneName'])],['渠道','Channel',channelName(row)],
      ['HashKey','HashKey',channel(row)],['用户标识','User ID',first(row,['user_id','userId','user_identifier'])],
      ['创建时间','Created',displayTime(first(row,['created_at','createdAt','create_time']))],
      ['更新时间','Updated',displayTime(first(row,['updated_at','updatedAt','update_time']))],
      ['ComfyUI 地址','ComfyUI URL',first(row,['comfyui_url','comfyui_address','comfyuiUrl','comfyuiAddress','comfyui'])],
      ['上游 fee','Upstream fee',row.fee]
    ];
    el(`${prefix}-meta`).innerHTML = fields.filter(([,,value]) => value !== '' && value != null).map(([zh,en,value]) => `<dt class="col-sm-2">${esc(t(zh,en))}</dt><dd class="col-sm-4">${linkValue(value)}</dd>`).join('');
    el(`${prefix}-json`).textContent = JSON.stringify(row,null,2);
  }

  function showDetail(index) {
    detailRow = pageData?.items?.[index];
    if (!detailRow) return;
    renderDetail(detailRow,'tasks-detail');
    bootstrap.Modal.getOrCreateInstance(el('tasks-detail-dialog')).show();
  }

  function lookupMessage(key) {
    lookupMessageKey = key;
    const output = el('task-lookup-message');
    output.textContent = lookupLanguages[locale()][key] || '';
    output.dataset.state = ['invalid','unauthorized','forbidden','failed'].includes(key) ? 'error' : '';
    output.classList.toggle('visually-hidden',key === 'found' || key === 'searching');
    output.classList.toggle('hidden',!key);
  }

  function lookupMode() {
    return el('task-lookup-mode-uuid').checked ? 'uuid' : 'task_id';
  }

  function lookupIdentifier() {
    return lookupMode() === 'uuid' ? 'UUID' : lookupLanguages[locale()].taskId;
  }

  function renderLookupLanguage() {
    const labels = lookupLanguages[locale()];
    el('view-task-lookup').lang = locale();
    el('task-lookup-title').textContent = labels.title;
    el('task-lookup-kind-label').textContent = labels.identifier;
    el('task-lookup-mode-id-label').textContent = labels.taskId;
    el('task-lookup-label').textContent = lookupIdentifier();
    const input = el('task-lookup-value');
    input.placeholder = labels.placeholder(lookupIdentifier());
    input.setCustomValidity('');
    el('task-lookup-submit').textContent = lookupController ? labels.searching : labels.search;
    el('task-lookup-result-title').textContent = labels.details;
    el('task-lookup-json-title').textContent = labels.json;
    el('task-lookup-copy').textContent = labels.copy;
    lookupMessage(lookupMessageKey);
    if (lookupResult) renderDetail(lookupResult,'task-lookup');
  }

  function clearLookup() {
    lookupResult = null;
    el('task-lookup-value').setCustomValidity('');
    el('task-lookup-result').classList.add('hidden');
    el('task-lookup-meta').textContent = '';
    el('task-lookup-json').textContent = '';
    lookupMessage('');
  }

  async function lookupTask(event) {
    event.preventDefault();
    if (lookupController) return;
    const kind = el('task-lookup-kind');
    const mode = lookupMode();
    const input = el('task-lookup-value');
    const value = mode === 'uuid' ? input.value.trim() : input.value;
    if (!value.trim()) {
      input.setCustomValidity(lookupLanguages[locale()].missing(lookupIdentifier()));
      input.reportValidity();
      return;
    }
    const controller = lookupController = new AbortController();
    const button = el('task-lookup-submit');
    const payload = {[mode]:value};
    clearLookup();
    kind.disabled = input.disabled = button.disabled = true;
    button.textContent = lookupLanguages[locale()].searching;
    lookupMessage('searching');
    try {
      const {task} = await api('/api/tasks/lookup',{method:'POST',body:JSON.stringify(payload),signal:controller.signal});
      if (controller.signal.aborted) return;
      if (task === null) {
        lookupMessage('notFound');
        return;
      }
      if (!task || typeof task !== 'object' || Array.isArray(task)) {
        lookupMessage('failed');
        return;
      }
      lookupResult = task;
      renderDetail(task,'task-lookup');
      el('task-lookup-result').classList.remove('hidden');
      lookupMessage('found');
    } catch (error) {
      if (controller.signal.aborted) return;
      if (error.status === 404) lookupMessage('notFound');
      else if (error.status === 400) lookupMessage('invalid');
      else if (error.status === 401 || /\bHTTP 401\b/.test(error.message)) lookupMessage('unauthorized');
      else if (error.status === 403 || /\bHTTP 403\b/.test(error.message)) lookupMessage('forbidden');
      else lookupMessage('failed');
    } finally {
      lookupController = null;
      kind.disabled = input.disabled = button.disabled = false;
      button.textContent = lookupLanguages[locale()].search;
    }
  }

  async function copyDetail(prefix) {
    const data = el(`${prefix}-json`).textContent;
    if (!data) return;
    const lookup = prefix === 'task-lookup';
    try {await copyText(data);toast(lookup ? lookupLanguages[locale()].copied : t('任务数据已复制。','Task data copied.'));}
    catch(error) {
      if (lookup) toast(lookupLanguages[locale()].copyFailed,true);
      else notifyError(error,t('复制任务详情','Copy task details'));
    }
  }

  function exportPage() {
    if (!pageData?.items?.length) return;
    const headings = [t('任务 ID','Task ID'),'UUID',t('任务类型','Task type'),t('工作流名称','Workflow name'),
      t('状态','Status'),'HashKey',t('渠道','Channel'),t('创建时间','Created'),t('完整数据','Raw JSON')];
    const rows = [headings,...pageData.items.map((row) => [taskId(row),row.uuid,taskType(row),workflowName(row),first(row,['status','task_status']),channel(row),row.channelName,first(row,['created_at','createdAt','create_time']),JSON.stringify(row)])];
    const cell = (value) => {
      const text = scalar(value);
      return `"${(/^[=+\-@\t\r]/.test(text) ? "'" + text : text).replace(/"/g,'""')}"`;
    };
    const blob = new Blob(['\uFEFF' + rows.map((row) => row.map(cell).join(',')).join('\r\n')],{type:'text/csv;charset=utf-8'});
    const url = URL.createObjectURL(blob);
    const anchor = document.createElement('a');
    anchor.href = url;anchor.download = `p0-tasks-${applied.start_time.slice(0,10)}-page-${applied.page}.csv`;
    anchor.click();setTimeout(() => URL.revokeObjectURL(url),1000);
  }

  function renderAiScope() {
    const note = aiDisplay?.result?.analysisNote;
    let text = t('先在「配置 → AI 数据总结」启用 AI 并设置模型密钥。','Enable AI and set a model key in Settings → AI summaries.');
    if (note) text = english() ? englishAnalysisNote(note) : note;
    else if (statistics?.complete === false) text = t('部分状态统计尚未完成，补齐后自动生成 AI 简报。','The AI brief starts after all status counts are available.');
    else if (config?.llmEnabled && config?.llmApiKeyConfigured) {
      text = t('查询后自动分析未成功任务、工作流与渠道集中情况，并给出排查重点。',
        'After search, AI reviews non-success tasks and concentrations by workflow or channel, then suggests what to investigate.');
    }
    el('tasks-ai-scope').textContent = text;
  }

  function englishAnalysisNote(note) {
    const records = note.match(/^已(?:核对|读取) (\d+) 条/)?.[1];
    if (note.startsWith('诊断取数时未发现')) return 'No non-success tasks were found during the review. Refresh for the latest status.';
    if (records == null) return 'The brief uses status counts because task details could not be fully loaded.';
    const text = note.startsWith('已核对') ? `Reviewed ${fmt(records)} non-success tasks by age, update time, workflow, and channel.`
      : `Reviewed a sample of ${fmt(records)} non-success tasks. The brief reflects only the records read.`;
    return note.includes('两次取数间发生变化') ? `${text} Statuses changed between requests, so read the details and counts separately.` : text;
  }

  function renderAi() {
    const output = el('tasks-ai-output');
    output.classList.toggle('hidden',!aiDisplay);
    if (!aiDisplay) return;
    if (aiDisplay.state === 'cancelled') {
      output.textContent = t('已取消 AI 简报，任务查询和统计可以继续使用。','AI brief canceled. Task search and counts are still available.');
    } else if (aiDisplay.progress) {
      const elapsed = Math.floor(aiDisplay.progress.elapsedSeconds || 0);
      const remaining = Math.ceil(aiDisplay.progress.remainingSeconds);
      output.textContent = t(`正在分析任务并生成简报，已等待 ${elapsed} 秒，剩余最多 ${remaining} 秒…`,
        `Generating the task brief… ${elapsed}s elapsed, up to ${remaining}s left.`);
    } else if (aiDisplay.result) {
      const {aiStatus,aiText,aiMessage} = aiDisplay.result;
      output.textContent = aiStatus === 'ok' && aiText ? aiText
        : (aiMessage && !english() ? aiMessage : t('AI 暂不可用，任务统计已保留。','The AI brief is unavailable. Task counts are still available.'));
    } else if (aiDisplay.error) {
      const message = errorText(aiDisplay.error);
      output.textContent = t(`简报未生成：${message}`,`Could not generate the brief. ${message}`);
    }
  }

  async function generateAi() {
    if (!statistics || statistics.complete === false || statsBusy || aiController || !config?.llmEnabled || !config?.llmApiKeyConfigured) return;
    const captured = statistics;
    const sequence = ++aiSequence;
    aiController = new AbortController();
    const timeoutSeconds = aiTimeoutSeconds;
    el('tasks-ai-cancel').classList.remove('hidden');
    aiDisplay = {progress:{elapsedSeconds:0,remainingSeconds:timeoutSeconds}};
    renderAiScope();
    renderAi();
    updateActions();
    try {
      const result = await runAiTask({kind:'tasks',statistics:captured},{timeoutSeconds,signal:aiController.signal,onProgress:(progress) => {
        if (sequence !== aiSequence) return;
        if (progress && Number.isFinite(progress.remainingSeconds)) {
          aiDisplay = {progress};
          renderAi();
        }
      }});
      if (sequence !== aiSequence || captured !== statistics) return;
      aiDisplay = {result};
      renderAiScope();
      renderAi();
    } catch (error) {
      if (sequence !== aiSequence || error.name === 'AbortError') return;
      aiDisplay = {error};
      notifyError(error,t('P0 AI 任务简报','P0 AI brief'));
      renderAi();
    } finally {
      if (sequence === aiSequence) {
        aiController = null;
        el('tasks-ai-cancel').classList.add('hidden');
        updateActions();
      }
    }
  }

  function configure(next) {
    const previousConfig = config;
    const firstConfig = !config;
    const changed = config !== next;
    config = next;
    el('tasks-zone').textContent = config.timezone || 'Asia/Shanghai';
    if (firstConfig || changed) {
      if (!firstConfig) {lookupController?.abort();clearLookup();}
      listController?.abort();statsController?.abort();listSequence++;statsSequence++;stopAi();
      applied=null;queryChanged=false;pageData=null;listError=null;statistics=null;statsQuery=null;statsError=null;
      listBusy=false;statsBusy=false;aiDisplay=null;
      resetFailureBreakdown();
      if (firstConfig || previousConfig?.timezone !== config.timezone) setRange('today');
      renderListState();
      renderAi();
    }
    if (!aiController) {
      const timeout = Number(config.llmTimeoutSeconds);
      aiTimeoutSeconds = Number.isInteger(timeout) && timeout >= 5 && timeout <= 300 ? timeout : 120;
      aiTimeoutOptions.add(aiTimeoutSeconds);
      renderAiTimeoutMenu();
    }
    renderStats();
    if (location.hash === '#tasks' && !applied) activate();
  }

  function activate() {
    if (!config) {
      if (typeof state !== 'undefined' && state.config) configure(state.config);
      return;
    }
    if (!applied) search();
  }

  function timeoutOptionLabel(seconds) {
    return t(`最多 ${seconds} 秒`,`Up to ${seconds}s`);
  }

  function renderAiTimeoutSelection() {
    el('tasks-ai-timeout-value').textContent = timeoutOptionLabel(aiTimeoutSeconds);
    for (const option of el('tasks-ai-timeout-menu').children) {
      option.setAttribute('aria-checked',String(Number(option.dataset.aiTimeout) === aiTimeoutSeconds));
    }
  }

  function renderAiTimeoutMenu() {
    el('tasks-ai-timeout-menu').innerHTML = [...aiTimeoutOptions].sort((a,b) => a-b).map((seconds) =>
      `<button type="button" class="dropdown-item ai-timeout-option" role="menuitemradio" tabindex="-1" data-ai-timeout="${seconds}"><span>${esc(timeoutOptionLabel(seconds))}</span><svg aria-hidden="true"><use href="#i-check"/></svg></button>`
    ).join('');
    renderAiTimeoutSelection();
  }

  el('tasks-ai-timeout-toggle').addEventListener('shown.bs.dropdown',() => {
    el('tasks-ai-timeout-menu').querySelector('[aria-checked="true"]')?.focus({preventScroll:true});
  });
  el('tasks-ai-timeout-menu').addEventListener('click',(event) => {
    const option = event.target.closest('[data-ai-timeout]');
    if (!option || aiController) return;
    aiTimeoutSeconds = Number(option.dataset.aiTimeout);
    renderAiTimeoutSelection();
    const toggle = el('tasks-ai-timeout-toggle');
    bootstrap.Dropdown.getOrCreateInstance(toggle).hide();
    toggle.focus({preventScroll:true});
  });

  function renderLanguage() {
    renderLookupLanguage();
    renderAiTimeoutMenu();
    renderListState();
    renderStats();
    renderFailureBreakdown();
    renderAi();
    if (detailRow) renderDetail(detailRow,'tasks-detail');
  }

  el('tasks-filter').addEventListener('submit',(event) => {event.preventDefault();search();});
  el('tasks-filter').addEventListener('input',edited);
  el('tasks-reset').addEventListener('click',() => {el('tasks-status').value='';el('tasks-keyword').value='';for(const id of ['tasks-type','tasks-hash','tasks-id']) el(id).value='';el('tasks-more-filters').open=false;setRange('today');search();});
  document.querySelectorAll('[data-task-range]').forEach((button) => button.addEventListener('click',() => {setRange(button.dataset.taskRange);search();}));
  document.querySelectorAll('[data-task-status]').forEach((button) => button.addEventListener('click',() => {el('tasks-status').value=button.dataset.taskStatus;search();}));
  el('tasks-keyword').addEventListener('input',renderRows);
  el('tasks-prev').addEventListener('click',() => {if(applied && applied.page>1) loadList({...applied,page:applied.page-1});});
  el('tasks-next').addEventListener('click',() => {if(applied && pageData?.hasMore) loadList({...applied,page:applied.page+1});});
  el('tasks-page-size').addEventListener('change',() => {if(applied) loadList({...applied,page:1,page_size:Number(el('tasks-page-size').value)});});
  el('tasks-stats-retry').addEventListener('click',() => {if(applied) loadStatistics({...applied});});
  el('tasks-failure-load').addEventListener('click',loadFailureBreakdown);
  el('tasks-failure-group').addEventListener('change',renderFailureBreakdown);
  el('tasks-rows').addEventListener('click',async(event) => {
    const button = event.target.closest('button');
    if (!button) return;
    if (button.dataset.taskDetail !== undefined) showDetail(Number(button.dataset.taskDetail));
    if (button.dataset.taskCopy !== undefined) {
      const row = pageData?.items?.[Number(button.dataset.taskCopy)];
      if (!row) return;
      const field = button.dataset.taskCopyField || 'taskId';
      const values = {taskId:[taskId(row),t('任务 ID','Task ID')],workflowName:[workflowName(row),t('工作流名称','Workflow name')],
        taskType:[taskType(row),t('任务类型','Task type')],channelName:[channelName(row),t('渠道名称','Channel name')],
        hashKey:[channel(row),'HashKey'],userId:[scalar(first(row,['user_id','userId','user_identifier','userIdentifier'])),t('用户标识','User ID')]};
      if (!Object.hasOwn(values,field)) return;
      const [value,label] = values[field];
      if (!value) return;
      try {await copyText(value);toast(t(`${label} 已复制。`,`${label} copied.`));}
      catch(error) {notifyError(error,t(`复制 ${label}`,`Copy ${label}`));}
    }
  });
  el('tasks-copy-detail').addEventListener('click',() => copyDetail('tasks-detail'));
  el('tasks-detail-dialog').addEventListener('hidden.bs.modal',() => {detailRow = null;});
  el('task-lookup-form').addEventListener('submit',lookupTask);
  el('task-lookup-kind').addEventListener('change',() => {
    clearLookup();
    renderLookupLanguage();
  });
  el('task-lookup-value').addEventListener('input',clearLookup);
  el('task-lookup-value').addEventListener('invalid',(event) => {
    if (event.target.validity.valueMissing) event.target.setCustomValidity(lookupLanguages[locale()].missing(lookupIdentifier()));
  });
  el('task-lookup-copy').addEventListener('click',() => copyDetail('task-lookup'));
  window.addEventListener('app-language-change',renderLanguage);
  renderLanguage();
  el('tasks-export').addEventListener('click',exportPage);
  el('tasks-ai-generate').addEventListener('click',generateAi);
  el('tasks-ai-cancel').addEventListener('click',() => stopAi(true));
  if (typeof state !== 'undefined' && state.config) configure(state.config);
  return {activate,configure};
})();
