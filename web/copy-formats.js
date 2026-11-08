export function copyFormats(window, md, renderedHtml, source) {
  const document = window.document, holder = document.createElement('div');
  holder.innerHTML = renderedHtml;
  for (const math of holder.querySelectorAll('.math-source')) {
    math.replaceWith(document.createTextNode(math.dataset.tex || math.textContent));
  }
  for (const image of holder.querySelectorAll('img')) image.replaceWith(document.createTextNode(image.alt || '图片'));
  function text(node) {
    if (node.nodeType === 3) return node.nodeValue;
    const tag = node.tagName?.toLowerCase();
    if (tag === 'br') return '\n';
    if (tag === 'tr') return [...node.children].map(text).join('\t') + '\n';
    if (tag === 'pre') return node.textContent + '\n';
    const inner = [...node.childNodes].map(text).join('');
    if (tag === 'li') return '- ' + inner.trim() + '\n';
    return /^(p|div|h[1-6]|blockquote|ul|ol|table|section|span)$/.test(tag || '') &&
      (tag !== 'span' || node.classList.contains('katex-display')) ? inner + '\n' : inner;
  }
  const plain = text(holder).replace(/\n{3,}/g, '\n\n').trim();
  const rich = document.createElement('div'); rich.innerHTML = renderedHtml;
  for (const math of rich.querySelectorAll('.katex')) {
    const mathml = math.querySelector('math');
    if (mathml) {
      const clone = mathml.cloneNode(true);
      // Sanitization may unwrap KaTeX's TeX annotation into a direct text node.
      for (const child of [...clone.childNodes]) if (child.nodeType === 3) child.remove();
      math.replaceWith(clone);
    }
  }
  for (const image of rich.querySelectorAll('img')) image.replaceWith(document.createTextNode(image.alt || '图片'));
  for (const link of rich.querySelectorAll('a[data-href]')) {
    const href = link.dataset.href || '';
    if (/^(https?:|mailto:)/i.test(href)) link.setAttribute('href', href);
    else link.replaceWith(document.createTextNode(link.textContent));
  }
  for (const element of rich.querySelectorAll('*')) {
    for (const attribute of [...element.attributes]) if (attribute.name.startsWith('data-')) element.removeAttribute(attribute.name);
    if (element.tagName === 'TABLE') element.setAttribute('style','border-collapse:collapse');
    if (/^(TH|TD)$/.test(element.tagName)) element.setAttribute('style','border:1px solid #999;padding:6px');
    if (element.tagName === 'PRE') element.setAttribute('style','white-space:pre-wrap;background:#f2f4f4;padding:10px;font-family:monospace');
  }
  const codes = md.parse(source, {}).filter(token => token.type === 'fence' || token.type === 'code_block')
    .map(token => ({language: token.info.trim().split(/\s+/)[0] || '', code: token.content}));
  return {source, plain, html: rich.innerHTML, markdown: source, codes};
}
