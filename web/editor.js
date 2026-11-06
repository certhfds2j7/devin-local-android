import {EditorView, basicSetup} from "codemirror";
import {EditorState, Compartment} from "@codemirror/state";
import {javascript} from "@codemirror/lang-javascript";
import {python} from "@codemirror/lang-python";
import {html} from "@codemirror/lang-html";
import {css} from "@codemirror/lang-css";
import {oneDark} from "@codemirror/theme-one-dark";
let loading = false;
const language = new Compartment();
const theme = new Compartment();
const view = new EditorView({
  parent: document.getElementById("editor"),
  state: EditorState.create({extensions:[
    basicSetup, language.of([]), theme.of([]),
    EditorState.phrases.of({
      "Find":"查找", "Replace":"替换", "next":"下一个", "previous":"上一个",
      "all":"全选匹配", "match case":"区分大小写", "regexp":"正则表达式",
      "by word":"完整单词", "replace":"替换", "replace all":"全部替换", "close":"关闭",
      "Go to line":"跳转到行", "go":"跳转", "Control character":"控制字符",
      "Completions":"补全建议", "Diagnostics":"诊断", "No diagnostics":"没有诊断信息",
      "folded code":"已折叠代码", "unfold":"展开", "Fold line":"折叠本行", "Unfold line":"展开本行",
      "current match":"当前匹配", "on line":"所在行", "to":"至",
      "replaced $ matches":"已替换 $ 处匹配", "replaced match on line $":"已替换第 $ 行的匹配",
      "Selection deleted":"已删除选中内容"
    }),
    EditorView.lineWrapping,
    EditorView.theme({"&":{height:"100%"}, ".cm-scroller":{fontFamily:"monospace",fontSize:"14px"}}),
    EditorView.updateListener.of(update=>{
      if(update.docChanged && !loading) window.Native?.changed(update.state.doc.toString());
    })
  ]})
});
window.loadFile = (text, name, dark) => {
  loading = true;
  const lang = name.endsWith(".py") ? python() :
    /\.(html?|vue)$/.test(name) ? html() : name.endsWith(".css") ? css() :
    /\.[cm]?[jt]sx?$/.test(name) ? javascript({typescript:/\.tsx?$/.test(name),jsx:/\.[jt]sx$/.test(name)}) : [];
  view.dispatch({changes:{from:0,to:view.state.doc.length,insert:text},
    effects:[language.reconfigure(lang),theme.reconfigure(dark ? oneDark : [])]});
  loading = false;
};
window.Native?.ready();
