// 徽章使用提示池（badge-tip-mix）——与浪漫池（locales think.* 星云文案）物理分离。
//
// 加提示 = 往 BADGE_TIPS 数组加一行字符串。不要在别处改逻辑。
//
// 依据（给未来维护者）：候选与代码面证据原记录在仓外临时台账（badge-tip-pool 批）。
// 关键实现位置（Scala 侧工具源码，类名即检索锚点；local-coupling 闸要求 ship js
// 注释不携带仓库路径/行号，故此处只记类名——合并批 II 闸适应，语义未动）：
//   - 「给好友也能直接发文件」「文件也能发到自己另一台机器」→ FriendMessageTool
//   - 「问答窗可以把任务转给主会话」→ AskTools
//   - 「密钥不用贴聊天，弹框直接存」→ AskUserQuestionTool（secret 题，只回指针）
//
// 混入规则（createBadgeTipMixer）：徽章每出现一次计数一次；每 TIP_EVERY 次出现中
// 恰好第 TIP_EVERY 次换成一条 tip，其余走浪漫池。周期 = TIP_EVERY ≥ 2，因此两条 tip
// 永不相邻。tip 按池内顺序轮转。计数器与 every/tips 可注入，便于确定性测试。

export const BADGE_TIPS = Object.freeze([
  '给好友也能直接发文件',
  '文件也能发到自己另一台机器',
  '问答窗可以把任务转给主会话',
  '密钥不用贴聊天，弹框直接存',
]);

export const TIP_EVERY = 6;

/**
 * @param {{every?: number, tips?: readonly string[]}} [opts]
 * @returns {{next: () => (string|null)}} 每次调用消耗一次徽章出现；返回 tip 文本或 null（走浪漫池）。
 */
export function createBadgeTipMixer({ every = TIP_EVERY, tips = BADGE_TIPS } = {}) {
  if (!Number.isInteger(every) || every < 2) throw new Error('every must be an integer >= 2');
  let count = 0;
  return {
    next() {
      const n = count++;
      if (tips.length === 0 || n % every !== every - 1) return null;
      const slot = Math.floor(n / every);
      return tips[slot % tips.length];
    },
  };
}

/** tip 标注前缀：让「这是条使用提示」一眼可辨（浪漫语不带此前缀）。 */
export const TIP_LABEL = '提示：';

/**
 * 组装 tip 形态的徽章 phrase：`✻ 提示：<tip>（<时长>）`。
 * @param {string} tip 提示文案（来自 BADGE_TIPS）
 * @param {string} durationText 已格式化的时长（如 "8s"）
 */
export function composeTipPhrase(tip, durationText) {
  return '✻ ' + TIP_LABEL + tip + '（' + durationText + '）';
}

/** 模块级默认混入器（浏览器页生命周期内计数）。 */
export const defaultBadgeTipMixer = createBadgeTipMixer();
