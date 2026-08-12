// flowCss.js — All CSS for the Team/Flow visualization panels.
// Imported once by flowCanvas.js on first render.

export const FLOW_CSS = `
<style id="team-canvas-style">
/* Tab pane: establish positioning context so overlays center within it.
   Covers all flow-panel types: Teams list, Flows list, per-instance run. */
.canvas-tab-pane[data-type="flow"],
.canvas-tab-pane[data-type="teams"],
.canvas-tab-pane[data-type="flow-run"],
.canvas-tab-pane[data-type="flow-def"] {
  position: relative;
  overflow: hidden;
}

/* Scroll container — fills the tab pane */
.team-scroll {
  position: absolute; top: 0; left: 0; right: 0; bottom: 12px;
  overflow-y: auto; padding: 14px 14px 24px;
  display: flex; flex-wrap: wrap; align-content: flex-start; gap: 14px;
}
.team-scroll::-webkit-scrollbar { width: 8px; }
.team-scroll::-webkit-scrollbar-thumb { background: var(--color-border); border-radius: 4px; }

/* Team card */
.team-card {
  flex: 1 1 280px; min-width: 260px; max-width: 460px;
  border-radius: 16px; background: var(--color-surface);
  border: 1px solid var(--glass-border);
  box-shadow: 0 2px 8px rgba(0,0,0,0.05), 0 4px 16px rgba(0,0,0,0.03);
  overflow: hidden; display: flex; flex-direction: column;
}
.team-card-header {
  display: flex; align-items: center; gap: 8px; padding: 12px 14px;
  border-bottom: 1px solid var(--glass-border);
  background: var(--glass-bg);
  -webkit-backdrop-filter: blur(var(--glass-blur)) saturate(1.15);
  backdrop-filter: blur(var(--glass-blur)) saturate(1.15);
}
.team-card-title {
  font: 600 13px -apple-system, sans-serif; color: var(--color-text);
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
  cursor: pointer; transition: color 0.15s;
}
.team-card-title[data-act="def"]:hover { color: var(--color-primary, #07c160); }
.team-card-actions { display: flex; gap: 4px; flex-shrink: 0; }
.team-act-btn {
  width: 26px; height: 26px; border: 1px solid transparent; background: transparent;
  border-radius: 7px; color: var(--color-text-muted); cursor: pointer;
  display: flex; align-items: center; justify-content: center;
  font: 600 13px -apple-system, sans-serif;
  transition: background 0.15s, color 0.15s, border-color 0.15s, box-shadow 0.15s;
}
.team-act-btn svg { width: 15px; height: 15px; }
.team-act-btn:hover {
  background: var(--glass-control-bg-hover);
  -webkit-backdrop-filter: blur(var(--glass-control-blur)) saturate(1.2);
  backdrop-filter: blur(var(--glass-control-blur)) saturate(1.2);
  border-color: var(--glass-control-border);
  box-shadow:
    inset 0 1px 0 var(--glass-control-highlight),
    inset 0 -1px 0 var(--glass-control-underedge);
  color: var(--color-text);
}
.team-card-summary {
  font: 500 11px -apple-system, sans-serif; color: var(--color-text-muted);
  flex-shrink: 0; margin-left: auto; display: flex; align-items: center; gap: 5px;
}
.team-card-summary .dot { width: 7px; height: 7px; border-radius: 50%; background: var(--color-text-muted); opacity: 0.4; }
.team-card-summary.running .dot { background: var(--color-primary, #07c160); opacity: 1; animation: flow-pulse 1.6s ease-out infinite; }
@keyframes flow-pulse { 0% { box-shadow: 0 0 0 0 rgba(7,193,96,0.45); } 100% { box-shadow: 0 0 0 8px rgba(7,193,96,0); } }

.team-agents { display: grid; grid-template-columns: repeat(auto-fill, minmax(96px, 1fr)); gap: 8px; padding: 14px; }
.team-tile {
  display: flex; flex-direction: column; align-items: center; gap: 6px;
  padding: 12px 8px; border-radius: 11px; border: 1px solid var(--glass-border);
  background: var(--color-surface); cursor: pointer; text-align: center;
  transition: border-color 0.15s, box-shadow 0.15s, background 0.15s; min-width: 0;
}
.team-tile:hover { border-color: var(--color-primary, #07c160); box-shadow: 0 0 0 3px rgba(7,193,96,0.10); }
.team-tile.manager { border-color: var(--color-primary, #07c160); background: var(--glass-bg); }
.team-tile-dot { width: 9px; height: 9px; border-radius: 50%; background: var(--color-text-muted); opacity: 0.4; flex-shrink: 0; }
.team-tile.running .team-tile-dot { background: var(--color-primary, #07c160); opacity: 1; animation: flow-pulse 1.6s ease-out infinite; }

.team-flow-tags { display: flex; flex-wrap: wrap; gap: 6px; padding: 0 14px 14px; }
.team-flow-tag {
  font: 500 11px -apple-system, sans-serif; color: var(--color-primary, #07c160);
  padding: 3px 10px; border-radius: 20px; border: 1px solid var(--color-primary, #07c160);
  opacity: 0.7; cursor: pointer; transition: opacity 0.15s, background 0.15s; white-space: nowrap;
}
.team-flow-tag:hover { opacity: 1; background: rgba(7,193,96,0.08); }
.team-tile.mail-flash .team-tile-dot { background: var(--color-primary, #07c160); opacity: 1; transition: none; }
.team-tile-name { font: 600 12px -apple-system, sans-serif; color: var(--color-text); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; max-width: 100%; }
.team-tile-role { font: 400 10px -apple-system, sans-serif; color: var(--color-text-muted); text-transform: uppercase; letter-spacing: 0.04em; }

.team-empty { display: flex; align-items: center; justify-content: center; width: 100%; height: 100%; min-height: 240px; flex-direction: column; gap: 8px; }
.team-empty .hint { font: 400 11px -apple-system, sans-serif; color: var(--color-text-muted); opacity: 0.6; }
.dag-empty { display: flex; align-items: center; justify-content: center; width: 100%; height: 100%; min-height: 240px; flex-direction: column; gap: 8px; }
.dag-empty .hint { font: 400 11px -apple-system, sans-serif; color: var(--color-text-muted); opacity: 0.6; }

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
.dag-card-status.cancelled .dot { background: #ff9800; opacity: 1; }
.dag-card-cancel {
  margin: 0 14px 14px; align-self: flex-start;
  font: 600 11px -apple-system, sans-serif; color: #f44336;
  background: transparent; border: 1px solid rgba(244,67,54,0.4);
  border-radius: 8px; padding: 5px 14px; cursor: pointer;
  transition: background 0.15s, opacity 0.15s;
}
.dag-card-cancel:hover { background: rgba(244,67,54,0.08); }
.dag-card-cancel:disabled { opacity: 0.5; cursor: default; }
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

/* ── Flow rows in team cards ─────────────────────────────── */

.team-flows-divider {
  border-top: 1px dashed var(--glass-border);
  margin: 0 14px; opacity: 0.5;
}
.team-flows-section { padding: 0 14px 14px; }
.team-flows-header {
  font: 600 10px -apple-system, sans-serif; color: var(--color-text-muted);
  text-transform: uppercase; letter-spacing: 0.06em;
  margin: 10px 0 6px;
}

/* Hexagon icon — CSS clip-path, no image needed */
.team-flow-hex {
  width: 13px; height: 14px; flex-shrink: 0;
  background: var(--color-text-muted); opacity: 0.3;
  clip-path: polygon(50% 0%, 100% 25%, 100% 75%, 50% 100%, 0% 75%, 0% 25%);
  transition: background 0.2s, opacity 0.2s;
}

/* Flow row */
.team-flow-row {
  display: flex; align-items: center; gap: 8px;
  padding: 6px 8px; border-radius: 9px; cursor: pointer;
  transition: background 0.15s;
}
.team-flow-row:hover { background: rgba(128, 128, 128, 0.05); }
.team-flow-row.running .team-flow-hex { background: var(--color-primary, #07c160); opacity: 0.75; }
.team-flow-row.expanded .team-flow-hex { opacity: 0.6; }
.team-flow-label {
  font: 500 12px -apple-system, sans-serif; color: var(--color-text);
  flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.team-flow-row.running .team-flow-label { color: var(--color-text); }

/* Status dot — 8px, animated when running */
.team-flow-status {
  width: 8px; height: 8px; border-radius: 50%;
  background: var(--color-text-muted); opacity: 0.25; flex-shrink: 0;
}
.team-flow-row.running .team-flow-status {
  background: var(--color-primary, #07c160); opacity: 1;
  animation: flow-pulse 1.6s ease-out infinite;
}

/* Chevron — rotates on expand */
.team-flow-chevron {
  font-size: 9px; color: var(--color-text-muted); opacity: 0.5;
  flex-shrink: 0; transition: transform 0.2s; user-select: none;
  line-height: 1;
}
.team-flow-row.expanded .team-flow-chevron { transform: rotate(90deg); }

/* Expanded DAG inline area */
.team-flow-dag {
  padding: 2px 0 6px 14px; margin-left: 6px;
  border-left: 2px dashed var(--glass-border);
  display: flex; flex-direction: column; gap: 3px;
  margin-bottom: 2px;
}

/* DAG inline node — compact pill */
.dag-inline-node {
  display: flex; align-items: center; gap: 7px;
  padding: 4px 10px; border-radius: 7px;
  background: rgba(128, 128, 128, 0.03);
  border: 1px solid transparent; cursor: pointer;
  transition: background 0.15s, border-color 0.15s;
}
.dag-inline-node:hover { background: rgba(128, 128, 128, 0.07); border-color: var(--glass-border); }
.dag-inline-dot {
  width: 7px; height: 7px; border-radius: 50%; flex-shrink: 0;
  background: var(--color-text-muted); opacity: 0.3;
}
.dag-inline-node.running .dag-inline-dot { background: var(--color-primary, #07c160); opacity: 1; animation: flow-pulse 1.6s ease-out infinite; }
.dag-inline-node.completed .dag-inline-dot { background: #4caf50; opacity: 1; }
.dag-inline-node.failed .dag-inline-dot { background: #f44336; opacity: 1; }
.dag-inline-node.failed { background: rgba(244, 67, 54, 0.04); }
.dag-inline-id { font: 500 11px -apple-system, sans-serif; color: var(--color-text); }
.dag-inline-agent { font: 400 10px -apple-system, sans-serif; color: var(--color-text-muted); margin-left: auto; }

/* ── Per-agent model config in definition viewer ── */
.flow-agent-model-row { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.flow-agent-model-select {
  flex: 1; min-width: 0; height: 30px;
  border: 1px solid var(--glass-etched-border, var(--glass-border));
  border-radius: 8px; background: var(--glass-etched-bg, rgba(0,0,0,0.025));
  color: var(--color-text); font: 500 12px -apple-system, sans-serif;
  padding: 0 8px; cursor: pointer; outline: none;
  appearance: none; -webkit-appearance: none;
  background-image: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='10' height='6' viewBox='0 0 10 6' fill='none'%3E%3Cpath d='M1 1l4 4 4-4' stroke='%23888' stroke-width='1.5' stroke-linecap='round' stroke-linejoin='round'/%3E%3C/svg%3E");
  background-repeat: no-repeat; background-position: right 10px center; padding-right: 28px;
  transition: border-color 0.2s, box-shadow 0.2s;
}
.flow-agent-model-select:focus { border-color: rgba(91,127,191,0.35); box-shadow: 0 0 0 2px rgba(91,127,191,0.08); }
.flow-agent-model-current { font: 500 10px -apple-system, sans-serif; color: var(--color-text-muted); }
.flow-agent-model-current.fallback { color: rgb(91, 127, 191); background: rgba(91,127,191,0.10); padding: 2px 8px; border-radius: 6px; }
.flow-agent-model-fbs { display: flex; flex-wrap: wrap; gap: 4px; margin-top: 4px; }
.flow-agent-model-fb { font: 400 10px -apple-system, sans-serif; color: var(--color-text-muted); background: var(--glass-etched-bg, rgba(0,0,0,0.025)); border: 1px solid var(--glass-etched-border, var(--glass-border)); padding: 2px 8px; border-radius: 6px; }

/* ── Agent popup header model badge ── */
.flow-agent-model-badge { font: 500 10px -apple-system, sans-serif; color: rgb(91, 127, 191); background: rgba(91,127,191,0.08); padding: 2px 8px; border-radius: 6px; margin-left: 4px; }

/* ══ Solar-system flow visualization (P5) ══ */
.solar-card {
  flex: 1 1 100%; min-width: 300px;
  border-radius: 16px; background: var(--color-surface);
  border: 1px solid var(--glass-border);
  box-shadow: 0 2px 8px rgba(0,0,0,0.05), 0 4px 16px rgba(0,0,0,0.03);
  overflow: hidden; display: flex; flex-direction: column;
}
.solar-card-header {
  display: flex; align-items: center; gap: 8px; padding: 12px 14px;
  border-bottom: 1px solid var(--glass-border);
  background: var(--glass-bg);
  -webkit-backdrop-filter: blur(var(--glass-blur)) saturate(1.15);
  backdrop-filter: blur(var(--glass-blur)) saturate(1.15);
  flex-shrink: 0;
}
.solar-card-title { font: 600 13px -apple-system, sans-serif; color: var(--color-text); }
.solar-card-status { font: 500 11px -apple-system, sans-serif; color: var(--color-text-muted); margin-left: auto; display: flex; align-items: center; gap: 5px; }
.solar-card-status .dot { width: 7px; height: 7px; border-radius: 50%; background: var(--color-text-muted); opacity: 0.4; }
.solar-card-status.running .dot { background: var(--color-primary, #07c160); opacity: 1; animation: flow-pulse 1.6s ease-out infinite; }
.solar-card-status.completed .dot { background: #4caf50; opacity: 1; }
.solar-card-status.failed .dot { background: #f44336; opacity: 1; }
.solar-card-status.cancelled .dot { background: #ff9800; opacity: 1; }
.solar-card-desc { font: 400 11px -apple-system, sans-serif; color: var(--color-text-muted); padding: 10px 14px 0; flex-shrink: 0; }
.solar-card-footer { display: flex; align-items: center; padding: 0 14px 14px; flex-shrink: 0; }
.solar-card-footer:empty { display: none; }

/* Scrollable canvas wrapper */
.solar-scroll {
  overflow: auto; padding: 6px 14px 4px; flex: 1;
  scrollbar-color: var(--color-frame-border) transparent;
}
.solar-scroll::-webkit-scrollbar { width: 8px; height: 8px; }
.solar-scroll::-webkit-scrollbar-thumb { background: var(--color-border); border-radius: 4px; }

/* Canvas: absolute-positioned nodes + SVG edges */
.solar-canvas { position: relative; margin: 0 auto; }

/* SVG edges */
.solar-edges { position: absolute; top: 0; left: 0; pointer-events: none; overflow: visible; }
.flow-edge { stroke: var(--color-border); stroke-width: 1.5; fill: none; opacity: 0.5; }
.flow-edge.active { stroke: var(--color-accent, rgb(91,127,191)); stroke-dasharray: 6 4; opacity: 1; animation: dash-flow 1s linear infinite; }
.flow-edge-arrow { fill: var(--color-border); opacity: 0.5; }
.solar-edge-label { font: 500 9px -apple-system, sans-serif; fill: var(--color-text-muted); opacity: 0.7; }
.flow-edge-label { font: 400 9px ui-monospace; fill: var(--color-text-muted); }
@keyframes dash-flow { to { stroke-dashoffset: -10; } }

/* Node card */
.solar-node {
  position: absolute; width: 130px; height: 150px;
  display: flex; flex-direction: column; align-items: center;
  cursor: pointer; user-select: none;
  border-radius: 14px; border: 1px solid transparent;
  transition: border-color 0.15s, background 0.15s, box-shadow 0.15s;
}
.solar-node:hover { border-color: var(--glass-border); background: var(--glass-bg); box-shadow: 0 2px 10px rgba(0,0,0,0.05); }
.solar-node.running { border-color: rgba(91,127,191,0.25); }

/* Orbit area */
.solar-rings { position: relative; width: 100px; height: 100px; margin-top: 2px; flex-shrink: 0; }

/* Three concentric rings — scoped to solar-rings (margin-based centering) */
.solar-rings .flow-ring {
  position: absolute; top: 50%; left: 50%;
  margin: -50px 0 0 -50px;
  border-radius: 50%;
  border: 1.5px solid var(--color-border);
  box-sizing: border-box;
}
.solar-rings .flow-ring.outer  { width: 100px; height: 100px; }
.solar-rings .flow-ring.middle { width: 74px;  height: 74px;  margin: -37px 0 0 -37px; }
.solar-rings .flow-ring.inner  { width: 48px;  height: 48px;  margin: -24px 0 0 -24px; }

/* Orbit dots — scoped to solar-rings */
.solar-rings .flow-dot {
  position: absolute; top: 50%; left: 50%;
  width: 5px; height: 5px; margin: -2.5px 0 0 -2.5px;
  border-radius: 50%; background: var(--color-accent, rgb(91,127,191));
  opacity: 0.85;
}

/* Running: rings spin at different speeds */
.solar-node.running .flow-ring.outer.spin-outer  { animation: spin-cw  6s  linear infinite; }
.solar-node.running .flow-ring.middle.spin-middle{ animation: spin-ccw 4.5s linear infinite; }
.solar-node.running .flow-ring.inner.spin-inner  { animation: spin-cw  3s  linear infinite; }
@keyframes spin-cw  { from { transform: rotate(0deg); }   to { transform: rotate(360deg); } }
@keyframes spin-ccw { from { transform: rotate(0deg); }   to { transform: rotate(-360deg); } }

/* Pending: dashed, dim */
.solar-node.pending .flow-ring { border-style: dashed; opacity: 0.4; }
.solar-node.pending .flow-dot { opacity: 0.3; }

/* Completed: green solid rings */
.solar-node.completed .flow-ring { border-color: rgba(76,175,80,0.55); }
.solar-node.completed .flow-dot { background: #4caf50; }

/* Failed: red */
.solar-node.failed .flow-ring { border-color: rgba(244,67,54,0.6); }
.solar-node.failed .flow-dot { background: #f44336; }

/* Status icon (✓ / ✗) centered over rings */
.solar-node-status {
  position: absolute; top: 50%; left: 50%; transform: translate(-50%, -50%);
  font: 700 20px -apple-system, sans-serif; pointer-events: none;
}
.solar-node-status.ok { color: #4caf50; }
.solar-node-status.err { color: #f44336; }

/* Node label */
.solar-node-label {
  font: 600 12px -apple-system, sans-serif; color: var(--color-text);
  max-width: 120px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
  margin-top: 3px;
}
.solar-node-sub {
  font: 400 10px -apple-system, sans-serif; color: var(--color-text-muted);
  max-width: 120px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
  opacity: 0.7;
}

/* ══ Flow list page (P6) ══ */
.flow-list-header {
  width: 100%; display: flex; align-items: center; justify-content: space-between;
  padding: 4px 4px 2px;
}
.flow-list-title { font: 600 13px -apple-system, sans-serif; color: var(--color-text); }
.flow-list-count { font: 400 11px -apple-system, sans-serif; color: var(--color-text-muted); opacity: 0.7; }
.flow-def-card {
  flex: 1 1 300px; min-width: 280px; max-width: 480px;
  border-radius: 14px; background: var(--color-surface);
  border: 1px solid var(--glass-border);
  box-shadow: 0 2px 8px rgba(0,0,0,0.05), 0 4px 16px rgba(0,0,0,0.03);
  overflow: hidden; display: flex; flex-direction: column;
  transition: border-color 0.15s, box-shadow 0.15s;
}
.flow-def-card:hover { border-color: rgba(91,127,191,0.3); box-shadow: 0 3px 12px rgba(0,0,0,0.07); }
.flow-def-card-header {
  display: flex; align-items: center; gap: 8px; padding: 12px 14px;
  border-bottom: 1px solid var(--glass-border);
  background: var(--glass-bg);
  -webkit-backdrop-filter: blur(var(--glass-blur)) saturate(1.15);
  backdrop-filter: blur(var(--glass-blur)) saturate(1.15);
}
.flow-def-card-icon {
  width: 22px; height: 22px; border-radius: 7px; flex-shrink: 0;
  display: flex; align-items: center; justify-content: center;
  background: rgba(91,127,191,0.10); color: rgb(91,127,191);
  font: 600 11px -apple-system, sans-serif;
}
.flow-def-card-name { font: 600 13px -apple-system, sans-serif; color: var(--color-text); }
.flow-def-card-meta { font: 500 10px -apple-system, sans-serif; color: var(--color-text-muted); margin-left: auto; display: flex; gap: 8px; flex-shrink: 0; }
.flow-def-card-desc {
  font: 400 11.5px -apple-system, sans-serif; color: var(--color-text-muted);
  line-height: 1.5; padding: 10px 14px 4px; flex: 1;
  display: -webkit-box; -webkit-line-clamp: 3; -webkit-box-orient: vertical; overflow: hidden;
}
.flow-def-card-footer { display: flex; align-items: center; gap: 8px; padding: 10px 14px 12px; }
.flow-def-view-btn {
  font: 600 11px -apple-system, sans-serif; color: rgb(91,127,191);
  border: 1px solid rgba(91,127,191,0.35); border-radius: 7px;
  background: transparent; padding: 5px 12px; cursor: pointer;
  transition: background 0.15s, opacity 0.15s;
}
.flow-def-view-btn:hover { background: rgba(91,127,191,0.08); }
.flow-def-entry-tag {
  font: 500 10px -apple-system, sans-serif; color: var(--color-text-muted);
  border: 1px solid var(--color-border); border-radius: 5px; padding: 2px 7px; opacity: 0.75;
}

/* ===== Stellar System Visualization (flow-orbit-node, 3-point) ===== */
.stellar-container {
  position: absolute; top: 0; left: 0; right: 0; bottom: 0;
  overflow-y: auto; padding: 14px;
}
.stellar-container::-webkit-scrollbar { width: 8px; }
.stellar-container::-webkit-scrollbar-thumb { background: var(--color-border); border-radius: 4px; }

.stellar-card {
  max-width: 700px; margin: 0 auto 20px;
  border-radius: 16px; background: var(--color-surface);
  border: 1px solid var(--glass-border);
  box-shadow: 0 2px 8px rgba(0,0,0,0.05), 0 4px 16px rgba(0,0,0,0.03);
  overflow: hidden; display: flex; flex-direction: column;
}
.stellar-header {
  padding: 14px 18px; display: flex; align-items: center; justify-content: space-between;
  border-bottom: 1px solid var(--color-border);
}
.stellar-title { font: 600 15px -apple-system; color: var(--color-text); }
.stellar-status { font: 500 11px ui-monospace; padding: 2px 8px; border-radius: 6px; text-transform: uppercase; }
.stellar-status.running { color: var(--color-primary); background: rgba(91,127,191,0.1); }
.stellar-status.completed { color: #34c759; background: rgba(52,199,89,0.1); }
.stellar-status.failed { color: #ff3b30; background: rgba(255,59,48,0.1); }
.stellar-desc { padding: 8px 18px; font: 400 12px -apple-system; color: var(--color-text-muted); }
.stellar-stage {
  position: relative; width: 100%; min-height: 300px;
  padding: 20px 0;
}

/* Orbit node — centered horizontally in stage */
.flow-orbit-node {
  position: absolute; left: 50%; transform: translateX(-50%);
  width: 120px; height: 110px;
  display: flex; align-items: center; justify-content: center;
  cursor: pointer; z-index: 2;
}
.flow-orbit-content { position: relative; z-index: 3; text-align: center; pointer-events: none; }
.flow-orbit-agent { font: 600 11px -apple-system; color: var(--color-text); }
.flow-orbit-nodeid { font: 400 9px ui-monospace; color: var(--color-text-muted); margin-top: 2px; }
.flow-orbit-status { font: 600 16px -apple-system; margin-top: 2px; }

/* Three orbit rings — scoped to flow-orbit-node (transform-based centering) */
.flow-orbit-node .flow-ring {
  position: absolute; border-radius: 50%; top: 50%; left: 50%;
  border: 1.5px solid var(--color-border);
}
.flow-orbit-node .flow-ring.outer  { width: 100px; height: 100px; transform: translate(-50%, -50%); }
.flow-orbit-node .flow-ring.middle { width: 75px; height: 75px; transform: translate(-50%, -50%); }
.flow-orbit-node .flow-ring.inner  { width: 50px; height: 50px; transform: translate(-50%, -50%); }

/* Running: animated rotation (renamed keyframes to avoid clash with solar-node) */
.flow-orbit-node.running .flow-ring.outer  { border-color: rgba(91,127,191,0.4); animation: orbit-spin-cw 6s linear infinite; }
.flow-orbit-node.running .flow-ring.middle { border-color: rgba(91,127,191,0.3); animation: orbit-spin-ccw 4.5s linear infinite; }
.flow-orbit-node.running .flow-ring.inner  { border-color: rgba(91,127,191,0.2); animation: orbit-spin-cw 3s linear infinite; }

.flow-orbit-node.pending .flow-ring { border-style: dashed; opacity: 0.35; }
.flow-orbit-node.completed .flow-ring { border-color: #34c759; opacity: 0.6; }
.flow-orbit-node.completed .flow-orbit-status { color: #34c759; }
.flow-orbit-node.failed .flow-ring { border-color: #ff3b30; opacity: 0.6; }
.flow-orbit-node.failed .flow-orbit-status { color: #ff3b30; }

/* Orbit dots — scoped to flow-orbit-node */
.flow-orbit-node .flow-dot {
  position: absolute; width: 4px; height: 4px; border-radius: 50%;
  background: var(--color-primary, #5b7fbf); top: -2px; left: 50%; transform: translateX(-50%);
}
.flow-orbit-node.running .flow-dot { box-shadow: 0 0 6px rgba(91,127,191,0.6); }

@keyframes orbit-spin-cw  { from { transform: translate(-50%, -50%) rotate(0deg); } to { transform: translate(-50%, -50%) rotate(360deg); } }
@keyframes orbit-spin-ccw { from { transform: translate(-50%, -50%) rotate(0deg); } to { transform: translate(-50%, -50%) rotate(-360deg); } }

/* Flow definition cards (P6) — p5p6-v2 Flows tab */
.flow-defs-section, .flow-running-section { width: 100%; margin-bottom: 16px; }
.flow-defs-header { font: 600 11px -apple-system; color: var(--color-text-muted); text-transform: uppercase; letter-spacing: 0.5px; padding: 4px 0 10px; }
.flow-defs-grid { display: flex; flex-wrap: wrap; gap: 12px; }
.flow-def-card {
  flex: 1 1 240px; min-width: 220px; max-width: 360px;
  border-radius: 12px; background: var(--color-surface);
  border: 1px solid var(--glass-border);
  padding: 14px; display: flex; flex-direction: column; gap: 6px;
}
.flow-def-name { font: 600 13px -apple-system; color: var(--color-text); }
.flow-def-desc { font: 400 11px -apple-system; color: var(--color-text-muted); line-height: 1.4; }
.flow-def-meta { font: 400 10px ui-monospace; color: var(--color-text-muted); opacity: 0.7; }
.flow-def-view-btn {
  align-self: flex-start; font: 500 11px -apple-system;
  color: var(--color-primary, #5b7fbf); background: none;
  border: 1px solid rgba(91,127,191,0.3); border-radius: 6px;
  padding: 4px 10px; cursor: pointer; transition: all 0.15s;
}
.flow-def-view-btn:hover { background: rgba(91,127,191,0.08); }
</style>
`;
