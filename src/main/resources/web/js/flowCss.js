// flowCss.js — All CSS for the Team/Flow visualization panels.
// Imported once by flowCanvas.js on first render.

export const FLOW_CSS = `
<style id="flow-canvas-style">
/* Tab pane: establish positioning context so overlays center within it */
.canvas-tab-pane[data-type="flow"] {
  position: relative;
  overflow: hidden;
}

/* Scroll container — fills the tab pane */
.flow-scroll {
  position: absolute; top: 0; left: 0; right: 0; bottom: 12px;
  overflow-y: auto; padding: 14px 14px 24px;
  display: flex; flex-wrap: wrap; align-content: flex-start; gap: 14px;
}
.flow-scroll::-webkit-scrollbar { width: 8px; }
.flow-scroll::-webkit-scrollbar-thumb { background: var(--color-border); border-radius: 4px; }

/* Team card */
.flow-card {
  flex: 1 1 280px; min-width: 260px; max-width: 460px;
  border-radius: 16px; background: var(--color-surface);
  border: 1px solid var(--glass-border);
  box-shadow: 0 2px 8px rgba(0,0,0,0.05), 0 4px 16px rgba(0,0,0,0.03);
  overflow: hidden; display: flex; flex-direction: column;
}
.flow-card-header {
  display: flex; align-items: center; gap: 8px; padding: 12px 14px;
  border-bottom: 1px solid var(--glass-border);
  background: var(--glass-bg);
  -webkit-backdrop-filter: blur(var(--glass-blur)) saturate(1.15);
  backdrop-filter: blur(var(--glass-blur)) saturate(1.15);
}
.flow-card-title {
  font: 600 13px -apple-system, sans-serif; color: var(--color-text);
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
  cursor: pointer; transition: color 0.15s;
}
.flow-card-title[data-act="def"]:hover { color: var(--color-primary, #07c160); }
.flow-card-actions { display: flex; gap: 4px; flex-shrink: 0; }
.flow-act-btn {
  width: 26px; height: 26px; border: none; background: transparent;
  border-radius: 7px; color: var(--color-text-muted); cursor: pointer;
  display: flex; align-items: center; justify-content: center;
  font: 600 13px -apple-system, sans-serif; transition: background 0.15s, color 0.15s;
}
.flow-act-btn svg { width: 15px; height: 15px; }
.flow-act-btn:hover { background: var(--color-frame-hover, rgba(0,0,0,0.06)); color: var(--color-text); }
.flow-card-summary {
  font: 500 11px -apple-system, sans-serif; color: var(--color-text-muted);
  flex-shrink: 0; margin-left: auto; display: flex; align-items: center; gap: 5px;
}
.flow-card-summary .dot { width: 7px; height: 7px; border-radius: 50%; background: var(--color-text-muted); opacity: 0.4; }
.flow-card-summary.running .dot { background: var(--color-primary, #07c160); opacity: 1; animation: flow-pulse 1.6s ease-out infinite; }
@keyframes flow-pulse { 0% { box-shadow: 0 0 0 0 rgba(7,193,96,0.45); } 100% { box-shadow: 0 0 0 8px rgba(7,193,96,0); } }

.flow-agents { display: grid; grid-template-columns: repeat(auto-fill, minmax(96px, 1fr)); gap: 8px; padding: 14px; }
.flow-tile {
  display: flex; flex-direction: column; align-items: center; gap: 6px;
  padding: 12px 8px; border-radius: 11px; border: 1px solid var(--glass-border);
  background: var(--color-surface); cursor: pointer; text-align: center;
  transition: border-color 0.15s, box-shadow 0.15s, background 0.15s; min-width: 0;
}
.flow-tile:hover { border-color: var(--color-primary, #07c160); box-shadow: 0 0 0 3px rgba(7,193,96,0.10); }
.flow-tile.manager { border-color: var(--color-primary, #07c160); background: var(--glass-bg); }
.flow-tile-dot { width: 9px; height: 9px; border-radius: 50%; background: var(--color-text-muted); opacity: 0.4; flex-shrink: 0; }
.flow-tile.running .flow-tile-dot { background: var(--color-primary, #07c160); opacity: 1; animation: flow-pulse 1.6s ease-out infinite; }

.flow-tags { display: flex; flex-wrap: wrap; gap: 6px; padding: 0 14px 14px; }
.flow-tag {
  font: 500 11px -apple-system, sans-serif; color: var(--color-primary, #07c160);
  padding: 3px 10px; border-radius: 20px; border: 1px solid var(--color-primary, #07c160);
  opacity: 0.7; cursor: pointer; transition: opacity 0.15s, background 0.15s; white-space: nowrap;
}
.flow-tag:hover { opacity: 1; background: rgba(7,193,96,0.08); }
.flow-tile.mail-flash .flow-tile-dot { background: var(--color-primary, #07c160); opacity: 1; transition: none; }
.flow-tile-name { font: 600 12px -apple-system, sans-serif; color: var(--color-text); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; max-width: 100%; }
.flow-tile-role { font: 400 10px -apple-system, sans-serif; color: var(--color-text-muted); text-transform: uppercase; letter-spacing: 0.04em; }

.flow-empty { display: flex; align-items: center; justify-content: center; width: 100%; height: 100%; min-height: 240px; flex-direction: column; gap: 8px; }
.flow-empty .hint { font: 400 11px -apple-system, sans-serif; color: var(--color-text-muted); opacity: 0.6; }

/* DAG flow card */
.dag-card {
  flex: 1 1 320px; min-width: 300px; max-width: 520px;
  border-radius: 16px; background: var(--color-surface);
  border: 1px solid var(--glass-border);
  box-shadow: 0 2px 8px rgba(0,0,0,0.05), 0 4px 16px rgba(0,0,0,0.03);
  overflow: hidden; display: flex; flex-direction: column;
}
.dag-card-header { display: flex; align-items: center; gap: 8px; padding: 12px 14px; border-bottom: 1px solid var(--glass-border); background: var(--glass-bg); -webkit-backdrop-filter: blur(var(--glass-blur)) saturate(1.15); backdrop-filter: blur(var(--glass-blur)) saturate(1.15); }
.dag-card-title { font: 600 13px -apple-system, sans-serif; color: var(--color-text); }
.dag-card-status { font: 500 11px -apple-system, sans-serif; color: var(--color-text-muted); margin-left: auto; display: flex; align-items: center; gap: 5px; }
.dag-card-status .dot { width: 7px; height: 7px; border-radius: 50%; background: var(--color-text-muted); opacity: 0.4; }
.dag-card-status.running .dot { background: var(--color-primary, #07c160); opacity: 1; animation: flow-pulse 1.6s ease-out infinite; }
.dag-card-status.completed .dot { background: #4caf50; opacity: 1; }
.dag-card-status.failed .dot { background: #f44336; opacity: 1; }
.dag-card-desc { font: 400 11px -apple-system, sans-serif; color: var(--color-text-muted); padding: 10px 14px 0; }
.dag-nodes { padding: 14px; display: flex; flex-direction: column; align-items: center; gap: 0; }
.dag-node { display: flex; align-items: center; gap: 10px; padding: 10px 16px; border-radius: 12px; border: 1.5px solid var(--glass-border); background: var(--color-surface); cursor: pointer; transition: border-color 0.15s, box-shadow 0.15s; min-width: 180px; position: relative; }
.dag-node:hover { border-color: var(--color-primary, #07c160); box-shadow: 0 0 0 3px rgba(7,193,96,0.10); }
.dag-node.pending { opacity: 0.5; }
.dag-node.running { border-color: var(--color-primary, #07c160); box-shadow: 0 0 0 3px rgba(7,193,96,0.10); }
.dag-node.completed { border-color: rgba(76,175,80,0.4); }
.dag-node.failed { border-color: rgba(244,67,54,0.5); background: rgba(244,67,54,0.04); }
.dag-node-dot { width: 9px; height: 9px; border-radius: 50%; background: var(--color-text-muted); opacity: 0.4; flex-shrink: 0; }
.dag-node.running .dag-node-dot { background: var(--color-primary, #07c160); opacity: 1; animation: flow-pulse 1.6s ease-out infinite; }
.dag-node.completed .dag-node-dot { background: #4caf50; opacity: 1; }
.dag-node.failed .dag-node-dot { background: #f44336; opacity: 1; }
.dag-node-info { display: flex; flex-direction: column; gap: 2px; min-width: 0; }
.dag-node-id { font: 600 12px -apple-system, sans-serif; color: var(--color-text); }
.dag-node-agent { font: 400 10px -apple-system, sans-serif; color: var(--color-text-muted); }
.dag-node-output { font: 400 10px ui-monospace, SFMono-Regular, monospace; color: var(--color-text-muted); max-width: 220px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; margin-top: 2px; }
.dag-edge { width: 2px; height: 20px; background: var(--glass-border); margin: 0 auto; position: relative; }
.dag-edge-label { position: absolute; left: 10px; top: 50%; transform: translateY(-50%); font: 400 9px -apple-system, sans-serif; color: var(--color-text-muted); background: var(--color-surface); padding: 1px 5px; border-radius: 4px; white-space: nowrap; }

/* Overlay viewer — fixed within pane, centered, ignores scroll */
.flow-viewer-overlay { position: absolute; inset: 0; display: flex; align-items: center; justify-content: center; z-index: 60; pointer-events: auto; animation: flow-viewer-fade 0.18s ease; }
@keyframes flow-viewer-fade { from { opacity: 0; } to { opacity: 1; } }
.flow-viewer { position: relative; width: calc(100% - 48px); max-height: calc(100% - 48px); display: flex; flex-direction: column; background: var(--glass-bg); -webkit-backdrop-filter: blur(var(--glass-blur)) saturate(1.15); backdrop-filter: blur(var(--glass-blur)) saturate(1.15); border: 1px solid var(--glass-border); border-radius: 18px; overflow: hidden; box-shadow: 0px 8px 32px rgba(0,0,0,0.16); }
.flow-viewer-header { display: flex; align-items: center; gap: 8px; padding: 11px 16px; border-bottom: 1px solid var(--glass-border); flex-shrink: 0; }
.flow-viewer-title { font: 600 13px -apple-system, sans-serif; color: var(--color-text); }
.flow-viewer-close { margin-left: auto; cursor: pointer; font-size: 16px; line-height: 1; opacity: 0.5; transition: opacity 0.15s; padding: 0 4px; color: var(--color-text-muted); z-index: 10; }
.flow-viewer-close:hover { opacity: 1; }
.flow-viewer .code-copy-btn { right: 40px; }
.flow-viewer-body { flex: 1; overflow-y: auto; padding: 14px 18px; scrollbar-color: var(--color-frame-border) transparent; }
.flow-viewer-footer { display: flex; align-items: center; gap: 8px; justify-content: flex-end; padding: 10px 16px; border-top: 1px solid var(--glass-border); flex-shrink: 0; }
.flow-viewer-save { font: 600 12px -apple-system, sans-serif; color: #fff; background: var(--color-primary, #07c160); border: none; border-radius: 8px; padding: 6px 16px; cursor: pointer; transition: opacity 0.15s; }
.flow-viewer-save:hover { opacity: 0.88; }
.flow-viewer-save:disabled { opacity: 0.5; cursor: default; }
.flow-viewer-status { font: 400 11px -apple-system, sans-serif; color: var(--color-text-muted); margin-right: auto; }

/* Mail records */
.flow-mail-empty { font: 400 12px -apple-system, sans-serif; color: var(--color-text-muted); opacity: 0.6; text-align: center; padding: 40px 0; }
.flow-mail-row { display: flex; flex-direction: column; gap: 5px; padding: 12px 14px; border: 1px solid rgba(128,128,128,0.12); border-radius: 8px; background: rgba(128,128,128,0.06); margin-bottom: 8px; cursor: pointer; transition: background 0.15s, border-color 0.15s; }
.flow-mail-row:hover { background: rgba(128,128,128,0.10); border-color: rgba(128,128,128,0.18); }
.flow-mail-row.expanded { background: rgba(128,128,128,0.10); border-color: rgba(91,127,191,0.25); }
.flow-mail-meta { display: flex; align-items: center; gap: 6px; font: 500 11px -apple-system, sans-serif; flex-wrap: wrap; }
.flow-mail-from { color: var(--color-primary, #07c160); max-width: 120px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.flow-mail-arrow { color: var(--color-text-muted); opacity: 0.6; flex-shrink: 0; }
.flow-mail-to { color: var(--color-text); font-weight: 600; max-width: 120px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.flow-mail-time { margin-left: auto; font: 400 10px -apple-system, sans-serif; color: var(--color-text-muted); opacity: 0.7; flex-shrink: 0; }
.flow-mail-expand { font: 500 10px -apple-system, sans-serif; color: var(--color-primary, #07c160); opacity: 0.85; flex-shrink: 0; cursor: pointer; user-select: none; }
.flow-mail-content { font: 400 12.5px -apple-system, sans-serif; color: var(--color-text); line-height: 1.55; word-break: break-word; overflow-wrap: anywhere; overflow-x: auto; max-width: 100%; position: relative; }
.flow-mail-row:not(.expanded) .flow-mail-content { max-height: 3.6em; overflow: hidden; }
.flow-mail-row:not(.expanded) .flow-mail-content::after { content: ''; position: absolute; bottom: 0; left: 0; right: 0; height: 1.4em; background: linear-gradient(to bottom, transparent, rgba(128,128,128,0.06)); pointer-events: none; }
.flow-mail-content p:first-child { margin-top: 0; } .flow-mail-content p:last-child { margin-bottom: 0; } .flow-mail-content p { margin: 4px 0; }
.flow-mail-content ul, .flow-mail-content ol { margin: 4px 0 4px 18px; padding: 0; } .flow-mail-content li { margin: 2px 0; }
.flow-mail-content code { background: var(--color-frame-input-bg, rgba(0,0,0,0.05)); padding: 1px 4px; border-radius: 3px; font-size: 0.9em; font-family: ui-monospace, SFMono-Regular, monospace; word-break: break-all; }
.flow-mail-content pre { background: var(--color-frame-input-bg, rgba(0,0,0,0.05)); padding: 8px 12px 10px; border-radius: 8px; overflow-x: auto; font-size: 12px; margin: 6px 0; line-height: 1.4; }
.flow-mail-content pre code { background: none; padding: 0; font-size: 12px; word-break: normal; }
.flow-mail-content blockquote { border-left: 3px solid var(--color-border); margin: 6px 0; padding-left: 10px; color: var(--color-text-muted); }
.flow-mail-content h1, .flow-mail-content h2, .flow-mail-content h3, .flow-mail-content h4 { margin: 8px 0 4px 0; font-weight: 600; }
.flow-mail-content h1 { font-size: 16px; } .flow-mail-content h2 { font-size: 15px; } .flow-mail-content h3 { font-size: 14px; }
.flow-mail-content table { display: block; overflow-x: auto; max-width: 100%; border-collapse: collapse; margin: 6px 0; font-size: 12px; }
.flow-mail-content th, .flow-mail-content td { border: 1px solid var(--color-border); padding: 4px 8px; }
.flow-mail-content th { background: var(--color-frame-input-bg, #f5f5f5); }
.flow-mail-content a { color: var(--color-primary); } .flow-mail-content hr { border: none; border-top: 1px solid var(--color-border); margin: 8px 0; }
.flow-mail-content strong { font-weight: 600; } .flow-mail-content img { max-width: 100%; border-radius: 8px; }

/* Definition editor */
.flow-def-section { margin-bottom: 16px; }
.flow-def-section h3 { font: 600 12px -apple-system, sans-serif; color: var(--color-text-muted); text-transform: uppercase; letter-spacing: 0.05em; margin: 0 0 6px; }
.flow-def-meta { font: 400 12px -apple-system, sans-serif; color: var(--color-text); line-height: 1.5; }
.flow-agent-block { border: 1px solid rgba(128,128,128,0.12); border-radius: 10px; background: rgba(128,128,128,0.06); padding: 14px 16px; margin-bottom: 16px; }
.flow-agent-block-head { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; background: var(--glass-etched-bg, rgba(0,0,0,0.025)); border-radius: 8px; padding: 6px 10px; }
.flow-agent-block-name { font: 600 13px -apple-system, sans-serif; color: var(--color-text); border-left: 3px solid rgb(91, 127, 191); padding-left: 8px; }
.flow-agent-block.is-manager .flow-agent-block-name { border-left-color: var(--color-primary, #07c160); }
.flow-agent-block-badge { font: 500 10px -apple-system, sans-serif; color: var(--color-primary, #07c160); border: 1px solid var(--color-primary, #07c160); border-radius: 4px; padding: 1px 5px; }
.flow-agent-block-ext { font: 400 11px -apple-system, sans-serif; color: var(--color-text-muted); }
.flow-agent-block-field { margin-top: 8px; } .flow-agent-block-field:first-of-type { margin-top: 4px; }
.flow-agent-block-label { display: block; font: 600 10px -apple-system, sans-serif; color: var(--color-text-muted); text-transform: uppercase; letter-spacing: 0.05em; margin-bottom: 3px; }
.flow-agent-block-text { font: 400 12.5px -apple-system, sans-serif; color: var(--color-text); line-height: 1.55; word-break: break-word; }
.flow-agent-block-text p:first-child { margin-top: 0; } .flow-agent-block-text p:last-child { margin-bottom: 0; }
.flow-agent-block-text code { background: var(--color-frame-input-bg, rgba(0,0,0,0.05)); padding: 1px 5px; border-radius: 4px; font-size: 0.9em; }
.flow-agent-block-text pre { background: var(--color-frame-input-bg, rgba(0,0,0,0.05)); padding: 8px 10px; border-radius: 6px; overflow-x: auto; }
.flow-agent-block-empty { color: var(--color-text-muted); opacity: 0.6; font-style: italic; }
.flow-agent-block-duty { font: 400 12px -apple-system, sans-serif; color: var(--color-text); white-space: pre-wrap; line-height: 1.5; margin: 4px 0; }
.flow-agent-block-tools { font: 400 11px -apple-system, sans-serif; color: var(--color-text-muted); margin-top: 4px; }
.flow-agent-block-tools code { background: var(--color-frame-input-bg, rgba(0,0,0,0.05)); padding: 1px 5px; border-radius: 4px; margin-right: 3px; }
.flow-def-edit { width: 100%; background: var(--glass-etched-bg, rgba(0,0,0,0.025)); color: var(--color-frame-input-text, var(--color-text)); border: 1px solid var(--glass-etched-border, var(--glass-border)); border-radius: 8px; padding: 8px 10px; font: 400 12px ui-monospace, SFMono-Regular, monospace; resize: vertical; outline: none; min-height: 60px; line-height: 1.5; transition: border-color 0.15s; }
.flow-def-edit:focus { border-color: var(--glass-etched-border-focus, rgba(91,127,191,0.35)); }
.flow-def-edit.flow-def-edit-sm { min-height: 44px; }
.flow-def-tools { display: flex; flex-wrap: wrap; gap: 5px; }
.flow-def-tool { display: inline-flex; align-items: center; gap: 4px; font: 500 11px -apple-system, sans-serif; color: var(--color-frame-text-dim, var(--color-text-muted)); cursor: pointer; user-select: none; background: var(--glass-etched-bg, rgba(0,0,0,0.025)); border: 1px solid var(--glass-etched-border, var(--glass-border)); border-radius: 6px; padding: 3px 8px; transition: all 0.15s; }
.flow-def-tool.checked { border-color: rgba(91, 127, 191, 0.5); color: rgb(91, 127, 191); background: rgba(91, 127, 191, 0.08); }
.flow-def-source { font: 400 10px -apple-system, sans-serif; color: var(--color-text-muted); opacity: 0.6; margin-bottom: 3px; }
.flow-agent-block-readonly { font: 400 12px -apple-system, sans-serif; color: var(--color-text); line-height: 1.5; background: var(--glass-etched-bg, rgba(0,0,0,0.025)); border: 1px solid var(--glass-etched-border, var(--glass-border)); border-radius: 8px; padding: 8px 10px; }
.flow-def-tabs { display: flex; gap: 2px; position: sticky; top: 0; background: var(--glass-bg); -webkit-backdrop-filter: blur(var(--glass-blur)) saturate(1.15); backdrop-filter: blur(var(--glass-blur)) saturate(1.15); border-bottom: 1px solid var(--glass-border); padding: 6px 2px 0; margin: -14px -18px 12px; z-index: 2; }
.flow-def-tab { padding: 6px 12px; font: 500 12px -apple-system, sans-serif; color: var(--color-text-muted); background: none; border: none; border-bottom: 2px solid transparent; border-radius: 0; cursor: pointer; font-family: inherit; margin-bottom: -1px; transition: color 0.2s, border-color 0.2s; display: inline-flex; align-items: center; gap: 4px; }
.flow-def-tab:hover { color: var(--color-text); }
.flow-def-tab.active { color: rgb(91, 127, 191); border-bottom-color: rgb(91, 127, 191); }
.flow-def-tab-badge { color: var(--color-primary, #07c160); font-size: 8px; line-height: 1; }
.flow-def-tab-content { display: none; } .flow-def-tab-content.active { display: block; }
</style>
`;
