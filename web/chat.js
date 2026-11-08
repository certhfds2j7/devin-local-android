import {createRenderer} from './renderer.js';
import 'katex/dist/katex.min.css';
import './chat.css';

const renderer = createRenderer(window), root = document.querySelector('#chat');
let pending = null, scheduled = false;
function render() {
  scheduled = false;
  const {rows, dark} = pending;
  document.documentElement.dataset.theme = dark ? 'dark' : 'light';
  const nearBottom = innerHeight + scrollY >= document.documentElement.scrollHeight - 120;
  while (root.children.length > rows.length) root.lastElementChild.remove();
  rows.forEach((row, index) => {
    let node = root.children[index];
    if (!node) {node = document.createElement('article'); root.append(node);}
    const signature = JSON.stringify(row);
    if (node.signature === signature) return;
    node.signature = signature; node.className = row.role;
    const label = ({user: '你', assistant: 'Devin', tool: '本地工具输出'})[row.role];
    const body = row.role === 'assistant' ? renderer.markdown(row.text || '') : '<pre>' + renderer.escape(row.text || '') + '</pre>';
    node.innerHTML = (label ? '<header>' + label + '</header>' : '') + '<div class="body">' + body + '</div>';
    for (const file of row.files || []) {
      const path = file.path || file.uri;
      if (!path) continue;
      const link = document.createElement('a');
      link.href = '#'; link.dataset.href = path; link.className = 'attachment';
      link.textContent = '↓ ' + (file.name || path.split('/').pop() || '附件');
      node.append(link);
    }
  });
  if (nearBottom) requestAnimationFrame(() => scrollTo(0, document.documentElement.scrollHeight));
}
window.renderChat = (rows, dark) => {
  pending = {rows, dark};
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
