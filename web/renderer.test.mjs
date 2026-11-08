import test from 'node:test';
import assert from 'node:assert/strict';
import {JSDOM} from 'jsdom';
import {createRenderer} from './renderer.js';
const window = new JSDOM('').window, renderer = createRenderer(window);
const document = source => new JSDOM(renderer.markdown(source)).window.document;
test('complex Markdown preserves tables, nested lists, tasks, footnotes and highlighted code', () => {
  const page = document('# 标题\n\n| 项目 | 状态 |\n|---|---|\n| 中文 | **完成** |\n\n- 父项\n  - 子项\n- [x] 已检查\n\n脚注[^1]\n\n[^1]: 来源\n\n```python\nprint("你好")\n```');
  assert.equal(page.querySelector('td').textContent, '中文');
  assert.ok(page.querySelector('ul ul')); assert.ok(page.querySelector('input[checked]'));
  assert.ok(page.querySelector('.footnotes')); assert.ok(page.querySelector('code span'));
});
test('inline and block LaTeX include fractions, matrices and backslash delimiters', () => {
  const page = document('行内 $x^2+1$ 和 \\(\\alpha+\\beta\\)。\n\n$$\n\\frac{1}{2}+\\begin{bmatrix}a&b\\\\c&d\\end{bmatrix}\n$$\n\n\\[\\int_0^1 x\\,dx\\]');
  assert.equal(page.querySelectorAll('.katex').length, 4);
  assert.equal(page.querySelectorAll('.katex-display').length, 2);
});
test('fenced code remains literal and incomplete streaming formula does not throw', () => {
  const page = document('```tex\n$x$\n```\n\n尚未结束 $\\frac{');
  assert.equal(page.querySelectorAll('.katex').length, 0);
  assert.match(page.body.textContent, /\$\\frac/);
});
test('untrusted HTML, protocols and LaTeX commands cannot execute or load remote resources', () => {
  const page = document('<script>window.pwned=1</script>\n\n<img src=x onerror=alert(1)>\n\n[坏链接](javascript:alert%281%29)\n\n$\\href{javascript:alert(1)}{bad}$\n\n![远程](https://example.invalid/track.png)');
  assert.equal(page.querySelectorAll('script,iframe,object,img,[onerror]').length, 0);
  assert.equal(page.querySelectorAll('[href^="javascript:"]').length, 0);
  assert.match(page.body.textContent, /查看图片/);
});
test('project links and local images remain native actions without revealing any bridge credential', () => {
  const page = document('[下载报告](reports/result.pdf)\n\n[官方文件](file:///mnt/devin-local/workspaces/project/files/processed.txt)\n\n![结果](output/chart.png)');
  assert.equal(page.querySelector('a').dataset.href, 'reports/result.pdf');
  assert.equal(page.querySelectorAll('a')[1].dataset.href, 'file:///mnt/devin-local/workspaces/project/files/processed.txt');
  assert.match(page.querySelector('img').src, /^https:\/\/appassets.androidplatform.net\/project-image\?path=/);
  assert.doesNotMatch(page.documentElement.outerHTML, /Bearer|127\.0\.0\.1|token=/);
});
