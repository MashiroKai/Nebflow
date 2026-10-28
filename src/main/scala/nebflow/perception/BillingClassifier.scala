package nebflow.perception

import scala.util.matching.Regex

/**
 * L0 账单分类（r2 §5.1 裁决 5 的「账单正则」腿）：新邮件 → 当日账单缓冲。
 *
 * 判据（命中即账单类，旁路 L1 入缓冲、日结攒批零 UI）：
 *   - 发件域 ∈ 账单域表（微信支付 / 支付宝 / 主要银行账单中心），或
 *   - 主题/正文含 账单关键词 + 金额形（¥12.34 / 12.34元 / ￥1,234.00）。
 *
 * 纯函数；审计行只记 `billing` 判定与金额摘要，不落正文。
 */
object BillingClassifier:

  private val billingDomains: Set[String] = Set(
    "wx.tenpay.com",
    "pay.weixin.qq.com",
    "tenpay.com", // 微信支付
    "alipay.com",
    "alipaymail.com", // 支付宝
    "cmbchina.com",
    "icbc.com.cn",
    "ccb.com",
    "abchina.com",
    "boc.cn",
    "boc.com",
    "psbc.com",
    "bankcomm.com",
    "cgbchina.com.cn",
    "cebbank.com",
    "spdb.com.cn",
    "citicbank.com",
    "ccb.com.cn",
    "hsbc.com",
    "paypal.com",
    "apple.com", // App Store 收据
    "bill.microsoft.com",
    "aws.amazon.com",
    "cloud.tencent.com",
    "aliyun.com"
  )

  private val keywords: List[String] = List(
    "账单",
    "对账单",
    "消费",
    "交易",
    "支付成功",
    "付款",
    "扣款",
    "发票",
    "invoice",
    "receipt",
    "payment",
    "billing",
    "statement",
    "transaction",
    "order confirmed"
  )

  /** 金额形：¥12.34 / ￥1,234.00 / 12.34元 / CNY 12.34 / $12.34。 */
  private val amount: Regex =
    """[¥￥]?\s*[0-9][0-9,]{0,12}(?:\.[0-9]{1,2})?\s*(?:元|CNY|USD|\$|RMB)?""".r

  case class Verdict(isBilling: Boolean, matchedBy: String, amountSample: String)

  def classify(fromDomain: String, subject: String, body: String): Verdict =
    val dom = Option(fromDomain).getOrElse("").toLowerCase
    val s = Option(subject).getOrElse("")
    val b = Option(body).getOrElse("")
    if billingDomains.contains(dom) then
      Verdict(true, s"domain:$dom", amount.findFirstIn(s + " " + b).getOrElse("").trim)
    else
      val kw = keywords.find(k => s.contains(k) || b.contains(k))
      kw match
        case Some(k) =>
          val amt = amount.findFirstIn(s).orElse(amount.findFirstIn(b)).getOrElse("")
          if amt.nonEmpty then Verdict(true, s"keyword:$k+amount", amt.trim)
          else Verdict(false, s"keyword:$k-no-amount", "")
        case None => Verdict(false, "", "")
end BillingClassifier
