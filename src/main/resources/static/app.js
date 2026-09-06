import { createWorkbench } from './modules/workbench.js';

let workbench;
let filesRequest = 0;
let filesAbort;
let redirectingToLogin = false;
let sessionInitialization;
function readFolderUiState() {
  try {
    const value = JSON.parse(localStorage.getItem('ragFolderUi') || '{}');
    return {
      expandedFolders: Array.isArray(value.expandedFolders) ? value.expandedFolders.map(String) : [],
      paneCollapsed: Boolean(value.paneCollapsed)
    };
  } catch (error) {
    return { expandedFolders: [], paneCollapsed: false };
  }
}

const folderUiState = readFolderUiState();
// 清理旧版 Basic Auth 留下的浏览器凭证，账号和密码不再保存在前端。
localStorage.removeItem('basicAuth');
const state = {
  page: 1,
  size: 10,
  total: 0,
  authenticated: false,
  username: '',
  csrfToken: '',
  csrfHeaderName: 'X-CSRF-TOKEN',
  folderSelection: 'all',
  folderMap: new Map(),
  folderTree: [],
  expandedFolders: new Set(folderUiState.expandedFolders),
  folderQuery: '',
  folderPaneCollapsed: folderUiState.paneCollapsed,
  fileMap: new Map(),
  uploadBusy: false,
  movingFileId: null,
  movingSourceFolderId: null,
  moveTargetFolderId: null,
  moveTargetChosen: false,
  folderEditorMode: null,
  knowledgeTypeSelection: 'ALL',
  exportBusy: false
};

const supportedExtensions = new Set([
  'txt', 'md', 'doc', 'docx', 'pdf', 'xls', 'xlsx', 'java', 'xml'
]);
const ignoredProjectDirectories = new Set([
  '.git', '.idea', '.gradle', 'target', 'build', 'out', 'node_modules'
]);
const uploadBatchMaxFiles = 20;
const uploadBatchMaxBytes = 80 * 1024 * 1024;

const elements = {
  fileInput: document.getElementById('fileInput'),
  folderInput: document.getElementById('folderInput'),
  chooseFilesBtn: document.getElementById('chooseFilesBtn'),
  chooseFolderBtn: document.getElementById('chooseFolderBtn'),
  uploadFilesBtn: document.getElementById('uploadFilesBtn'),
  uploadFolderBtn: document.getElementById('uploadFolderBtn'),
  fileSelection: document.getElementById('fileSelection'),
  folderSelection: document.getElementById('folderSelection'),
  uploadStatus: document.getElementById('uploadStatus'),
  uploadTarget: document.getElementById('uploadTarget'),
  workspaceCard: document.getElementById('workspaceCard'),
  folderPane: document.getElementById('folderPane'),
  folderPaneToggle: document.getElementById('folderPaneToggle'),
  folderSearch: document.getElementById('folderSearch'),
  clearFolderSearch: document.getElementById('clearFolderSearch'),
  folderShortcuts: document.getElementById('folderShortcuts'),
  folderTree: document.getElementById('folderTree'),
  folderTreeMeta: document.getElementById('folderTreeMeta'),
  collapseFoldersBtn: document.getElementById('collapseFoldersBtn'),
  locateFolderBtn: document.getElementById('locateFolderBtn'),
  folderCount: document.getElementById('folderCount'),
  createFolderBtn: document.getElementById('createFolderBtn'),
  renameFolderBtn: document.getElementById('renameFolderBtn'),
  exportFolderBtn: document.getElementById('exportFolderBtn'),
  knowledgeTypeBtn: document.getElementById('knowledgeTypeBtn'),
  deleteFolderBtn: document.getElementById('deleteFolderBtn'),
  parentFolderBtn: document.getElementById('parentFolderBtn'),
  folderBreadcrumb: document.getElementById('folderBreadcrumb'),
  currentFolderTitle: document.getElementById('currentFolderTitle'),
  currentFolderPath: document.getElementById('currentFolderPath'),
  currentFileCount: document.getElementById('currentFileCount'),
  fileTable: document.getElementById('fileTable'),
  emptyFiles: document.getElementById('emptyFiles'),
  pageInfo: document.getElementById('pageInfo'),
  prevPage: document.getElementById('prevPage'),
  nextPage: document.getElementById('nextPage'),
  refreshBtn: document.getElementById('refreshBtn'),
  rebuildBtn: document.getElementById('rebuildBtn'),
  authenticatedWorkspace: document.getElementById('authenticatedWorkspace'),
  sessionGate: document.getElementById('sessionGate'),
  sessionMessage: document.getElementById('sessionMessage'),
  sessionRecovery: document.getElementById('sessionRecovery'),
  retrySessionBtn: document.getElementById('retrySessionBtn'),
  authStatus: document.getElementById('authStatus'),
  logoutBtn: document.getElementById('logoutBtn'),
  previewModal: document.getElementById('previewModal'),
  previewText: document.getElementById('previewText'),
  closePreview: document.getElementById('closePreview'),
  moveModal: document.getElementById('moveModal'),
  moveFileName: document.getElementById('moveFileName'),
  moveFolderSearch: document.getElementById('moveFolderSearch'),
  moveFolderOptions: document.getElementById('moveFolderOptions'),
  moveTargetPath: document.getElementById('moveTargetPath'),
  closeMove: document.getElementById('closeMove'),
  confirmMove: document.getElementById('confirmMove'),
  folderEditorModal: document.getElementById('folderEditorModal'),
  folderEditorKicker: document.getElementById('folderEditorKicker'),
  folderEditorTitle: document.getElementById('folderEditorTitle'),
  folderEditorContext: document.getElementById('folderEditorContext'),
  folderNameInput: document.getElementById('folderNameInput'),
  closeFolderEditor: document.getElementById('closeFolderEditor'),
  cancelFolderEditor: document.getElementById('cancelFolderEditor'),
  confirmFolderEditor: document.getElementById('confirmFolderEditor'),
  knowledgeTypeModal: document.getElementById('knowledgeTypeModal'),
  knowledgeTypeContext: document.getElementById('knowledgeTypeContext'),
  knowledgeTypeOptions: document.getElementById('knowledgeTypeOptions'),
  knowledgeTypeRecursive: document.getElementById('knowledgeTypeRecursive'),
  closeKnowledgeType: document.getElementById('closeKnowledgeType'),
  cancelKnowledgeType: document.getElementById('cancelKnowledgeType'),
  confirmKnowledgeType: document.getElementById('confirmKnowledgeType'),
  fileUploadQueue: document.getElementById('fileUploadQueue'),
  folderUploadQueue: document.getElementById('folderUploadQueue'),
  toastRegion: document.getElementById('toastRegion'),
  codeScope: document.getElementById('codeScope'),
  codeQuery: document.getElementById('codeQuery'),
  codeCategory: document.getElementById('codeCategory'),
  searchCodeBtn: document.getElementById('searchCodeBtn'),
  codePath: document.getElementById('codePath'),
  codeLine: document.getElementById('codeLine'),
  locateCodeBtn: document.getElementById('locateCodeBtn'),
  codeSearchStatus: document.getElementById('codeSearchStatus'),
  codeResults: document.getElementById('codeResults'),
  emptyCodeResults: document.getElementById('emptyCodeResults'),
  codeModal: document.getElementById('codeModal'),
  codeModalType: document.getElementById('codeModalType'),
  codeModalTitle: document.getElementById('codeModalTitle'),
  codeModalMeta: document.getElementById('codeModalMeta'),
  codeSourceText: document.getElementById('codeSourceText'),
  closeCodeModal: document.getElementById('closeCodeModal')
};

function setStatus(text, type = '') {
  elements.uploadStatus.textContent = text;
  elements.uploadStatus.dataset.type = type;
}

function setCodeStatus(text, type = '') {
  elements.codeSearchStatus.textContent = text;
  elements.codeSearchStatus.dataset.type = type;
}

function updateAuthUi() {
  const authenticated = state.authenticated;
  elements.authStatus.dataset.state = authenticated ? 'online' : 'offline';
  elements.authStatus.textContent = authenticated ? `已登录 · ${state.username}` : '';
  elements.authenticatedWorkspace.hidden = !authenticated || document.hidden;
  elements.sessionGate.hidden = authenticated && !document.hidden;
  document.body.classList.toggle('signed-in', authenticated);
  workbench?.authChanged(authenticated);
}

function showSessionGate(message = '正在验证登录状态…', recover = false) {
  elements.authenticatedWorkspace.hidden = true;
  elements.sessionGate.hidden = false;
  elements.sessionMessage.textContent = message;
  elements.sessionRecovery.hidden = !recover;
}

function redirectToLogin(reason = 'expired') {
  if (redirectingToLogin) return;
  redirectingToLogin = true;
  clearAuthenticatedWorkspace('请重新登录');
  showSessionGate('正在返回登录页…');
  window.location.replace(`login.html?reason=${encodeURIComponent(reason)}`);
}

function responseErrorMessage(body, response) {
  const base = (body && body.message ? body.message : `请求失败 (${response.status})`) + (body?.requestId ? ` · 请求 ${body.requestId}` : '');
  return body && body.clientIp ? `${base} 当前访问 IP：${body.clientIp}` : base;
}

async function readJsonBody(response) {
  try {
    return await response.json();
  } catch (error) {
    return { success: false, message: '服务端返回了无法解析的响应' };
  }
}

async function loadAuthStatus() {
  const response = await fetch('/api/auth/status', {
    credentials: 'same-origin',
    cache: 'no-store',
    headers: { Accept: 'application/json' }
  });
  const body = await readJsonBody(response);
  if (!response.ok || !body.success) {
    throw new Error(responseErrorMessage(body, response));
  }
  if (redirectingToLogin) return body;
  state.authenticated = Boolean(body.authenticated);
  state.username = body.username || '';
  state.csrfToken = body.csrfToken || '';
  state.csrfHeaderName = body.csrfHeaderName || 'X-CSRF-TOKEN';
  updateAuthUi();
  return body;
}

async function logout() {
  elements.logoutBtn.disabled = true;
  try {
    const response = await apiFetch('/api/auth/logout', {
      method: 'POST',
      headers: { Accept: 'application/json' }
    });
    const body = await readJsonBody(response);
    if (!response.ok || !body.success) {
      throw new Error(responseErrorMessage(body, response));
    }
    redirectToLogin('logged-out');
  } catch (error) {
    showError(error);
  } finally {
    elements.logoutBtn.disabled = false;
  }
}

async function apiFetch(url, options = {}) {
  const headers = new Headers(options.headers || {});
  const method = String(options.method || 'GET').toUpperCase();
  if (!['GET', 'HEAD', 'OPTIONS', 'TRACE'].includes(method) && state.csrfToken) {
    headers.set(state.csrfHeaderName, state.csrfToken);
  }
  const response = await fetch(url, { cache: 'no-store', ...options, headers, credentials: 'same-origin' });
  await handleAuthenticationFailure(response.status);
  return response;
}

async function handleAuthenticationFailure(status) {
  if (status === 401) {
    redirectToLogin();
    throw new Error('登录已失效，请重新登录');
  }
  if (status === 403) {
    // Session 过期后的写请求可能先被 CSRF 拒绝，需再区分登录失效与权限不足。
    let session;
    try { session = await loadAuthStatus(); }
    catch (error) {
      clearAuthenticatedWorkspace('无法验证登录状态');
      showSessionGate(error.message || '无法连接管理端，请稍后重试。', true);
      throw error;
    }
    if (!session.authenticated) {
      redirectToLogin();
      throw new Error('登录已失效，请重新登录');
    }
  }
}

async function requestJson(url, options = {}) {
  const response = await apiFetch(url, options);
  const body = await readJsonBody(response);
  if (body.authenticated === false) {
    redirectToLogin();
    throw new Error('登录已失效，请重新登录');
  }
  if (!response.ok || !body.success) {
    throw new Error(responseErrorMessage(body, response));
  }
  return body;
}

async function loadFolders() {
  const body = await requestJson('/api/folders/tree');
  state.folderTree = body.data || [];
  rebuildFolderMap();
  if (isConcreteFolderSelection() && !state.folderMap.has(String(state.folderSelection))) {
    state.folderSelection = 'root';
  }
  state.expandedFolders = new Set(
    Array.from(state.expandedFolders).filter(folderId => state.folderMap.has(String(folderId)))
  );
  expandSelectedAncestors();
  renderFolderNavigation();
  updateSelectionLabels();
  applyFolderPaneState();
  workbench?.updateScopes();
}

function rebuildFolderMap() {
  state.folderMap = new Map();
  const visit = (node, parentPath = '', depth = 0) => {
    const path = parentPath ? `${parentPath}/${node.name}` : node.name;
    const folder = {
      ...node,
      path,
      depth,
      totalFileCount: Number(node.fileCount || 0)
    };
    state.folderMap.set(String(node.id), folder);
    (node.children || []).forEach(child => {
      folder.totalFileCount += visit(child, path, depth + 1);
    });
    return folder.totalFileCount;
  };
  state.folderTree.forEach(node => visit(node));
  elements.folderCount.textContent = String(state.folderMap.size);
}

function persistFolderUiState() {
  localStorage.setItem('ragFolderUi', JSON.stringify({
    expandedFolders: Array.from(state.expandedFolders),
    paneCollapsed: state.folderPaneCollapsed
  }));
}

function renderFolderNavigation() {
  renderFolderShortcuts();
  renderFolderTree();
}

function renderFolderShortcuts() {
  elements.folderShortcuts.innerHTML = '';
  appendSpecialFolder('all', '全部文件', 'ALL');
  appendSpecialFolder('root', '根目录', 'ROOT');
}

function renderFolderTree() {
  elements.folderTree.innerHTML = '';
  const normalizedQuery = state.folderQuery.trim().toLocaleLowerCase('zh-CN');
  if (normalizedQuery) {
    const matches = Array.from(state.folderMap.values())
      .filter(folder => folder.path.toLocaleLowerCase('zh-CN').includes(normalizedQuery))
      .sort((left, right) => left.path.localeCompare(right.path, 'zh-CN'));
    elements.folderTreeMeta.textContent = `${matches.length} 个匹配`;
    matches.forEach(folder => appendFolderRow(folder, true));
    if (!matches.length) {
      appendFolderTreeEmpty('没有匹配的目录', '可以输入目录名称或路径中的任意一段');
    }
    return;
  }

  elements.folderTreeMeta.textContent = state.folderMap.size ? '目录树' : '暂无目录';
  const appendVisibleNodes = nodes => {
    nodes.forEach(node => {
      const folder = state.folderMap.get(String(node.id));
      appendFolderRow(folder, false);
      if (state.expandedFolders.has(String(node.id))) {
        appendVisibleNodes(node.children || []);
      }
    });
  };
  appendVisibleNodes(state.folderTree);
  if (!state.folderTree.length) {
    appendFolderTreeEmpty('还没有目录', '点击“新建下级目录”开始整理知识库');
  }
}

function appendSpecialFolder(selection, labelText, markerText) {
  const button = document.createElement('button');
  button.type = 'button';
  button.className = 'folder-node special';
  button.dataset.folderSelection = selection;
  button.setAttribute('aria-pressed', String(state.folderSelection === selection));
  if (state.folderSelection === selection) {
    button.classList.add('selected');
  }
  const marker = document.createElement('span');
  marker.className = 'shortcut-mark';
  marker.textContent = markerText;
  const label = document.createElement('span');
  label.className = 'folder-label';
  label.textContent = labelText;
  button.append(marker, label);
  elements.folderShortcuts.appendChild(button);
}

function appendFolderRow(folder, searchResult) {
  if (!folder) return;
  const hasChildren = (folder.children || []).length > 0;
  const expanded = state.expandedFolders.has(String(folder.id));
  const selected = String(state.folderSelection) === String(folder.id);
  const row = document.createElement('div');
  row.className = `folder-row${searchResult ? ' search-result' : ''}`;
  row.style.setProperty('--visual-depth', searchResult ? 0 : Math.min(folder.depth, 6));

  const toggle = document.createElement('button');
  toggle.type = 'button';
  toggle.className = `folder-toggle${expanded ? ' expanded' : ''}${hasChildren && !searchResult ? '' : ' leaf'}`;
  toggle.dataset.folderToggle = String(folder.id);
  toggle.disabled = !hasChildren || searchResult;
  toggle.setAttribute('aria-label', expanded ? `收起 ${folder.name}` : `展开 ${folder.name}`);

  const button = document.createElement('button');
  button.type = 'button';
  button.className = `folder-node${selected ? ' selected' : ''}`;
  button.dataset.folderSelection = String(folder.id);
  button.title = folder.path;
  button.setAttribute('role', 'treeitem');
  button.setAttribute('aria-level', String(folder.depth + 1));
  button.setAttribute('aria-selected', String(selected));
  if (hasChildren) {
    button.setAttribute('aria-expanded', String(expanded));
  }

  const icon = document.createElement('span');
  icon.className = 'folder-icon';
  icon.setAttribute('aria-hidden', 'true');
  const labelGroup = document.createElement('span');
  labelGroup.className = 'folder-label-group';
  const label = document.createElement('span');
  label.className = 'folder-label';
  label.textContent = folder.name;
  labelGroup.appendChild(label);
  if (searchResult) {
    const path = document.createElement('span');
    path.className = 'folder-path-hint';
    path.textContent = folder.path;
    labelGroup.appendChild(path);
  }
  const count = document.createElement('span');
  count.className = 'folder-file-count';
  const directFileCount = Number(folder.fileCount || 0);
  const totalFileCount = Number(folder.totalFileCount || 0);
  count.textContent = directFileCount === totalFileCount
    ? String(directFileCount)
    : `${directFileCount}/${totalFileCount}`;
  count.title = `当前目录 ${directFileCount} 个文件，包含下级共 ${totalFileCount} 个`;
  button.append(icon, labelGroup, count);
  row.append(toggle, button);
  elements.folderTree.appendChild(row);
}

function appendFolderTreeEmpty(title, hint) {
  const empty = document.createElement('div');
  empty.className = 'folder-tree-empty';
  const strong = document.createElement('strong');
  strong.textContent = title;
  const detail = document.createElement('div');
  detail.textContent = hint;
  empty.append(strong, detail);
  elements.folderTree.appendChild(empty);
}

function selectFolder(selection) {
  state.folderSelection = String(selection);
  state.folderQuery = '';
  elements.folderSearch.value = '';
  elements.clearFolderSearch.classList.add('hidden');
  state.page = 1;
  expandSelectedAncestors();
  renderFolderNavigation();
  updateSelectionLabels();
  requestAnimationFrame(scrollSelectedFolderIntoView);
  loadFiles().catch(showError);
}

function toggleFolder(folderId) {
  const normalizedId = String(folderId);
  if (state.expandedFolders.has(normalizedId)) {
    state.expandedFolders.delete(normalizedId);
  } else {
    state.expandedFolders.add(normalizedId);
  }
  persistFolderUiState();
  renderFolderTree();
}

function expandSelectedAncestors() {
  if (!isConcreteFolderSelection()) return;
  let folder = state.folderMap.get(String(state.folderSelection));
  while (folder && folder.parentId != null) {
    state.expandedFolders.add(String(folder.parentId));
    folder = state.folderMap.get(String(folder.parentId));
  }
  persistFolderUiState();
}

function collapseAllFolders() {
  state.expandedFolders.clear();
  persistFolderUiState();
  renderFolderTree();
}

function locateCurrentFolder() {
  setFolderPaneCollapsed(false);
  state.folderQuery = '';
  elements.folderSearch.value = '';
  elements.clearFolderSearch.classList.add('hidden');
  expandSelectedAncestors();
  renderFolderNavigation();
  requestAnimationFrame(scrollSelectedFolderIntoView);
}

function scrollSelectedFolderIntoView() {
  const selected = elements.folderTree.querySelector('.folder-node.selected');
  if (selected) {
    selected.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
  }
}

function setFolderPaneCollapsed(collapsed) {
  state.folderPaneCollapsed = Boolean(collapsed);
  applyFolderPaneState();
  persistFolderUiState();
}

function applyFolderPaneState() {
  elements.workspaceCard.classList.toggle('folder-pane-collapsed', state.folderPaneCollapsed);
  elements.folderPane.classList.toggle('collapsed', state.folderPaneCollapsed);
  elements.folderPaneToggle.setAttribute('aria-label', state.folderPaneCollapsed ? '展开目录栏' : '收起目录栏');
  elements.folderPaneToggle.title = state.folderPaneCollapsed ? '展开目录栏' : '收起目录栏';
}

function updateSelectionLabels() {
  const concrete = isConcreteFolderSelection();
  let title = '全部文件';
  let uploadTarget = '根目录';
  let pathText = '跨目录查看整个知识库';
  if (state.folderSelection === 'root') {
    title = '根目录';
    pathText = '未归入任何目录的文件';
  } else if (concrete) {
    const folder = state.folderMap.get(String(state.folderSelection));
    title = folder ? folder.name : '根目录';
    uploadTarget = folder ? folder.path : '根目录';
    pathText = folder ? folder.path : '根目录';
  }
  elements.currentFolderTitle.textContent = title;
  elements.currentFolderTitle.title = pathText;
  elements.currentFolderPath.textContent = pathText;
  elements.currentFolderPath.title = pathText;
  elements.uploadTarget.textContent = uploadTarget;
  elements.uploadTarget.title = uploadTarget;
  elements.codeScope.textContent = state.folderSelection === 'all' ? '全部目录' : uploadTarget;
  elements.codeScope.title = state.folderSelection === 'all' ? '全部目录' : uploadTarget;
  elements.renameFolderBtn.disabled = !concrete;
  elements.exportFolderBtn.disabled = !concrete || state.exportBusy;
  elements.knowledgeTypeBtn.disabled = !concrete;
  elements.deleteFolderBtn.disabled = !concrete;
  elements.parentFolderBtn.disabled = !concrete;
  renderBreadcrumb();
}

function renderBreadcrumb() {
  elements.folderBreadcrumb.innerHTML = '';
  if (state.folderSelection === 'all') {
    appendBreadcrumb('all', '全部文件', true);
    return;
  }
  appendBreadcrumb('all', '全部文件', false);
  appendBreadcrumbSeparator();
  if (state.folderSelection === 'root') {
    appendBreadcrumb('root', '根目录', true);
    return;
  }
  appendBreadcrumb('root', '根目录', false);
  getFolderTrail(state.folderSelection).forEach((folder, index, trail) => {
    appendBreadcrumbSeparator();
    appendBreadcrumb(String(folder.id), folder.name, index === trail.length - 1);
  });
  requestAnimationFrame(() => {
    elements.folderBreadcrumb.scrollLeft = elements.folderBreadcrumb.scrollWidth;
  });
}

function appendBreadcrumb(selection, label, current) {
  const button = document.createElement('button');
  button.type = 'button';
  button.dataset.breadcrumbSelection = selection;
  button.textContent = label;
  if (current) button.setAttribute('aria-current', 'page');
  elements.folderBreadcrumb.appendChild(button);
}

function appendBreadcrumbSeparator() {
  const separator = document.createElement('span');
  separator.className = 'breadcrumb-separator';
  separator.textContent = '/';
  elements.folderBreadcrumb.appendChild(separator);
}

function getFolderTrail(folderId) {
  const trail = [];
  let folder = state.folderMap.get(String(folderId));
  while (folder) {
    trail.unshift(folder);
    folder = folder.parentId == null ? null : state.folderMap.get(String(folder.parentId));
  }
  return trail;
}

function selectParentFolder() {
  if (!isConcreteFolderSelection()) return;
  const folder = state.folderMap.get(String(state.folderSelection));
  selectFolder(folder && folder.parentId != null ? String(folder.parentId) : 'root');
}

function isConcreteFolderSelection() {
  return state.folderSelection !== 'all' && state.folderSelection !== 'root';
}

function selectedFolderId() {
  return isConcreteFolderSelection() ? Number(state.folderSelection) : null;
}

function selectedCodeFolderId() {
  if (state.folderSelection === 'root') return 0;
  return selectedFolderId();
}

async function loadFiles() {
  const request = ++filesRequest;
  filesAbort?.abort();
  filesAbort = new AbortController();
  const params = new URLSearchParams({ page: state.page, size: state.size });
  for (const [key, id] of [['query', 'fileQuery'], ['status', 'fileStatus'], ['knowledgeType', 'fileType'], ['sort', 'fileSort']]) {
    const value = document.getElementById(id).value.trim();
    if (value) params.set(key, value);
  }
  if (state.folderSelection === 'root') {
    params.set('folderId', '0');
  } else if (isConcreteFolderSelection()) {
    params.set('folderId', String(state.folderSelection));
  }
  let body;
  elements.fileTable.setAttribute('aria-busy', 'true');
  try {
    body = await requestJson(`/api/files?${params}`, { signal: filesAbort.signal });
  } catch (error) {
    if (error.name === 'AbortError' || request !== filesRequest) return;
    throw error;
  } finally {
    if (request === filesRequest) elements.fileTable.removeAttribute('aria-busy');
  }
  if (request !== filesRequest || !state.authenticated) return;
  state.total = body.total || 0;
  const pages = Math.max(1, Math.ceil(state.total / state.size));
  if (state.page > pages) { state.page = pages; return loadFiles(); }
  renderTable(body.data || []);
  updatePagination();
  elements.currentFileCount.textContent = `${state.total} 个文件`;
}

function renderTable(files) {
  elements.fileTable.innerHTML = '';
  state.fileMap = new Map(files.map(file => [String(file.id), file]));
  elements.emptyFiles.textContent = document.getElementById('fileQuery').value || document.getElementById('fileStatus').value || document.getElementById('fileType').value
    ? '没有符合筛选条件的文件，可清空搜索或调整筛选' : '当前目录还没有文档，选择文件或拖入文件开始建立知识库';
  elements.emptyFiles.classList.toggle('hidden', files.length > 0);
  files.forEach(file => {
    const row = document.createElement('tr');
    const selectCell = document.createElement('td');
    selectCell.className = 'selection-cell';
    const checkbox = document.createElement('input');
    checkbox.type = 'checkbox';
    checkbox.dataset.fileSelect = file.id;
    checkbox.setAttribute('aria-label', `选择 ${file.filename}`);
    selectCell.append(checkbox);
    row.append(selectCell);
    const nameCell = document.createElement('td');
    nameCell.className = 'file-name-cell'; nameCell.dataset.label = '文件名';
    const nameBlock = document.createElement('div');
    const name = document.createElement('strong'); name.textContent = file.filename;
    const path = document.createElement('small'); path.textContent = `${file.folderPath || '根目录'} · ID ${file.id}`;
    path.title = path.textContent;
    nameBlock.append(name, path); nameCell.append(nameBlock); row.append(nameCell);
    appendKnowledgeTypeCell(row, file.knowledgeType);
    appendTextCell(row, formatBytes(file.size), '', '大小');

    const statusCell = document.createElement('td');
    statusCell.dataset.label = '状态';
    const status = document.createElement('span');
    status.className = `status-pill status-${String(file.status || '').toLowerCase()}`;
    status.textContent = { INDEXED: '已索引', PENDING: '待索引', FAILED: '索引失败' }[file.status] || file.status || '-';
    statusCell.appendChild(status);
    if (file.errorMessage) {
      const issue = document.createElement('button');
      issue.type = 'button';
      issue.className = 'file-issue';
      issue.textContent = file.status === 'INDEXED' ? '更新失败 · 旧版可用' : '查看失败原因';
      issue.title = file.errorMessage;
      issue.addEventListener('click', () => previewFile(file.id));
      statusCell.append(issue);
    }
    row.appendChild(statusCell);

    appendTextCell(row, formatTime(file.updatedAt), '', '更新时间');
    appendTextCell(row, String(file.chunkCount || 0), '', '切片');

    const actions = document.createElement('td');
    actions.className = 'row-actions';
    actions.dataset.label = '操作';
    addActionButton(actions, '详情', 'preview', file.id);
    if (/\.(java|xml)$/i.test(file.filename || '')) {
      addActionButton(actions, '符号', 'outline', file.id);
    }
    addActionButton(actions, '下载', 'download', file.id);
    addActionButton(actions, '移动', 'move', file.id);
    addActionButton(actions, '重建', 'reindex', file.id);
    addActionButton(actions, '删除', 'delete', file.id, true);
    row.appendChild(actions);
    elements.fileTable.appendChild(row);
  });
  workbench?.clearSelection();
}

function appendTextCell(row, value, className = '', label = '') {
  const cell = document.createElement('td');
  cell.textContent = value == null ? '-' : String(value);
  if (className) {
    cell.className = className;
    cell.title = cell.textContent;
  }
  if (label) cell.dataset.label = label;
  row.appendChild(cell);
}

function appendKnowledgeTypeCell(row, knowledgeType) {
  const normalized = ['ALL', 'BUSINESS', 'CODE'].includes(String(knowledgeType).toUpperCase())
    ? String(knowledgeType).toUpperCase()
    : 'ALL';
  const labels = { ALL: '全部', BUSINESS: '业务', CODE: '代码' };
  const cell = document.createElement('td');
  cell.dataset.label = '检索类型';
  const badge = document.createElement('span');
  badge.className = `knowledge-badge knowledge-${normalized.toLowerCase()}`;
  badge.textContent = labels[normalized];
  badge.title = normalized === 'ALL' ? '业务和代码分类检索均可命中' : `${normalized} 分类检索`;
  cell.appendChild(badge);
  row.appendChild(cell);
}

function addActionButton(container, label, action, id, danger = false) {
  const button = document.createElement('button');
  button.type = 'button';
  button.className = `btn ghost small${danger ? ' danger-text' : ''}`;
  button.dataset.action = action;
  button.dataset.id = String(id);
  button.textContent = label;
  container.appendChild(button);
}

function updatePagination() {
  const totalPages = Math.max(Math.ceil(state.total / state.size), 1);
  if (state.page > totalPages) {
    state.page = totalPages;
  }
  elements.pageInfo.textContent = `${state.page} / ${totalPages}`;
  elements.prevPage.disabled = state.page <= 1;
  elements.nextPage.disabled = state.page >= totalPages;
}

async function uploadSelectedFiles() {
  const selected = Array.from(elements.fileInput.files || []);
  const files = selected.filter(file => isSupportedFile(file.name));
  if (!files.length) {
    showToast('请先选择至少一个支持的文件', 'warning');
    return;
  }
  await uploadFiles(files, false, selected.length - files.length);
  elements.fileInput.value = '';
  elements.fileSelection.textContent = '尚未选择文件';
  updateUploadControls();
}

async function uploadSelectedFolder() {
  const selected = Array.from(elements.folderInput.files || []);
  const files = selected.filter(file => isUploadableProjectFile(file));
  if (!files.length) {
    showToast('所选文件夹中没有可上传的文件', 'warning');
    return;
  }
  await uploadFiles(files, true, selected.length - files.length);
  elements.folderInput.value = '';
  elements.folderSelection.textContent = '保留层级并忽略构建目录';
  updateUploadControls();
}

async function uploadFiles(files, preservePaths, skippedCount = 0) {
  const batches = createUploadBatches(files);
  const folderId = selectedFolderId();
  const results = [];
  let completedBatches = 0;
  let uploadCompleted = false;
  setUploadBusy(true);
  const skippedText = skippedCount ? `，已忽略 ${skippedCount} 个不支持或构建目录中的文件` : '';
  try {
    for (let index = 0; index < batches.length; index += 1) {
      const batch = batches[index];
      const formData = new FormData();
      batch.forEach(file => {
        formData.append('files', file, file.name);
        if (preservePaths) {
          formData.append('relativePaths', file.webkitRelativePath || file.name);
        }
      });
      if (folderId != null) {
        formData.append('folderId', String(folderId));
      }

      setStatus(
        `正在提交第 ${index + 1}/${batches.length} 批（${batch.length} 个文件）${skippedText}…`
      );
      const body = await workbench.upload(formData, percent => setStatus(`上传第 ${index + 1}/${batches.length} 批 · ${percent}%${skippedText}`));
      results.push(...(body.data || []));
      completedBatches = index + 1;
    }

    uploadCompleted = true;
    const succeeded = results.filter(result => result.success).length;
    const failed = results.length - succeeded;
    setStatus(
      `已提交 ${succeeded} 个索引任务，提交失败 ${failed}${skippedCount ? `，忽略 ${skippedCount}` : ''}。在任务中心查看处理进度。`,
      failed ? 'warning' : 'success'
    );
    if (failed) {
      const details = results.filter(result => !result.success)
        .slice(0, 4)
        .map(result => `${result.fileName || '未知文件'}：${result.message}`)
        .join('\n');
      showToast(`部分文件提交失败：\n${details}`, 'warning', 9000);
    }
    workbench.jobsSubmitted();
  } catch (error) {
    setStatus(
      `上传中断：已完成 ${completedBatches}/${batches.length} 批。${error.message || '上传失败'}`,
      'error'
    );
  } finally {
    try {
      await loadFolders();
      await loadFiles();
    } catch (error) {
      if (uploadCompleted) {
        setStatus(`任务已提交，但列表刷新失败：${error.message || '未知错误'}`, 'warning');
      }
    }
    setUploadBusy(false);
  }
}

function createUploadBatches(files) {
  const batches = [];
  let currentBatch = [];
  let currentBytes = 0;

  files.forEach(file => {
    const fileBytes = Number(file.size || 0);
    const exceedsCount = currentBatch.length >= uploadBatchMaxFiles;
    const exceedsBytes = currentBatch.length > 0 && currentBytes + fileBytes > uploadBatchMaxBytes;
    if (exceedsCount || exceedsBytes) {
      batches.push(currentBatch);
      currentBatch = [];
      currentBytes = 0;
    }
    currentBatch.push(file);
    currentBytes += fileBytes;
  });

  if (currentBatch.length) {
    batches.push(currentBatch);
  }
  return batches;
}

function isSupportedFile(filename) {
  const separator = String(filename || '').lastIndexOf('.');
  if (separator < 0) return false;
  return supportedExtensions.has(filename.substring(separator + 1).toLowerCase());
}

function isUploadableProjectFile(file) {
  if (!isSupportedFile(file.name)) return false;
  const relativePath = String(file.webkitRelativePath || file.name).replaceAll('\\', '/');
  const segments = relativePath.split('/').map(segment => segment.toLowerCase());
  return !segments.some(segment => ignoredProjectDirectories.has(segment));
}

function setUploadBusy(busy) {
  state.uploadBusy = Boolean(busy);
  updateUploadControls();
}

function updateUploadControls() {
  const fileCount = elements.fileInput.files ? elements.fileInput.files.length : 0;
  const folderCount = elements.folderInput.files ? elements.folderInput.files.length : 0;
  elements.chooseFilesBtn.disabled = state.uploadBusy;
  elements.chooseFolderBtn.disabled = state.uploadBusy;
  elements.uploadFilesBtn.disabled = state.uploadBusy || fileCount === 0;
  elements.uploadFolderBtn.disabled = state.uploadBusy || folderCount === 0;
  elements.fileUploadQueue.dataset.active = String(fileCount > 0);
  elements.folderUploadQueue.dataset.active = String(folderCount > 0);
}

function createFolder() {
  const parentId = selectedFolderId();
  state.folderEditorMode = { type: 'create', parentId };
  elements.folderEditorKicker.textContent = 'NEW FOLDER';
  elements.folderEditorTitle.textContent = '新建下级目录';
  elements.folderEditorContext.textContent = `父目录：${elements.uploadTarget.textContent}`;
  elements.folderNameInput.value = '';
  elements.confirmFolderEditor.textContent = '创建目录';
  elements.folderEditorModal.classList.remove('hidden');
  requestAnimationFrame(() => elements.folderNameInput.focus());
}

function renameFolder() {
  if (!isConcreteFolderSelection()) return;
  const folder = state.folderMap.get(String(state.folderSelection));
  if (!folder) return;
  state.folderEditorMode = { type: 'rename', folderId: folder.id };
  elements.folderEditorKicker.textContent = 'RENAME FOLDER';
  elements.folderEditorTitle.textContent = '重命名目录';
  elements.folderEditorContext.textContent = `当前位置：${folder.path}`;
  elements.folderNameInput.value = folder.name;
  elements.confirmFolderEditor.textContent = '保存名称';
  elements.folderEditorModal.classList.remove('hidden');
  requestAnimationFrame(() => {
    elements.folderNameInput.focus();
    elements.folderNameInput.select();
  });
}

async function submitFolderEditor() {
  if (elements.confirmFolderEditor.disabled) return;
  const mode = state.folderEditorMode;
  const name = elements.folderNameInput.value.trim();
  if (!mode || !name) {
    showToast('请输入目录名称', 'warning');
    elements.folderNameInput.focus();
    return;
  }
  elements.confirmFolderEditor.disabled = true;
  try {
    if (mode.type === 'create') {
      const body = await requestJson('/api/folders', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ parentId: mode.parentId, name })
      });
      if (mode.parentId != null) {
        state.expandedFolders.add(String(mode.parentId));
      }
      if (body.data && body.data.id != null) {
        state.folderSelection = String(body.data.id);
      }
      state.page = 1;
      showToast(`目录“${name}”已创建`, 'success');
    } else {
      await requestJson(`/api/folders/${mode.folderId}`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ name })
      });
      showToast(`目录已重命名为“${name}”`, 'success');
    }
    closeFolderEditor();
    await loadFolders();
    await loadFiles();
  } catch (error) {
    showError(error);
  } finally {
    elements.confirmFolderEditor.disabled = false;
  }
}

function closeFolderEditor() {
  state.folderEditorMode = null;
  elements.folderEditorModal.classList.add('hidden');
}

function openKnowledgeTypeModal() {
  if (!isConcreteFolderSelection()) return;
  const folder = state.folderMap.get(String(state.folderSelection));
  if (!folder) return;
  state.knowledgeTypeSelection = 'ALL';
  elements.knowledgeTypeContext.textContent = `${folder.path} · 当前及下级共 ${folder.totalFileCount || 0} 个文件`;
  elements.knowledgeTypeRecursive.checked = true;
  renderKnowledgeTypeSelection();
  elements.knowledgeTypeModal.classList.remove('hidden');
}

function selectKnowledgeType(knowledgeType) {
  const normalized = String(knowledgeType || '').toUpperCase();
  if (!['ALL', 'BUSINESS', 'CODE'].includes(normalized)) return;
  state.knowledgeTypeSelection = normalized;
  renderKnowledgeTypeSelection();
}

function renderKnowledgeTypeSelection() {
  elements.knowledgeTypeOptions.querySelectorAll('[data-knowledge-type]').forEach(option => {
    const selected = option.dataset.knowledgeType === state.knowledgeTypeSelection;
    option.classList.toggle('selected', selected);
    option.setAttribute('aria-checked', String(selected));
  });
}

async function applyKnowledgeType() {
  if (!isConcreteFolderSelection() || elements.confirmKnowledgeType.disabled) return;
  const folder = state.folderMap.get(String(state.folderSelection));
  if (!folder) return;
  elements.confirmKnowledgeType.disabled = true;
  try {
    const body = await requestJson(`/api/folders/${folder.id}/knowledge-type`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        knowledgeType: state.knowledgeTypeSelection,
        recursive: elements.knowledgeTypeRecursive.checked
      })
    });
    const labels = { ALL: '全部可查', BUSINESS: '业务资料', CODE: '代码资料' };
    closeKnowledgeTypeModal();
    await loadFiles();
    showToast(
      `已将 ${body.updatedCount || 0} 个文件设置为“${labels[state.knowledgeTypeSelection]}”`,
      'success'
    );
  } catch (error) {
    showError(error);
  } finally {
    elements.confirmKnowledgeType.disabled = false;
  }
}

function closeKnowledgeTypeModal() {
  elements.knowledgeTypeModal.classList.add('hidden');
}

async function deleteFolder() {
  if (!isConcreteFolderSelection()) return;
  const folder = state.folderMap.get(String(state.folderSelection));
  if (!folder) return;
  const descendantCount = countDescendantFolders(folder);
  const message = `确认删除目录“${folder.path}”吗？\n将同时删除 ${descendantCount} 个下级目录、${folder.totalFileCount || 0} 个文件及其全部向量切片。`;
  if (!confirm(message)) return;
  const parentSelection = folder && folder.parentId != null ? String(folder.parentId) : 'root';
  try {
    await requestJson(`/api/folders/${state.folderSelection}?recursive=true`, { method: 'DELETE' });
    state.folderSelection = parentSelection;
    state.page = 1;
    showToast(`目录“${folder.name}”及其内容已删除`, 'success');
    await loadFolders();
    await loadFiles();
  } catch (error) {
    showError(error);
  }
}

async function exportFolder() {
  if (!isConcreteFolderSelection() || state.exportBusy) return;
  const folder = state.folderMap.get(String(state.folderSelection));
  if (!folder) return;

  state.exportBusy = true;
  elements.exportFolderBtn.textContent = '正在打包…';
  updateSelectionLabels();
  try {
    await workbench.download(`/api/folders/${folder.id}/export`);
    showToast(`已请求导出“${folder.name}”，请在浏览器下载列表查看进度`, 'info');
  } catch (error) {
    showError(error);
  } finally {
    state.exportBusy = false;
    elements.exportFolderBtn.textContent = '导出 ZIP';
    updateSelectionLabels();
  }
}

function countDescendantFolders(folder) {
  return (folder.children || []).reduce(
    (total, child) => total + 1 + countDescendantFolders(child),
    0
  );
}

async function previewFile(id) {
  await workbench.preview(id);
}

async function downloadFile(id) {
  try { await workbench.download(`/api/files/${id}/download`); }
  catch (error) { showError(error); }
}

function parseDownloadName(disposition) {
  if (!disposition) return '';
  const match = disposition.match(/filename\*=UTF-8''([^;]+)/i);
  return match ? decodeURIComponent(match[1]) : '';
}

async function deleteFile(id) {
  if (!confirm('确认删除该文件及其全部向量切片？')) return;
  const file = state.fileMap.get(String(id));
  try {
    await requestJson(`/api/files/${id}`, { method: 'DELETE' });
    showToast(`文件“${file ? file.filename : id}”已删除`, 'success');
    await loadFolders();
    await loadFiles();
  } catch (error) {
    showError(error);
  }
}

async function reindexFile(id) {
  try {
    await workbench.queue([id], false);
  } catch (error) {
    showError(error);
  }
}

async function rebuildAll() {
  if (!confirm('确认重建全部文件的向量索引？')) return;
  try {
    await workbench.queue([], true);
  } catch (error) {
    showError(error);
  }
}

function openMoveModal(fileId) {
  const batch = Array.isArray(fileId) ? fileId : null;
  const file = state.fileMap.get(String(batch ? batch[0] : fileId));
  if (!file) {
    showToast('文件信息已过期，请刷新列表后重试', 'warning');
    return;
  }
  state.movingFileId = fileId;
  state.movingSourceFolderId = batch ? -1 : (file.folderId == null ? null : Number(file.folderId));
  state.moveTargetFolderId = null;
  state.moveTargetChosen = false;
  elements.moveFileName.textContent = batch ? `已选 ${batch.length} 个文件` : file.filename;
  elements.moveFolderSearch.value = '';
  elements.moveTargetPath.textContent = '请选择目标目录';
  elements.confirmMove.disabled = true;
  renderMoveFolderOptions();
  elements.moveModal.classList.remove('hidden');
  requestAnimationFrame(() => elements.moveFolderSearch.focus());
}

function renderMoveFolderOptions() {
  const query = elements.moveFolderSearch.value.trim().toLocaleLowerCase('zh-CN');
  const folders = [
    { id: null, name: '根目录', path: '根目录', depth: 0 },
    ...Array.from(state.folderMap.values())
      .sort((left, right) => left.path.localeCompare(right.path, 'zh-CN'))
  ].filter(folder => !query || folder.path.toLocaleLowerCase('zh-CN').includes(query));

  elements.moveFolderOptions.innerHTML = '';
  folders.forEach(folder => appendMoveFolderOption(folder));
  if (!folders.length) {
    const empty = document.createElement('div');
    empty.className = 'move-folder-empty';
    empty.textContent = '没有找到匹配的目标目录';
    elements.moveFolderOptions.appendChild(empty);
  }
}

function appendMoveFolderOption(folder) {
  const folderKey = folder.id == null ? 'root' : String(folder.id);
  const sourceKey = state.movingSourceFolderId == null ? 'root' : String(state.movingSourceFolderId);
  const targetKey = state.moveTargetFolderId == null ? 'root' : String(state.moveTargetFolderId);
  const current = folderKey === sourceKey;
  const selected = state.moveTargetChosen && folderKey === targetKey;
  const button = document.createElement('button');
  button.type = 'button';
  button.className = `move-folder-option${selected ? ' selected' : ''}`;
  button.dataset.moveFolderId = folderKey;
  button.disabled = current;
  button.setAttribute('role', 'option');
  button.setAttribute('aria-selected', String(selected));
  button.title = folder.path;

  const icon = document.createElement('span');
  icon.className = 'folder-icon';
  icon.setAttribute('aria-hidden', 'true');
  const main = document.createElement('span');
  main.className = 'move-folder-main';
  const name = document.createElement('span');
  name.className = 'move-folder-name';
  name.textContent = folder.name;
  const path = document.createElement('span');
  path.className = 'move-folder-path';
  path.textContent = folder.path;
  main.append(name, path);
  const badge = document.createElement('span');
  badge.className = 'move-folder-badge';
  badge.textContent = current ? '当前位置' : (folder.id == null ? 'ROOT' : `L${folder.depth + 1}`);
  button.append(icon, main, badge);
  elements.moveFolderOptions.appendChild(button);
}

function selectMoveTarget(folderId) {
  state.moveTargetFolderId = folderId === 'root' ? null : Number(folderId);
  state.moveTargetChosen = true;
  const folder = folderId === 'root' ? null : state.folderMap.get(String(folderId));
  elements.moveTargetPath.textContent = folder ? folder.path : '根目录';
  elements.moveTargetPath.title = folder ? folder.path : '根目录';
  elements.confirmMove.disabled = false;
  renderMoveFolderOptions();
}

async function moveFile() {
  if (state.movingFileId == null || !state.moveTargetChosen) return;
  elements.confirmMove.disabled = true;
  try {
    const batch = Array.isArray(state.movingFileId);
    const body = await requestJson(batch ? '/api/files/batch' : `/api/files/${state.movingFileId}/folder`, {
      method: batch ? 'POST' : 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ folderId: state.moveTargetFolderId, ...(batch ? {fileIds: state.movingFileId, action: 'move'} : {}) })
    });
    if (batch) workbench.summarize(body.data, '移动');
    else showToast(`文件已移动到${elements.moveTargetPath.textContent}`, 'success');
    closeMoveModal();
    await loadFolders();
    await loadFiles();
  } catch (error) {
    showError(error);
  } finally { elements.confirmMove.disabled = false; }
}

function closeMoveModal() {
  state.movingFileId = null;
  state.movingSourceFolderId = null;
  state.moveTargetFolderId = null;
  state.moveTargetChosen = false;
  elements.moveModal.classList.add('hidden');
}

async function searchCodeSymbols() {
  const query = elements.codeQuery.value.trim();
  if (!query) {
    setCodeStatus('请输入类名、方法名、签名或 SQL ID', 'warning');
    return;
  }
  const params = new URLSearchParams({
    query,
    category: elements.codeCategory.value,
    limit: '50'
  });
  appendCodeScope(params);
  setCodeBusy(true);
  setCodeStatus('正在检索结构化代码索引...');
  try {
    const body = await requestJson(`/api/code/symbols?${params}`);
    const results = body.data || [];
    renderCodeResults(results);
    setCodeStatus(`找到 ${results.length} 个代码符号`, results.length ? 'success' : 'warning');
  } catch (error) {
    setCodeStatus(error.message || '代码检索失败', 'error');
  } finally {
    setCodeBusy(false);
  }
}

async function locateCodeByLine() {
  const filePath = elements.codePath.value.trim();
  const line = Number(elements.codeLine.value);
  if (!filePath || !Number.isInteger(line) || line <= 0) {
    setCodeStatus('请输入文件相对路径和大于 0 的代码行号', 'warning');
    return;
  }
  const params = new URLSearchParams({ filePath, line: String(line) });
  appendCodeScope(params);
  setCodeBusy(true);
  setCodeStatus(`正在定位 ${filePath}:${line}...`);
  try {
    const body = await requestJson(`/api/code/location?${params}`);
    const results = body.data || [];
    renderCodeResults(results);
    setCodeStatus(
      results.length ? `已定位到 ${results.length} 个候选符号` : '该位置没有找到类、方法或 XML SQL',
      results.length ? 'success' : 'warning'
    );
    if (results.length === 1) {
      showCodeSource(results[0]);
    }
  } catch (error) {
    setCodeStatus(error.message || '代码定位失败', 'error');
  } finally {
    setCodeBusy(false);
  }
}

async function loadFileOutline(fileId) {
  workbench.switchView('code');
  setCodeBusy(true);
  setCodeStatus('正在读取文件符号大纲...');
  try {
    const body = await requestJson(`/api/code/files/${fileId}/outline`);
    const results = body.data || [];
    renderCodeResults(results);
    setCodeStatus(`文件中包含 ${results.length} 个代码符号`, results.length ? 'success' : 'warning');
    elements.codeResults.scrollIntoView({ behavior: 'smooth', block: 'center' });
  } catch (error) {
    setCodeStatus(error.message || '读取代码大纲失败', 'error');
  } finally {
    setCodeBusy(false);
  }
}

function appendCodeScope(params) {
  const folderId = selectedCodeFolderId();
  if (folderId != null) {
    params.set('folderId', String(folderId));
  }
}

function renderCodeResults(results) {
  elements.codeResults.innerHTML = '';
  elements.emptyCodeResults.classList.toggle('hidden', results.length > 0);
  if (!results.length) {
    elements.emptyCodeResults.textContent = '没有找到匹配的代码符号';
    return;
  }

  results.forEach(result => {
    const item = document.createElement('article');
    item.className = 'code-result';

    const type = document.createElement('span');
    type.className = `code-type code-type-${codeTypeGroup(result.symbolType).toLowerCase()}`;
    type.textContent = formatSymbolType(result.symbolType);

    const content = document.createElement('div');
    content.className = 'code-result-main';
    const title = document.createElement('h3');
    title.textContent = result.signature || result.qualifiedName || result.simpleName;
    const qualified = document.createElement('div');
    qualified.className = 'code-qualified-name';
    qualified.textContent = result.qualifiedName || result.simpleName || '-';
    const location = document.createElement('div');
    location.className = 'code-location';
    location.textContent = `${result.filePath || result.fileName || '-'} · ${formatLineRange(result)}`;
    content.append(title, qualified, location);

    const view = document.createElement('button');
    view.type = 'button';
    view.className = 'btn ghost small';
    view.dataset.codeSymbolId = String(result.id || result.symbolId);
    view.textContent = '查看源码';

    item.append(type, content, view);
    elements.codeResults.appendChild(item);
  });
}

async function openCodeSymbol(symbolId) {
  if (!symbolId || symbolId === 'undefined') return;
  try {
    const body = await requestJson(`/api/code/symbols/${symbolId}/source`);
    showCodeSource(body.data);
  } catch (error) {
    showError(error);
  }
}

function showCodeSource(result) {
  if (!result) return;
  elements.codeModalType.textContent = formatSymbolType(result.symbolType);
  elements.codeModalTitle.textContent = result.signature || result.qualifiedName || result.simpleName || '源码';
  elements.codeModalMeta.textContent = `${result.filePath || '-'} · ${formatLineRange(result)}${result.truncated ? ` · 超出阅读预算，当前显示部分源码（原文 ${result.totalChars} 字符），可下载原文件` : ''}`;
  elements.codeSourceText.textContent = result.numberedSource || result.source || '';
  elements.codeModal.classList.remove('hidden');
}

function formatLineRange(result) {
  const start = result.startLine || '?';
  const end = result.endLine || start;
  return `L${start}–L${end}`;
}

function formatSymbolType(symbolType) {
  const labels = {
    JAVA_CLASS: 'Java 类',
    JAVA_INTERFACE: 'Java 接口',
    JAVA_ENUM: 'Java 枚举',
    JAVA_RECORD: 'Java Record',
    JAVA_ANNOTATION: 'Java 注解',
    JAVA_METHOD: 'Java 方法',
    JAVA_CONSTRUCTOR: '构造方法',
    XML_MAPPER: 'Mapper XML',
    XML_SELECT: 'SELECT',
    XML_INSERT: 'INSERT',
    XML_UPDATE: 'UPDATE',
    XML_DELETE: 'DELETE',
    XML_SQL_FRAGMENT: 'SQL 片段'
  };
  return labels[symbolType] || symbolType || '源码';
}

function codeTypeGroup(symbolType) {
  if (String(symbolType || '').startsWith('XML_')) return 'SQL';
  if (['JAVA_METHOD', 'JAVA_CONSTRUCTOR'].includes(symbolType)) return 'METHOD';
  return 'CLASS';
}

function setCodeBusy(busy) {
  elements.searchCodeBtn.disabled = busy;
  elements.locateCodeBtn.disabled = busy;
}

function clearAuthenticatedWorkspace(hint, preserveCsrf = false) {
  filesRequest++;
  filesAbort?.abort();
  state.authenticated = false;
  state.username = '';
  if (!preserveCsrf) {
    state.csrfToken = '';
  }
  state.page = 1;
  state.total = 0;
  state.folderSelection = 'all';
  state.folderMap = new Map();
  state.folderTree = [];
  state.folderQuery = '';
  state.fileMap = new Map();

  elements.folderSearch.value = '';
  elements.clearFolderSearch.classList.add('hidden');
  elements.folderCount.textContent = '0';
  renderFolderShortcuts();
  elements.folderTree.innerHTML = '';
  elements.folderTreeMeta.textContent = '等待登录';
  appendFolderTreeEmpty('登录后显示目录树', hint);
  updateSelectionLabels();
  renderTable([]);
  elements.currentFileCount.textContent = '0 个文件';
  elements.emptyFiles.textContent = '登录后显示文件列表';
  updatePagination();
  elements.codeResults.innerHTML = '';
  elements.emptyCodeResults.classList.remove('hidden');
  elements.emptyCodeResults.textContent = '登录后可检索类、方法、SQL ID 或源码位置';
  setCodeStatus('');
  updateAuthUi();
}

async function initializeSession(refreshData = true) {
  if (redirectingToLogin) return;
  if (sessionInitialization) return sessionInitialization;
  const needsRefresh = refreshData || !state.authenticated;
  showSessionGate();
  sessionInitialization = (async () => {
    try {
      const status = await loadAuthStatus();
      if (!status.authenticated) { redirectToLogin(); return; }
      if (redirectingToLogin) return;
      if (needsRefresh) await refreshWorkspace(false);
    } catch (error) {
      clearAuthenticatedWorkspace('无法验证登录状态');
      showSessionGate(error.message || '无法连接管理端，请稍后重试。', true);
    }
  })();
  try { await sessionInitialization; }
  finally { sessionInitialization = null; }
}

async function refreshWorkspace(showSuccess = true) {
  try {
    await loadFolders();
    await loadFiles();
    if (showSuccess) {
      showToast('目录与文件列表已刷新', 'success', 2600);
    }
    return true;
  } catch (error) {
    showError(error);
    return false;
  }
}

function showError(error) {
  showToast(error.message || '操作失败', 'error', 6500);
}

function showToast(message, type = 'info', duration = 4500) {
  const toast = document.createElement('div');
  toast.className = `toast ${type}`;
  toast.textContent = String(message || '操作完成');
  toast.addEventListener('click', () => toast.remove());
  elements.toastRegion.appendChild(toast);
  window.setTimeout(() => toast.remove(), duration);
}

function formatBytes(bytes) {
  const value = Number(bytes || 0);
  if (value <= 0) return '0 B';
  const units = ['B', 'KB', 'MB', 'GB'];
  const index = Math.min(Math.floor(Math.log(value) / Math.log(1024)), units.length - 1);
  return `${(value / Math.pow(1024, index)).toFixed(1)} ${units[index]}`;
}

function formatTime(value) {
  if (!value) return '-';
  return new Date(value).toLocaleString('zh-CN', { hour12: false });
}

function handleFolderNavigationClick(event) {
  const toggle = event.target.closest('[data-folder-toggle]');
  if (toggle) {
    toggleFolder(toggle.dataset.folderToggle);
    return;
  }
  const button = event.target.closest('[data-folder-selection]');
  if (button) selectFolder(button.dataset.folderSelection);
}

function clearFolderSearch() {
  state.folderQuery = '';
  elements.folderSearch.value = '';
  elements.clearFolderSearch.classList.add('hidden');
  renderFolderTree();
  elements.folderSearch.focus();
}

function closeTopModal() {
  if (!elements.folderEditorModal.classList.contains('hidden')) {
    closeFolderEditor();
  } else if (!elements.knowledgeTypeModal.classList.contains('hidden')) {
    closeKnowledgeTypeModal();
  } else if (!elements.moveModal.classList.contains('hidden')) {
    closeMoveModal();
  } else if (!elements.previewModal.classList.contains('hidden')) {
    elements.previewModal.classList.add('hidden');
  } else if (!elements.codeModal.classList.contains('hidden')) {
    elements.codeModal.classList.add('hidden');
  }
}

elements.logoutBtn.addEventListener('click', logout);
elements.retrySessionBtn.addEventListener('click', () => initializeSession());
elements.chooseFilesBtn.addEventListener('click', () => elements.fileInput.click());
elements.chooseFolderBtn.addEventListener('click', () => elements.folderInput.click());
elements.uploadFilesBtn.addEventListener('click', uploadSelectedFiles);
elements.uploadFolderBtn.addEventListener('click', uploadSelectedFolder);
elements.fileInput.addEventListener('change', () => {
  const files = Array.from(elements.fileInput.files || []);
  const supported = files.filter(file => isSupportedFile(file.name)).length;
  elements.fileSelection.textContent = files.length
    ? `已选择 ${files.length} 个文件 · 可处理 ${supported} 个`
    : '尚未选择文件';
  updateUploadControls();
});
elements.folderInput.addEventListener('change', () => {
  const files = Array.from(elements.folderInput.files || []);
  const root = files[0] && files[0].webkitRelativePath
    ? files[0].webkitRelativePath.split('/')[0]
    : '文件夹';
  const supported = files.filter(isUploadableProjectFile).length;
  elements.folderSelection.textContent = files.length
    ? `${root} · ${files.length} 个文件 · 可处理 ${supported} 个`
    : '保留层级并忽略构建目录';
  updateUploadControls();
});
elements.folderPane.addEventListener('click', handleFolderNavigationClick);
elements.folderSearch.addEventListener('input', () => {
  state.folderQuery = elements.folderSearch.value;
  elements.clearFolderSearch.classList.toggle('hidden', !state.folderQuery);
  renderFolderTree();
});
elements.folderSearch.addEventListener('keydown', event => {
  if (event.key === 'Escape') clearFolderSearch();
});
elements.clearFolderSearch.addEventListener('click', clearFolderSearch);
elements.collapseFoldersBtn.addEventListener('click', collapseAllFolders);
elements.locateFolderBtn.addEventListener('click', locateCurrentFolder);
elements.folderPaneToggle.addEventListener('click', () => {
  setFolderPaneCollapsed(!state.folderPaneCollapsed);
});
elements.folderBreadcrumb.addEventListener('click', event => {
  const button = event.target.closest('[data-breadcrumb-selection]');
  if (button) selectFolder(button.dataset.breadcrumbSelection);
});
elements.parentFolderBtn.addEventListener('click', selectParentFolder);
elements.createFolderBtn.addEventListener('click', createFolder);
elements.renameFolderBtn.addEventListener('click', renameFolder);
elements.exportFolderBtn.addEventListener('click', exportFolder);
elements.knowledgeTypeBtn.addEventListener('click', openKnowledgeTypeModal);
elements.deleteFolderBtn.addEventListener('click', deleteFolder);
elements.refreshBtn.addEventListener('click', refreshWorkspace);
elements.rebuildBtn.addEventListener('click', rebuildAll);
elements.prevPage.addEventListener('click', () => {
  if (state.page > 1) {
    state.page -= 1;
    loadFiles().catch(showError);
  }
});
elements.nextPage.addEventListener('click', () => {
  const totalPages = Math.max(Math.ceil(state.total / state.size), 1);
  if (state.page < totalPages) {
    state.page += 1;
    loadFiles().catch(showError);
  }
});
elements.fileTable.addEventListener('click', event => {
  const button = event.target.closest('button[data-action]');
  if (!button) return;
  const id = Number(button.dataset.id);
  if (button.dataset.action === 'preview') previewFile(id);
  if (button.dataset.action === 'outline') loadFileOutline(id);
  if (button.dataset.action === 'download') downloadFile(id);
  if (button.dataset.action === 'move') openMoveModal(id);
  if (button.dataset.action === 'reindex') reindexFile(id);
  if (button.dataset.action === 'delete') deleteFile(id);
});
elements.closePreview.addEventListener('click', () => elements.previewModal.classList.add('hidden'));
elements.closeMove.addEventListener('click', closeMoveModal);
elements.moveFolderSearch.addEventListener('input', renderMoveFolderOptions);
elements.moveFolderOptions.addEventListener('click', event => {
  const option = event.target.closest('[data-move-folder-id]');
  if (option && !option.disabled) selectMoveTarget(option.dataset.moveFolderId);
});
elements.confirmMove.addEventListener('click', moveFile);
elements.closeFolderEditor.addEventListener('click', closeFolderEditor);
elements.cancelFolderEditor.addEventListener('click', closeFolderEditor);
elements.confirmFolderEditor.addEventListener('click', submitFolderEditor);
elements.folderNameInput.addEventListener('keydown', event => {
  if (event.key === 'Enter') submitFolderEditor();
});
elements.knowledgeTypeOptions.addEventListener('click', event => {
  const option = event.target.closest('[data-knowledge-type]');
  if (option) selectKnowledgeType(option.dataset.knowledgeType);
});
elements.closeKnowledgeType.addEventListener('click', closeKnowledgeTypeModal);
elements.cancelKnowledgeType.addEventListener('click', closeKnowledgeTypeModal);
elements.confirmKnowledgeType.addEventListener('click', applyKnowledgeType);
elements.searchCodeBtn.addEventListener('click', searchCodeSymbols);
elements.locateCodeBtn.addEventListener('click', locateCodeByLine);
elements.codeQuery.addEventListener('keydown', event => {
  if (event.key === 'Enter') searchCodeSymbols();
});
elements.codeLine.addEventListener('keydown', event => {
  if (event.key === 'Enter') locateCodeByLine();
});
elements.codeResults.addEventListener('click', event => {
  const button = event.target.closest('button[data-code-symbol-id]');
  if (button) openCodeSymbol(button.dataset.codeSymbolId);
});
elements.closeCodeModal.addEventListener('click', () => elements.codeModal.classList.add('hidden'));
[elements.previewModal, elements.moveModal, elements.folderEditorModal, elements.knowledgeTypeModal, elements.codeModal]
  .forEach(modal => modal.addEventListener('click', event => {
    if (event.target === modal) closeTopModal();
  }));
document.addEventListener('keydown', event => {
  if (event.key === 'Escape') closeTopModal();
});

workbench = createWorkbench({ state, requestJson, apiFetch, handleAuthenticationFailure, loadFiles, loadFolders, showToast,
  formatBytes, formatTime, openMoveModal, uploadFiles, supportedFile: isSupportedFile });
applyFolderPaneState();
updateUploadControls();
clearAuthenticatedWorkspace('请先登录管理端');
initializeSession();

// 离开页面时隐藏内容，浏览器后退或重新切回标签页后再次校验，防止恢复已退出的画面。
window.addEventListener('pagehide', () => showSessionGate());
window.addEventListener('pageshow', event => {
  if (event.persisted) {
    redirectingToLogin = false;
    initializeSession();
  }
});
document.addEventListener('visibilitychange', () => {
  if (document.hidden) showSessionGate();
  else initializeSession(false);
});
