import test from 'node:test';
import assert from 'node:assert/strict';
import {JSDOM} from 'jsdom';
import {createRenderer} from './renderer.js';
import {messageActions, appendToolbar} from './message-tools.js';
const window = new JSDOM('').window, renderer = createRenderer(window);
test('copy formats preserve readable text, formatting, exact Markdown and individual code blocks', () => {
  const source = '# 标题\n\n**粗体**，$x^2$\n\n| 名称 | 值 |\n|---|---|\n| 中文 | 12 |\n\n```python\nif True:\n    print("中文")\n```\n\n```sh\necho done\n```';
  const result = renderer.copyFormats(source);
  assert.equal(result.markdown, source);
  assert.match(result.plain, /粗体，x\^2/); assert.doesNotMatch(result.plain, /\*\*|```|katex/);
  assert.match(result.plain, /名称\t值/);
  assert.deepEqual(result.codes, [{language:'python', code:'if True:\n    print("中文")\n'}, {language:'sh', code:'echo done\n'}]);
  const page = new JSDOM(result.html).window.document;
  assert.equal(page.querySelector('strong').textContent, '粗体');
  assert.ok(page.querySelector('table')); assert.ok(page.querySelector('math'));
  assert.equal(page.querySelectorAll('.katex-html').length, 0);
});
test('rich clipboard removes native links and offline resource URLs and cannot execute source HTML', () => {
  const result = renderer.copyFormats('[本地](output.txt) [官网](https://example.com)\n\n![图](output.png)\n\n<script>alert(1)</script>');
  assert.doesNotMatch(result.html, /data-href|appassets|<script|<img|href="#"/);
  assert.match(result.html, /href="https:\/\/example.com"/);
  assert.equal(renderer.copyFormats('没有代码').codes.length, 0);
});
test('tools target only assistant answers or identified user turns, busy deletion is disabled', () => {
  assert.deepEqual(messageActions({role:'tool'}), []);
  assert.deepEqual(messageActions({role:'user'}), []);
  assert.deepEqual(messageActions({role:'assistant'}, {fullscreen:true}).map(x=>x.label), ['复制文本','退出全屏']);
  const actions = messageActions({role:'user',turnId:'one'}, {busy:true});
  assert.ok(actions.every(x=>x.disabled));
  const doc = window.document, article = doc.createElement('article'), row = {role:'user',turnId:'two',id:'u2'};
  let clicked;
  appendToolbar(doc, article, row, {}, (action,value)=>{clicked=[action,value.id];});
  article.querySelectorAll('button')[1].click();
  assert.deepEqual(clicked,['delete-turn','u2']);
});
