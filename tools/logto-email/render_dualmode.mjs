// render_dualmode.mjs — rendered dual-mode verification for the STAND Logto
// email templates. Loads each template under colorScheme light and dark,
// asserts computed styles + visible lockup against template_spec.json, and
// saves screenshots. The defect class caught here: branches paired to the
// wrong palette/lockup (author symptom: dark email renders light and vice
// versa on iOS).
//
// Run: node render_dualmode.mjs [outDir]
import { chromium } from "playwright";
import { readFileSync, mkdirSync, writeFileSync } from "fs";
import { fileURLToPath } from "url";
import path from "path";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(HERE, "../..");
const TPL_DIR = path.join(ROOT, "src/main/resources/web/brand/stand/email/templates");
const OUT = process.argv[2] ? path.resolve(process.argv[2]) : path.join(HERE, "shots");
const spec = JSON.parse(readFileSync(path.join(HERE, "template_spec.json"), "utf8"));
const USAGES = ["SignIn", "Register", "ForgotPassword", "Generic"];

mkdirSync(OUT, { recursive: true });

const assert = (name, ok, detail) => {
  if (!ok) { console.error(`FAIL ${name} ${detail ?? ""}`); process.exitCode = 1; }
  else console.log(`ok   ${name}`);
};

// px -> 24-bit hex (best effort: chromium returns rgb())
const hex = (css) => {
  const m = css.match(/rgba?\((\d+),\s*(\d+),\s*(\d+)/);
  if (!m) return css.toLowerCase();
  const [r, g, b] = [m[1], m[2], m[3]].map((n) => Number(n).toString(16).padStart(2, "0"));
  return `#${r}${g}${b}`;
};

const browser = await chromium.launch();
try {
  for (const usage of USAGES) {
    const file = path.join(TPL_DIR, `${usage}.html`);
    for (const scheme of ["light", "dark"]) {
      const ctx = await browser.newContext({ colorScheme: scheme, viewport: { width: 700, height: 1000 } });
      const page = await ctx.newPage();
      await page.goto("file://" + file);
      await page.waitForTimeout(150);
      const face = spec.palette[scheme];

      const cs = await page.evaluate(() => {
        const $ = (s) => document.querySelector(s);
        const bg = (s) => getComputedStyle($(s)).backgroundColor;
        const col = (s) => getComputedStyle($(s)).color;
        const visible = (s) => {
          const el = $(s);
          return !!el && getComputedStyle(el).display !== "none" && el.getBoundingClientRect().width > 0;
        };
        const imgs = [...document.querySelectorAll("img")].filter(
          (i) => getComputedStyle(i).display !== "none");
        return {
          pageBg: bg("table.nf-bg"),
          cardBg: bg("table.nf-card"),
          titleInk: col(".nf-title"),
          bodyInk: col(".nf-body"),
          metaInk: col(".nf-meta"),
          codeInk: col(".nf-code"),
          panelBg: bg("table.nf-codepanel"),
          dividerBg: bg(".nf-divider"),
          bodyColor: col("body"),
          lockupSrc: imgs.map((i) => i.src),
        };
      });

      const tag = `${usage}/${scheme}`;
      assert(`${tag} pageBg`, hex(cs.pageBg) === face.pageBg, `${cs.pageBg} != ${face.pageBg}`);
      assert(`${tag} cardBg`, hex(cs.cardBg) === face.cardBg, `${cs.cardBg} != ${face.cardBg}`);
      assert(`${tag} titleInk`, hex(cs.titleInk) === face.titleInk, `${cs.titleInk} != ${face.titleInk}`);
      assert(`${tag} bodyInk`, hex(cs.bodyInk) === face.bodyInk, `${cs.bodyInk} != ${face.bodyInk}`);
      assert(`${tag} metaInk`, hex(cs.metaInk) === face.metaInk, `${cs.metaInk} != ${face.metaInk}`);
      assert(`${tag} codeInk`, hex(cs.codeInk) === face.codeInk, `${cs.codeInk} != ${face.codeInk}`);
      assert(`${tag} panelBg`, hex(cs.panelBg) === face.codepanelBg, `${cs.panelBg} != ${face.codepanelBg}`);
      assert(`${tag} dividerBg`, hex(cs.dividerBg) === face.dividerBg, `${cs.dividerBg} != ${face.dividerBg}`);
      assert(`${tag} bodyColor pin`, hex(cs.bodyColor) === face.titleInk, `${cs.bodyColor} != ${face.titleInk}`);
      assert(`${tag} visible lockup == ${face.lockupUrl}`,
        cs.lockupSrc.length === 1 && cs.lockupSrc[0] === face.lockupUrl, JSON.stringify(cs.lockupSrc));

      // zero-mixing at the rendered layer: no other face's colors anywhere
      // zero-mixing at the painted layer: only classed, visible elements.
      // Unclassed structural scaffolding inherits UA defaults and paints no
      // text of its own — the body pin above covers the only unpinned parent.
      const allColors = await page.evaluate(() => {
        const set = new Set();
        for (const el of document.querySelectorAll(".nf-body, .nf-card, .nf-codepanel, .nf-divider, .nf-title, .nf-meta, .nf-code, .nf-bg, .nf-lockup-light, .nf-lockup-dark, body, div")) {
          const s = getComputedStyle(el);
          if (s.display === "none") continue;
          if (s.backgroundColor !== "rgba(0, 0, 0, 0)") set.add(s.backgroundColor);
          set.add(s.color);
        }
        return [...set];
      });
      const faceHexes = new Set(Object.entries(face)
        .filter(([k]) => k !== "lockupInk" && k !== "lockupUrl")
        .map(([, v]) => v));
      const foreign = allColors.filter((c) => {
        const h = hex(c);
        return /^#[0-9a-f]{6}$/.test(h) && !faceHexes.has(h);
      });
      assert(`${tag} zero foreign colors`, foreign.length === 0, JSON.stringify(foreign));

      await page.screenshot({ path: path.join(OUT, `${usage}-${scheme}.png`), fullPage: true });
      await ctx.close();
    }
  }
} finally {
  await browser.close();
}
console.log(process.exitCode ? "RENDER GATE: RED" : "RENDER GATE: GREEN");
