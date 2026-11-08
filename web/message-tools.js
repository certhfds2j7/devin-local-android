export function buildMessageBody(document, renderer, row) {
  const fragment = document.createElement('div');
  fragment.className = 'body';
  if (row.role === 'assistant') fragment.innerHTML = renderer.markdown(row.text || '');
  else { const pre = document.createElement('pre'); pre.textContent = row.text || ''; fragment.append(pre); }
  for (const file of row.files || []) {
    const path = file.path || file.uri;
    if (!path) continue;
    const link = document.createElement('a');
    link.href = '#'; link.dataset.href = path; link.className = 'attachment';
    link.textContent = '↓ ' + (file.name || path.split('/').pop() || '附件');
    fragment.append(link);
  }
  return fragment;
}

export function messageActions(row, {busy = false, fullscreen = false} = {}) {
  if (row.role === 'assistant') return [
    {action: 'copy', label: '复制文本', disabled: false},
    {action: 'fullscreen', label: fullscreen ? '退出全屏' : '全屏', disabled: false}
  ];
  if (row.role === 'user' && row.turnId) return [
    {action: 'restart-turn', label: '从此处重新开始', disabled: busy},
    {action: 'delete-turn', label: '删除该段对话', disabled: busy}
  ];
  return [];
}

export function appendToolbar(document, node, row, options, callback) {
  const actions = messageActions(row, options);
  if (!actions.length) return;
  const toolbar = document.createElement('footer'); toolbar.className = 'message-tools';
  for (const action of actions) {
    const button = document.createElement('button'); button.type = 'button';
    button.textContent = action.label; button.disabled = action.disabled;
    button.addEventListener('click', () => callback(action.action, row));
    toolbar.append(button);
  }
  node.append(toolbar);
}
