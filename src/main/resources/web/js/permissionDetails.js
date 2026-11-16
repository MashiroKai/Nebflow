// Shared, text-only permission payload view for live, restored and fallback cards.
import { t } from './i18n.js';

export function permissionInput(value) {
  if (typeof value === 'string') {
    try { return JSON.parse(value) || {}; } catch { return {}; }
  }
  return value && typeof value === 'object' ? value : {};
}

export function permissionDetails(toolName, value) {
  const input = permissionInput(value);
  const box = document.createElement('div');
  box.className = 'permission-payload';
  const add = (text) => {
    const pre = document.createElement('pre');
    pre.className = 'permission-payload-text';
    pre.textContent = text;
    box.appendChild(pre);
  };
  if (toolName === 'Bash' && typeof input.command === 'string') {
    add(input.command);
  } else if (toolName === 'Edit') {
    if (input.file_path) add(String(input.file_path));
    const lines = (value, prefix) => String(value ?? '').split('\n').map(line => prefix + line).join('\n');
    add(t('chat.permRequestNotDiff') + '\n' + lines(input.old_string, '-') + '\n' + lines(input.new_string, '+'));
    if (input.replace_all) add('replace_all: true');
  } else if (toolName === 'Write') {
    if (input.file_path) add(String(input.file_path));
    const content = String(input.content ?? '');
    const preview = content.split('\n').slice(0, 8).join('\n').slice(0, 1200);
    const bytes = new TextEncoder().encode(content).length;
    add(`${bytes} bytes\n${preview}`);
    if (preview.length < content.length) {
      const details = document.createElement('details');
      const summary = document.createElement('summary');
      summary.textContent = t('chat.permShowFull');
      const pre = document.createElement('pre');
      pre.className = 'permission-payload-text';
      pre.textContent = content;
      details.append(summary, pre);
      box.appendChild(details);
    }
  } else {
    add(JSON.stringify(input, null, 2));
  }
  return box;
}
