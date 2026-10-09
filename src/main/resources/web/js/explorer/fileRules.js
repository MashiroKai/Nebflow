// explorer.js 拆分(FE组件化批次五 2026-09-28):文件规则小簇 可复用模块(行为保持)。
// 原 explorer.js :633-:665 FILE_ICONS/HIDDEN_DIRS 两张纯数据表与 getFileInfo/shouldHide
// 两个纯函数。getSiblingPaths(原 :109)已被选择状态族收编,不在本模块。
// $(原 :631)属主体 DOM helper,留守 explorer.js。

/** File extension → lucide icon name + color class. */
const FILE_ICONS = {
  scala: { icon: 'file-code', cls: 'f-scala' },
  java:  { icon: 'file-code', cls: 'f-java' },
  py:    { icon: 'file-code', cls: 'f-py' },
  js:    { icon: 'file-code', cls: 'f-js' },
  ts:    { icon: 'file-code', cls: 'f-ts' },
  css:   { icon: 'file-code', cls: 'f-css' },
  html:  { icon: 'file-code', cls: 'f-html' },
  json:  { icon: 'braces',    cls: 'f-json' },
  yaml:  { icon: 'file-text', cls: 'f-yaml' },
  yml:   { icon: 'file-text', cls: 'f-yaml' },
  md:    { icon: 'file-text', cls: 'f-md' },
  xml:   { icon: 'file-code', cls: 'f-xml' },
  sql:   { icon: 'database',  cls: 'f-sql' },
  sh:    { icon: 'terminal',  cls: 'f-sh' },
};

/** Directories to hide in the tree (build artifacts, VCS, etc.). */
const HIDDEN_DIRS = new Set([
  '.git', '.svn', 'target', 'node_modules', 'dist', 'build',
  '.gradle', '.idea', '.vscode', '__pycache__', '.cache',
  '.meta', 'DerivedData',
]);

export function getFileInfo(name) {
  const ext = name.split('.').pop()?.toLowerCase() || '';
  return FILE_ICONS[ext] || { icon: 'file', cls: 'f-default' };
}

export function shouldHide(name, isDir) {
  return isDir && HIDDEN_DIRS.has(name);
}
