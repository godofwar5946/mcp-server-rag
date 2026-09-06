const $ = id => document.getElementById(id);
const post = body => ({method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify(body)});
const el = (tag, text, css = '') => { const element = document.createElement(tag); element.textContent = text ?? ''; element.className = css; return element; };
const percent = value => value == null ? '—' : `${(value * 100).toFixed(1)}%`;

export function createEvaluation({request, state, showToast}) {
  let data = {data: [], runs: []}, running = false, stop = false, epoch = 0;
  const fail = error => showToast(error.message || '评测操作失败', 'error');
  async function load() {
    const sequence = epoch;
    const body = await request('/api/evaluations');
    if (sequence !== epoch || !state.authenticated) return;
    data = body; render();
  }
  function clear() {
    epoch++; stop = true; data = {data: [], runs: []};
    $('evaluationCases').replaceChildren(); $('evaluationSummary').replaceChildren();
  }
  function render() {
    $('evaluationCases').replaceChildren();
    if (!data.data.length) $('evaluationCases').append(el('div', '尚无评测问题。先选取实际使用中的问题，并人工确认正确的参考文件。', 'empty-state'));
    data.data.forEach(sample => {
      const row = el('article', '', 'evaluation-row');
      const main = el('div'); main.append(el('strong', sample.question), el('p', sample.expectEmpty ? '期望：无结果' : `参考文件：${JSON.parse(sample.expectedFileIds).join(', ')}`, 'source-meta'));
      row.append(main);
      for (const mode of ['vector', 'hybrid']) {
        const run = data.runs.find(item => item.caseId === sample.id && item.mode === mode);
        const cell = el('div', '', 'eval-result');
        cell.append(el('small', mode === 'vector' ? '纯向量 · 最近一次' : '混合检索 · 最近一次'));
        if (run) {
          const result = JSON.parse(run.result);
          cell.append(el('strong', result.hit ? '符合预期' : '未达预期', result.hit ? 'positive' : 'negative'),
            el('small', `${result.expectEmpty ? '空结果检查' : `Recall ${percent(result.recall)} · MRR ${result.reciprocalRank.toFixed(3)}`} · ${result.totalMillis} ms`),
            el('small', `K ${result.topK ?? '默认'} · 阈值 ${result.minSimilarity ?? '默认'} · ${run.createdAt}`));
        } else cell.append(el('span', '未运行'));
        row.append(cell);
      }
      const remove = el('button', '删除', 'btn ghost small danger-text'); remove.disabled = running;
      remove.onclick = async () => { if (!confirm('删除这条评测问题及其运行记录？')) return; try { await request(`/api/evaluations/${sample.id}`, {method: 'DELETE'}); await load(); } catch (error) { fail(error); } };
      row.append(remove); $('evaluationCases').append(row);
    });
  }
  $('evaluationForm').onsubmit = async event => {
    event.preventDefault();
    const button = event.submitter; button.disabled = true;
    try {
      const text = $('evalExpected').value.trim();
      const ids = text ? text.split(/[,，\s]+/).map(Number) : [];
      if (ids.some(id => !Number.isSafeInteger(id) || id <= 0)) throw new Error('参考文件 ID 应为正整数，用逗号分隔');
      await request('/api/evaluations', post({question: $('evalQuestion').value,
        folderId: $('evalFolder').value === '' ? null : Number($('evalFolder').value), knowledgeType: $('evalType').value,
        expectedFileIds: ids, expectEmpty: $('evalEmpty').checked}));
      $('evalQuestion').value = ''; $('evalExpected').value = '';
      showToast('评测问题已保存', 'success'); await load();
    } catch (error) { fail(error); } finally { button.disabled = false; }
  };
  $('evalEmpty').onchange = () => { $('evalExpected').disabled = $('evalEmpty').checked; if ($('evalEmpty').checked) $('evalExpected').value = ''; };
  $('stopEvaluation').onclick = () => { stop = true; $('evaluationStatus').textContent = '当前请求完成后停止，已完成的结果会保留。'; };
  $('runEvaluation').onclick = async () => {
    if (running) return;
    const topK = Number($('evalTopK').value), minSimilarity = Number($('evalThreshold').value);
    if (!Number.isInteger(topK) || topK < 1 || topK > 20 || !Number.isFinite(minSimilarity) || minSimilarity < 0 || minSimilarity > 1)
      return showToast('Top K 应为 1～20，最低相似度应为 0～1', 'warning');
    if (!data.data.length) return showToast('请先添加评测问题', 'warning');
    running = true; stop = false;
    const sequence = epoch, samples = [...data.data], results = {vector: [], hybrid: []};
    let completed = 0, errors = 0;
    $('runEvaluation').disabled = true; $('stopEvaluation').classList.remove('hidden');
    $('evaluationSummary').replaceChildren(); render();
    try {
      outer: for (const sample of samples) {
        for (const mode of ['vector', 'hybrid']) {
          if (stop || epoch !== sequence) break outer;
          $('evaluationStatus').textContent = `正在运行 ${completed + 1}/${samples.length * 2} · ${mode === 'vector' ? '纯向量' : '混合检索'} · ${sample.question}`;
          try {
            const body = await request(`/api/evaluations/${sample.id}/run`, post({mode, topK, minSimilarity}));
            if (sequence !== epoch) break outer;
            results[mode].push(body.data);
          } catch (error) { errors++; if (state.authenticated) fail(error); else break outer; }
          completed++;
        }
        if (sequence === epoch) await load();
      }
      if (sequence === epoch) {
        $('evaluationStatus').textContent = `${stop ? '已停止' : '运行完成'}：${completed} 次请求，失败 ${errors} 次。以下统计仅包含本轮成功请求；耗时受顺序执行与缓存影响。`;
        for (const [mode, rows] of Object.entries(results)) {
          const positives = rows.filter(row => !row.expectEmpty), empty = rows.filter(row => row.expectEmpty);
          const average = (items, key) => items.length ? items.reduce((sum, row) => sum + Number(row[key]), 0) / items.length : null;
          const card = el('div', '', 'eval-summary');
          card.append(el('strong', `${mode === 'vector' ? '纯向量' : '混合检索'} · ${rows.length} 次`),
            el('span', `命中率 ${percent(average(positives, 'hit'))} · Recall@${topK} ${percent(average(positives, 'recall'))}`),
            el('span', `MRR ${average(positives, 'reciprocalRank')?.toFixed(3) ?? '—'} · 空结果准确率 ${percent(average(empty, 'hit'))}`));
          $('evaluationSummary').append(card);
        }
      }
    } catch (error) { fail(error); }
    finally { running = false; $('runEvaluation').disabled = false; $('stopEvaluation').classList.add('hidden'); if (sequence === epoch) load().catch(fail); }
  };
  $('exportEvaluation').onclick = () => {
    const blob = new Blob([JSON.stringify({exportedAt: new Date().toISOString(), cases: data.data, runs: data.runs}, null, 2)], {type: 'application/json'});
    const url = URL.createObjectURL(blob), link = el('a'); link.href = url; link.download = 'rag-evaluation.json'; link.click(); setTimeout(() => URL.revokeObjectURL(url), 2000);
  };
  return {load, clear};
}
