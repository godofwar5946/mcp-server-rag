const form = document.getElementById('loginForm');
const usernameInput = document.getElementById('username');
const passwordInput = document.getElementById('password');
const loginButton = document.getElementById('loginBtn');
const togglePassword = document.getElementById('togglePassword');
const message = document.getElementById('loginMessage');
let busy = false;
let navigating = false;

const reasons = {
  expired: '登录已失效，请重新登录。',
  'logged-out': '已退出登录。'
};
const reason = new URLSearchParams(window.location.search).get('reason');
const initialMessage = Object.hasOwn(reasons, reason) ? reasons[reason] : '';

function showMessage(text, type = '') {
  message.textContent = text;
  message.dataset.type = type;
}

function setBusy(value, label = '登录') {
  busy = value;
  loginButton.disabled = value || navigating;
  loginButton.textContent = label;
  form.setAttribute('aria-busy', String(value));
}

async function authRequest(path, options = {}) {
  const response = await fetch(path, { credentials: 'same-origin', cache: 'no-store', ...options });
  let body;
  try { body = await response.json(); }
  catch { throw new Error('无法读取服务端响应，请稍后重试。'); }
  if (!response.ok || !body.success) {
    const detail = body.clientIp ? ` 当前访问 IP：${body.clientIp}` : '';
    throw new Error((body.message || `请求失败（${response.status}）`) + detail);
  }
  return body;
}

function loadSession() {
  return authRequest('api/auth/status', { headers: { Accept: 'application/json' } });
}

function enterWorkspace() {
  navigating = true;
  passwordInput.value = '';
  showMessage('登录成功，正在进入工作台…', 'success');
  window.location.replace('./');
}

async function initializeLogin() {
  if (busy || navigating) return;
  setBusy(true, '正在连接…');
  try {
    const session = await loadSession();
    if (session.authenticated) enterWorkspace();
    else showMessage(initialMessage);
  } catch (error) {
    showMessage(error.message || '无法连接管理端，请检查网络后重试。', 'error');
  } finally {
    setBusy(false);
  }
}

form.addEventListener('submit', async event => {
  event.preventDefault();
  if (busy || navigating || !form.reportValidity()) return;
  const username = usernameInput.value.trim();
  if (!username) { showMessage('请输入用户名。', 'error'); usernameInput.focus(); return; }
  setBusy(true, '正在登录…');
  showMessage('正在验证账号…');
  try {
    // 每次提交获取当前 Session 的 CSRF Token，兼容长时间停留和登录后的 Token 轮换。
    const session = await loadSession();
    if (session.authenticated) { enterWorkspace(); return; }
    await authRequest('api/auth/login', {
      method: 'POST',
      headers: {
        Accept: 'application/json',
        'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8',
        [session.csrfHeaderName]: session.csrfToken
      },
      body: new URLSearchParams({ username, password: passwordInput.value })
    });
    const authenticatedSession = await loadSession();
    if (!authenticatedSession.authenticated) throw new Error('登录状态未建立，请重试。');
    enterWorkspace();
  } catch (error) {
    showMessage(error.message || '登录失败，请稍后重试。', 'error');
  } finally {
    setBusy(false);
  }
});

togglePassword.addEventListener('click', () => {
  const visible = passwordInput.type === 'password';
  passwordInput.type = visible ? 'text' : 'password';
  togglePassword.textContent = visible ? '隐藏' : '显示';
  togglePassword.setAttribute('aria-label', visible ? '隐藏密码' : '显示密码');
  togglePassword.setAttribute('aria-pressed', String(visible));
});

window.addEventListener('pageshow', event => {
  if (event.persisted) { navigating = false; initializeLogin(); }
});
initializeLogin();
