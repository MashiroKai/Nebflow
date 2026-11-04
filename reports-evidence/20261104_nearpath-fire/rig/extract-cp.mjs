// extract-cp.mjs — 从 `sbt "show Compile/fullClasspath"` 的输出里抽 JVM classpath。
//
// sbt 1.10 的输出形态（每个条目一行）：
//   [info] * Attributed(/tmp/nearpath-fire/engine-src/target/scala-3.5.2/classes)
//   [info] * Attributed(/Users/.../scala3-library_3-3.5.2.jar)
// 仓路径含空格（"Claude code"），所以必须整条 Attributed(...) 取出（不能按空格切）。
//
// 用法：node extract-cp.mjs <cp-raw.log> <out.txt>

import fs from 'node:fs';

const [logPath, outPath] = process.argv.slice(2);
if (!logPath || !outPath) {
  console.error('usage: node extract-cp.mjs <cp-raw.log> <out.txt>');
  process.exit(2);
}
const entries = [];
const re = /Attributed\(([^()]*)\)/g;
for (const line of fs.readFileSync(logPath, 'utf8').split('\n')) {
  let m;
  while ((m = re.exec(line)) !== null) entries.push(m[1]);
  re.lastIndex = 0;
}
if (entries.length < 20) { console.error(`too few entries: ${entries.length}`); process.exit(1); }
const appClasses = entries.filter((e) => /\/target\/scala-3[^/]*\/classes$/.test(e));
if (!appClasses.length) { console.error('no application classes dir (target/scala-3*/classes) found'); process.exit(1); }
const missing = entries.filter((e) => !fs.existsSync(e));
if (missing.length) { console.error('missing classpath entries:\n  ' + missing.join('\n  ')); process.exit(1); }

fs.writeFileSync(outPath, entries.join(':') + '\n');
console.log(`classpath written: ${outPath} entries=${entries.length} appClasses=${appClasses[0]}`);
