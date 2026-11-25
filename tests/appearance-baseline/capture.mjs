import { writeFileSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { measure } from './measure.mjs';

const [output, screenshot] = process.argv.slice(2);
if (!output || !screenshot) throw new Error('用法：node capture.mjs <基线.json> <截图.png>');
const value = await measure(screenshot);
const baseline = { approved: false, approvedBy: null, approvedAt: null, sourceCommit: execFileSync('git', ['rev-parse', 'HEAD'], { encoding: 'utf8' }).trim(), ...value };
writeFileSync(output, JSON.stringify(baseline, null, 2) + '\n');
console.log(`草稿 ${output}；截图 ${screenshot}；元素 ${Object.keys(value.elements).length}`);
