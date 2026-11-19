// messages.js 拆分(FE组件化试点 2026-09-27):元素与头像助手 可复用模块(行为保持)。
import { platformDisplay } from '../neblink.js';
import { avatarNodeFor } from '../avatarRender.js';

function el(tag, cls, text) {
  const e = document.createElement(tag);
  if (cls) e.className = cls;
  if (text !== undefined) e.textContent = text;
  return e;
}

function avatarEl(person, size) {
  const a = el('span', `fm-avatar fm-avatar-${size}`);
  // sessperf Phase B（2026-09-20）：src 解析收口到 `avatarRender.avatarNodeFor`
  // —— 本地层（`localStore.readAvatar`，键 = userId）命中 ⇒ 直接拿 objectURL，
  // **零网络、零重取**；未命中 ⇒ 回落既有解码复用池 + 远端直拉（零回归），且由
  // 本地层低频补字节供下一次开窗命中。判据面（有无 avatarUrl）与改前逐字相同。
  const node = person ? avatarNodeFor(person) : null;
  if (node) {
    a.appendChild(node);
  } else {
    a.textContent = ((person && (person.name || person.neblinkId)) || '?').trim().charAt(0).toUpperCase();
  }
  a.setAttribute('aria-hidden', 'true');
  return a;
}

/** 设备行头像（①，作者 2026-09-15：「设备在消息列表里的头像应该要和在联系人面板里
 *  一致」）。**同款判据 = 同一字形源**：联系人面板设备行（`contacts.js:401`
 *  `platformDisplay(d.platform).icon`）与本处**共用同一函数、同一返回值**
 *  （`neblink.js:401-414` 单点，禁第二份平台→图标映射），故同一设备在两面板里渲染出
 *  逐字节同形的 `<svg>`（同 viewBox / 同 path `d` / 同 fill|stroke 语义）。
 *  🔴 落槽 = `.fm-avatar` 家族（几何随既有 `-40` 档，不新开尺寸座），字形尺寸由
 *  `.fm-avatar-device svg` 单条规则决定（`friends.css`）。
 *  消费点两处：会话列表设备行（`convRow` 设备分支）＋ 设备**窗头**（`renderChatModal`
 *  设备分支，作者 2026-09-21 16:54 令「三类对话框统一显示头像」）——同一函数、同一
 *  字形源，禁第二份构造。
 *  🔴 只换**设备**这一支：好友头像照旧走档案 `avatarUrl` / 首字母，群行照旧首字母
 *  ⇒ 三类头像互不影响（逐类读数见本批报告 §①）。
 *  ⚠ 平台映射的兜底档（未知平台）返回**显示器/笔记本形**glyph（`neblink.js`
 *  `platformDisplay` 兜底档，现读 `:565` generic）⇒ 无名/未知平台的「空白态」设备同样有设备语义图标，不回落字母。 */
function deviceAvatarEl(device, size) {
  const a = el('span', `fm-avatar fm-avatar-${size} fm-avatar-device`);
  a.innerHTML = platformDisplay(device && device.platform).icon;
  a.setAttribute('aria-hidden', 'true');
  return a;
}

export { el, avatarEl, deviceAvatarEl };
