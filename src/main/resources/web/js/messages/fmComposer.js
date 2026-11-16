// messages/fmComposer.js — 好友 / 群聊 / 设备三面输入条**唯一**装配点
// （三面-composer 统一批 2026-11-02 · 作者令「好友/群/设备的 composer 收口为同一
// 组件（同布局同能力同行为）」+「现状设备面板缺语音输入 ⇒ 以能力最全的面为基准
// 补齐，三面最终能力清单一致」）。
//
// 三面能力清单（本组件 = 三面**共同**能力清单，逐面零分叉）：
//   ① 文本输入（单行 `input.cfg-input.fm-input`，maxLength 2000，IME 组字守卫）
//   ② 语音输入（`.icon-btn.fm-mic-btn` 麦克风键 —— 点一下开 / 再点一下关，与主窗
//      同一交互约定；识别流/双模/态机全在 voiceEngine.js 单点，本组件只接线）
//   ③ 附件（`.icon-btn.fm-attach-btn` 纸夹键 + 隐藏 file input；挂载判据由调用方
//      `canAttach` 单点给出——不可用面**不挂假入口**）
//   ④ 发送（`.cfg-btn.cfg-btn-primary.fm-send-btn` 发送键 + 裸 Enter；可用态判据
//      由调用方 `onTextChange` 单点收敛）
//   ⑤ 停止态（录音中 = 麦克风键 `recording` / 收尾转写 `processing`，再点一次即停；
//      本面**无** agent-busy 中断键——fm 窗是消息面不是回合面，申报见批文档）
//
// 与主会话输入组件的关系（申报，**结构同源、行为分层**）：结构自 chat-ui-package
// 批（2026-11-16）起同源 —— 两面的外壳/键位结构均出自 js/chatUi.js 的 composer()
// 工厂（主会话 variant 'main'，三面 variant 'compact'）；行为仍是两套 —— 主会话 =
// `input.js` + `input/composer.js` 全家桶（多行 textarea、slash/mention 菜单、输入
// 历史、busy↔idle 中断键 swap、附件条带、排队/回执面）；三面是**轻量 composer**
// （单行 input + 四键位）。
// 全部差异逐条列在批文档（docs/20261102_234900_fm-threeface-align__delegate-nebflow-c1142b9b.md）。
//
// 依赖方向：i18n / chatUi / voiceEngine / imeGuard —— 均不回指
// messages.js（无环；voiceEngine 是既有单点，本模块禁新造第二套语音机制）。

import { t } from '../i18n.js';
import { composer } from '../chatUi.js';
import { startDictation, stopDictation, sttEnabled } from '../voiceEngine.js';
import { bindImeGuard, isImeComposing } from '../imeGuard.js';

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
  const { bar, input, micBtn, sendBtn, attachBtn, attachInput } = composer({
    variant: 'compact',
    micLabel: t('messages.voiceInput'),
    placeholder: t('messages.inputPlaceholder'),
    sendLabel: t('messages.send'),
    attachLabel: t('dropbox.attachFile'),
    canAttach: opts.canAttach === true,
  });

  // ── 语音输入（②；三面同挂 —— 设备面本批补齐，令出 2026-11-02）──────────────
  //  形态 = 主窗既有 `.icon-btn` 族 + lucide `mic`（与纸夹同族）；位置 = 输入条
  //  最左（微信参照稿同位）。接线复用主窗既有管线：同一 voiceEngine 识别流、同一
  //  回调契约（onInterim 占位刷新 / onText 落终稿 / onState 态机）、同一「点一下开、
  //  再点一下关」交互（作者 2026-08-25 21:34 令，与主窗一致）。反馈 = 主窗既有
  //  `.icon-btn.recording`（微信绿 + voicePulse）+ 转写中 `.fm-mic-btn.processing`
  //  （既有 sapphire）；出错 = toast 可见提示（禁 console-only）。
  //  STT 全局停用态：`body.stt-disabled .fm-mic-btn { display:none }`（sidebar.css）
  //  走同一枚类名收口 —— 三面同时隐藏，无需第四处判据。
  let voiceActive = false;
  let voiceAnchor = 0;      // 当前语音段插入锚点（字符偏移）
  let voiceInterimLen = 0;  // 当前占位 interim 文本长度
  // micBtn 由工厂装配（形态/属性/字形单源在 chatUi.js）；反馈态类在此切换。
  const setMicState = (s) => {
    micBtn.classList.toggle('recording', s === 'listening' || s === 'speaking');
    micBtn.classList.toggle('processing', s === 'processing');
    micBtn.setAttribute('aria-pressed', String(s === 'listening' || s === 'speaking'));
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
        input.value = before + text + after;
        voiceInterimLen = text.length;
        input.setSelectionRange(voiceAnchor + text.length, voiceAnchor + text.length);
      },
      onText: (text) => {
        // 终稿落定 = 占位槽换终稿 + 尾随空格，锚点随移（同主窗契约）
        const before = input.value.substring(0, voiceAnchor);
        const after = input.value.substring(voiceAnchor + voiceInterimLen);
        const insert = text + ' ';
        input.value = before + insert + after;
        voiceInterimLen = 0;
        voiceAnchor = before.length + insert.length;
        input.setSelectionRange(voiceAnchor, voiceAnchor);
        onTextChange(); // 程序化写入不触发 input 事件 ⇒ 发送键可用态就地同步
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
  input.addEventListener('input', onTextChange);
  // ⑤ 中文输入（作者点名的面）：组字期间 Enter 属于输入法（确认候选），不得
  // 触发发送；组字结束后的 Enter 照旧发送（⑤A3）。
  bindImeGuard(input);
  input.addEventListener('keydown', (e) => {
    if (isImeComposing(e, input)) return;
    if (e.key === 'Enter') { e.preventDefault(); onSend(); }
  });

  return { bar, input, sendBtn, stopVoice };
}
