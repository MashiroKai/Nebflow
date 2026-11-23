// messages/fmComposer.js — 好友 / 群聊 / 设备三面输入条**唯一**装配点
// （三面-composer 统一批 2026-11-02 · 作者令「好友/群/设备的 composer 收口为同一
// 组件（同布局同能力同行为）」+「现状设备面板缺语音输入 ⇒ 以能力最全的面为基准
// 补齐，三面最终能力清单一致」）。
//
// 三面能力清单（本组件 = 三面**共同**能力清单，逐面零分叉）：
//   ① 文本输入（`textarea.cfg-input.fm-input.nf-field--bare`，maxLength 2000，IME 组字守卫）
//   ② 语音输入（**主会话同款液态光球** `.mic-orb-wrap > button.micbubble.fm-mic-btn`
//      —— 点一下开 / 再点一下关，与主窗同一交互约定；识别流/双模/态机全在
//      voiceEngine.js 单点，光球渲染全在 micOrb.js 单点，本组件只接线）
//   ③ 附件（`.icon-btn.fm-attach-btn` 纸夹键 + 隐藏 file input；挂载判据由调用方
//      `canAttach` 单点给出——不可用面**不挂假入口**）
//   ④ 发送（`.ds-turn-btn.ds-turn-btn--send.fm-send-btn` 发送键 + 裸 Enter；可用态
//      判据由调用方 `onTextChange` 单点收敛）
//   ⑤ 停止态（录音中 = 光球 `s-listening` / `recording`，收尾转写 `s-processing` /
//      `processing`，再点一次即停；本面**无** agent-busy 中断键——fm 窗是消息面
//      不是回合面，申报见批文档）
//
// 与主会话输入组件的关系（social-chat-input-unify 批 2026-11-21 收口后
// **结构 + 形态 + 交互全同源**）：结构自 chat-ui-package 批（2026-11-16）起同源
// —— 两面的外壳/键位结构均出自 js/chatUi.js 的 composer() 工厂（主会话 variant
// 'main'，三面 variant 'compact'）；语音入口自本批起同源 —— 不再是「lucide 图标
// 键 vs 液态光球」两种形态，而是同一个光球 DOM 壳 + 同一条 micOrb 渲染管线。
// 行为仍是两套（**消息面不是回合面**）：主会话 = `input.js` + `input/composer.js`
// 全家桶（slash/mention 菜单、输入历史、busy↔idle 中断键 swap、附件条带、排队/
// 回执面、拖动开全双工手势）；三面是**轻量 composer**（文本 + 语音 + 附件 + 发送）。
// 全部差异逐条列在批文档（docs/20261102_234900_fm-threeface-align__delegate-nebflow-c1142b9b.md
// + 本批功能差异清单）。
//
// 依赖方向：i18n / chatUi / voiceEngine / imeGuard —— 均不回指
// messages.js（无环；voiceEngine 是既有单点，本模块禁新造第二套语音机制）。

import { t } from '../i18n.js';
import { composer } from '../chatUi.js';
import { startDictation, stopDictation, sttEnabled } from '../voiceEngine.js';
// 语音光球 = **主会话同一条渲染管线**（social-chat-input-unify 批 2026-11-21）：
// 同一个 MicOrb 单例按 wrap 注册第二个 surface（registerSurface/setSurfaceState）。
// 禁第二套光球：着色器/预设表/状态表/WebGL 回落全在 micOrb.js 单点。
import { getMicOrb } from '../micOrb.js';
// 键盘 / 自适应行高 = 主会话同一底座（composer 统一批 2026-11-18）：本文件不再
// 自带注册 IME 守卫与 Enter 判定 —— `bindComposerKeys` 就是主会话 `input.js` 走的
// 那一份契约（组字前置短路 → cascade → commitEnter），Shift+Enter 交还浏览器默认
// 换行（textarea），本文件只留 `onSend` 接线；autosize 同走 `growComposerInput`。
import { bindComposerKeys, growComposerInput, setComposerText, composerPlaceholder } from '../input/composer.js';

/**
 * 装配一条 fm 输入条（三面同款）。返回引用给宿主（messages.js）挂状态收敛。
 *
 * @param {{
 *   canAttach: boolean,                       // 附件入口可用面（设备窗恒 true；好友/群 = attachAvailable(conv)）
 *   onSend: () => void,                       // 发送动作（= 宿主 dispatchSend 单点）
 *   onFiles: (files: FileList) => void,       // 附件动作（= 宿主 dispatchFiles 单点）
 *   onTextChange?: () => void,                // 文本/语音写入后的可用态收敛（= 宿主 syncComposerSend）
 *   toast?: (msg: string) => void,            // 语音错误面（= 宿主 modalToast；缺省静默降级到 console）
 * }} opts
 * @returns {{
 *   bar: HTMLElement,
 *   input: HTMLInputElement,
 *   sendBtn: HTMLButtonElement,
 *   stopVoice: () => void,
 * }} 宿主挂 `modalEls.{input,sendBtn,stopVoice}` + 收窗时调 `stopVoice()`。
 */
export function buildFmComposer(opts) {
  const onSend = opts.onSend;
  const onTextChange = typeof opts.onTextChange === 'function' ? opts.onTextChange : () => {};
  const toast = typeof opts.toast === 'function' ? opts.toast : (() => {});

  // 输入条结构单源 = js/chatUi.js 的 composer() compact 变体（chat-ui-package
  // 批③，2026-11-16）：外壳/输入件/麦克风键/纸夹键/发送键的类名、属性与子序
  // 全部出自工厂（与主输入条同一结构源），本文件只留行为接线（语音/附件/发送/
  // 键盘契约）。外壳仍是**悬浮毛玻璃条**（contact-panel-unify 链 · 作者令
  // 2026-11-06「输入框：同样采用悬浮、毛玻璃半透明、带大圆角的设计」，参照物 =
  // 主会话 `#input-bar`）：
  //   · `nf-glass` = 全站唯一毛玻璃工具类（css/glass.css 单源，玻璃底/模糊/细边/
  //     影四件套由类供，本文件与 friends.css 都**不写** backdrop-filter 与色值）；
  //   · 圆角/内距/落位 = css/friends.css 的 `.fm-input-bar`（20px 外壳档 + 6/12 内距，
  //     与 `#input-bar` 逐值同）；
  //   · 内件 `.nf-field--bare`（css/inputSurface.css 显式类）：外壳已给材质，内件
  //     只留字与 caret —— 与主会话 `#input` 同档，禁「框里套框」。
  // 禁第二个类名/第二份材质：换个名字再画一遍就是「第二种输入面」（check-input-surface
  // 的 I2 与 check-glass-unify 的 G1 都会红）。
  const { bar, input, micBtn, micWrap, sendBtn, attachBtn, attachInput } = composer({
    variant: 'compact',
    micLabel: t('messages.voiceInput'),
    placeholder: composerPlaceholder({ baseToken: 'messages.inputPlaceholder' }),
    sendLabel: t('messages.send'),
    attachLabel: t('dropbox.attachFile'),
    canAttach: opts.canAttach === true,
  });

  // ── 语音输入（②；三面同挂 —— 设备面 2026-11-02 补齐）────────────────────────
  //  形态 = **主会话同一枚液态光球**（social-chat-input-unify 批 2026-11-21，作者令
  //  「社交对话窗口的输入框完全与主会话输入框保持一致——包括语音气泡、发送按钮」）；
  //  位置 = 输入条最左（与主窗同位）。接线复用主窗既有管线：同一 voiceEngine 识别流、
  //  同一回调契约（onInterim 占位刷新 / onText 落终稿 / onState 态机）、同一「点一下开、
  //  再点一下关」交互（作者 2026-08-25 21:34 令，与主窗一致）。渲染 = 同一个 MicOrb
  //  单例注册的**第二面**（setSurfaceState）—— 不新造渲染器，不吃主条回合面 dims
  //  （`owned:true`：frozen/busy/offline 是回合语义，消息窗不是回合面）。
  //  反馈 = 光球状态类（`s-listening` / `s-processing` / `s-micerr`）+ 宿主钩子类
  //  `.fm-mic-btn` 上的 `recording`/`processing`（既有 QA 寻址面与 stt-disabled 隐藏
  //  规则继续按它工作，零第二套状态位）。出错 = toast 可见提示（禁 console-only）。
  let voiceActive = false;
  let voiceAnchor = 0;      // 当前语音段插入锚点（字符偏移）
  let voiceInterimLen = 0;  // 当前占位 interim 文本长度
  // 光球面注册：单例可能尚未挂载（voiceOrbMountable 的 configData latch 未到 /
  // 显式 voiceOrb:false）⇒ 全部调用 null-safe（与主窗 notifyVoiceState 同一纪律）。
  const orb = getMicOrb();
  if (orb) orb.registerSurface(micWrap, { size: 64, owned: true });
  // 占位文案 = 全站单点 composerPlaceholder（composer 统一批 2026-11-18）：本面
  // 声明自己的 baseToken（`messages.inputPlaceholder`），语音/引用帧/附件/通用四条
  // 分支的**判据**与主会话共用同一份实现（含 mobile-layer 附件恒文案档）。本面
  // 无附件条与纯引用帧闸 ⇒ 两个上下文参数恒为默认；语音态随麦克风态机刷新。
  const refreshPlaceholder = () => {
    input.placeholder = composerPlaceholder({
      baseToken: 'messages.inputPlaceholder',
      voiceActive,
    });
  };
  const setMicState = (s) => {
    // 光球态 = 唯一视觉面（`s-*` 类 + 着色器状态）；宿主钩子类 = 既有 QA/stt 寻址面。
    const orbKey = s === 'listening' || s === 'speaking' ? 'listening'
      : s === 'processing' ? 'processing'
        : s === 'error' ? 'mic-error' : 'idle';
    if (orb) orb.setSurfaceState(micWrap, orbKey);
    micBtn.classList.toggle('recording', s === 'listening' || s === 'speaking');
    micBtn.classList.toggle('processing', s === 'processing');
    micBtn.setAttribute('aria-pressed', String(s === 'listening' || s === 'speaking'));
    refreshPlaceholder();
  };
  const stopVoice = () => {
    if (!voiceActive) return;
    voiceActive = false;
    // 丢弃未落定的 interim 占位（与主窗 stopVoice 同一约定）
    if (voiceInterimLen > 0) {
      input.value = input.value.substring(0, voiceAnchor) + input.value.substring(voiceAnchor + voiceInterimLen);
      voiceInterimLen = 0;
    }
    // 尾段转写在飞 ⇒ processing；引擎随后的 onState('idle') 收敛回常态
    setMicState('processing');
    stopDictation();
    onTextChange();
  };
  micBtn.addEventListener('click', () => {
    if (input.disabled) return; // 拉黑/断连/设备身份缺席等禁用态不挂假入口行为
    // settings-panel 批 D 件（2026-10-28）：STT 停用态入口收口（toast；引擎闸
    // voiceEngine.startDictation 是纵深第二层）。
    if (!sttEnabled()) {
      window.__showToast?.(t('stt.disabled'), 'error');
      return;
    }
    if (voiceActive) { stopVoice(); return; }
    voiceActive = true;
    voiceAnchor = input.selectionStart ?? input.value.length;
    voiceInterimLen = 0;
    // 与主窗 startVoice 同一约定：锚点前字符非空白 ⇒ 补一个分隔空格
    if (voiceAnchor > 0) {
      const ch = input.value[voiceAnchor - 1];
      if (ch && ch !== ' ') {
        input.value = input.value.substring(0, voiceAnchor) + ' ' + input.value.substring(voiceAnchor);
        voiceAnchor++;
      }
    }
    setMicState('listening');
    input.focus();
    startDictation({
      onInterim: (text) => {
        // [anchor, anchor+interimLen) 占位槽原位刷新（同主窗契约）
        const before = input.value.substring(0, voiceAnchor);
        const after = input.value.substring(voiceAnchor + voiceInterimLen);
        voiceInterimLen = text.length;
        // 程序化写值走唯一入口（输入框显示缺陷批 2026-11-21）：量高 + 光标锚定
        // 一次成型 —— 改前这一腿连量高都没有，多行口述结果会塌在一行里。
        setComposerText(input, before + text + after, { caret: voiceAnchor + text.length });
      },
      onText: (text) => {
        // 终稿落定 = 占位槽换终稿 + 尾随空格，锚点随移（同主窗契约）
        const before = input.value.substring(0, voiceAnchor);
        const after = input.value.substring(voiceAnchor + voiceInterimLen);
        const insert = text + ' ';
        voiceInterimLen = 0;
        voiceAnchor = before.length + insert.length;
        // 程序化写入不触发 input 事件 ⇒ 行高与发送键可用态就地同步（同主窗契约）
        setComposerText(input, before + insert + after, { caret: voiceAnchor });
        onTextChange();
      },
      onState: (s, data) => {
        if (s === 'error') {
          // data = 引擎内已分类、已 i18n 的文案；错误必须用户可见（同 #stt-hotfix 纪律）
          setMicState('idle');
          toast(String(data || t('messages.voiceInput')));
          console.warn('[voice] fm composer mic error:', data);
          return;
        }
        setMicState(s);
      },
    });
  });

  // 发送键族统一批（作者 2026-09-15「正常绿 / 掉线灰」）：本键与全站发送键共用
  // 同一套状态色（`sapphire.css` 的发送族块）。`cfg-btn-primary` 只为**承接既有
  // 墨色**（既有类名 ⇒ 零新增字面量色值）；材质/几何/状态一律由发送族块覆盖。
  // 发送键 = **主会话同一枚**（四会话统一批 2026-11-05，作者令「按钮……都保持一致」）：
  // `.ds-turn-btn.ds-turn-btn--send`（design-system.css 单点 = 圆钮 + 绿实底；
  // 手机档 `--ds-turn-btn-size` 自动 48/22）。旧形态 = `.cfg-btn.cfg-btn-primary`
  // 文字键 52×44 / r10 —— 与主会话 48×48 / r50% 差两档，正是作者点名的不同。
  // `fm-send-btn` 保留为宿主钩子：`syncComposerSend` 的 disabled / `.disconnected`
  // 两态、`sapphire.css` 发送族与 QA 断言面都按它寻址（禁第二套状态位）。
  // （sendBtn / input 的形态、属性与子序均由工厂装配，此处不重复。）

  // 附件入口（③）：结构（`.icon-btn` 纸夹键 + 隐藏 file input）由工厂装配；
  // 面间差异全部收在宿主 `dispatchFiles`（传输协议/授权/受理面），本组件只接线。
  // 不可用面（canAttach=false）工厂即不造入口（attachBtn/attachInput = null），
  // 也不做灰置假入口。
  if (attachBtn && attachInput) {
    attachInput.addEventListener('change', () => {
      if (attachInput.files && attachInput.files.length > 0) opts.onFiles(attachInput.files);
      attachInput.value = '';
    });
    attachBtn.addEventListener('click', () => attachInput.click());
  }

  // ── 发送 + 键盘契约（④；三面同款）────────────────────────────────────────
  sendBtn.addEventListener('click', () => onSend());
  // ③ 输入非空 ↔ 发送键可用态即时同步（含发送后清空 ⇒ 回禁用态；禁两态分叉）
  input.addEventListener('input', () => {
    // 显示缺陷批 2026-11-21：塌陷由量高原语自理（见 js/input/composer.js ④）。
    growComposerInput(input);
    onTextChange();
  });
  // ⑤ 键盘契约 = 主会话**同一份**（js/input/composer.js bindComposerKeys）：组字
  // 期间每一个键都属于输入法（中文 IME 确认候选的 Enter 绝不触发发送），裸 Enter
  // 提交、Shift+Enter 走浏览器默认换行 —— 与主输入条逐条同序，禁本面另写一份判定。
  bindComposerKeys(input, { onSend: () => onSend() });

  // 关窗收敛：光球面注销（渲染器 pause、contextlost 监听解绑）—— 与 stopVoice 同拍
  // 由宿主 closeChat 调用，零跨窗残留（渲染上下文不累积）。
  const dispose = () => {
    if (voiceActive) stopVoice();
    if (orb) orb.unregisterSurface(micWrap);
  };

  return { bar, input, sendBtn, stopVoice, dispose };
}
