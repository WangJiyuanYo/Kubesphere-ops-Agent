'use strict';
(() => {
  const $ = id => document.getElementById(id);
  const state = {auth: '', authVersion: 0, data: null, page: 'chat', session: null, discovery: null, sending: false, editing: null, tests: new Map()};
  const pageMeta = {
    chat: ['Agent 对话', '和 Agent 一起排查部署问题', '关联任务与集群，把故障现象整理成可验证的排查步骤。'],
    connections: ['KubeSphere 连接', '连接你的部署环境', '管理集群入口与只读凭据，为对话补充真实环境信息。'],
    llm: ['LLM 设置', '为 Agent 配置语言模型', '接入你选择的模型服务，用于对话分析和故障诊断。'],
    runs: ['部署任务', '掌握每一次部署的进展', '查看监控状态、故障证据与恢复结果。'],
    watches: ['站点开通', '跟踪站点开通的每个阶段', '按明确范围设置托管，查看异常、调查和恢复结果。'],
    memories: ['排障记忆', '把解决问题的经验留下来', '记录思路、验证结果，再让历史经验帮助下一次排查。']
  };
  const labels = {DISCOVERING:'发现资源',MONITORING:'监控中',DIAGNOSING:'诊断中',WAITING_APPROVAL:'等待批准',RETRY_WAIT:'等待重试',EXECUTING:'恢复中',VERIFYING:'验证中',SUCCEEDED:'已成功',MANUAL_REQUIRED:'需人工处理',FAILED:'已失败',CANCELLED:'已取消'};
  let toastTimer, sessionVersion = 0, loadingSession = false, modelListVersion = 0;
  const pendingRequests = new Set();
  function toast(message, error = false) {
    clearTimeout(toastTimer); $('toast').textContent = message; $('toast').classList.toggle('error', error); $('toast').hidden = false;
    toastTimer = setTimeout(() => { $('toast').hidden = true; }, 5500);
  }
  function node(tag, className, text) {
    const el = document.createElement(tag); if (className) el.className = className;
    if (text !== undefined) el.textContent = text; return el;
  }
  function signOut() {
    sessionVersion++; loadingSession = false;
    state.authVersion++; pendingRequests.forEach(controller => controller.abort());
    state.sending = false;
    ['send-message','session-select','new-chat','chat-input'].forEach(id => { $(id).disabled = false; });
    state.auth = ''; state.data = null; state.session = null; state.discovery = null; state.tests.clear();
    discoveryVersion++; discoveryOffset=0; discoveryBusy.clear();platformVersion++;platformOffset=0;discoveryDetailVersion++;
    watchVersion++;watchDetailVersion++;watchOffset=0;editingPolicy=null;
    resetModelList();
    document.querySelectorAll('input[type=password]').forEach(input => { input.value = ''; });
    $('connection-ca').value = '';
    $('app').hidden = true; $('login-panel').hidden = false; $('login-error').textContent = '';
    document.querySelectorAll('dialog[open]').forEach(dialog => dialog.close());
  }
  async function api(path, options = {}) {
    const authentication = state.auth;
    const version = state.authVersion;
    const controller = new AbortController();
    pendingRequests.add(controller);
    const timer = setTimeout(() => controller.abort(), 65000);
    try {
      const response = await fetch(path, {...options, credentials:'omit', cache:'no-store', signal:controller.signal,
        headers:{'Authorization':authentication,'Content-Type':'application/json','X-Console-Request':'1',...(options.headers || {})}});
      if (state.authVersion !== version) throw new Error('当前登录已结束');
      if (response.status === 401) { signOut(); throw new Error('账号或密码错误，或登录已失效'); }
      const text = await response.text(); let body;
      if (state.authVersion !== version) throw new Error('当前登录已结束');
      try { body = text ? JSON.parse(text) : null; } catch { body = null; }
      if (!response.ok) throw new Error(body?.detail || body?.message || `请求失败（${response.status}），请稍后重试`);
      return body;
    } catch (error) {
      if (error.name === 'AbortError') throw new Error('请求超时，请刷新状态后重试；原消息可能仍在处理中。');
      throw error;
    } finally { clearTimeout(timer); pendingRequests.delete(controller); }
  }
  const write = (path, body, method='POST') => api(path, {method, ...(body === undefined ? {} : {body:JSON.stringify(body)})});
  function showPage(page) {
    state.page = page;
    Object.keys(pageMeta).forEach(key => { $('page-' + key).hidden = key !== page; });
    document.querySelectorAll('.nav-item').forEach(el => el.classList.toggle('active', el.dataset.page === page));
    const [breadcrumb,title,description] = pageMeta[page];
    $('breadcrumb').textContent = breadcrumb; $('page-title').textContent = title; $('page-description').textContent = description;
    if (page === 'runs') refreshRuns().catch(error => toast(error.message,true));
    if (page === 'watches') refreshWatches().catch(error => toast(error.message,true));
    if (page === 'memories') refreshMemories().catch(error => { $('memory-error').textContent = error.message; });
  }
  function fillSelect(select, values, emptyText, makeLabel) {
    const previous = select.value; select.replaceChildren(new Option(emptyText,''));
    values.forEach(value => select.add(new Option(makeLabel(value),value.id)));
    if (values.some(value => value.id === previous)) select.value = previous;
  }
  function updateContextOptions() {
    fillSelect($('chat-connection'),state.data.connections,'不关联集群',value=>`${value.name} / ${value.namespace}`);
    fillSelect($('chat-run'),state.data.runs,'不关联任务',value=>`${labels[value.state] || value.state} · ${value.provisioningId}`);
    updateContext();
  }
  function updateContext() {
    const fixed = Boolean(state.session || state.discovery);
    if(state.discovery){
      const value='discovered:'+state.discovery.id;
      if(!Array.from($('chat-run').options).some(option=>option.value===value))$('chat-run').add(new Option('发现 · '+state.discovery.observation.target.name,value));
      $('chat-run').value=value;
    }
    $('chat-connection').disabled = fixed || state.sending; $('chat-run').disabled = fixed || state.sending;
    const hasConnection = Boolean($('chat-connection').value);
    $('include-cluster').disabled = !hasConnection || state.sending;
    if (!hasConnection) $('include-cluster').checked = false;
    $('context-note').textContent = fixed ? '上下文已固定。切换环境或任务时，请新建对话。' : '发送首条消息后将固定本次对话的上下文。';
    const connection=state.data.connections.find(value=>value.id===$('chat-connection').value);
    $('chat-connection-note').textContent=connection
      ? `${connection.name} · ${connection.apiUrl}\n命名空间：${state.discovery?.observation.target.namespace || connection.namespace}\n${$('include-cluster').checked?(state.discovery?'发送时采集关联目标的容器、事件和有界日志；缺口单独标记。':'发送时执行只读排查Graph，采集异常Pod、事件、当前及上次退出日志；可指定Pod名称继续。'):'本轮仅发送连接配置，不读取实时 Pod。'}`
      : 'Agent 可查看已保存连接的名称、地址和命名空间。实时排查前请选择连接；不会自动访问未选择的集群。';
    $('chat-discovery-note').hidden = !state.discovery;
    $('chat-discovery-note').textContent = state.discovery ? `发现的部署：${state.discovery.observation.target.kind}/${state.discovery.observation.target.name} · 只读排查` : '';
  }
  function updateModelStatus() {
    const llm = state.data.llm;
    $('model-badge').replaceChildren(node('span','status-dot'),document.createTextNode(llm.enabled ? llm.model : '规则助手'));
    $('context-model').textContent = llm.enabled ? `当前模型 · ${llm.model}` : '尚未启用 LLM';
  }
  function renderSessions() {
    fillSelect($('session-select'),state.data.sessions,'新对话',session=>session.title);
    $('session-select').value = state.session?.id || '';
  }
  function newChat() {
    if (state.sending) return;
    sessionVersion++; loadingSession = false; $('send-message').disabled = false;
    state.session = null; state.discovery = null; $('session-select').value = ''; $('chat-input').value = ''; $('chat-error').textContent = '';
    Array.from($('chat-run').options).filter(option=>option.value.startsWith('discovered:')).forEach(option=>option.remove());
    $('chat-connection').disabled = false; $('chat-run').disabled = false;
    $('include-cluster').checked=Boolean($('chat-connection').value);
    renderMessages([]); updateContext(); $('chat-input').focus();
  }
  function welcome() {
    const container = node('div','welcome'); container.id = 'chat-welcome';
    container.append(node('div','welcome-symbol','✧'),node('h2',null,'从一个部署问题开始'));
    container.append(node('p',null,'描述你看到的现象，或选择一个部署任务。\n我会结合已有证据，帮你梳理可能原因与下一步检查。'));
    const suggestions = node('div','suggestions');
    ['部署一直处于 Pending，应该先检查什么？','请分析关联任务的证据，整理排查顺序。','怎样判断部署恢复已经真正成功？'].forEach(prompt => {
      const button = node('button',null,prompt); button.type = 'button'; button.append(node('span',null,'↗'));
      button.addEventListener('click',()=>{ $('chat-input').value = prompt; $('chat-input').focus(); }); suggestions.append(button);
    });
    container.append(suggestions); return container;
  }
  function appendMessage(role, text, meta, extra='') {
    const message = node('div',`message ${role} ${extra}`); const main = node('div','message-main');
    const content = node('div','message-text',text);
    if (role === 'assistant' && !extra) window.AgentMarkdown?.render(content, text);
    main.append(node('div','message-meta',meta),content);
    message.append(node('div','message-avatar',role === 'user' ? '你' : '✧'),main); $('messages').append(message);
  }
  function memoryFeedbackForm(turn,reference) {
    const form=node('form','memory-feedback'),label=node('label','small',`经验反馈 · 引用版本 ${reference.revision}`),choice=node('select'),note=node('input'),submit=node('button','button small-button','提交反馈'),status=node('span','small muted');
    choice.setAttribute('aria-label','经验是否适用');
    [['HELPFUL','有帮助'],['NOT_APPLICABLE','本次不适用'],['OUTDATED','已过期，退回待确认']].forEach(([value,text])=>choice.add(new Option(text,value)));
    note.maxLength=500;note.placeholder='可选：实际验证结果或不适用原因';note.setAttribute('aria-label','反馈依据');submit.type='submit';
    let requestId=crypto.randomUUID(),busy=false;
    const show=feedback=>{form.replaceChildren(node('span','small muted',`已反馈：${{HELPFUL:'有帮助',NOT_APPLICABLE:'本次不适用',OUTDATED:'已过期，已退回待确认'}[feedback.kind]} · 引用版本 ${feedback.revision}${feedback.note?' · '+feedback.note:''}`));};
    choice.addEventListener('change',()=>{requestId=crypto.randomUUID();});note.addEventListener('input',()=>{requestId=crypto.randomUUID();});
    form.append(label,choice,note,submit,status);
    submit.disabled=true;
    api('/api/console/memories/feedback/turns/'+turn.id).then(items=>{
      const existing=items.find(item=>item.memoryId===reference.id);if(existing)show(existing);else submit.disabled=false;
    }).catch(error=>{status.textContent=error.message;submit.disabled=false;});
    form.addEventListener('submit',async event=>{
      event.preventDefault();if(busy)return;busy=true;submit.disabled=true;choice.disabled=true;note.disabled=true;status.textContent='';
      try{show(await write(`/api/console/memories/${reference.id}/feedback`,{requestId,turnId:turn.id,revision:reference.revision,kind:choice.value,note:note.value.trim()}));}
      catch(error){status.textContent=error.message;submit.disabled=false;choice.disabled=false;note.disabled=false;}
      finally{busy=false;}
    });
    return form;
  }
  function inspectionDetails(turn) {
    const sessionId=state.session.id, version=state.authVersion;
    const details=node('details','chat-inspection'),summary=node('summary',null,'本轮集群采证记录'),body=node('div','inspection-body');
    const refresh=node('button','text-button','刷新排查记录');refresh.type='button';
    let busy=false;
    async function load() {
      if(busy)return;busy=true;refresh.disabled=true;body.replaceChildren(node('p','small muted','正在读取实际请求与结果…'));
      try {
        const view=await api(`/api/console/sessions/${sessionId}/turns/${turn.id}/inspection`);
        if(version!==state.authVersion)return;
        body.replaceChildren();
        if(!view.available){body.append(node('p','small muted','此轮未保存排查审计。历史消息不会自动补执行；可勾选只读排查后发送新问题。'));return;}
        const report=view.report;
        const stages={APPROVED:'安全校验通过',RUNNING:'采证中',COMPLETED:'采证完成',FAILED:'采证失败',PARTIAL:'部分采证有缺口',UNKNOWN:'执行中断，结果未确认'};
        body.append(node('p','small',`${stages[report.stage] || report.stage} · ${report.plan.namespace} · ${new Date(report.at).toLocaleString('zh-CN',{hour12:false})}`),
          node('p','small muted','执行方式：通过当前 KubeSphere 连接调用 Kubernetes 只读 API。以下为实际请求，未启动 Shell。'));
        report.requests.forEach(request=>{
          const item=node('div','inspection-request');
          item.append(node('code',null,request.request),node('span','small',`${{RUNNING:'执行中',SUCCEEDED:'成功',FAILED:'失败',UNKNOWN:'结果未确认'}[request.state] || request.state} · ${request.result}`));
          body.append(item);
        });
        report.issues.forEach(issue=>body.append(node('p','form-error',issue)));
        if(report.evidence)body.append(node('pre','inspection-evidence',report.evidence));
      }catch(error){if(version===state.authVersion)body.replaceChildren(node('p','form-error',error.message));}
      finally{busy=false;refresh.disabled=false;}
    }
    details.append(summary,body,refresh);
    details.addEventListener('toggle',()=>{if(details.open)load();});refresh.addEventListener('click',load);
    return details;
  }
  function renderMessages(turns) {
    $('messages').replaceChildren();
    if (!turns.length) $('messages').append(welcome());
    turns.forEach(turn => {
      appendMessage('user',turn.userContent,turn.includeCluster?'你 · 已请求本轮只读排查':'你 · 本轮未请求集群采证');
      appendMessage('assistant',turn.assistantContent || '正在整理证据与生成回复…',turn.source || 'Agent',
        turn.state === 'FAILED' ? 'failed' : turn.state === 'RUNNING' ? 'pending' : '');
      const main = $('messages').lastElementChild.querySelector('.message-main');
      if(turn.includeCluster && state.session && turn.state!=='RUNNING')main.append(inspectionDetails(turn));
      if (turn.memories?.length) {
        const references = node('div','memory-references'); references.append(node('span',null,'参考记忆'));
        turn.memories.forEach(reference => {
          const link = node('button','text-button',reference.title); link.type = 'button';
          link.addEventListener('click',()=>api('/api/console/memories/'+reference.id).then(entry=>openMemory(entry)).catch(error=>toast(error.message,true)));
          references.append(link);
          if(turn.state==='SUCCEEDED'&&reference.revision>0)references.append(memoryFeedbackForm(turn,reference));
        }); main.append(references);
      }
      if (turn.state === 'SUCCEEDED') {
        const remember = node('button','text-button remember-button','＋ 记为排障思路'); remember.type = 'button';
        remember.addEventListener('click',()=>openMemory(null,turn));main.append(remember);
        const reflection=node('button','text-button','查看自动复盘');reflection.type='button';
        reflection.addEventListener('click',async()=>{
          reflection.disabled=true;
          try{const result=await api('/api/console/memories/reflections/'+turn.id);
            if(result.state==='READY')openMemory(result.entry);
            else toast(result.state==='PENDING'?'复盘正在归档，稍后可再次查看。':'本轮未生成自动复盘；关联目标后的新回复会自动记录，也可手工记录思路。');
          }catch(error){toast(error.message,true);}finally{reflection.disabled=false;}
        });main.append(reflection);
      }
      if(turn.state==='FAILED') {
        const retry=node('button','text-button','重新提问');retry.type='button';
        retry.addEventListener('click',()=>{
          if(state.sending||loadingSession)return;
          $('chat-input').value=turn.userContent;$('include-cluster').checked=turn.includeCluster&&Boolean($('chat-connection').value);
          updateContext();$('chat-input').focus();toast('已填入上次问题，确认后点击发送。');
        });main.append(retry);
      }
    });
    $('messages').scrollTop = $('messages').scrollHeight;
  }
  async function loadSession(id) {
    if (state.sending) return;
    const session = state.data.sessions.find(value=>value.id === id);
    if (!session) { newChat(); return; }
    const version = ++sessionVersion; loadingSession = true; $('send-message').disabled = true;
    try {
      const turns = await api(`/api/console/sessions/${id}/turns`);
      const discovery = session.discoveryId ? await api(`/api/console/discovery/tasks/${session.discoveryId}`) : null;
      if (version !== sessionVersion) return;
      state.session = session; state.discovery = discovery; $('chat-connection').value = session.connectionId || ''; $('chat-run').value = session.runId || '';
      $('include-cluster').checked=Boolean(session.connectionId);
      $('chat-error').textContent = ''; updateContext(); renderMessages(turns);
    } catch (error) {
      if (version === sessionVersion) $('session-select').value = state.session?.id || '';
      throw error;
    } finally {
      if (version === sessionVersion) { loadingSession = false; $('send-message').disabled = false; }
    }
  }
  async function sendMessage(event) {
    event.preventDefault(); if (state.sending || loadingSession) return;
    const version = state.authVersion;
    const content = $('chat-input').value.trim(); if (!content) return;
    const includeCluster = $('include-cluster').checked;
    state.sending = true; $('send-message').disabled = true; $('session-select').disabled = true; $('new-chat').disabled = true; $('chat-input').disabled = true;
    $('chat-error').textContent = ''; updateContext();
    try {
      if (!state.session) {
        state.session = await write('/api/console/sessions',{title:content.slice(0,40),connectionId:$('chat-connection').value || null,runId:state.discovery?null:$('chat-run').value || null,discoveryId:state.discovery?.id || null});
        state.data.sessions.unshift(state.session); renderSessions();
      }
      $('chat-welcome')?.remove(); appendMessage('user',content,'你'); appendMessage('assistant','正在整理证据与生成回复…','Agent','pending');
      $('messages').scrollTop = $('messages').scrollHeight; $('chat-input').value = '';
      await write(`/api/console/sessions/${state.session.id}/messages`,{requestId:crypto.randomUUID(),content,includeCluster});
      renderMessages(await api(`/api/console/sessions/${state.session.id}/turns`));
    } catch (error) {
      if (version !== state.authVersion) return;
      $('chat-error').textContent = error.message;
      if (state.auth && state.session) {
        try { renderMessages(await api(`/api/console/sessions/${state.session.id}/turns`)); } catch { /* 保留原错误 */ }
      }
    } finally {
      if (version === state.authVersion) {
        state.sending = false; $('send-message').disabled = false; $('session-select').disabled = false; $('new-chat').disabled = false; $('chat-input').disabled = false;
        if (state.auth) { updateContext(); $('chat-input').focus(); }
      }
    }
  }
  function fillLlm() {
    resetModelList();
    const value = state.data.llm;
    $('llm-enabled').checked = value.enabled; $('llm-provider').value = value.provider; $('llm-url').value = value.baseUrl;
    $('llm-model').value = value.model; $('llm-temperature').value = value.temperature; $('llm-tokens').value = value.maxTokens;
    $('llm-key').value = ''; $('llm-clear-key').checked = false;
    $('llm-key-state').textContent = value.hasApiKey ? '密钥已保存 · 留空保留原密钥' : '尚未配置密钥';
    $('llm-key').placeholder = value.hasApiKey ? '已配置，留空则不修改' : '输入 API Key';
    $('llm-test').disabled = !value.saved || !value.hasApiKey; $('llm-error').textContent = ''; protocolHelp(); updateModelStatus();
  }
  function protocolHelp() {
    $('protocol-help').textContent = $('llm-provider').value === 'DASHSCOPE'
      ? 'DashScope 填写服务根地址，无需追加 API 路径。' : '填写完整 API Base URL（含 /v1），无需追加 /chat/completions。';
  }
  function resetModelList() {
    modelListVersion++;
    $('llm-model-picker').hidden = true;
    $('llm-model-list').replaceChildren(new Option('请选择模型',''));
    $('llm-model-list-status').textContent = ''; $('llm-model-list-error').textContent = '';
    $('llm-fetch-models').disabled = false; $('llm-fetch-models').textContent = '获取模型';
  }
  async function fetchModelList() {
    const button = $('llm-fetch-models'); if (button.disabled) return;
    if (!$('llm-url').reportValidity()) return;
    resetModelList(); const version = modelListVersion;
    button.disabled = true; button.textContent = '获取中…';
    $('llm-model-list-status').textContent = '正在获取模型列表…';
    const input = {revision:state.data.llm.revision,provider:$('llm-provider').value,baseUrl:$('llm-url').value.trim(),
      apiKey:$('llm-key').value,clearApiKey:$('llm-clear-key').checked};
    try {
      const result = await write('/api/console/llm/models',input);
      if (version !== modelListVersion || !state.auth) return;
      result.models.forEach(model => $('llm-model-list').add(new Option(model,model)));
      $('llm-model-picker').hidden = result.models.length === 0;
      if (result.models.includes($('llm-model').value)) $('llm-model-list').value = $('llm-model').value;
      $('llm-model-list-status').textContent = result.models.length
        ? `已获取${result.truncated ? '前 ' : ' '}${result.models.length} 个模型。选择后保存并测试连接，确认该模型实际可用。`
        : '服务未返回可选模型，可手工填写模型名称后保存并测试连接。';
    } catch (error) {
      if (version === modelListVersion && state.auth) { $('llm-model-list-status').textContent = ''; $('llm-model-list-error').textContent = error.message; }
    } finally {
      if (version === modelListVersion) { button.disabled = false; button.textContent = '获取模型'; }
    }
  }
  async function saveLlm(event) {
    event.preventDefault(); const button = $('llm-save'); if (button.disabled) return; button.disabled = true; $('llm-error').textContent = '';
    try {
      const input = {revision:state.data.llm.revision,enabled:$('llm-enabled').checked,provider:$('llm-provider').value,
        baseUrl:$('llm-url').value.trim(),model:$('llm-model').value.trim(),apiKey:$('llm-key').value,
        clearApiKey:$('llm-clear-key').checked,temperature:Number($('llm-temperature').value),maxTokens:Number($('llm-tokens').value)};
      state.data.llm = await write('/api/console/llm',input,'PUT'); fillLlm(); toast('模型配置已保存，将用于后续对话和诊断');
    } catch(error) { $('llm-error').textContent = error.message; }
    finally { button.disabled = false; }
  }
  function openConnection(connection=null) {
    state.editing = connection; $('connection-form').reset(); $('connection-error').textContent = '';
    $('connection-dialog-title').textContent = connection ? '编辑连接' : '添加连接';
    $('connection-name').value = connection?.name || ''; $('connection-api').value = connection?.apiUrl || '';
    $('connection-console').value = connection?.consoleUrl || ''; $('connection-namespace').value = connection?.namespace || 'default';
    $('connection-token-state').textContent = connection?.hasToken ? 'Token 已保存 · 留空保留原 Token' : '尚未配置 Token';
    $('connection-token').placeholder = connection?.hasToken ? '已配置，留空则不修改' : '输入只读访问 Token';
    $('connection-ca-state').textContent = connection?.hasCaCertificate ? 'CA 证书已保存 · 留空保留原证书' : '使用系统默认信任证书';
    $('connection-dialog').showModal();
  }
  async function saveConnection(event) {
    event.preventDefault(); const button = event.submitter; if (button.disabled) return; button.disabled = true; $('connection-error').textContent = '';
    try {
      const input = {revision:state.editing?.revision || 0,name:$('connection-name').value.trim(),apiUrl:$('connection-api').value.trim(),
        consoleUrl:$('connection-console').value.trim(),namespace:$('connection-namespace').value.trim(),token:$('connection-token').value,clearToken:$('connection-clear-token').checked,
        caCertificate:$('connection-ca').value,clearCaCertificate:$('connection-clear-ca').checked};
      const path = '/api/console/connections' + (state.editing ? '/' + state.editing.id : '');
      const saved = await write(path,input,state.editing ? 'PUT' : 'POST'); state.tests.delete(saved.id);
      state.data.connections = await api('/api/console/connections'); $('connection-token').value = ''; $('connection-ca').value = ''; $('connection-dialog').close();
      renderConnections(); updateContextOptions(); toast('KubeSphere 连接已保存');
    } catch(error) { $('connection-error').textContent = error.message; }
    finally { button.disabled = false; }
  }
  function renderConnections() {
    $('connection-list').replaceChildren(); $('connection-count').textContent = `${state.data.connections.length} 个连接`;
    $('connection-empty').hidden = state.data.connections.length > 0;
    state.data.connections.forEach(connection => {
      const card = node('article','card connection-card'); const head = node('div','connection-card-head'); const title = node('div');
      title.append(node('h2',null,connection.name),node('p','muted',connection.hasToken ? '访问凭据已配置' : '未配置访问凭据'));
      if(connection.hasCaCertificate)title.append(node('p','small muted','已配置自定义 CA · TLS 校验开启'));
      head.append(node('div','cluster-mark','▦'),title); card.append(head);
      const info = node('dl','connection-info');
      [['API 网关',connection.apiUrl],['命名空间',connection.namespace]].forEach(([name,value])=>info.append(node('dt',null,name),node('dd',null,value)));
      card.append(info); const actions = node('div','connection-actions');
      const test = node('button','button','测试连接'); test.type = 'button';
      test.addEventListener('click',async()=>{
        test.disabled = true; test.textContent = '检测中…';
        try {
          const snapshot = await write(`/api/console/connections/${connection.id}/test`);
          state.tests.set(connection.id,{text:`${snapshot.summary}；本次返回 ${snapshot.pods.length} 个 Pod`,error:false});
        } catch(error) { state.tests.set(connection.id,{text:error.message,error:true}); }
        finally { if(state.auth) renderConnections(); }
      });
      const edit = node('button','button','编辑'); edit.addEventListener('click',()=>openConnection(connection));
      const remove = node('button','button danger-text','删除'); remove.addEventListener('click',async()=>{
        if(!window.confirm(`删除连接「${connection.name}」？已有对话记录会保留。`)) return;
        remove.disabled = true;
        try {
          await api(`/api/console/connections/${connection.id}?revision=${connection.revision}`,{method:'DELETE'});
          state.data.connections = await api('/api/console/connections'); state.tests.delete(connection.id); renderConnections(); updateContextOptions(); toast('连接已删除');
        }catch(error){remove.disabled = false;toast(error.message,true);}
      });
      actions.append(test,edit,remove);
      if(connection.consoleUrl){const link=node('a',null,'打开控制台 ↗');link.href=connection.consoleUrl;link.target='_blank';link.rel='noopener noreferrer';actions.append(link);}
      card.append(actions);
      const result = state.tests.get(connection.id);
      card.append(node('p',result ? `connection-result ${result.error?'error':''}` : 'connection-result muted',result?.text || '尚未检测连接'));
      $('connection-list').append(card);
    });
  }
  function statusBadge(status) {
    const type = ['FAILED','MANUAL_REQUIRED'].includes(status)?'danger':status==='SUCCEEDED'?'':'warning';
    return node('span','badge '+type,labels[status] || status);
  }
  function renderRuns() {
    $('run-list').replaceChildren(); $('run-empty').hidden = state.data.runs.length > 0;
    $('start-demo').hidden = state.data.mode !== 'DEMO';
    state.data.runs.forEach(run=>{
      const row = node('tr'); const identity = node('td'); identity.append(node('strong',null,run.provisioningId),node('small',null,run.id.slice(0,8) + ' · ' + (run.deploymentVersion || '未绑定版本')));
      const status = node('td');status.append(statusBadge(run.state));
      const actions = node('td'); const inspect = node('button','text-button','详情');
      inspect.addEventListener('click',()=>showRun(run.id).catch(error=>toast(error.message,true)));
      const discuss=node('button','text-button','询问 Agent');discuss.addEventListener('click',()=>{
        if(state.sending){toast('请等待当前回复完成');return;}
        newChat();$('chat-run').value=run.id;updateContext();showPage('chat');$('chat-input').value='请根据这个部署任务，整理当前状态、根因假设和下一步排查方案。';$('chat-input').focus();
      });actions.append(inspect,discuss);
      row.append(identity,status,node('td',null,String(run.attempts)),node('td',null,new Date(run.createdAt).toLocaleString('zh-CN',{hour12:false})),actions);$('run-list').append(row);
    });
  }
  async function showRun(id) {
    const [run,commands]=await Promise.all([api(`/api/runs/${id}`),api(`/api/runs/${id}/commands`)]);
    $('run-detail').textContent=JSON.stringify({...run,commandExecutions:commands},null,2);
    $('run-actions').replaceChildren();appendRunControls($('run-actions'),run,()=>showRun(id));
    if(!$('run-dialog').open)$('run-dialog').showModal();
  }
  function appendRunControls(container,run,reload) {
    const action=(label,path,body)=>{
      const button=node('button','button',label);button.type='button';
      button.addEventListener('click',async()=>{button.disabled=true;try{await write(`/api/runs/${run.id}/${path}`,body);await refreshRuns();await reload();}catch(error){toast(error.message,true);button.disabled=false;}});
      container.append(button);
    };
    if(run.state==='WAITING_APPROVAL' && run.plan?.action==='RETRY_TRANSIENT')action('批准当前恢复方案','approve',{planId:run.plan.id});
    if(['MONITORING','DISCOVERING','DIAGNOSING','WAITING_APPROVAL','RETRY_WAIT'].includes(run.state))action('停止后续恢复','cancel');
    const refresh=node('button','button','刷新任务');refresh.type='button';
    refresh.addEventListener('click',()=>reload().catch(error=>toast(error.message,true)));container.append(refresh);
  }
  let refreshing = false;
  let watchOffset=0,watchVersion=0,watchDetailVersion=0,editingPolicy=null;
  const levelLabels={OBSERVE:'仅观察',DIAGNOSE:'自动排查',APPROVAL:'人工批准恢复',AUTO_LOW_RISK:'低风险自动恢复'};
  const anomalyLabels={UNMANAGED:'未启用托管',OBSERVATION_DEGRADED:'观察降级',PHASE_FAILED:'阶段失败',PHASE_STALLED:'阶段停滞',TOTAL_TIMEOUT:'总时限耗尽'};
  const phaseStateLabels={RUNNING:'进行中',BLOCKED:'依赖阻塞',FAILED:'失败',SUCCEEDED:'平台阶段成功',CANCELLED:'已取消'};
  async function refreshWatches() {
    const version=++watchVersion;
    const [page,policies,outcomes]=await Promise.all([api('/api/console/provisioning-watches?offset='+watchOffset),api('/api/console/autonomy-policies'),api('/api/console/learning-outcomes')]);
    if(version!==watchVersion||!state.auth)return;
    $('watch-status').textContent=page.source.warning||'已同步平台阶段';$('sync-watches').disabled=!page.source.enabled;$('new-policy').disabled=!page.source.enabled;
    $('watch-list').replaceChildren();$('watch-empty').hidden=page.items.length>0;
    $('watch-previous').disabled=watchOffset===0;$('watch-next').disabled=page.items.length<30;
    page.items.forEach(item=>{
      const w=item.watch,s=w.stage,row=node('tr'),identity=node('td'),phase=node('td'),scope=node('td'),actions=node('td');
      identity.append(node('strong',null,'站点 '+s.siteId),node('small',null,s.provisioningId+' · '+s.deploymentVersion));
      phase.append(node('strong',null,s.phase+' · '+phaseStateLabels[s.state]),node('small',null,(w.paused?'已暂停 · ':'')+(w.observationWarning||anomalyLabels[item.anomaly]||'正常观察')));
      scope.append(node('strong',null,s.namespace||'项目尚未创建'),node('small',null,item.policy?levelLabels[item.policy.scope.level]:'手动接管'));
      const detail=node('button','text-button','详情 / 时间线');detail.addEventListener('click',()=>showWatch(w.id).catch(error=>toast(error.message,true)));actions.append(detail);row.append(identity,phase,scope,actions);$('watch-list').append(row);
    });
    $('policy-list').replaceChildren();
    if(!policies.length)$('policy-list').append(node('p','muted','尚未配置托管策略，沿用手动行为。'));
    policies.forEach(p=>{
      const card=node('article','card discovery-source'),edit=node('button','button','编辑策略'),toggle=node('button','button',p.scope.enabled?'暂停策略':'启用策略');
      card.append(node('strong',null,`${p.scope.workspaceId} · ${p.scope.siteId?'站点 '+p.scope.siteId:'项目 '+p.scope.projectId}`),node('p',null,`${p.scope.namespace} · ${levelLabels[p.scope.level]} · 版本 ${p.revision} · ${p.scope.enabled?'已启用':'已暂停'}`));
      edit.addEventListener('click',()=>editPolicy(p));toggle.addEventListener('click',async()=>{toggle.disabled=true;try{await write('/api/console/autonomy-policies/'+p.id,{...p.scope,revision:p.revision,enabled:!p.scope.enabled},'PUT');await refreshWatches();}catch(error){toast(error.message,true);toggle.disabled=false;}});card.append(edit,toggle);$('policy-list').append(card);
    });
    const outcomeLabels={VERIFIED_RECOVERY:'恢复验收成功',NATURAL_CONVERGENCE:'自然收敛',RECOVERY_NOT_VERIFIED:'恢复未验收成功',MANUAL_OR_FAILED:'转人工或失败',CANCELLED:'已取消'};
    $('learning-outcomes').replaceChildren(node('p','small muted',outcomes.note));
    outcomes.counts.forEach(c=>$('learning-outcomes').append(node('p',null,`${outcomeLabels[c.outcome]||c.outcome}：${c.samples} 个样本`)));
    if(!outcomes.counts.length)$('learning-outcomes').append(node('p','muted','暂无本轮升级后完成的样本。'));
  }
  async function showWatch(id) {
    const version=++watchDetailVersion,result=await api('/api/console/provisioning-watches/'+id);
    if(version!==watchDetailVersion||!state.auth)return;
    const w=result.item.watch,s=w.stage,detail=$('watch-detail');
    detail.replaceChildren(node('h3',null,`站点 ${s.siteId} · ${s.phase}`),node('p',null,s.summary||'尚无平台错误摘要'),node('p','muted',`工作空间 ${s.workspaceId} · 发布 ${s.deploymentVersion} · 序号 ${s.sequence}`),node('p',null,`目标：${s.namespace||'项目尚未创建'}；依赖：${(s.dependencies||[]).join('、')||'无'}`),node('p',null,`阶段开始：${new Date(w.phaseStartedAt).toLocaleString()}；最后有效进展：${new Date(w.lastMeaningfulProgressAt).toLocaleString()}`));
    if(w.observationWarning)detail.append(node('p','form-error',w.observationWarning));
    const pause=node('button','button',w.paused?'恢复观察与托管':'暂停新调查与恢复');pause.addEventListener('click',async()=>{pause.disabled=true;try{await write(`/api/console/provisioning-watches/${id}/pause`,{revision:w.revision,paused:!w.paused});await showWatch(id);await refreshWatches();}catch(error){toast(error.message,true);pause.disabled=false;}});detail.append(pause,node('h3',null,'阶段时间线'));
    result.timeline.forEach(e=>detail.append(node('p',null,`${new Date(e.occurredAt).toLocaleString()} · ${e.phase} · ${phaseStateLabels[e.state]} · ${e.errorCode||''} ${e.summary||''}`)));
    detail.append(node('h3',null,'异常处理与证据'));
    result.triggers.forEach(t=>{
      const card=node('article','card');card.append(node('p',null,`${t.state} · ${t.detail}`));
      if(t.discoveryId){const inspect=node('button','text-button','调查证据与方案');inspect.addEventListener('click',()=>{$('watch-dialog').close();showDiscovery(t.discoveryId).catch(error=>toast(error.message,true));});card.append(inspect);}detail.append(card);
    });
    if(result.recovery){const run=result.recovery;detail.append(node('h3',null,'恢复账本'),node('p',null,`${labels[run.state]} · 已用 ${run.attempts} 次恢复意图`));appendRunControls(detail,run,()=>showWatch(id));}
    if(!$('watch-dialog').open)$('watch-dialog').showModal();
  }
  function editPolicy(policy) {
    editingPolicy=policy;const p=policy?.scope;
    $('policy-form').reset();$('policy-error').textContent='';
    fillSelect($('policy-connection'),state.data.connections,'选择连接',c=>c.name+' / '+c.namespace);
    for(const [id,key] of [['workspace','workspaceId'],['site','siteId'],['project','projectId'],['connection','connectionId'],['namespace','namespace']]) {
      $('policy-'+id).value=p?.[key]||'';if(id==='connection')$('policy-'+id).disabled=Boolean(policy);else $('policy-'+id).readOnly=Boolean(policy);
    }
    $('policy-level').value=p?.level||'OBSERVE';$('policy-enabled').checked=p?.enabled||false;
    $('policy-stall').value=p?.stallSeconds||600;$('policy-total').value=p?.totalSeconds||3600;$('policy-budget').value=p?.maxDiagnoses||2;$('policy-cooldown').value=p?.cooldownSeconds||60;
    $('policy-dialog').showModal();
  }
  $('policy-form').addEventListener('submit',async event=>{
    event.preventDefault();const button=event.submitter;button.disabled=true;
    try {
      const input={revision:editingPolicy?.revision||0,workspaceId:$('policy-workspace').value.trim(),siteId:$('policy-site').value.trim()||null,projectId:$('policy-project').value.trim()||null,connectionId:$('policy-connection').value,namespace:$('policy-namespace').value.trim(),level:$('policy-level').value,enabled:$('policy-enabled').checked,stallSeconds:Number($('policy-stall').value),totalSeconds:Number($('policy-total').value),maxDiagnoses:Number($('policy-budget').value),cooldownSeconds:Number($('policy-cooldown').value)};
      await write('/api/console/autonomy-policies/'+(editingPolicy?.id||crypto.randomUUID()),input,'PUT');$('policy-dialog').close();await refreshWatches();toast('托管策略已保存');
    }catch(error){$('policy-error').textContent=error.message;}finally{button.disabled=false;}
  });
  $('new-policy').addEventListener('click',()=>editPolicy(null));$('close-policy').addEventListener('click',()=>$('policy-dialog').close());$('close-watch').addEventListener('click',()=>{watchDetailVersion++;$('watch-dialog').close();});
  $('refresh-watches').addEventListener('click',()=>refreshWatches().catch(error=>toast(error.message,true)));
  $('sync-watches').addEventListener('click',async()=>{const button=$('sync-watches');button.disabled=true;try{await write('/api/console/provisioning-watches/sync');await refreshWatches();}catch(error){toast(error.message,true);}finally{button.disabled=false;}});
  $('watch-previous').addEventListener('click',()=>{watchOffset=Math.max(0,watchOffset-30);refreshWatches().catch(error=>toast(error.message,true));});
  $('watch-next').addEventListener('click',()=>{watchOffset+=30;refreshWatches().catch(error=>toast(error.message,true));});
  let platformOffset=0,platformVersion=0;
  async function refreshPlatformCatalog() {
    const version=++platformVersion;
    try {
      const result=await api('/api/console/platform-catalog?offset='+platformOffset);
      if(version!==platformVersion||!state.auth)return;
      $('platform-catalog-list').replaceChildren();$('sync-platform-catalog').disabled=!result.enabled;
      $('platform-catalog-status').textContent=(result.warning||'已同步平台站点目录')+(result.checkedAt?' · '+new Date(result.checkedAt).toLocaleString('zh-CN',{hour12:false}):'');
      $('platform-previous').disabled=platformOffset===0;$('platform-next').disabled=!result.hasMore;
      result.items.forEach(entry=>{
        const row=node('tr'),identity=node('td');identity.append(node('strong',null,entry.domain||entry.projectName||entry.siteId),node('small',null,`站点 ${entry.siteId} · 任务 ${entry.id}`));
        const phase=node('td');phase.append(node('strong',null,entry.status),node('small',null,entry.currentStep||'等待平台推进'));
        const scope=node('td');scope.append(node('strong',null,entry.projectId||'项目尚未创建'),node('small',null,`${entry.clusterCode||'集群待定'} / ${entry.namespace||'空间待创建'}`));
        const actions=node('td'),inspect=node('button','text-button','查看平台阶段');
        inspect.addEventListener('click',()=>{
          discoveryDetailVersion++;
          const detail=$('discovery-detail');detail.replaceChildren(node('h3',null,entry.domain||entry.projectName||entry.id),node('p',null,entry.unavailableReason||'平台任务已发现；选择监控项目后可关联当前资源。'),node('pre',null,JSON.stringify(entry,null,2)));
          $('discovery-dialog').showModal();
        });actions.append(inspect);
        if(entry.namespace&&!entry.unavailableReason){
          const select=node('select');select.setAttribute('aria-label','用于项目监控的 KubeSphere 连接');select.add(new Option('选择同集群连接',''));
          state.data.connections.forEach(connection=>select.add(new Option(connection.name,connection.id)));
          const monitor=node('button','text-button','监控并关联资源');monitor.disabled=true;
          select.addEventListener('change',()=>{monitor.disabled=!select.value;});
          monitor.addEventListener('click',async()=>{
            monitor.disabled=true;try{
              const mapping=await write(`/api/console/platform-catalog/${entry.id}/monitor`,{connectionId:select.value});
              $('discovery-connection').value=mapping.connectionId;discoveryOffset=0;await refreshDiscovery();toast(mapping.message);
              if(mapping.investigations.length===1)await showDiscovery(mapping.investigations[0]);
            }catch(error){toast(error.message,true);}finally{monitor.disabled=!select.value;}
          });actions.append(select,monitor);
        }
        const discuss=node('button','text-button','询问 Agent');discuss.addEventListener('click',()=>{
          if(state.sending){toast('请等待当前回复完成');return;}
          newChat();$('chat-connection').value='';$('include-cluster').checked=false;$('chat-run').value='';updateContext();showPage('chat');
          $('chat-input').value='下面是平台目录同步的任务信息，请说明当前阶段与下一步只读排查建议。尚未关联资源或授权恢复。\n'+JSON.stringify(entry,null,2);$('chat-input').focus();
        });actions.append(discuss);row.append(identity,phase,scope,actions);$('platform-catalog-list').append(row);
      });
    }catch(error){if(version===platformVersion&&state.auth)$('platform-catalog-status').textContent=error.message;}
  }
  $('sync-platform-catalog').addEventListener('click',async()=>{const button=$('sync-platform-catalog');button.disabled=true;try{await write('/api/console/platform-catalog/sync');platformOffset=0;await refreshPlatformCatalog();}catch(error){toast(error.message,true);}finally{button.disabled=false;}});
  $('platform-previous').addEventListener('click',()=>{platformOffset=Math.max(0,platformOffset-30);refreshPlatformCatalog();});
  $('platform-next').addEventListener('click',()=>{platformOffset+=30;refreshPlatformCatalog();});
  let discoveryOffset=0, discoveryVersion=0,discoveryDetailVersion=0;
  const discoveryBusy=new Set();
  const discoveryLabels={PENDING:'待接管',QUEUED:'已排队',RUNNING:'排查中',DONE:'已有方案',FAILED:'排查失败',IGNORED:'已忽略'};
  const presenceLabels={PRESENT:'当前资源',MISSING:'已不在清单',SUPERSEDED:'已有新代次',OUTDATED:'连接目标已变更'};
  async function discoveryAction(key,action) {
    if(discoveryBusy.has(key))return;discoveryBusy.add(key);
    try{await action();if(state.auth)await refreshDiscovery();}
    catch(error){if(state.auth)$('discovery-error').textContent=error.message;}
    finally{discoveryBusy.delete(key);}
  }
  async function refreshDiscovery() {
    const version=++discoveryVersion;
    const query=new URLSearchParams({offset:String(discoveryOffset)});
    if($('discovery-connection').value)query.set('connectionId',$('discovery-connection').value);
    if($('discovery-state').value)query.set('state',$('discovery-state').value);
    const [sources,page]=await Promise.all([api('/api/console/discovery/sources'),api('/api/console/discovery/tasks?'+query)]);
    if(version!==discoveryVersion||!state.auth)return;
    $('discovery-error').textContent='';
    fillSelect($('discovery-connection'),state.data.connections,'全部连接',c=>`${c.name} / ${c.namespace}`);
    $('discovery-sources').replaceChildren();$('discovery-no-connections').hidden=state.data.connections.length>0;
    state.data.connections.forEach(connection=>{
      const source=sources.find(s=>s.connectionId===connection.id)||{enabled:false,revision:0,namespaces:[connection.namespace]};
      const card=node('article','card discovery-source');
      card.append(node('strong',null,connection.name),node('p','muted',`${source.namespaces.join('、')} · ${source.enabled?'发现已启用':'发现未启用'}${source.checkedAt?' · 最近尝试 '+new Date(source.checkedAt).toLocaleString('zh-CN',{hour12:false}):''}`));
      const rangeLabel=node('label',null,'监控命名空间（每行一个，最多 20 个）');
      const ranges=node('textarea');ranges.value=source.namespaces.join('\n');ranges.rows=2;ranges.maxLength=1280;
      ranges.setAttribute('aria-label',connection.name+'的监控命名空间');rangeLabel.append(ranges);card.append(rangeLabel);
      const saveRange=node('button','button','保存监控范围');saveRange.disabled=discoveryBusy.has(connection.id);
      saveRange.addEventListener('click',()=>{saveRange.disabled=true;discoveryAction(connection.id,async()=>{
        const namespaces=ranges.value.split(/[\s,，]+/).filter(Boolean);
        await write(`/api/console/discovery/sources/${connection.id}`,{revision:source.revision,enabled:source.enabled,namespaces},'PUT');toast('监控范围已保存');
      });});
      if(source.error)card.append(node('p','source-error',source.error));
      const toggle=node('button','button',source.enabled?'暂停发现':'启用发现');toggle.disabled=discoveryBusy.has(connection.id);
      toggle.addEventListener('click',()=>{toggle.disabled=true;discoveryAction(connection.id,async()=>{await write(`/api/console/discovery/sources/${connection.id}`,{revision:source.revision,enabled:!source.enabled},'PUT');toast(source.enabled?'已暂停部署发现':'已启用部署发现，后台将同步当前清单');});});
      const sync=node('button','button',source.syncing?'同步中…':'立即同步');sync.disabled=!source.enabled||source.syncing||discoveryBusy.has(connection.id);
      sync.addEventListener('click',()=>{sync.disabled=true;discoveryAction(connection.id,async()=>{const result=await write(`/api/console/discovery/sources/${connection.id}/sync`);if(result.error)throw new Error(result.error);toast(result.syncing?'同步正在进行，请稍后刷新':'部署清单已同步');});});
      card.append(saveRange,toggle,sync);$('discovery-sources').append(card);
    });
    $('discovery-list').replaceChildren();$('discovery-empty').hidden=page.items.length>0;
    page.items.forEach(task=>{
      const target=task.observation.target,row=node('tr'),identity=node('td'),deployment=node('td'),processing=node('td'),actions=node('td');
      identity.append(node('strong',null,`${target.kind}/${target.name}`),node('small',null,`${task.connectionName} · ${target.namespace}`),node('small',null,`代次 ${target.generation}${task.observation.releaseVersion?' · '+task.observation.releaseVersion:''}`));
      deployment.append(node('strong',null,task.observation.observedGeneration===target.generation?task.observation.phase:'等待当前代次'),node('small',null,presenceLabels[task.presence]||task.presence));
      processing.append(node('span','badge '+(task.state==='FAILED'?'danger':task.state==='DONE'?'':'warning'),discoveryLabels[task.state]));
      const detail=node('button','text-button','详情 / 方案');detail.addEventListener('click',()=>showDiscovery(task.id).catch(error=>toast(error.message,true)));
      const select=node('button','text-button',task.state==='DONE'||task.state==='FAILED'?'重新排查':'交给 Agent');
      select.disabled=!task.connectionId||task.presence!=='PRESENT'||['QUEUED','RUNNING','IGNORED'].includes(task.state)||discoveryBusy.has(task.id);
      select.addEventListener('click',()=>{select.disabled=true;discoveryAction(task.id,async()=>{await write(`/api/console/discovery/tasks/${task.id}/select`,{revision:task.revision,requestId:crypto.randomUUID()});toast('已交给 Agent，排查完成后可查看方案');});});
      const ignore=node('button','text-button',task.state==='IGNORED'?'恢复待接管':'忽略');ignore.disabled=['QUEUED','RUNNING'].includes(task.state)||discoveryBusy.has(task.id);
      ignore.addEventListener('click',()=>{ignore.disabled=true;discoveryAction(task.id,()=>write(`/api/console/discovery/tasks/${task.id}/ignore`,{revision:task.revision,ignored:task.state!=='IGNORED'}));});
      const discuss=node('button','text-button','询问 Agent');discuss.disabled=!task.connectionId;
      discuss.addEventListener('click',()=>{
        if(state.sending){toast('请等待当前回复完成');return;}
        newChat();state.discovery=task;$('chat-connection').value=task.connectionId;$('include-cluster').checked=Boolean(task.connectionId);$('chat-run').value='';updateContext();showPage('chat');
        $('chat-input').value='请根据关联部署的状态和已有方案，整理事实、根因假设与下一步排查步骤。';$('chat-input').focus();
      });
      actions.append(detail,select,ignore,discuss);
      row.append(identity,deployment,processing,node('td',null,new Date(task.discoveredAt).toLocaleString('zh-CN',{hour12:false})),actions);$('discovery-list').append(row);
    });
    $('discovery-count').textContent=`共 ${page.total} 条 · 当前 ${page.items.length?discoveryOffset+1:0}–${discoveryOffset+page.items.length}`;
    $('discovery-previous').disabled=discoveryOffset===0;$('discovery-next').disabled=discoveryOffset+30>=page.total;
  }
  async function showDiscovery(id) {
    const detailVersion=++discoveryDetailVersion;
    const caseView=await api('/api/console/discovery/tasks/'+id+'/case');if(detailVersion!==discoveryDetailVersion)return;
    const task=caseView.investigation,target=task.observation.target,detail=$('discovery-detail');detail.replaceChildren();
    detail.append(node('h3',null,`${target.kind}/${target.name}`),node('p','muted',`${task.connectionName} / ${target.namespace} · 代次 ${target.generation} · UID ${target.uid}`),
      node('p',null,`集群状态：${task.observation.phase} · ${presenceLabels[task.presence]}；Agent：${discoveryLabels[task.state]}`),node('p','muted',`最后同步：${new Date(task.observedAt).toLocaleString('zh-CN',{hour12:false})}`),node('pre',null,`${task.observation.reason}\n${task.observation.message}`));
    if(task.selectedAt)detail.append(node('p','muted',`操作员 ${task.selectedBy} 于 ${new Date(task.selectedAt).toLocaleString('zh-CN',{hour12:false})} 选择排查`));
    if(task.error)detail.append(node('p','form-error',task.error));
    if(task.report){
      const diagnosis=task.report.plan.diagnosis;
      detail.append(node('p','muted',`方案采证时间：${new Date(task.report.collectedAt).toLocaleString('zh-CN',{hour12:false})} · ${diagnosis.source}`));
      if(task.report.evidence){
        detail.append(node('h3',null,'目标证据'),node('pre',null,task.report.evidence.facts.join('\n')));
        if(task.report.evidence.issues.length)detail.append(node('p','form-error','采证缺口：'+task.report.evidence.issues.map(issue=>`${issue.source} · ${issue.errorType}`).join('；')));
      }
      [['原因分析',diagnosis.cause],['检查步骤',diagnosis.checks],['处理建议',diagnosis.repairSteps],['验证方式',diagnosis.verification]].forEach(([title,value])=>{
        detail.append(node('h3',null,title));if(Array.isArray(value)){const list=node('ul');value.forEach(text=>list.append(node('li',null,text)));detail.append(list);}else detail.append(node('p',null,value));
      });
      const assessment=task.report.plan.assessment;
      if(assessment)[['支持证据',assessment.supportingEvidence],['反对证据',assessment.opposingEvidence],['缺失证据',assessment.missingEvidence]].forEach(([title,values])=>{
        detail.append(node('h3',null,title));const list=node('ul');(values?.length?values:['本次未记录']).forEach(text=>list.append(node('li',null,text)));detail.append(list);
      });
      if(diagnosis.memories?.length){
        detail.append(node('h3',null,'参考记忆'));diagnosis.memories.forEach(reference=>{
          const link=node('button','text-button',reference.title);link.addEventListener('click',()=>api('/api/console/memories/'+reference.id).then(entry=>openMemory(entry)).catch(error=>toast(error.message,true)));detail.append(link);
        });
      }
    }
    detail.append(node('p','small muted',`调查案例：${caseView.id} · 最近关联对话 ${caseView.sessions.length} 个`));
    if(caseView.learningWarning)detail.append(node('p','form-error',caseView.learningWarning));
    if(caseView.reflections.length){
      detail.append(node('h3',null,'案例总结与学习记录'));
      caseView.reflections.forEach(entry=>{
        const open=node('button','text-button',`${entry.title} · ${memoryLabels[entry.status] || entry.status}`);
        open.addEventListener('click',()=>openMemory(entry));detail.append(open);
      });
    }
    if(caseView.recovery){
      const run=caseView.recovery;
      detail.append(node('h3',null,'受控恢复'),node('p',null,`平台任务 ${run.provisioningId} · ${run.deploymentVersion} · ${labels[run.state]} · 已尝试 ${run.attempts} 次`));
      if(run.plan)detail.append(node('pre',null,JSON.stringify(run.plan,null,2)));
      detail.append(node('pre',null,JSON.stringify({runId:run.id,targets:run.targets,evidence:run.evidence,events:run.events,commandExecutions:caseView.commandExecutions},null,2)));
      appendRunControls(detail,run,()=>showDiscovery(id));
    }else if(caseView.recoveryBlockedReason){
      detail.append(node('p','discovery-note','恢复暂不可用：'+caseView.recoveryBlockedReason));
    }else{
      detail.append(node('h3',null,'转交受控恢复'),node('p','muted','填写平台发布身份后先核验完整目标。开始监控后按服务端策略批准和重试，页面只读连接不会取得写权限。'));
      const form=node('form','dialog-form'),provisioning=node('input'),version=node('input'),profile=node('select');
      provisioning.required=true;provisioning.pattern='[1-9][0-9]{0,18}';provisioning.inputMode='numeric';provisioning.maxLength=19;
      version.required=true;version.maxLength=100;version.readOnly=true;version.placeholder='读取平台发布信息后自动填写';profile.disabled=true;
      caseView.verificationProfiles.forEach(value=>profile.add(new Option(value,value)));
      [['平台任务 ID',provisioning],['平台发布版本',version],['应用验收目录',profile]].forEach(([title,input])=>{const label=node('label',null,title);label.append(input);form.append(label);});
      const resolve=node('button','button','读取平台发布信息');resolve.type='button';
      const preview=node('button','button','核验并预览完整目标');preview.type='submit';preview.disabled=true;form.append(resolve,preview);detail.append(form);
      const result=node('div');detail.append(result);
      try {
        const candidates=await api(`/api/console/platform-catalog/investigations/${id}`);
        if(detailVersion!==discoveryDetailVersion)return;
        if(candidates.length){
          const choices=node('select');choices.setAttribute('aria-label','关联的平台部署任务');choices.add(new Option('从平台目录选择任务',''));
          candidates.forEach(entry=>choices.add(new Option(`${entry.domain||entry.projectName||entry.siteId} · ${entry.id} · ${entry.status}`,entry.id)));
          choices.addEventListener('change',()=>{provisioning.value=choices.value;provisioning.dispatchEvent(new Event('input'));});form.prepend(choices);
          if(candidates.length===1)provisioning.value=candidates[0].id;
        }
      }catch(error){if(detailVersion!==discoveryDetailVersion)return;result.append(node('p','muted','平台目录暂不可用，可按受权任务 ID 查询发布身份。'));}
      let resolveVersion=0;
      provisioning.addEventListener('input',()=>{resolveVersion++;version.value='';preview.disabled=true;result.replaceChildren();});
      resolve.addEventListener('click',async()=>{
        if(!provisioning.reportValidity())return;
        const current=++resolveVersion;resolve.disabled=true;preview.disabled=true;result.replaceChildren();
        try{
          const registration=await write(`/api/console/discovery/tasks/${id}/platform-registration`,{revision:task.revision,provisioningId:provisioning.value.trim()});
          if(current!==resolveVersion||detailVersion!==discoveryDetailVersion)return;
          version.value=registration.deploymentVersion;profile.value=registration.verificationProfile;preview.disabled=false;
          result.append(node('p','muted','已从受权平台读取发布身份并核对完整目标，确认后可继续预览。'));
        }catch(error){if(current===resolveVersion)result.append(node('p','form-error',error.message));}finally{resolve.disabled=false;}
      });
      form.addEventListener('submit',async event=>{event.preventDefault();preview.disabled=true;result.replaceChildren();const requestVersion=resolveVersion;
        const input={revision:task.revision,provisioningId:provisioning.value.trim(),deploymentVersion:version.value.trim(),verificationProfile:profile.value};
        try{
          const registration=await write(`/api/console/discovery/tasks/${id}/recovery-preview`,input);
          if(requestVersion!==resolveVersion||detailVersion!==discoveryDetailVersion)return;
          result.append(node('pre',null,JSON.stringify(registration,null,2)));
          const confirm=node('button','button primary','确认转交并开始受控监控');
          confirm.addEventListener('click',async()=>{if(requestVersion!==resolveVersion||detailVersion!==discoveryDetailVersion)return;confirm.disabled=true;try{await write(`/api/console/discovery/tasks/${id}/recovery`,input);await refreshRuns();await showDiscovery(id);toast('已关联恢复账本；重复转交不会增加预算');}catch(error){toast(error.message,true);confirm.disabled=false;}});result.append(confirm);
        }catch(error){if(requestVersion===resolveVersion&&detailVersion===discoveryDetailVersion)result.append(node('p','form-error',error.message));}finally{if(requestVersion===resolveVersion&&version.value)preview.disabled=false;}
      });
    }
    detail.append(node('p','discovery-note','集群就绪不等于应用验收成功。对话提供排查建议，实际动作和验证以关联恢复账本为准。'));
    if(!$('discovery-dialog').open)$('discovery-dialog').showModal();
  }
  let memoryEditing = null, memoryTurn = null, memoryRequestId = null, memoryVersion = 0;
  const memoryLabels = {DRAFT:'待确认',VERIFIED:'已验证',ARCHIVED:'已归档'};
  const sourceLabels = {MANUAL:'手工记录',CHAT:'对话思路',RUN:'恢复任务',REFLECTION:'Agent 自动复盘'};
  const memoryScopeLabel=entry=>entry.scopeKey==='DEMO'?'模拟环境':entry.scopeKey?.startsWith('KUBERNETES:')?`真实集群范围 ${entry.scopeKey.slice(11,19)}`:'未绑定来源';
  async function refreshMemories() {
    const version = ++memoryVersion;
    const query = new URLSearchParams({q:$('memory-search').value.trim()});
    if($('memory-filter').value)query.set('status',$('memory-filter').value);
    const result = await api('/api/console/memories?'+query);
    if(version !== memoryVersion || !state.auth)return;
    $('memory-storage').textContent = `${result.items.length} 条记忆 · ${result.storage==='SQLITE'?'任务与配置：SQLite':'任务与配置：PostgreSQL'} · ${result.memorySearch}`;
    $('memory-error').textContent='';$('memory-list').replaceChildren();$('memory-empty').hidden=result.items.length>0;
    result.items.forEach(entry=>{
      const card=node('article','card memory-card'),head=node('div','memory-card-head');
      head.append(node('h2',null,entry.title),node('span','badge '+(entry.status==='VERIFIED'?'':'warning'),memoryLabels[entry.status]));card.append(head);
      card.append(node('p','memory-excerpt',entry.problem),node('p','small muted',`${sourceLabels[entry.sourceType] || entry.sourceType} · ${memoryScopeLabel(entry)} · ${new Date(entry.updatedAt).toLocaleString('zh-CN',{hour12:false})}`));
      if(entry.tags)card.append(node('p','memory-tags',entry.tags));
      const edit=node('button','text-button','查看 / 编辑 →');edit.type='button';edit.addEventListener('click',()=>openMemory(entry));card.append(edit);$('memory-list').append(card);
    });
  }
  function openMemory(entry=null,turn=null) {
    memoryEditing=entry;memoryTurn=turn;memoryRequestId=crypto.randomUUID();$('memory-form').reset();$('memory-form-error').textContent='';
    $('memory-dialog-title').textContent=entry?'编辑排障记忆':'记录排障思路';
    $('memory-origin').textContent=entry?`${sourceLabels[entry.sourceType] || entry.sourceType} · ${memoryScopeLabel(entry)} · ${entry.sourceRef || '运维记录'}`:turn?'来自当前对话 · 默认待确认，请核实后再标记已验证':'手工记录 · 默认待确认';
    $('memory-title').value=entry?.title || turn?.userContent.slice(0,120) || '';
    $('memory-problem').value=entry?.problem || turn?.userContent || '';
    $('memory-cause').value=entry?.cause || '';$('memory-solution').value=entry?.solution || turn?.assistantContent?.slice(0,4000) || '';
    $('memory-verification').value=entry?.verification || '';$('memory-tags').value=entry?.tags || '';$('memory-status').value=entry?.status || 'DRAFT';
    $('memory-status').querySelector('option[value=ARCHIVED]').disabled=!entry;$('memory-dialog').showModal();
  }
  async function saveMemory(event) {
    event.preventDefault();const button=$('save-memory');if(button.disabled)return;button.disabled=true;$('memory-form-error').textContent='';
    try {
      const input={requestId:memoryRequestId,revision:memoryEditing?.revision || 0,title:$('memory-title').value.trim(),problem:$('memory-problem').value.trim(),
        cause:$('memory-cause').value.trim(),solution:$('memory-solution').value.trim(),verification:$('memory-verification').value.trim(),tags:$('memory-tags').value.trim(),
        status:$('memory-status').value,sourceTurnId:memoryTurn?.id || null};
      if(input.status==='VERIFIED'&&!input.verification)throw new Error('标记已验证前，请填写验证依据');
      const saved=await write('/api/console/memories'+(memoryEditing?'/'+memoryEditing.id:''),input,memoryEditing?'PUT':'POST');
      $('memory-dialog').close();toast(`记忆已保存 · ${memoryLabels[saved.status]}`);
      if(state.page==='memories')await refreshMemories();
    }catch(error){$('memory-form-error').textContent=error.message;}
    finally{button.disabled=false;}
  }
  async function refreshReadiness() {
    const version=state.authVersion;
    try {
      const result=await api('/api/console/readiness');
      if(version!==state.authVersion||!state.auth)return;
      const states={CONFIGURED:'已配置',UNCONFIGURED:'未配置',RULES:'规则分析',DISABLED:'未启用',UNCHECKED:'待观测',OBSERVED:'历史观测',DEGRADED:'需处理',DEMO:'演示'};
      $('readiness-note').textContent=result.note;$('readiness-checks').replaceChildren();
      result.checks.forEach(check=>{
        const row=node('div','readiness-row');
        row.append(node('strong',null,check.label),node('span','badge '+(['DEGRADED','UNCONFIGURED'].includes(check.state)?'warning':'neutral'),states[check.state] || check.state),
          node('p','small muted',check.detail+(check.observedAt?` 上次观测：${new Date(check.observedAt).toLocaleString('zh-CN',{hour12:false})}`:'')));
        $('readiness-checks').append(row);
      });
    }catch(error){if(version===state.authVersion&&state.auth){$('readiness-checks').replaceChildren();$('readiness-note').textContent='状态读取失败：'+error.message;}}
  }
  $('refresh-readiness').addEventListener('click',refreshReadiness);
  async function refreshRuns() {
    if(!state.auth || refreshing) return;refreshing=true;
    try{state.data.runs=await api('/api/runs?limit=30&offset=0');renderRuns();updateContextOptions();await Promise.all([refreshDiscovery(),refreshPlatformCatalog(),refreshReadiness()]);}
    finally{refreshing=false;}
  }
  $('login-form').addEventListener('submit',async event=>{
    event.preventDefault();const button=event.submitter;button.disabled=true;$('login-error').textContent='';
    try{
      const values=new FormData(event.currentTarget);const bytes=new TextEncoder().encode(values.get('username')+':'+values.get('password'));
      state.authVersion++;
      state.auth='Basic '+btoa(Array.from(bytes,byte=>String.fromCharCode(byte)).join(''));
      state.data=await api('/api/console/bootstrap');$('login-form').elements.password.value='';
      $('login-panel').hidden=true;$('app').hidden=false;$('account-name').textContent=state.data.username;
      $('runtime-mode').textContent=state.data.mode==='DEMO'?'演示恢复 · 支持只读调查':'Kubernetes · 按任务校验';
      state.session=null;fillLlm();renderConnections();renderRuns();renderSessions();updateContextOptions();newChat();showPage('chat');
    }catch(error){state.auth='';$('login-error').textContent=error.message;}
    finally{button.disabled=false;}
  });
  $('logout').addEventListener('click',signOut);
  $('add-memory').addEventListener('click',()=>openMemory());
  $('memory-form').addEventListener('submit',saveMemory);
  $('memory-search-form').addEventListener('submit',event=>{event.preventDefault();refreshMemories().catch(error=>{$('memory-error').textContent=error.message;});});
  $('memory-filter').addEventListener('change',()=>refreshMemories().catch(error=>{$('memory-error').textContent=error.message;}));
  $('close-memory').addEventListener('click',()=>$('memory-dialog').close());$('cancel-memory').addEventListener('click',()=>$('memory-dialog').close());
  $('mobile-logout').addEventListener('click',signOut);
  document.querySelectorAll('[data-page]').forEach(el=>el.addEventListener('click',()=>showPage(el.dataset.page)));
  document.querySelectorAll('[data-prompt]').forEach(el=>el.addEventListener('click',()=>{$('chat-input').value=el.dataset.prompt;$('chat-input').focus();}));
  $('go-connections').addEventListener('click',()=>showPage('connections'));$('go-llm').addEventListener('click',()=>showPage('llm'));
  $('new-chat').addEventListener('click',newChat);$('chat-connection').addEventListener('change',()=>{$('include-cluster').checked=Boolean($('chat-connection').value);updateContext();});
  $('include-cluster').addEventListener('change',updateContext);
  $('session-select').addEventListener('change',event=>loadSession(event.target.value).catch(error=>toast(error.message,true)));
  $('refresh-chat').addEventListener('click',()=>{if(state.session)loadSession(state.session.id).catch(error=>toast(error.message,true));});
  $('chat-form').addEventListener('submit',sendMessage);
  $('chat-input').addEventListener('keydown',event=>{if(event.key==='Enter'&&!event.shiftKey&&!event.isComposing){event.preventDefault();if(!state.sending)$('chat-form').requestSubmit();}});
  $('llm-form').addEventListener('submit',saveLlm);
  $('llm-form').addEventListener('input',event=>{
    $('llm-test').disabled=true;
    if (['llm-provider','llm-url','llm-key','llm-clear-key'].includes(event.target.id)) resetModelList();
    if (event.target.id === 'llm-model') $('llm-model-list').value = event.target.value;
  });
  $('llm-fetch-models').addEventListener('click',fetchModelList);
  $('llm-model-list').addEventListener('change',()=>{
    if (!$('llm-model-list').value) return;
    $('llm-model').value = $('llm-model-list').value;
    $('llm-model').dispatchEvent(new Event('input',{bubbles:true}));
  });
  $('llm-provider').addEventListener('change',()=>{
    const url=$('llm-url');if(['https://dashscope.aliyuncs.com','https://dashscope.aliyuncs.com/compatible-mode/v1'].includes(url.value))
      url.value=$('llm-provider').value==='DASHSCOPE'?'https://dashscope.aliyuncs.com':'https://dashscope.aliyuncs.com/compatible-mode/v1';
    resetModelList();protocolHelp();$('llm-test').disabled=true;
  });
  $('llm-test').addEventListener('click',async()=>{
    $('llm-test').disabled=true;$('llm-error').textContent='';
    try{const result=await write('/api/console/llm/test');toast(`${result.message} · ${result.model}`);}
    catch(error){$('llm-error').textContent=error.message;}
    finally{$('llm-test').disabled=false;}
  });
  $('add-connection').addEventListener('click',()=>openConnection());$('first-connection').addEventListener('click',()=>openConnection());
  $('close-connection').addEventListener('click',()=>$('connection-dialog').close());$('cancel-connection').addEventListener('click',()=>$('connection-dialog').close());
  $('connection-dialog').addEventListener('close',()=>{$('connection-token').value='';});
  $('connection-form').addEventListener('submit',saveConnection);$('close-run').addEventListener('click',()=>$('run-dialog').close());
  $('refresh-runs').addEventListener('click',()=>refreshRuns().catch(error=>toast(error.message,true)));
  $('close-discovery').addEventListener('click',()=>$('discovery-dialog').close());
  ['discovery-connection','discovery-state'].forEach(id=>$(id).addEventListener('change',()=>{discoveryOffset=0;refreshDiscovery().catch(error=>{$('discovery-error').textContent=error.message;});}));
  $('discovery-previous').addEventListener('click',()=>{discoveryOffset=Math.max(0,discoveryOffset-30);refreshDiscovery().catch(error=>{$('discovery-error').textContent=error.message;});});
  $('discovery-next').addEventListener('click',()=>{discoveryOffset+=30;refreshDiscovery().catch(error=>{$('discovery-error').textContent=error.message;});});
  $('start-demo').addEventListener('click',async()=>{
    $('start-demo').disabled=true;
    try{await write('/api/runs',{requestKey:'ui_'+crypto.randomUUID().replaceAll('-',''),provisioningId:'9007199254740993',targets:[{kind:'ProjectRuntime',namespace:'agent-demo',name:'runtime-demo',uid:'demo-runtime-uid',generation:1}]});await refreshRuns();toast('演示任务已创建，Agent 将自动监控和恢复');}
    catch(error){toast(error.message,true);}finally{$('start-demo').disabled=false;}
  });
  setInterval(()=>{if(state.auth&&state.page==='runs')refreshRuns().catch(error=>toast(error.message,true));},5000);
})();
