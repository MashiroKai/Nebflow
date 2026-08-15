// viewers/yaml.js — YAML viewer (structured view with syntax highlighting).
// Migrated verbatim from fileViewers.js (viewYaml).

import { escapeHtml } from './shared.js';

/** YAML viewer — structured view with syntax highlighting */
function viewYaml(pane, { content, fileName }) {
  const escaped = escapeHtml(content || '');
  // Simple YAML syntax highlighting: keys, comments, strings, numbers
  const highlighted = escaped
    .split('\n')
    .map(line => {
      // Comments
      if (line.trimStart().startsWith('#'))
        return `<span style="color:var(--color-text-muted)">${line}</span>`;
      // Key: value
      const kvMatch = line.match(/^(\s*)([\w.-]+)(:)(.*)$/);
      if (kvMatch) {
        const [, indent, key, colon, rest] = kvMatch;
        const valStyled = rest.trimStart()
          ? `<span style="color:var(--color-text)">${rest}</span>`
          : '';
        return `${indent}<span style="color:var(--color-primary)">${key}</span><span style="color:var(--color-text-muted)">${colon}</span>${valStyled}`;
      }
      // List items
      if (line.match(/^\s*-\s/))
        return `<span style="color:var(--color-text-muted)">${line}</span>`;
      return line;
    })
    .join('\n');

  pane.innerHTML = `
    <div class="canvas-yaml-viewer">
      <pre><code>${highlighted}</code></pre>
    </div>`;
  pane.classList.add('scrollable');
}

export default {
  name: 'yaml',
  label: 'YAML',
  extensions: ['.yaml', '.yml'],
  binary: false,
  priority: 0,
  render: viewYaml,
};
