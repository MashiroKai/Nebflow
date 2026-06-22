"""
智谱 GLM Coding Plan Max 抢购脚本
==================================

功能: 监控 bigmodel.cn 上 GLM Coding Plan Max 套餐的库存，自动下单

支持两种模式:
  Mode 1: API 直调模式（推荐，更快）—— 用户手动提取 token，脚本直接调 API
  Mode 2: Playwright 浏览器自动化 —— 打开浏览器让用户手动登录，之后自动操作

用法:
  # 1. 安装依赖
  pip install httpx

  # 2. 先手动登录一次 bigmodel.cn，用 --capture 模式找到正确的 API 调用
  python snap_glm_coding_plan.py --capture

  # 3. 获取 token 后运行 API 模式
  python snap_glm_coding_plan.py --mode api --token zp_xxxxxx --plan max

  # (可选) 浏览器模式
  pip install playwright && playwright install chromium
  python snap_glm_coding_plan.py --mode browser

流程说明:
  每天 10:00 (北京时间) 开售 Max 套餐。
  脚本会在开售前自动启动轮询，一旦检测到可购买立即下单。
"""

import argparse
import asyncio
import json
import os
import sys
import time
from datetime import datetime, timedelta
from pathlib import Path

# ─── 配置 ───────────────────────────────────────────────────
# 套餐信息（来自 docs.bigmodel.cn）
PLANS = {
    "lite": {"name": "Lite", "price": 18},
    "pro":  {"name": "Pro",  "price": 199},
    "max":  {"name": "Max",  "price": 399},
}

# API 端点
API_BASE = "https://open.bigmodel.cn/api/coding/paas/v4"
PAGE_BASE = "https://www.bigmodel.cn"

# 开售时间（北京时间 10:00）
SALE_HOUR = 10
SALE_MINUTE = 0

# Cookie 文件存储路径
COOKIE_FILE = Path.home() / ".glm_coding_cookies.json"

# 轮询间隔（秒）
POLL_INTERVAL_FAST = 0.5   # 开售前后的快速轮询
POLL_INTERVAL_SLOW = 5     # 常规轮询


# ─── 工具函数 ─────────────────────────────────────────────
def beijing_now() -> datetime:
    """返回当前北京时间"""
    from datetime import timezone, timedelta
    tz = timezone(timedelta(hours=8))
    return datetime.now(tz)


def next_sale_time() -> datetime:
    """返回下一个开售时间（北京时间 10:00）"""
    tz = timezone(timedelta(hours=8))
    now = beijing_now()
    sale = now.replace(hour=SALE_HOUR, minute=SALE_MINUTE, second=0, microsecond=0)
    if now >= sale:
        sale += timedelta(days=1)
    return sale


def countdown_until(target: datetime):
    """倒计时打印，直到目标时间"""
    while True:
        now = beijing_now()
        remaining = (target - now).total_seconds()
        if remaining <= 0:
            print(f"\n[⏰] 开售时间到！")
            return
        if remaining > 60:
            print(f"\r[⏳] 距离开售还有 {int(remaining // 60)} 分 {int(remaining % 60)} 秒", end="")
            time.sleep(5)
        elif remaining > 10:
            print(f"\r[⏳] 距离开售还有 {int(remaining)} 秒", end="")
            time.sleep(1)
        else:
            print(f"\r[⏳] 距离开售还有 {remaining:.1f} 秒", end="")
            time.sleep(0.1)


# ─── API 直调模式 ─────────────────────────────────────────
class ApiSnapper:
    """用 token/cookie 直接调 API 抢购"""

    def __init__(self, token: str):
        import httpx
        self.client = httpx.Client(
            base_url=API_BASE,
            headers={
                "Authorization": f"Bearer {token}",
                "Content-Type": "application/json",
                "User-Agent": "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
                              "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                "Origin": "https://www.bigmodel.cn",
                "Referer": "https://www.bigmodel.cn/",
            },
            timeout=10,
        )

    def check_plan(self, plan: str = "max") -> dict | None:
        """查询套餐库存状态"""
        resp = self.client.get(f"{API_BASE}/plan/listSeason")
        if resp.status_code == 401:
            print("[!] Token 无效或已过期，请重新获取")
            return None
        if resp.status_code != 200:
            print(f"[!] 查询失败: HTTP {resp.status_code}")
            return None

        data = resp.json()
        # 尝试从不同路径找到套餐信息
        plans = data.get("data", []) if isinstance(data, dict) else data
        for p in plans:
            if isinstance(p, dict):
                p_id = (p.get("planId") or p.get("id") or "").lower()
                if plan in p_id:
                    return p
        return None

    def subscribe(self, plan_id: str) -> bool:
        """下单订阅"""
        resp = self.client.post(
            f"{API_BASE}/subscribe",
            json={"planId": plan_id, "period": "monthly"},
        )
        result = resp.json()
        if resp.status_code == 200 and result.get("success"):
            print(f"[✓] 下单成功！{result}")
            return True
        else:
            print(f"[✗] 下单失败: {result}")
            return False

    def run(self, plan: str = "max"):
        """持续轮询直到抢到"""
        print(f"[*] API 模式启动，目标套餐: {plan.upper()}")
        sale_time = next_sale_time()
        countdown_until(sale_time)

        # 进入快速轮询
        attempt = 0
        while True:
            attempt += 1
            print(f"\r[{attempt}] 正在检查库存...", end="", flush=True)
            info = self.check_plan(plan)
            if info:
                status = (info.get("status") or info.get("stockStatus") or "").lower()
                available = status in ("available", "in_stock", "onsale", "")
                if available:
                    print(f"\n[✓] 套餐可用！信息: {info}")
                    plan_id = info.get("planId") or info.get("id")
                    if plan_id:
                        self.subscribe(plan_id)
                        return True
                else:
                    print(f"\n[-] 套餐状态: {status}，继续等待")
            else:
                print("\n[-] 无法获取套餐信息，重试中...")

            # 开售后 2 分钟内快速轮询，之后逐步放慢
            elapsed = (beijing_now() - sale_time).total_seconds()
            if elapsed > 120:
                interval = POLL_INTERVAL_SLOW
            else:
                interval = POLL_INTERVAL_FAST
            time.sleep(interval)


# ─── Playwright 浏览器模式 ─────────────────────────────────
class BrowserSnapper:
    """用 Playwright 打开浏览器，让用户手动登录后自动操作"""

    def __init__(self, headless: bool = False, user_data_dir: str | None = None):
        self.headless = headless
        self.user_data_dir = user_data_dir

    async def run(self, plan: str = "max"):
        from playwright.async_api import async_playwright

        print("[*] 浏览器模式启动")
        print("[*] 正在启动 Chromium 浏览器...")
        print("[*] 请在浏览器中手动登录 bigmodel.cn（支持微信扫码/短信验证码）")
        print()

        async with async_playwright() as p:
            # 启动浏览器
            launch_args = {
                "headless": self.headless,
                "args": [
                    "--disable-blink-features=AutomationControlled",
                    "--no-sandbox",
                    "--disable-dev-shm-usage",
                ],
            }

            if self.user_data_dir:
                context = await p.chromium.launch_persistent_context(
                    self.user_data_dir, **launch_args
                )
                page = context.pages[0] if context.pages else await context.new_page()
            else:
                browser = await p.chromium.launch(**launch_args)
                context = await browser.new_context(
                    viewport={"width": 1440, "height": 900},
                    user_agent=(
                        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
                        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                    ),
                )
                page = await context.new_page()

            # Step 1: 导航到首页，等待用户登录
            print("[*] 正在打开 bigmodel.cn...")
            await page.goto(f"{PAGE_BASE}/console/overview", wait_until="networkidle")
            current_url = page.url

            if "login" in current_url.lower():
                print("\n[!] 检测到未登录状态")
                print("[!] 请在弹出的页面中完成登录：")
                print("    - 使用手机号 + 短信验证码")
                print("    - 或使用微信扫码登录")
                print()
                print("[*] 等待登录完成...")

                # 等待用户登录，直到 URL 不再是登录页面
                try:
                    await page.wait_for_url(
                        lambda url: "login" not in url.lower(),
                        timeout=300_000,  # 5 min timeout
                    )
                    print("[✓] 登录成功！")
                except Exception as e:
                    print(f"[✗] 登录超时或失败: {e}")
                    return

            # 保存 cookie 供后续使用
            cookies = await context.cookies()
            COOKIE_FILE.parent.mkdir(parents=True, exist_ok=True)
            with open(COOKIE_FILE, "w") as f:
                json.dump(cookies, f, ensure_ascii=False, indent=2)
            print(f"[*] Cookie 已保存到 {COOKIE_FILE}")

            # Step 2: 导航到套餐订阅页面
            print(f"\n[*] 正在导航到套餐订阅页面...")
            await page.goto(
                f"{PAGE_BASE}/console/subscribe-overview",
                wait_until="networkidle",
            )
            await asyncio.sleep(2)

            # Step 3: 等待开售时间
            sale_time = next_sale_time()
            print(f"[*] 目标开售时间: {sale_time.strftime('%Y-%m-%d %H:%M:%S')} (北京时间)")

            remaining = (sale_time - beijing_now()).total_seconds()
            if remaining > 0:
                print(f"[*] 当前时间: {beijing_now().strftime('%H:%M:%S')}")
                print(f"[*] 等待开售，还有 {remaining:.0f} 秒...")
                countdown_until(sale_time)
            else:
                print("[*] 已过开售时间，立即检查...")

            # Step 4: 快速轮询，寻找 Max 套餐的"订阅"或"购买"按钮
            print(f"\n[*] 正在抢购 {plan.upper()} 套餐...")

            # 构造可能的选择器列表
            selectors = [
                # 套餐卡片选择
                f'//div[contains(text(), "{plan.upper()}")]/ancestor::div[contains(@class, "card") or contains(@class, "plan")]',
                f'//div[contains(@class, "plan") and contains(., "{plan.upper()}")]',
                f'//div[contains(text(), "Max")]',
                # 订阅按钮
                '//button[contains(text(), "订阅") or contains(text(), "购买") or contains(text(), "开通")]',
                '//span[contains(text(), "订阅") or contains(text(), "购买") or contains(text(), "开通")]',
                '//div[contains(@class, "subscribe") or contains(@class, "purchase")]//button',
                # 根据页面结构和常见的 class 名
                'button:has-text("订阅")',
                'button:has-text("购买")',
                'button:has-text("开通")',
                'span:has-text("订阅")',
                'span:has-text("购买")',
                'span:has-text("开通")',
            ]

            start_time = time.time()
            timeout = 300  # 5 分钟超时

            while time.time() - start_time < timeout:
                elapsed = time.time() - start_time
                print(f"\r[⏳] 已等待 {elapsed:.0f} 秒，正在检查...", end="", flush=True)

                # 刷新页面，确保状态最新
                if int(elapsed) % 10 == 0 and int(elapsed) > 0:
                    await page.reload(wait_until="domcontentloaded")
                    await asyncio.sleep(1.5)

                found = False
                for selector in selectors:
                    try:
                        btn = await page.query_selector(selector)
                        if btn:
                            text = await btn.inner_text()
                            is_disabled = await btn.get_attribute("disabled")
                            class_attr = await btn.get_attribute("class") or ""

                            if is_disabled is None and "disabled" not in class_attr:
                                print(f"\n[✓] 找到可用按钮: '{text.strip()}' [{selector}]")
                                await btn.click()
                                await asyncio.sleep(2)

                                # 检查是否跳转到支付页面
                                current_url = page.url
                                if "pay" in current_url.lower() or "order" in current_url.lower():
                                    print(f"\n[✓] 已跳转到支付页面！")
                                    print(f"[*] 当前页面: {current_url}")
                                    print(f"[*] 请完成支付（微信/支付宝扫码）")
                                    # 保持页面打开，让用户完成支付
                                    print("\n[🔔] 脚本已完成，浏览器保持打开，请完成支付。")
                                    print("[🔔] 按 Ctrl+C 退出脚本。")
                                    await asyncio.sleep(3600)
                                    return True
                                else:
                                    print(f"[*] 已点击，当前 URL: {current_url}")
                                    found = True
                                    break
                    except Exception as e:
                        continue

                if found:
                    # 点击后等待页面变化
                    await asyncio.sleep(3)
                    continue

                await asyncio.sleep(POLL_INTERVAL_FAST)

            print(f"\n[✗] 抢购超时 ({timeout} 秒)，未检测到可购买的套餐")

            # 保持浏览器打开
            input("\n按 Enter 关闭浏览器...")
            if not self.user_data_dir:
                await browser.close()


# ─── 主入口 ─────────────────────────────────────────────────
def main():
    parser = argparse.ArgumentParser(
        description="智谱 GLM Coding Plan Max 抢购脚本",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
使用示例:
  # 浏览器模式（推荐）—— 打开浏览器后手动登录
  python snap_glm_coding_plan.py --mode browser

  # API 模式 —— 需要先提供 token
  python snap_glm_coding_plan.py --mode api --token zp_xxxxxx

  # 指定套餐（默认 max）
  python snap_glm_coding_plan.py --mode browser --plan pro

如何获取 API Token:
  1. 在浏览器中登录 bigmodel.cn
  2. 打开 DevTools (F12) > Application > Local Storage
  3. 找到类似 token 或 access_token 的项
  4. 复制其值作为 --token 参数传入

  或者从 Cookie 中提取:
  1. DevTools > Application > Cookies > www.bigmodel.cn
  2. 找到 SESSION 或类似 cookie 的值
        """
    )
    parser.add_argument(
        "--mode", "-m",
        choices=["browser", "api"],
        default="browser",
        help="运行模式: browser（浏览器自动化）/ api（API 直调）"
    )
    parser.add_argument(
        "--plan", "-p",
        choices=["lite", "pro", "max"],
        default="max",
        help="目标套餐 (默认: max)"
    )
    parser.add_argument(
        "--token", "-t",
        default=None,
        help="API Token（仅 API 模式需要）"
    )
    parser.add_argument(
        "--headless",
        action="store_true",
        help="浏览器模式的无头模式（不显示浏览器窗口，不推荐）"
    )
    parser.add_argument(
        "--user-data-dir",
        default=None,
        help="浏览器用户数据目录（保留登录状态，避免重复登录）"
    )
    parser.add_argument(
        "--sale-time",
        default="10:00",
        help="开售时间（北京时间, 默认 10:00）"
    )

    args = parser.parse_args()

    # 解析开售时间
    try:
        parts = args.sale_time.split(":")
        global SALE_HOUR, SALE_MINUTE
        SALE_HOUR = int(parts[0])
        SALE_MINUTE = int(parts[1])
    except (ValueError, IndexError):
        print(f"[!] 无效的时间格式: {args.sale_time}，使用默认 10:00")

    plan = args.plan
    print(f"{'='*60}")
    print(f"  智谱 GLM Coding Plan 抢购助手")
    print(f"  目标套餐: {plan.upper()} ({PLANS[plan]['price']}元/月)")
    print(f"  开售时间: 每日 {SALE_HOUR:02d}:{SALE_MINUTE:02d} (北京时间)")
    print(f"  运行模式: {args.mode}")
    print(f"{'='*60}")
    print()

    if args.mode == "api":
        if not args.token:
            print("[!] API 模式需要提供 --token 参数")
            print("[!] 请先登录 bigmodel.cn，然后从 DevTools 中获取 token")
            sys.exit(1)
        snapper = ApiSnapper(args.token)
        snapper.run(plan)
    else:
        snapper = BrowserSnapper(
            headless=args.headless,
            user_data_dir=args.user_data_dir,
        )
        asyncio.run(snapper.run(plan))


if __name__ == "__main__":
    main()
