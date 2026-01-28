const state = {
  page: 1,
  size: 10,
  total: 0,
  auth: localStorage.getItem('basicAuth') || ''
};

const elements = {
  fileInput: document.getElementById('fileInput'),
  uploadBtn: document.getElementById('uploadBtn'),
  uploadStatus: document.getElementById('uploadStatus'),
  fileTable: document.getElementById('fileTable'),
  pageInfo: document.getElementById('pageInfo'),
  prevPage: document.getElementById('prevPage'),
  nextPage: document.getElementById('nextPage'),
  refreshBtn: document.getElementById('refreshBtn'),
  rebuildBtn: document.getElementById('rebuildBtn'),
  loginBtn: document.getElementById('loginBtn'),
  username: document.getElementById('username'),
  password: document.getElementById('password'),
  previewModal: document.getElementById('previewModal'),
  previewText: document.getElementById('previewText'),
  closePreview: document.getElementById('closePreview')
};

function setStatus(text) {
  elements.uploadStatus.textContent = text;
}

function saveAuth() {
  const username = elements.username.value.trim();
  const password = elements.password.value.trim();
  if (!username || !password) {
    alert('请输入用户名和密码');
    return;
  }
  state.auth = 'Basic ' + btoa(`${username}:${password}`);
  localStorage.setItem('basicAuth', state.auth);
  alert('凭证已保存');
}

async function apiFetch(url, options = {}) {
  const headers = options.headers || {};
  if (state.auth) {
    headers['Authorization'] = state.auth;
  }
  return fetch(url, { ...options, headers });
}

async function loadFiles() {
  const res = await apiFetch(`/api/files?page=${state.page}&size=${state.size}`);
  if (res.status === 401) {
    alert('未登录或凭证错误');
    return;
  }
  const data = await res.json();
  if (!data.success) {
    alert(data.message || '加载失败');
    return;
  }
  state.total = data.total || 0;
  renderTable(data.data || []);
  updatePagination();
}

function renderTable(files) {
  elements.fileTable.innerHTML = '';
  files.forEach(file => {
    const tr = document.createElement('tr');
    tr.innerHTML = `
      <td>${file.filename}</td>
      <td>${formatBytes(file.size)}</td>
      <td>${file.status}</td>
      <td>${formatTime(file.updatedAt)}</td>
      <td>${formatTime(file.indexedAt)}</td>
      <td>${file.chunkCount}</td>
      <td>
        <button class="btn ghost small" data-action="preview" data-id="${file.id}">预览</button>
        <button class="btn ghost small" data-action="download" data-id="${file.id}">下载</button>
        <button class="btn ghost small" data-action="reindex" data-id="${file.id}">重建</button>
        <button class="btn ghost small" data-action="delete" data-id="${file.id}">删除</button>
      </td>
    `;
    elements.fileTable.appendChild(tr);
  });
}

function updatePagination() {
  const totalPages = Math.max(Math.ceil(state.total / state.size), 1);
  elements.pageInfo.textContent = `${state.page} / ${totalPages}`;
  elements.prevPage.disabled = state.page <= 1;
  elements.nextPage.disabled = state.page >= totalPages;
}

async function uploadFiles() {
  if (!elements.fileInput.files.length) {
    alert('请选择文件');
    return;
  }
  const formData = new FormData();
  Array.from(elements.fileInput.files).forEach(file => {
    formData.append('files', file);
  });
  setStatus('上传中...');
  const res = await apiFetch('/api/files/upload', {
    method: 'POST',
    body: formData
  });
  const data = await res.json();
  if (!data.success) {
    setStatus('上传失败');
    alert(data.message || '上传失败');
    return;
  }
  setStatus('上传完成');
  await loadFiles();
}

async function previewFile(id) {
  const res = await apiFetch(`/api/files/${id}/preview?maxChars=4000`);
  const data = await res.json();
  if (!data.success) {
    alert(data.message || '预览失败');
    return;
  }
  elements.previewText.textContent = data.data || '';
  elements.previewModal.classList.remove('hidden');
}

async function downloadFile(id) {
  const res = await apiFetch(`/api/files/${id}/download`);
  if (!res.ok) {
    alert('下载失败');
    return;
  }
  const blob = await res.blob();
  const url = window.URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = '';
  a.click();
  window.URL.revokeObjectURL(url);
}

async function deleteFile(id) {
  if (!confirm('确认删除该文件及其切片？')) {
    return;
  }
  const res = await apiFetch(`/api/files/${id}`, { method: 'DELETE' });
  const data = await res.json();
  if (!data.success) {
    alert(data.message || '删除失败');
    return;
  }
  await loadFiles();
}

async function reindexFile(id) {
  const res = await apiFetch(`/api/files/${id}/reindex`, { method: 'POST' });
  const data = await res.json();
  if (!data.success) {
    alert(data.message || '重建失败');
    return;
  }
  await loadFiles();
}

async function rebuildAll() {
  if (!confirm('确认重建全部索引？')) {
    return;
  }
  const res = await apiFetch('/api/index/rebuild', { method: 'POST' });
  const data = await res.json();
  if (!data.success) {
    alert(data.message || '重建失败');
    return;
  }
  alert('重建任务已提交');
  await loadFiles();
}

function formatBytes(bytes) {
  if (bytes === 0) return '0 B';
  const k = 1024;
  const sizes = ['B', 'KB', 'MB', 'GB'];
  const i = Math.floor(Math.log(bytes) / Math.log(k));
  return `${(bytes / Math.pow(k, i)).toFixed(1)} ${sizes[i]}`;
}

function formatTime(value) {
  if (!value) return '-';
  const date = new Date(value);
  return date.toLocaleString();
}

elements.loginBtn.addEventListener('click', saveAuth);
elements.uploadBtn.addEventListener('click', uploadFiles);
elements.refreshBtn.addEventListener('click', loadFiles);
elements.rebuildBtn.addEventListener('click', rebuildAll);
elements.prevPage.addEventListener('click', () => {
  if (state.page > 1) {
    state.page -= 1;
    loadFiles();
  }
});
elements.nextPage.addEventListener('click', () => {
  const totalPages = Math.max(Math.ceil(state.total / state.size), 1);
  if (state.page < totalPages) {
    state.page += 1;
    loadFiles();
  }
});
elements.fileTable.addEventListener('click', event => {
  const target = event.target;
  if (!(target instanceof HTMLButtonElement)) return;
  const id = target.dataset.id;
  const action = target.dataset.action;
  if (!id) return;
  if (action === 'preview') previewFile(id);
  if (action === 'download') downloadFile(id);
  if (action === 'reindex') reindexFile(id);
  if (action === 'delete') deleteFile(id);
});
elements.closePreview.addEventListener('click', () => {
  elements.previewModal.classList.add('hidden');
});

loadFiles();
