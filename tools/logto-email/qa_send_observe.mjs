// qa_send_observe.mjs — drive the live forgot-password code send with ground
// truth: network response for the send call + post-click button state.
import { chromium } from "playwright";

const EMAIL = process.argv[2] || "qa-e2e@neblink.space";
const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 480, height: 900 } });
const net = [];
page.on("response", async (r) => {
  const u = r.url();
  if (/code|verif|reset|forgot|password/i.test(u) && r.request().method() !== "GET") {
    let body = "";
    try { body = (await r.text()).slice(0, 200); } catch {}
    net.push(`${r.status()} ${r.request().method()} ${u}\n  req=${(r.request().postData() || "").slice(0, 160)}\n  res=${body}`);
  }
});
try {
  await page.goto("https://mystand.space/forgot-password", { waitUntil: "networkidle", timeout: 45000 });
  const input = page.locator("input[type='email']").first();
  await input.waitFor({ timeout: 15000 });
  await input.fill(EMAIL);
  const btn = page.locator("button:has-text('Send reset code')").first();
  const before = (await btn.textContent()).trim();
  await btn.click();
  await page.waitForTimeout(4000);
  const after = (await btn.textContent().catch(() => "<gone>")).trim();
  const disabled = await btn.isDisabled().catch(() => "?");
  await page.screenshot({ path: "shots/qa-observe-after-click.png", fullPage: true });
  console.log(`button before=${JSON.stringify(before)} after=${JSON.stringify(after)} disabled=${disabled}`);
  console.log("network observations:");
  for (const n of net) console.log("  " + n);
  await browser.close();
} catch (e) {
  console.error("ERROR", e.message.slice(0, 200));
  await page.screenshot({ path: "shots/qa-observe-error.png", fullPage: true }).catch(() => {});
  await browser.close();
  process.exit(1);
}
