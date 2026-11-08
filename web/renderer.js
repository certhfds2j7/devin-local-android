import MarkdownIt from 'markdown-it';
import footnote from 'markdown-it-footnote';
import taskLists from 'markdown-it-task-lists';
import katex from 'katex';
import hljs from 'highlight.js/lib/common';
import createDOMPurify from 'dompurify';

function mathPlugin(md) {
  const formula = (source, displayMode) => katex.renderToString(source, {
    displayMode, throwOnError: false, trust: false, strict: 'ignore', maxExpand: 500, maxSize: 30
  });
  md.inline.ruler.before('escape', 'local_math', (state, silent) => {
    const start = state.pos;
    const open = state.src.startsWith('\\(', start) ? '\\(' : state.src[start] === '$' && state.src[start + 1] !== '$' ? '$' : null;
    if (!open) return false;
    const close = open === '$' ? '$' : '\\)';
    let end = state.src.indexOf(close, start + open.length);
    while (end > 0 && state.src[end - 1] === '\\') end = state.src.indexOf(close, end + close.length);
    if (end < 0 || end === start + open.length || state.src.slice(start, end).includes('\n')) return false;
    if (!silent) {
      const token = state.push('local_math', 'math', 0);
      token.content = state.src.slice(start + open.length, end);
    }
    state.pos = end + close.length;
    return true;
  });
  md.block.ruler.before('fence', 'local_math_block', (state, start, end, silent) => {
    const first = state.src.slice(state.bMarks[start] + state.tShift[start], state.eMarks[start]).trim();
    const open = first.startsWith('$$') ? '$$' : first.startsWith('\\[') ? '\\[' : null;
    if (!open || state.sCount[start] - state.blkIndent >= 4) return false;
    const close = open === '$$' ? '$$' : '\\]';
    let content = first.slice(open.length), line = start;
    let position = content.indexOf(close);
    while (position < 0 && ++line < end) {
      const next = state.src.slice(state.bMarks[line], state.eMarks[line]);
      content += '\n' + next;
      position = content.indexOf(close);
    }
    if (position < 0) return false;
    if (silent) return true;
    const token = state.push('local_math_block', 'math', 0);
    token.block = true; token.content = content.slice(0, position); token.map = [start, line + 1];
    state.line = line + 1;
    return true;
  }, {alt: ['paragraph', 'reference', 'blockquote', 'list']});
  md.renderer.rules.local_math = (tokens, index) => formula(tokens[index].content, false);
  md.renderer.rules.local_math_block = (tokens, index) => formula(tokens[index].content, true);
}

export function createRenderer(window) {
  const purifier = createDOMPurify(window);
  const md = new MarkdownIt({html: false, linkify: true, typographer: false, breaks: false,
    highlight(source, language) {
      return language && hljs.getLanguage(language) ? hljs.highlight(source, {language, ignoreIllegals: true}).value : '';
    }
  }).use(footnote).use(taskLists, {enabled: false}).use(mathPlugin);
  const validateLink = md.validateLink.bind(md);
  md.validateLink = href => /^file:\/\/(?:\/|localhost\/)/i.test(href) || validateLink(href);
  const escape = md.utils.escapeHtml;
  const local = href => !/^(?:[a-z][a-z0-9+.-]*:|\/\/)/i.test(href) || /^file:\/\//i.test(href);
  md.renderer.rules.link_open = (tokens, index) => {
    const href = tokens[index].attrGet('href') || '';
    if (href.startsWith('#')) return '<a href="' + escape(href) + '">';
    return '<a href="#" data-href="' + escape(href) + '">';
  };
  md.renderer.rules.image = (tokens, index) => {
    const token = tokens[index], href = token.attrGet('src') || '', alt = token.content || '图片';
    if (local(href)) return '<img loading="lazy" alt="' + escape(alt) + '" src="https://appassets.androidplatform.net/project-image?path=' + encodeURIComponent(href) + '">';
    return '<a href="#" data-href="' + escape(href) + '">查看图片：' + escape(alt) + '</a>';
  };
  return {
    markdown(text) {
      return purifier.sanitize(md.render(text), {USE_PROFILES: {html: true, mathMl: true, svg: true},
        FORBID_TAGS: ['script', 'iframe', 'object', 'embed', 'form'], FORBID_ATTR: ['srcdoc', 'formaction']});
    }, escape
  };
}
