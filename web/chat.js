import {createRenderer} from './renderer.js';
import {buildMessageBody, appendToolbar} from './message-tools.js';
import 'katex/dist/katex.min.css';
import './chat.css';

const renderer = createRenderer(window), root = document.querySelector('#chat');
let pending = null, scheduled = false;
function render() {
  scheduled = false;
  const {rows, dark, options} = pending;
  document.documentElement.dataset.theme = dark ? 'dark' : 'light';
  const nearBottom = !(options.fullscreen && root.children.length === 0) &&
    innerHeight + scrollY >= document.documentElement.scrollHeight - 120;
  while (root.children.length > rows.length) root.lastElementChild.remove();
  rows.forEach((row, index) => {
    let node = root.children[index];
    if (!node) {node = document.createElement('article'); root.append(node);}
    const signature = JSON.stringify([row, options]);
    if (node.signature === signature) return;
    node.signature = signature; node.className = row.role;
    const label = ({user: '你', assistant: 'Devin', tool: '本地工具输出'})[row.role];
    node.replaceChildren();
    if (label) {const header = document.createElement('header'); header.textContent = label; node.append(header);}
    node.append(buildMessageBody(document, renderer, row));
    appendToolbar(document, node, row, options, (action, value) => {
      if (action === 'copy') {
        const attachments = (value.files || []).map(file => file.name || file.path || file.uri || '').filter(Boolean).join('\n');
        const payload = renderer.copyFormats((value.text || '') + (attachments ? '\n\n' + attachments : ''));
        payload.originalText = value.text || '';
        window.Native?.copyRequest(value.id, JSON.stringify(payload));
      } else window.Native?.messageAction(action, value.id);
    });
  });
  if (nearBottom) requestAnimationFrame(() => scrollTo(0, document.documentElement.scrollHeight));
}
window.renderChat = (rows, dark, options = {}) => {
  pending = {rows, dark, options};
  if (!scheduled) {scheduled = true; setTimeout(render, 80);}
};
root.addEventListener('click', event => {
  const link = event.target.closest('a[data-href]');
  if (!link) return;
  event.preventDefault();
  const value = link.dataset.href || '';
  if (/^(?:javascript|data|intent|content):/i.test(value) || value.startsWith('//')) return;
  window.Native?.openLink(value);
});
window.Native?.ready();
