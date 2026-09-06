const $ = id => document.getElementById(id);
const post = body => ({ method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify(body) });
const stages = { WAITING: '等待工作器', EMBEDDING: '生成新模型向量', CATCHING_UP: '补齐期间新增的切片', BUILDING_INDEX: '构建向量检索索引', FINISHED: '处理结束' };
const statuses = { QUEUED: '排队中', RUNNING: '重建中', FAILED: '失败，可重试', CANCELLED: '已取消', SUCCEEDED: '已切换' };
const pending = run => run && ['QUEUED', 'RUNNING', 'FAILED'].includes(run.status);
const indexText = dimension => dimension > 2000 ? '精确检索（大库查询可能较慢）' : 'HNSW 加速检索';

export function createModelManager({ request, state, showToast, onChanged }) {
  let data, verified, busy = false, polling = false, catalogLoaded = false, timer, epoch = 0;
  function message(id, text, type = '') { $(id).textContent = text; $(id).dataset.type = type; }
  function config() {
    return { modelName: $('modelSelect').value,
      outputDimension: $('modelDimensionMode').value === 'custom' ? Number($('modelDimension').value) : null,
      queryInstruction: $('modelInstruction').value.trim() };
  }
  function controls() {
    $('modelFields').disabled = busy || !data || !state.authenticated;
    $('modelDimension').disabled = $('modelDimensionMode').value !== 'custom';
    $('modelProbe').disabled = busy || !$('modelSelect').value;
    $('modelSwitch').disabled = busy || !verified || !data?.enabled || !!pending(data?.run);
    $('modelRetry').disabled = busy || !data?.enabled;
    $('modelCancel').disabled = busy;
  }
  function invalidate() {
    verified = null;
    message('modelProbeResult', '设置已变化，请验证模型后再切换。');
    controls();
  }
  $('modelSelect').onchange = invalidate;
  $('modelInstruction').oninput = invalidate;
  $('modelDimension').oninput = invalidate;
  $('modelDimensionMode').onchange = () => {
    const custom = $('modelDimensionMode').value === 'custom';
    $('modelDimensionLabel').classList.toggle('hidden', !custom);
    $('modelDimension').required = custom;
    invalidate();
  };

  function render() {
    const { active, run, target } = data;
    $('modelCurrentName').textContent = active.modelName;
    $('modelCurrentDetails').textContent = `${active.dimension} 维 · ${indexText(active.dimension)} · 版本 #${active.id}`;
    message('modelStatusMessage', !data.enabled ? '后台索引工作器已禁用，当前无法提交重建。' :
      pending(run) ? '已有切换任务。完成、重试或取消后可发起下一次切换。' : `全库 ${data.totalChunks.toLocaleString()} 个已发布切片。`, !data.enabled ? 'warning' : '');
    $('modelRebuild').classList.toggle('hidden', !run);
    if (run) {
      const total = Number(run.totalChunks), done = Number(run.completedChunks);
      $('modelRunStatus').textContent = statuses[run.status] || run.status;
      $('modelRunStatus').dataset.status = run.status;
      $('modelRunTarget').textContent = `目标：${target?.modelName || ''} · ${target?.dimension || ''} 维 · ${indexText(target?.dimension)}`;
      $('modelRunProgress').value = run.status === 'SUCCEEDED' ? 100 : total ? Math.min(100, done / total * 100) : 0;
      message('modelRunSummary', `${stages[run.stage] || run.stage} · ${done.toLocaleString()} / ${total.toLocaleString()} 个切片${run.status === 'RUNNING' ? ' · 自动更新中' : ''}`);
      message('modelRunMessage', run.message || (run.stage === 'BUILDING_INDEX' ? '向量已生成，正在建立检索索引，完成后自动切换。' : '切片数量会随文件更新调整。'), run.status === 'FAILED' ? 'error' : '');
      $('modelRetry').classList.toggle('hidden', run.status !== 'FAILED');
      $('modelCancel').classList.toggle('hidden', !pending(run));
    }
    controls();
  }

  async function status() {
    if (polling || !state.authenticated) return;
    polling = true;
    const token = epoch;
    try {
      const {data: latest} = await request('/api/embedding/status');
      if (token !== epoch || !state.authenticated) return;
      const changed = data && data.active.id !== latest.active.id;
      data = latest; render();
      if (changed) { verified = null; controls(); onChanged?.(); }
    } catch (error) {
      if (token === epoch) message('modelStatusMessage', `状态更新失败：${error.message}，稍后自动重试。`, 'error');
    } finally { if (token === epoch) polling = false; }
  }

  async function catalog() {
    const token = epoch;
    const {data: list} = await request('/api/embedding/models');
    if (token !== epoch || !state.authenticated) return;
    const selected = $('modelSelect').value;
    $('modelSelect').replaceChildren(new Option('选择一个已安装模型', ''));
    list.forEach(model => $('modelSelect').add(new Option(model.name, model.name)));
    if (list.some(model => model.name === selected)) $('modelSelect').value = selected;
    catalogLoaded = true; verified = null;
    message('modelProbeResult', list.length ? `已读取 ${list.length} 个模型。请选择目标模型并验证向量能力。` : 'Ollama 尚未安装模型，请先在服务端安装 embedding 模型。');
    controls();
  }

  async function action(task) {
    if (busy || !state.authenticated) return;
    busy = true; controls();
    const token = epoch;
    try { await task(token); }
    catch (error) { if (token === epoch) message('modelProbeResult', error.message || '操作失败', 'error'); }
    finally { if (token === epoch) { busy = false; controls(); } }
  }
  $('modelRefreshCatalog').onclick = () => action(catalog);
  $('modelForm').onsubmit = event => {
    event.preventDefault();
    action(async token => {
      const settings = config(); verified = null;
      message('modelProbeResult', '正在验证向量能力、版本与输出维度，首次加载模型可能需要一些时间…');
      const {data: model} = await request('/api/embedding/probe', post(settings));
      if (token !== epoch || !state.authenticated) return;
      verified = { ...model, settings };
      message('modelProbeResult', `验证通过：${model.modelName} · ${model.dimension} 维 · ${indexText(model.dimension)}。${model.dimension > 2000 ? '如模型支持，可指定不超过 2000 的输出维度后重新验证以启用 HNSW。' : ''}`, 'success');
    });
  };
  $('modelSwitch').onclick = () => {
    if (!verified || !data || pending(data.run) || busy) return;
    const selected = verified, active = data.active;
    if (!confirm(`将整个知识库从 ${active.modelName}（${active.dimension} 维）切换到 ${selected.modelName}（${selected.dimension} 维）？\n\n将重新生成全部 ${data.totalChunks.toLocaleString()} 个已发布切片的向量并构建索引。完成后自动切换，期间仍使用当前模型。`)) return;
    action(async token => {
      message('modelProbeResult', '正在校验并提交切换任务…');
      await request('/api/embedding/switch', post({ ...selected.settings, expectedActiveId: active.id,
        expectedDigest: selected.modelDigest, expectedDimension: selected.dimension }));
      if (token !== epoch || !state.authenticated) return;
      verified = null;
      message('modelProbeResult', '已提交后台重建，下面会自动显示处理进度。', 'success');
      showToast('模型重建已提交，全部完成后自动切换', 'success');
      await status();
    });
  };
  function runAction(actionName) {
    const run = data?.run;
    if (!run || busy) return;
    if (actionName === 'cancel' && !confirm('取消本次模型切换？当前生效的模型与索引会保留；下次切换将创建新任务。')) return;
    action(async token => {
      await request(`/api/embedding/rebuilds/${run.id}/${actionName}`, post({}));
      if (token !== epoch || !state.authenticated) return;
      message('modelProbeResult', actionName === 'cancel' ? '已取消切换，继续使用当前模型。' : '已重新排队，将从已完成的进度继续。', 'success');
      await status();
    });
  }
  $('modelRetry').onclick = () => runAction('retry');
  $('modelCancel').onclick = () => runAction('cancel');
  async function load() {
    if (!state.authenticated) return;
    if (!timer) timer = setInterval(() => { if (!document.hidden) status(); }, 4000);
    await status();
    if (!catalogLoaded && data) await action(catalog);
  }
  function clear() {
    epoch++; clearInterval(timer); timer = null; data = null; verified = null;
    busy = false; polling = false; catalogLoaded = false;
    $('modelCurrentName').textContent = ''; $('modelCurrentDetails').textContent = '';
    $('modelSelect').replaceChildren(new Option('登录后读取模型列表', ''));
    $('modelInstruction').value = ''; $('modelDimension').value = '';
    $('modelRebuild').classList.add('hidden');
    ['modelStatusMessage', 'modelProbeResult', 'modelRunMessage', 'modelRunSummary', 'modelRunTarget'].forEach(id => message(id, ''));
    controls();
  }
  return { load, clear };
}
