import {Terminal} from "@xterm/xterm";
import {FitAddon} from "@xterm/addon-fit";
import "@xterm/xterm/css/xterm.css";
const terminal = new Terminal({cursorBlink:true,fontSize:14,fontFamily:"monospace",
  theme:{background:"#111827",foreground:"#e5e7eb"},scrollback:3000});
const fit = new FitAddon();
terminal.loadAddon(fit);
terminal.open(document.getElementById("terminal"));
terminal.textarea?.setAttribute("aria-label","终端输入");
terminal.onData(data=>window.Native?.input(data));
terminal.onResize(size=>window.Native?.resize(size.cols,size.rows));
window.writeTerminal = data => terminal.write(Uint8Array.from(atob(data),c=>c.charCodeAt(0)));
window.sendKey = data => { window.Native?.input(data); terminal.focus(); };
window.fitTerminal = () => { fit.fit(); window.Native?.resize(terminal.cols,terminal.rows); };
new ResizeObserver(()=>{try{fit.fit();}catch{}}).observe(document.body);
setTimeout(()=>{fit.fit();window.Native?.ready();},100);
