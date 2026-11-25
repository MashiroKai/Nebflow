import { readFileSync, writeFileSync } from 'node:fs';
import { measure } from './measure.mjs';

const [baselinePath, reportPath, screenshotPath] = process.argv.slice(2);
if (!baselinePath || !reportPath || !screenshotPath) throw new Error('用法：node compare.mjs <基线.json> <报告.json> <实际截图.png>');
const expected = JSON.parse(readFileSync(baselinePath));
const actual = await measure(screenshotPath);
const differences = [];
for (const [id, target] of Object.entries(expected.elements)) {
  const observed = actual.elements[id];
  for (const [property, value] of Object.entries(target.styles)) if (observed.styles[property] !== value) differences.push({ element: id, selector: target.selector, property, expected: value, actual: observed.styles[property] });
  if (target.box) for (const [property, value] of Object.entries(target.box)) if (observed.box[property] !== value) differences.push({ element: id, selector: target.selector, property: `box.${property}`, expected: value, actual: observed.box[property] });
}
const report = { panel: actual.panel, status: differences.length ? 'FAIL' : 'PASS', differences, screenshot: screenshotPath };
writeFileSync(reportPath, JSON.stringify(report, null, 2) + '\n');
console.log(JSON.stringify(report, null, 2));
if (differences.length) process.exitCode = 1;
