// Remove only explicitly delimited browser takeover viewer glue; reject malformed exemptions.
export function stripBrowserTakeoverGlue(source) {
  const marker = /\/\/ (BEGIN|END) BROWSER TAKEOVER VIEWER GLUE/g;
  const blocks = [];
  let outside = '';
  let lastEnd = 0;
  let start = null;
  let match;
  while ((match = marker.exec(source)) !== null) {
    if (match[1] === 'BEGIN') {
      if (start !== null) throw new Error('nested browser takeover viewer glue marker');
      outside += source.slice(lastEnd, match.index);
      start = match.index;
    } else {
      if (start === null) throw new Error('unpaired browser takeover viewer glue END marker');
      const text = source.slice(start, marker.lastIndex);
      const content = text.replace(/\/\/ (?:BEGIN|END) BROWSER TAKEOVER VIEWER GLUE/g, '');
      const lines = text.split('\n').length;
      if (lines > 40) throw new Error('browser takeover viewer glue exceeds 40 lines');
      if (!/takeover/i.test(content)) throw new Error('browser takeover viewer glue has no takeover reference');
      blocks.push({ start, end: marker.lastIndex, lines });
      lastEnd = marker.lastIndex;
      start = null;
    }
  }
  if (start !== null) throw new Error('unpaired browser takeover viewer glue BEGIN marker');
  outside += source.slice(lastEnd);
  return { outside, blocks };
}
