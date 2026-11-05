'use strict';

// The explorer keeps edited inputs separate from the query currently shown on screen.
window.P0Tasks = (() => {
  const el = (id) => document.getElementById(id);
  const esc = (value) => escapeHtml(value);
  const fmt = (value) => value == null ? '—' : number(value);
  const codes = ['0','1','2','3','-1'];
  let config = null;
  let applied = null;
  let pageData = null;
  let statistics = null;
  let statisticsKey = '';
  let listController = null;
  let statsController = null;
  let aiController = null;
  let listSequence = 0;
  let statsSequence = 0;
  let aiSequence = 0;
  let listBusy = false;
  let statsBusy = false;
  let detail = null;

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
  const statusLabel = (value) => String(value) === '-1' ? '失败 · -1' : String(value) === '2' ? '成功 · 2' : `状态 ${value ?? '未知'}`;
  const intervalKey = (query) => JSON.stringify([query.start_time,query.end_time,query.task_type,query.hash_key,query.task_id,config?.timezone]);
  const filterCaption = (query) => [['工作流',query.task_type],['HashKey',query.hash_key],['任务 ID',query.task_id]].filter(([,value]) => value).map(([name,value]) => `${name} ${compact(value,32)}`).join(' · ');
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
    const now = new Date();
    const end = nowLocal(now);
    let start = `${end.slice(0,10)}T00:00:00`;
    let until = end;
    if (kind === 'yesterday') {
      const previous = new Date(`${end.slice(0,10)}T12:00:00Z`);
      previous.setUTCDate(previous.getUTCDate() - 1);
      start = `${previous.toISOString().slice(0,10)}T00:00:00`;
      until = `${start.slice(0,10)}T23:59:59`;
    } else if (kind === '24h' || kind === '7d') {
      start = nowLocal(new Date(now.getTime() - (kind === '7d' ? 7 : 1) * 86400000));
    }
    el('tasks-start').value = start;
    el('tasks-end').value = until;
    document.querySelectorAll('[data-task-range]').forEach((button) => button.classList.toggle('active',button.dataset.taskRange === kind));
    edited();
  }

  function inputQuery() {
    const start = el('tasks-start').value;
    const end = el('tasks-end').value;
    if (!start || !end) throw new Error('请选择完整的开始和结束时间。');
    const seconds = (value) => value.length === 16 ? `${value}:00` : value;
    if (seconds(start) > seconds(end)) throw new Error('开始时间不能晚于结束时间。');
    return {start_time:`${seconds(start)}.000000`,end_time:`${seconds(end)}.999999`,status:el('tasks-status').value,task_type:el('tasks-type').value.trim(),hash_key:el('tasks-hash').value.trim(),task_id:el('tasks-id').value.trim(),page:1,page_size:Number(el('tasks-page-size').value)};
  }

  function edited() {
    el('tasks-query-note').textContent = applied ? '条件已修改，点击「查询」后应用。' : '支持精确到秒；统计按所选时间段计算。';
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
      if (timedOut) throw new Error('P0 查询超时，请缩小时间范围后重试。');
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

  function errorText(error, context) {
    const message = error?.message || '操作失败，请重试。';
    if (typeof handleError === 'function') handleError(error,context);
    return redactDiagnostic(message);
  }

  function stopAi(showMessage=false) {
    aiSequence++;
    aiController?.abort();
    aiController = null;
    el('tasks-ai-cancel').classList.add('hidden');
    el('tasks-ai-timeout').disabled = false;
    if (showMessage) {
      el('tasks-ai-output').textContent = '已取消 AI 简报，任务查询和统计可以继续使用。';
      el('tasks-ai-output').classList.remove('hidden');
    }
    updateActions();
  }

  function updateActions() {
    el('tasks-search').disabled = listBusy;
    el('tasks-search').querySelector('span').textContent = listBusy ? '查询中' : '查询';
    el('tasks-search').querySelector('svg').classList.toggle('spinning',listBusy);
    el('tasks-reset').disabled = listBusy;
    el('tasks-prev').disabled = listBusy || !applied || applied.page <= 1;
    el('tasks-next').disabled = listBusy || !pageData?.hasMore;
    el('tasks-page-size').disabled = listBusy;
    el('tasks-export').disabled = listBusy || !pageData?.items?.length;
    el('tasks-ai-generate').disabled = !statistics || statistics.complete === false || statsBusy || !!aiController || !config?.llmEnabled || !config?.llmApiKeyConfigured;
    el('tasks-ai-generate').textContent = aiController ? '正在生成' : '重新生成';
  }

  function renderStats() {
    el('tasks-count-all').textContent = fmt(statistics?.total);
    for (const code of codes) {
      const count = statistics?.counts?.[code];
      el(`tasks-count-${code}`).textContent = fmt(count);
      el(`tasks-ratio-${code}`).textContent = count == null ? statistics?.complete === false ? '统计未完成' : '点击筛选' : statistics?.consistent === false ? '独立查询计数' : statistics?.total > 0 ? `占比 ${(Number(count)/Number(statistics.total)*100).toFixed(1)}%` : statistics?.total === 0 ? '该时间段暂无任务' : '点击筛选';
    }
    document.querySelectorAll('[data-task-status]').forEach((button) => {
      button.classList.toggle('active',button.dataset.taskStatus === (applied?.status ?? ''));
      button.setAttribute('aria-pressed',String(button.dataset.taskStatus === (applied?.status ?? '')));
    });
    updateActions();
  }

  async function loadStatistics(query, force=false) {
    const key = intervalKey(query);
    if (!force && statistics && statistics.complete !== false && statisticsKey === key) {
      stopAi();
      void generateAi();
      return;
    }
    statsController?.abort();
    statsController = new AbortController();
    const sequence = ++statsSequence;
    stopAi();
    statistics = null;
    statisticsKey = key;
    statsBusy = true;
    el('tasks-ai-output').classList.add('hidden');
    el('tasks-stats-caption').textContent = '正在统计完整时间段…';
    el('tasks-stats-retry').classList.add('hidden');
    renderStats();
    try {
      const response = await get(`/api/tasks/statistics?${queryParams(query,true)}`,statsController.signal);
      if (sequence !== statsSequence) return;
      statistics = response;
      const difference = response.snapshotDifference;
      const note = response.complete === false ? ' · 部分状态统计未完成，请缩短时间段或重试' : difference != null && Number(difference) !== 0 ? ` · 状态统计与总数差 ${fmt(difference)}，任务状态可能在查询期间变化` : '';
      el('tasks-stats-caption').textContent = `时间段统计 · ${filterCaption(query) ? '匹配条件的全部状态' : '全部状态'}${note} · ${config?.timezone || response.timezone}`;
      el('tasks-stats-retry').classList.toggle('hidden',response.complete !== false && response.consistent !== false);
      el('tasks-ai-scope').textContent = response.complete === false ? '部分状态统计尚未完成，补齐后自动生成 AI 简报。' : config?.llmEnabled && config?.llmApiKeyConfigured ? '查询后自动分析未成功任务、工作流与渠道集中情况，并给出排查重点。' : '先在「配置 → AI 数据总结」启用 AI 并设置模型密钥。';
    } catch (error) {
      if (sequence !== statsSequence || error.name === 'AbortError') return;
      el('tasks-stats-caption').textContent = `统计未完成：${errorText(error,'P0 时间段统计')}`;
      el('tasks-stats-retry').classList.remove('hidden');
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
    listBusy = true;
    el('tasks-list-error').classList.add('hidden');
    el('tasks-query-note').textContent = '支持精确到秒；统计按所选时间段计算。';
    el('tasks-applied').textContent = `${displayTime(query.start_time)} — ${displayTime(query.end_time)} · ${query.status === '' ? '全部状态' : statusLabel(query.status)}${filterCaption(query) ? ' · ' + filterCaption(query) : ''}`;
    el('tasks-result-count').textContent = '查询中';
    el('tasks-rows').innerHTML = '';
    el('tasks-empty').classList.remove('hidden');
    el('tasks-empty').querySelector('p').textContent = '正在加载任务…';
    el('tasks-page-info').textContent = '正在查询 P0';
    renderStats();
    try {
      const response = await get(`/api/tasks?${queryParams(query)}`,listController.signal);
      if (sequence !== listSequence) return;
      pageData = response;
      if (Number.isInteger(response.pageSize) && response.pageSize > 0) applied.page_size = response.pageSize;
      renderRows();
    } catch (error) {
      if (sequence !== listSequence || error.name === 'AbortError') return;
      el('tasks-list-error').textContent = errorText(error,'P0 任务查询');
      el('tasks-list-error').classList.remove('hidden');
      el('tasks-result-count').textContent = '查询失败';
      el('tasks-empty').querySelector('p').textContent = '任务未加载，请调整条件或重新查询。';
      el('tasks-page-info').textContent = '—';
    } finally {
      if (sequence === listSequence) {listBusy=false;updateActions();}
    }
  }

  async function search(forceStats=true) {
    try {
      const query = inputQuery();
      el('tasks-keyword').value = '';
      await Promise.allSettled([loadList(query),loadStatistics(query,forceStats)]);
    } catch (error) {errorText(error,'P0 查询条件');}
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
    return summary || '查看详情了解任务输入与结果';
  }

  function statusBadge(row) {
    const value = first(row,['status','task_status','taskStatus']);
    const color = String(value) === '-1' ? 'text-danger bg-danger-subtle border-danger-subtle' : String(value) === '2' ? 'text-success bg-success-subtle border-success-subtle' : 'text-body-secondary bg-body-tertiary';
    return `<span class="badge border fw-normal ${color}">${esc(statusLabel(value === '' ? '未知' : value))}</span>`;
  }

  function copyableValue(value,index,field,label) {
    return `<div class="d-flex align-items-start gap-1"><span class="tasks-full-value flex-grow-1${['taskType','hashKey','userId'].includes(field) ? ' font-monospace text-body-secondary' : ''}">${esc(value || '未提供')}</span>${value ? `<button type="button" class="btn btn-link text-body-secondary tasks-copy flex-shrink-0" data-task-copy="${index}" data-task-copy-field="${field}" aria-label="复制 ${label}" title="复制 ${label}"><svg><use href="#i-copy"/></svg></button>` : ''}</div>`;
  }

  function renderRows() {
    const items = Array.isArray(pageData?.items) ? pageData.items : [];
    const keyword = el('tasks-keyword').value.trim().toLocaleLowerCase();
    const rows = items.map((row,index) => ({row,index})).filter(({row}) => !keyword || JSON.stringify(row).toLocaleLowerCase().includes(keyword));
    el('tasks-rows').innerHTML = rows.map(({row,index}) => {
      const id = taskId(row);
      const type = taskType(row);
      const hash = channel(row);
      const name = scalar(row.channelName || hash || '未分類');
      const created = first(row,['created_at','createdAt','create_time','createTime','creation_time','created_time']);
      const updated = first(row,['updated_at','updatedAt','update_time','finished_at','completed_at']);
      const user = scalar(first(row,['user_id','userId','user_identifier','userIdentifier']));
      return `<tr><td><div class="d-flex align-items-center gap-1 mb-1"><span class="font-monospace tasks-task-id tasks-clip" title="${esc(id)}">${esc(compact(id)) || '未提供任务 ID'}</span>${id ? `<button type="button" class="btn btn-link text-body-secondary tasks-copy" data-task-copy="${index}" aria-label="复制任务 ID"><svg><use href="#i-copy"/></svg></button>` : ''}</div><span class="tasks-content text-body-secondary">${esc(contentSummary(row))}</span></td><td>${copyableValue(workflowName(row),index,'workflowName','Workflow name')}</td><td>${copyableValue(type,index,'taskType','Task type')}</td><td>${statusBadge(row)}</td><td>${name !== hash ? copyableValue(name,index,'channelName','渠道名称') : ''}${hash ? `<div${name !== hash ? ' class="mt-1"' : ''}>${copyableValue(hash,index,'hashKey','HashKey')}</div>` : ''}${user ? `<div class="mt-1"><span class="small text-body-secondary">用户标识</span>${copyableValue(user,index,'userId','用户标识')}</div>` : ''}</td><td><span class="d-block text-nowrap">${esc(displayTime(created))}</span>${updated ? `<span class="d-block text-body-secondary mt-1 text-nowrap" title="更新时间">更新 ${esc(displayTime(updated))}</span>` : '<span class="text-body-secondary small">创建时间</span>'}</td><td class="text-end"><button type="button" class="btn btn-link btn-sm p-0" data-task-detail="${index}">查看</button></td></tr>`;
    }).join('');
    el('tasks-empty').classList.toggle('hidden',rows.length > 0);
    el('tasks-empty').querySelector('p').textContent = keyword ? '当前页没有匹配的任务，请修改关键词或查看其他页。' : '该条件下暂无任务，可调整时间或状态。';
    el('tasks-result-count').textContent = pageData?.total != null ? `${fmt(pageData.total)} 条` : `本页 ${fmt(items.length)} 条`;
    const page = applied?.page || 1;
    const totalPages = pageData?.total != null ? Math.max(1,Math.ceil(pageData.total/(pageData.pageSize || applied.page_size))) : null;
    const totalLabel = pageData?.total != null ? `共 ${fmt(pageData.total)} 条` : '总数未提供';
    el('tasks-page-info').textContent = `${totalLabel} · 第 ${page}${totalPages == null ? '' : ` / ${fmt(totalPages)}`} 页 · 本页 ${fmt(items.length)} 条${keyword ? ` · 匹配 ${fmt(rows.length)} 条` : ''}`;
    updateActions();
  }

  function linkValue(value) {
    const text = scalar(value);
    try {
      const url = new URL(text);
      if (['http:','https:'].includes(url.protocol) && !url.username && !url.password) return `<a href="${esc(url.href)}" target="_blank" rel="noreferrer noopener">${esc(text)}</a>`;
    } catch { /* Ordinary values render as text. */ }
    return esc(text || '—');
  }

  function showDetail(index) {
    const row = pageData?.items?.[index];
    if (!row) return;
    detail = row;
    const fields = [
      ['任务 ID',taskId(row)],['记录 UUID',row.uuid],['Workflow name',workflowName(row)],['Task type',taskType(row)],
      ['状态',statusLabel(first(row,['status','task_status','taskStatus']))],['渠道',row.channelName || '未分類'],['HashKey',channel(row)],
      ['用户标识',first(row,['user_id','userId','user_identifier'])],['创建时间',displayTime(first(row,['created_at','createdAt','create_time']))],
      ['更新时间',displayTime(first(row,['updated_at','updatedAt','update_time']))],['ComfyUI 地址',first(row,['comfyui_url','comfyui_address','comfyuiUrl','comfyuiAddress','comfyui'])],
      ['上游 fee',row.fee]
    ];
    el('tasks-detail-meta').innerHTML = fields.filter(([,value]) => value !== '' && value != null).map(([label,value]) => `<dt class="col-sm-2">${esc(label)}</dt><dd class="col-sm-4">${linkValue(value)}</dd>`).join('');
    el('tasks-detail-json').textContent = JSON.stringify(row,null,2);
    bootstrap.Modal.getOrCreateInstance(el('tasks-detail-dialog')).show();
  }

  function exportPage() {
    if (!pageData?.items?.length) return;
    const rows = [['任务ID','UUID','Task type','Workflow name','状态','HashKey','渠道','创建时间','完整数据'],...pageData.items.map((row) => [taskId(row),row.uuid,taskType(row),workflowName(row),first(row,['status','task_status']),channel(row),row.channelName,first(row,['created_at','createdAt','create_time']),JSON.stringify(row)])];
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

  async function generateAi() {
    if (!statistics || statistics.complete === false || statsBusy || aiController || !config?.llmEnabled || !config?.llmApiKeyConfigured) return;
    const captured = statistics;
    const sequence = ++aiSequence;
    aiController = new AbortController();
    const timeoutSeconds = Number(el('tasks-ai-timeout').value);
    el('tasks-ai-timeout').disabled = true;
    el('tasks-ai-cancel').classList.remove('hidden');
    el('tasks-ai-output').classList.remove('hidden');
    el('tasks-ai-output').textContent = `正在分析任务并生成简报，最多等待 ${timeoutSeconds} 秒…`;
    updateActions();
    try {
      const result = await runAiTask({kind:'tasks',statistics:captured},{timeoutSeconds,signal:aiController.signal,onProgress:(progress) => {
        if (sequence !== aiSequence) return;
        if (typeof progress === 'string') el('tasks-ai-output').textContent = progress;
        else if (progress && Number.isFinite(progress.remainingSeconds)) el('tasks-ai-output').textContent = `正在分析任务并生成简报，已等待 ${Math.floor(progress.elapsedSeconds || 0)} 秒，剩余最多 ${Math.ceil(progress.remainingSeconds)} 秒…`;
      }});
      if (sequence !== aiSequence || captured !== statistics) return;
      el('tasks-ai-output').textContent = result.aiStatus === 'ok' ? result.aiText || result.aiMessage : result.aiMessage || 'AI 暂不可用，任务统计已保留。';
      if (result.analysisNote) el('tasks-ai-scope').textContent = result.analysisNote;
    } catch (error) {
      if (sequence !== aiSequence || error.name === 'AbortError') return;
      el('tasks-ai-output').textContent = `简报未生成：${errorText(error,'P0 AI 任务简报')}`;
    } finally {
      if (sequence === aiSequence) {
        aiController = null;el('tasks-ai-timeout').disabled=false;el('tasks-ai-cancel').classList.add('hidden');updateActions();
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
      listController?.abort();statsController?.abort();listSequence++;statsSequence++;stopAi();
      applied=null;pageData=null;statistics=null;statisticsKey='';listBusy=false;statsBusy=false;
      if (firstConfig || previousConfig?.timezone !== config.timezone) setRange('today');
      renderStats();
      el('tasks-rows').innerHTML='';el('tasks-result-count').textContent='待查询';el('tasks-page-info').textContent='—';
      el('tasks-applied').textContent='查询后显示任务信息。';
      el('tasks-empty').classList.remove('hidden');el('tasks-empty').querySelector('p').textContent='配置已更新，请重新查询任务。';
      el('tasks-stats-caption').textContent='时间段统计 · 全部状态';
      el('tasks-ai-output').classList.add('hidden');
    }
    if (!aiController) {
      const timeout = String(config.llmTimeoutSeconds || 120);
      if (![...el('tasks-ai-timeout').options].some((option) => option.value === timeout)) {
        const option = new Option(`最多 ${timeout} 秒`,timeout);el('tasks-ai-timeout').add(option);
      }
      el('tasks-ai-timeout').value = timeout;
    }
    el('tasks-ai-scope').textContent = config.llmEnabled && config.llmApiKeyConfigured ? '查询后自动分析未成功任务、工作流与渠道集中情况，并给出排查重点。' : '先在「配置 → AI 数据总结」启用 AI 并设置模型密钥。';
    updateActions();
    if (location.hash === '#tasks' && !applied) activate();
  }

  function activate() {
    if (!config) {
      if (typeof state !== 'undefined' && state.config) configure(state.config);
      return;
    }
    if (!applied) search();
  }

  el('tasks-filter').addEventListener('submit',(event) => {event.preventDefault();search();});
  el('tasks-filter').addEventListener('input',edited);
  el('tasks-reset').addEventListener('click',() => {el('tasks-status').value='';el('tasks-keyword').value='';for(const id of ['tasks-type','tasks-hash','tasks-id']) el(id).value='';el('tasks-more-filters').open=false;setRange('today');search();});
  document.querySelectorAll('[data-task-range]').forEach((button) => button.addEventListener('click',() => {setRange(button.dataset.taskRange);search();}));
  document.querySelectorAll('[data-task-status]').forEach((button) => button.addEventListener('click',() => {el('tasks-status').value=button.dataset.taskStatus;search(false);}));
  el('tasks-keyword').addEventListener('input',renderRows);
  el('tasks-prev').addEventListener('click',() => {if(applied && applied.page>1) loadList({...applied,page:applied.page-1});});
  el('tasks-next').addEventListener('click',() => {if(applied && pageData?.hasMore) loadList({...applied,page:applied.page+1});});
  el('tasks-page-size').addEventListener('change',() => {if(applied) loadList({...applied,page:1,page_size:Number(el('tasks-page-size').value)});});
  el('tasks-stats-retry').addEventListener('click',() => {if(applied) loadStatistics({...applied},true);});
  el('tasks-rows').addEventListener('click',async(event) => {
    const button = event.target.closest('button');
    if (!button) return;
    if (button.dataset.taskDetail !== undefined) showDetail(Number(button.dataset.taskDetail));
    if (button.dataset.taskCopy !== undefined) {
      const row = pageData?.items?.[Number(button.dataset.taskCopy)];
      if (!row) return;
      const field = button.dataset.taskCopyField || 'taskId';
      const values = {taskId:[taskId(row),'任务 ID'],workflowName:[workflowName(row),'Workflow name'],taskType:[taskType(row),'Task type'],channelName:[scalar(row.channelName || channel(row) || '未分類'),'渠道名称'],hashKey:[channel(row),'HashKey'],userId:[scalar(first(row,['user_id','userId','user_identifier','userIdentifier'])),'用户标识']};
      if (!Object.hasOwn(values,field)) return;
      const [value,label] = values[field];
      if (!value) return;
      try {await copyText(value);toast(`${label} 已复制。`);}
      catch(error) {errorText(error,`复制 ${label}`);}
    }
  });
  el('tasks-copy-detail').addEventListener('click',async() => {
    if (!detail) return;
    try {await copyText(JSON.stringify(detail,null,2));toast('任务数据已复制。');}
    catch(error) {errorText(error,'复制任务详情');}
  });
  el('tasks-export').addEventListener('click',exportPage);
  el('tasks-ai-generate').addEventListener('click',generateAi);
  el('tasks-ai-cancel').addEventListener('click',() => stopAi(true));
  if (typeof state !== 'undefined' && state.config) configure(state.config);
  return {activate,configure};
})();
