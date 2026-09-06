import { createEvaluation } from './evaluation.js';
import { createModelManager } from './models.js';

const $ = id => document.getElementById(id);
export function node(tag, text, className = '') {
  const value = document.createElement(tag);
  if (text != null) value.textContent = String(text);
  if (className) value.className = className;
  return value;
}
export const jsonPost = body => ({ method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify(body) });
const labels = { QUEUED: '排队中', RUNNING: '处理中', SUCCEEDED: '已完成', SKIPPED: '内容未变', FAILED: '失败', CANCELLED: '已取消' };
const stages = { WAITING: '等待处理', STARTING: '准备处理', PARSING: '解析文档', EMBEDDING: '生成向量', PUBLISHING: '发布索引', FINISHED: '处理结束', UNCHANGED: '无需更新' };

export function createWorkbench(ctx) {
  const { state, requestJson: request, apiFetch, showToast, loadFiles, loadFolders, formatBytes, formatTime } = ctx;
  const fail = error => { if (error.name !== 'AbortError') showToast(error.message || '操作失败', 'error'); };
  const selection = new Set();
  const views = {library: 'workspaceCard', code: 'codePanel', retrieval: 'retrievalPanel', jobs: 'jobsPanel', evaluation: 'evaluationPanel', system: 'systemPanel'};
  let view = 'library', poll, jobsPage = 1, jobSequence = 0, lastActive = 0, pollBusy = false;
  let retrievalSequence = 0, retrievalAbort, report, evidenceSequence = 0;
  let previewId, previewRevision, previewMode = 'source', previewPage = 0, previewSequence = 0;
  let authEpoch = 0, wasAuthenticated = false;
  const evaluation = createEvaluation({ request, state, showToast });
  const models = createModelManager({ request, state, showToast, onChanged: () => refreshSystem().catch(fail) });

  function switchView(next) {
    if (!views[next]) return;
    view = next;
    Object.entries(views).forEach(([key, id]) => $(id).classList.toggle('hidden', key !== next));
    document.querySelectorAll('[data-view]').forEach(button => {
      if (button.dataset.view === next) button.setAttribute('aria-current', 'page');
      else button.removeAttribute('aria-current');
    });
    if (!state.authenticated) return;
    if (next === 'library') loadFolders().then(loadFiles).catch(fail);
    if (next === 'jobs') refreshJobs().catch(fail);
    if (next === 'system') { refreshSystem().catch(fail); models.load(); }
    if (next === 'evaluation') evaluation.load().catch(fail);
  }
  document.querySelectorAll('[data-view]').forEach(button => button.addEventListener('click', () => switchView(button.dataset.view)));

  function updateScopes() {
    for (const select of document.querySelectorAll('.scope-select')) {
      const current = select.value;
      select.replaceChildren(new Option('全部目录', ''));
      select.add(new Option('根目录文件', '0'));
      [...state.folderMap.values()].sort((a, b) => a.path.localeCompare(b.path, 'zh-CN'))
        .forEach(folder => select.add(new Option(folder.path, String(folder.id))));
      if ([...select.options].some(option => option.value === current)) select.value = current;
    }
  }

  function updateSelection() {
    $('batchBar').classList.toggle('hidden', !selection.size);
    $('selectionCount').textContent = `已选 ${selection.size} 项`;
    const boxes = [...document.querySelectorAll('[data-file-select]')];
    boxes.forEach(box => { box.checked = selection.has(Number(box.dataset.fileSelect)); });
    $('selectPage').checked = boxes.length > 0 && selection.size === boxes.length;
    $('selectPage').indeterminate = selection.size > 0 && selection.size < boxes.length;
  }
  function clearSelection() { selection.clear(); updateSelection(); }
  $('fileTable').addEventListener('change', event => {
    if (!event.target.matches('[data-file-select]')) return;
    const id = Number(event.target.dataset.fileSelect);
    event.target.checked ? selection.add(id) : selection.delete(id);
    updateSelection();
  });
  $('selectPage').addEventListener('change', event => {
    selection.clear();
    if (event.target.checked) state.fileMap.forEach(file => selection.add(file.id));
    updateSelection();
  });
  let filterTimer;
  const filter = () => { state.page = 1; loadFiles().catch(fail); };
  $('fileQuery').addEventListener('input', () => { clearTimeout(filterTimer); filterTimer = setTimeout(filter, 280); });
  ['fileStatus', 'fileType', 'fileSort'].forEach(id => $(id).addEventListener('change', filter));
  $('pageSize').addEventListener('change', () => { state.size = Number($('pageSize').value); filter(); });

  function summarize(results = [], action = '操作') {
    const failed = results.filter(result => !result.success);
    showToast(`${action}：成功 ${results.length - failed.length}，失败 ${failed.length}` +
      (failed.length ? '\n' + failed.slice(0, 3).map(result => `${result.fileName || result.fileId || ''}：${result.message}`).join('\n') : ''),
      failed.length ? 'warning' : 'success', failed.length ? 9000 : 4500);
  }
  async function queue(fileIds, all) {
    const body = await request('/api/index/jobs', jsonPost({fileIds, all, force: true}));
    summarize(body.data, '任务提交');
    if (body.message) showToast(body.message, 'warning', 8000);
    jobsSubmitted();
  }
  async function batchAction(action) {
    const fileIds = [...selection];
    if (!fileIds.length) return;
    if (action === 'delete' && !confirm(`删除选中的 ${fileIds.length} 个文件及其全部索引？此操作无法撤销。`)) return;
    const buttons = $('batchBar').querySelectorAll('button');
    buttons.forEach(button => { button.disabled = true; });
    try {
      if (action === 'rebuild') await queue(fileIds, false);
      else {
        const body = await request('/api/files/batch', jsonPost({fileIds, action, knowledgeType: $('batchType').value}));
        summarize(body.data, action === 'delete' ? '删除' : '分类更新');
        await loadFolders(); await loadFiles();
      }
    } catch (error) { fail(error); }
    finally { buttons.forEach(button => { button.disabled = false; }); }
  }
  $('batchRebuild').onclick = () => batchAction('rebuild');
  $('batchDelete').onclick = () => batchAction('delete');
  $('batchClassify').onclick = () => batchAction('classify');
  $('batchMove').onclick = () => ctx.openMoveModal([...selection]);

  function upload(form, onProgress) {
    return new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest();
      xhr.open('POST', '/api/index/jobs/upload');
      xhr.withCredentials = true;
      xhr.timeout = 180000;
      if (state.csrfToken) xhr.setRequestHeader(state.csrfHeaderName, state.csrfToken);
      xhr.upload.onprogress = event => { if (event.lengthComputable) onProgress(Math.round(event.loaded / event.total * 100)); };
      xhr.onerror = () => reject(new Error('网络中断，请到任务中心确认哪些文件已提交'));
      xhr.ontimeout = () => reject(new Error('上传超时，请到任务中心确认提交情况后重试'));
      xhr.onload = async () => {
        try {
          await ctx.handleAuthenticationFailure(xhr.status);
          const body = JSON.parse(xhr.responseText);
          if (xhr.status < 200 || xhr.status >= 300 || !body.success) throw new Error(body.message || '文件提交失败');
          resolve(body);
        } catch (error) { reject(error instanceof SyntaxError ? new Error(`上传失败（HTTP ${xhr.status}）`) : error); }
      };
      xhr.send(form);
    });
  }
  const dock = document.querySelector('.ingest-dock');
  dock.addEventListener('dragover', event => { event.preventDefault(); dock.classList.add('drag-over'); });
  dock.addEventListener('dragleave', event => { if (!dock.contains(event.relatedTarget)) dock.classList.remove('drag-over'); });
  dock.addEventListener('drop', event => {
    event.preventDefault(); dock.classList.remove('drag-over');
    if (state.uploadBusy || !state.authenticated) return;
    const files = [...event.dataTransfer.files];
    const accepted = files.filter(file => ctx.supportedFile(file.name));
    if (!accepted.length) return showToast('拖入支持的文件；上传完整目录请使用“选择文件夹”', 'warning');
    ctx.uploadFiles(accepted, false, files.length - accepted.length).catch(fail);
  });

  async function download(url) {
    await request('/api/auth/status').then(body => { if (!body.authenticated) throw new Error('请先登录'); });
    // 交给浏览器下载管理器流式写入，避免完整 ZIP 在 JavaScript 内存中缓冲。
    const link = node('a'); link.href = url; link.download = ''; link.target = '_blank'; link.rel = 'noopener';
    document.body.append(link); link.click(); link.remove();
  }
  function jobsSubmitted() { refreshJobs().catch(fail); }
  async function refreshJobs(silent = false) {
    if (!state.authenticated) return;
    const sequence = ++jobSequence;
    const params = new URLSearchParams({page: jobsPage, size: 25});
    if ($('jobStatus').value) params.set('status', $('jobStatus').value);
    const body = await request(`/api/index/jobs?${params}`);
    if (sequence !== jobSequence || !state.authenticated) return;
    const active = body.activeCount || 0;
    $('jobBadge').textContent = String(active);
    $('jobsSummary').textContent = `${active} 个待完成任务 · ${new Date().toLocaleTimeString('zh-CN')} 更新`;
    $('jobsPage').textContent = `第 ${jobsPage} 页`;
    $('jobsPrev').disabled = jobsPage <= 1;
    $('jobsNext').disabled = body.data.length < 25;
    // 用户正在操作任务按钮时保留焦点，下一轮轮询再更新列表。
    if (!silent || !$('jobsList').contains(document.activeElement)) renderJobs(body.data);
    if (lastActive > 0 && active < lastActive && view === 'library') { await loadFolders(); await loadFiles(); }
    lastActive = active;
  }
  function renderJobs(jobs) {
    $('jobsList').replaceChildren();
    if (!jobs.length) $('jobsList').append(node('div', '暂无符合条件的任务。上传文件或重建索引后可在此跟踪。', 'empty-state'));
    jobs.forEach(job => {
      const row = node('article', null, 'job-row');
      const main = node('div', null, 'job-main');
      const title = node('button', job.fileName || `文件 #${job.fileId}`, 'text-link');
      title.onclick = () => preview(job.fileId);
      main.append(title, node('div', `${formatTime(job.createdAt)} · 尝试 ${job.attempts} 次 · ${stages[job.stage] || job.stage}`, 'source-meta'));
      if (job.message) main.append(node('div', job.message, job.status === 'FAILED' ? 'job-error' : 'source-meta'));
      const status = node('span', labels[job.status] || job.status, `job-state ${job.status.toLowerCase()}`);
      const progress = node('div', null, 'job-progress');
      if (job.totalChunks > 0) {
        const bar = node('progress'); bar.max = job.totalChunks; bar.value = job.completedChunks;
        bar.setAttribute('aria-label', `${job.fileName} 向量处理进度`);
        progress.append(bar, node('small', `${job.completedChunks} / ${job.totalChunks} 切片`));
      }
      const actions = node('div', null, 'job-actions');
      const active = ['QUEUED', 'RUNNING'].includes(job.status);
      if (active || ['FAILED', 'CANCELLED'].includes(job.status)) {
        const button = node('button', active ? '取消' : '重试', 'btn ghost small');
        button.onclick = async () => {
          button.disabled = true;
          try { await request(`/api/index/jobs/${job.id}/${active ? 'cancel' : 'retry'}`, jsonPost({})); await refreshJobs(); }
          catch (error) { fail(error); button.disabled = false; }
        };
        actions.append(button);
      }
      row.append(main, status, progress, actions); $('jobsList').append(row);
    });
  }
  $('refreshJobs').onclick = () => refreshJobs().catch(fail);
  $('jobStatus').onchange = () => { jobsPage = 1; refreshJobs().catch(fail); };
  $('jobsPrev').onclick = () => { jobsPage = Math.max(1, jobsPage - 1); refreshJobs().catch(fail); };
  $('jobsNext').onclick = () => { jobsPage++; refreshJobs().catch(fail); };
  async function pollTasks() {
    if (!state.authenticated || document.hidden || pollBusy || state.uploadBusy) return;
    pollBusy = true;
    try { await refreshJobs(true); } catch (error) { $('jobsSummary').textContent = `更新失败：${error.message}，稍后自动重试`; }
    finally { pollBusy = false; }
  }

  function authChanged(authenticated) {
    if (authenticated === wasAuthenticated) return;
    wasAuthenticated = authenticated;
    authEpoch++;
    clearInterval(poll);
    if (authenticated) { pollTasks(); poll = setInterval(pollTasks, 4000); if (view !== 'library') switchView(view); }
    else {
      jobSequence++; retrievalSequence++; evidenceSequence++; previewSequence++;
      retrievalAbort?.abort(); report = null; evaluation.clear(); models.clear();
      $('retrieveBtn').disabled = false;
      $('jobsList').replaceChildren(); $('jobBadge').textContent = '0';
      $('retrievalHits').replaceChildren(); $('evidenceText').textContent = '登录后开始检索';
      $('evidenceMeta').textContent = ''; $('retrievalSummary').classList.add('hidden');
      $('copyContext').disabled = true; $('showContext').disabled = true;
      $('systemMetrics').replaceChildren(); $('systemConfig').replaceChildren(); $('mcpUrl').textContent = '';
      document.querySelectorAll('.modal').forEach(modal => modal.classList.add('hidden'));
      $('previewText').textContent = ''; $('previewChunks').replaceChildren();
    }
  }
  document.addEventListener('visibilitychange', () => { if (!document.hidden) pollTasks(); });

  $('retrievalForm').onsubmit = async event => {
    event.preventDefault();
    const sequence = ++retrievalSequence;
    retrievalAbort?.abort(); retrievalAbort = new AbortController();
    evidenceSequence++; report = null;
    $('retrieveBtn').disabled = true; $('copyContext').disabled = true; $('showContext').disabled = true;
    $('retrievalStatus').textContent = '正在检索并组织上下文…';
    $('retrievalHits').replaceChildren(node('div', '检索中…', 'empty-state'));
    $('evidenceText').textContent = ''; $('evidenceMeta').textContent = ''; $('retrievalSummary').classList.add('hidden');
    try {
      const body = await request('/api/search', {...jsonPost({query: $('retrievalQuery').value,
        topK: Number($('retrievalTopK').value), folderId: $('retrievalFolder').value === '' ? null : Number($('retrievalFolder').value),
        knowledgeType: $('retrievalType').value, mode: $('retrievalMode').value, minSimilarity: Number($('retrievalThreshold').value)}), signal: retrievalAbort.signal});
      if (sequence !== retrievalSequence || !state.authenticated) return;
      report = body.data;
      $('retrievalStatus').textContent = report.hits.length ? `命中 ${report.hits.length} 个片段${report.contextTruncated ? ' · 上下文已按预算截断，可读取完整来源' : ''}` : '未找到符合条件的片段，请调整问题、目录或阈值。';
      $('retrievalSummary').classList.remove('hidden');
      $('retrievalSummary').replaceChildren(metric('候选片段', report.candidateCount), metric('查询向量', `${report.embeddingMillis} ms`), metric('数据库检索', `${report.retrievalMillis} ms`), metric('总耗时', `${report.totalMillis} ms`));
      $('retrievalHits').replaceChildren();
      if (!report.hits.length) $('retrievalHits').append(node('div', '没有可引用的证据。当前查询不会生成上下文。', 'empty-state'));
      report.hits.forEach((hit, i) => {
        const button = node('button', null, 'hit-card');
        const result = hit.result;
        button.append(node('span', `#${i + 1} · ${hit.channels.join(' + ')} · ${report.mode === 'keyword' ? '融合排名分 ' + hit.rankScore.toFixed(4) : '相似度 ' + result.similarity.toFixed(3)}`, 'source-meta'),
          node('strong', result.fileName), node('span', result.content, 'hit-excerpt'), node('small', `切片 ${result.chunkIndex} · 版本 ${result.revision}${result.truncated ? ' · 片段已截断' : ''}`));
        button.onclick = () => showEvidence(result, button);
        $('retrievalHits').append(button);
      });
      $('copyContext').disabled = !report.context; $('showContext').disabled = !report.context;
      $('evidenceText').textContent = report.context || '无可用上下文';
      $('evidenceMeta').textContent = '最终上下文 · 包含来源引用';
    } catch (error) { if (sequence === retrievalSequence && error.name !== 'AbortError') { $('retrievalStatus').textContent = error.message; $('retrievalHits').replaceChildren(node('div', '检索未完成，请检查服务状态后重试', 'empty-state')); } }
    finally { if (sequence === retrievalSequence) $('retrieveBtn').disabled = false; }
  };
  $('retrievalMode').onchange = () => { $('retrievalThreshold').disabled = $('retrievalMode').value === 'keyword'; };
  async function showEvidence(result, button) {
    const sequence = ++evidenceSequence;
    document.querySelectorAll('.hit-card').forEach(card => card.classList.toggle('selected', card === button));
    $('evidenceMeta').textContent = result.sourceUri;
    $('evidenceText').textContent = '正在读取完整切片…';
    try {
      const body = await request(`/api/files/${result.fileId}/chunks/${result.chunkIndex}/source?revision=${result.revision}`);
      if (sequence !== evidenceSequence) return;
      $('evidenceText').textContent = body.data.content;
      $('evidenceMeta').textContent = `${result.sourceUri}\n${metadataText(result.metadata)}${body.data.truncated ? '\n来源超过阅读上限，当前显示前 20000 字符；可在知识库分段预览或下载原文。' : ''}`;
    } catch (error) { if (sequence === evidenceSequence) $('evidenceText').textContent = error.message; }
  }
  $('showContext').onclick = () => { evidenceSequence++; $('evidenceMeta').textContent = '最终上下文'; $('evidenceText').textContent = report?.context || ''; };
  $('copyContext').onclick = () => copy(report?.context || '');
  async function copy(value) {
    if (!value) return;
    try { await navigator.clipboard.writeText(value); showToast('已复制', 'success'); }
    catch { showToast('浏览器不允许自动复制，请选中文本后复制', 'warning'); }
  }

  async function preview(id) {
    previewId = id; previewPage = 0; previewMode = 'source';
    const sequence = ++previewSequence;
    $('previewModal').classList.remove('hidden'); $('previewTitle').textContent = '文件详情';
    $('previewMeta').textContent = '正在加载文件信息…'; $('previewText').textContent = '';
    $('previewChunks').replaceChildren();
    try {
      const body = await request(`/api/files/${id}`);
      if (sequence !== previewSequence) return;
      const file = body.data; previewRevision = file.revision;
      $('previewTitle').textContent = file.filename;
      $('previewMeta').textContent = `ID ${file.id} · 版本 ${file.revision} · ${formatBytes(file.size)} · ${file.embeddingModel || '尚无模型记录'}\n${file.errorMessage || '无索引错误'}`;
      await loadPreview();
    } catch (error) { if (sequence === previewSequence) $('previewMeta').textContent = error.message; }
  }
  async function loadPreview() {
    const sequence = ++previewSequence;
    const mode = previewMode, page = previewPage, id = previewId;
    $('previewPrev').disabled = true; $('previewNext').disabled = true;
    $('previewText').classList.toggle('hidden', mode !== 'source'); $('previewChunks').classList.toggle('hidden', mode === 'source');
    $('previewSourceTab').classList.toggle('active', mode === 'source'); $('previewChunksTab').classList.toggle('active', mode !== 'source');
    try {
      const body = await request(mode === 'source' ? `/api/files/${id}/preview?offset=${page * 8000}&maxChars=8000` : `/api/files/${id}/chunk-items?page=${page + 1}&size=5`);
      if (sequence !== previewSequence) return;
      if (body.revision !== previewRevision) throw new Error('文件索引版本已更新，请关闭详情后重新打开');
      if (mode === 'source') {
        $('previewText').textContent = body.data || '尚无解析原文，请查看索引任务状态。';
        $('previewPage').textContent = `${body.totalChars ? page * 8000 + 1 : 0}–${Math.min((page + 1) * 8000, body.totalChars)} / ${body.totalChars} 字符`;
        $('previewNext').disabled = !body.hasMore;
      } else {
        $('previewChunks').replaceChildren();
        if (!body.data.length) $('previewChunks').append(node('div', '没有更多切片', 'empty-state'));
        body.data.forEach(chunk => {
          const item = node('article', null, 'chunk-item');
          let metadata = {}; try { metadata = JSON.parse(chunk.metadataJson || '{}'); } catch { /* 原始文本仍可查看 */ }
          item.append(node('strong', `切片 ${chunk.chunkIndex}${chunk.startLine ? ` · L${chunk.startLine}–L${chunk.endLine}` : ''}${chunk.truncated ? ' · 当前显示前 20000 字符' : ''}`), node('div', metadataText(metadata), 'source-meta'), node('pre', chunk.content));
          $('previewChunks').append(item);
        });
        $('previewPage').textContent = `第 ${page + 1} 页切片`;
        $('previewNext').disabled = body.data.length < 5;
      }
      $('previewPrev').disabled = page <= 0;
    } catch (error) { if (sequence === previewSequence) { $('previewPage').textContent = error.message; $('previewText').textContent = ''; $('previewChunks').replaceChildren(); } }
  }
  $('previewSourceTab').onclick = () => { previewMode = 'source'; previewPage = 0; loadPreview(); };
  $('previewChunksTab').onclick = () => { previewMode = 'chunks'; previewPage = 0; loadPreview(); };
  $('previewPrev').onclick = () => { previewPage = Math.max(0, previewPage - 1); loadPreview(); };
  $('previewNext').onclick = () => { previewPage++; loadPreview(); };

  function metric(label, value) { const item = node('div', null, 'metric'); item.append(node('span', label), node('strong', value)); return item; }
  async function refreshSystem() {
    const epoch = authEpoch;
    $('systemStatus').textContent = '正在读取服务状态…';
    const {data} = await request('/api/system/status');
    if (epoch !== authEpoch || !state.authenticated) return;
    const counts = data.counts;
    $('systemStatus').textContent = `数据库可用 · ${new Date().toLocaleTimeString('zh-CN')} 更新 · 模型连通性以实际检索结果为准 · ${data.retentionDays ? `已结束任务与评测运行保留 ${data.retentionDays} 天` : '运行记录不自动清理'}`;
    $('systemMetrics').replaceChildren(metric('文件', counts.files), metric('已索引', counts.indexed), metric('索引失败', counts.failed), metric('切片', counts.chunks), metric('原文件总量', formatBytes(counts.bytes)), metric('近期检索 P95', `${Math.round(data.search.p95Millis)} ms`));
    $('systemConfig').replaceChildren();
    const entries = [['模型', data.model], ['向量维度', data.dimension ?? '自动检测'], ['切片 / 重叠', `${data.chunkSize} / ${data.overlap} 字符`], ['单文档解析上限', `${data.maxTextChars.toLocaleString()} 字符`], ['索引工作线程', data.workers], ['向量请求批量', data.batchSize], ['本次运行检索请求', data.search.count], ['本次运行模型请求', data.embedding.count], ['模型请求均耗时', `${Math.round(data.embedding.averageMillis)} ms`], ['向量缓存命中', data.embeddingCacheHits], ['模型 HTTP 错误', data.embeddingErrors]];
    entries.forEach(([label, value]) => $('systemConfig').append(node('dt', label), node('dd', value)));
    $('mcpUrl').textContent = new URL(data.mcpEndpoint, location.origin).href;
  }
  $('refreshSystem').onclick = () => { models.load(); refreshSystem().catch(error => { $('systemStatus').textContent = error.message; }); };
  $('copyMcp').onclick = () => copy($('mcpUrl').textContent);

  // 统一现有对话框的键盘行为：打开后聚焦、Tab 循环、关闭后恢复焦点。
  let activeModal, previousFocus;
  const focusable = modal => [...modal.querySelectorAll('button, input, select, textarea, a[href]')].filter(el => !el.disabled && el.getClientRects().length);
  const observer = new MutationObserver(() => {
    const modal = [...document.querySelectorAll('.modal')].reverse().find(el => !el.classList.contains('hidden'));
    if (modal === activeModal) return;
    if (modal) { if (!activeModal) previousFocus = document.activeElement; activeModal = modal; focusable(modal)[0]?.focus(); }
    else { activeModal = null; previousFocus?.focus(); }
  });
  document.querySelectorAll('.modal').forEach(modal => observer.observe(modal, {attributes: true, attributeFilter: ['class']}));
  document.addEventListener('keydown', event => {
    if (event.key !== 'Tab' || !activeModal) return;
    const options = focusable(activeModal); if (!options.length) return;
    if (event.shiftKey && document.activeElement === options[0]) { event.preventDefault(); options.at(-1).focus(); }
    else if (!event.shiftKey && document.activeElement === options.at(-1)) { event.preventDefault(); options[0].focus(); }
  });
  return { switchView, updateScopes, clearSelection, upload, download, queue, summarize, jobsSubmitted, preview, authChanged };
}

function metadataText(metadata = {}) {
  return Object.entries(metadata).filter(([, value]) => value != null && typeof value !== 'object')
    .map(([key, value]) => `${({page: '页码', heading: '章节', sheet: '工作表', row: '行', startLine: '起始行', endLine: '结束行'})[key] || key}: ${value}`).join(' · ');
}
