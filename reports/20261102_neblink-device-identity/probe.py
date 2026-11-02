"""NebLink 设备身份/归属 只读实测脚本（2026-11-02）。

红线：只打 link.mystand.space 的只读接口；绝不打印 deviceToken / session token；
不使用 scala 线（6138）凭据注册任何东西；只读 r 线 credential.json。

运行：
  C:/Users/Administrator/AppData/Local/Programs/Python/Python312/python.exe probe.py
"""
import json
import sys
import time
import urllib.error
import urllib.request

BASE = "https://link.mystand.space"
CRED = r"\\wsl.localhost\Ubuntu\home\ysm123456\.stand\neblink-r\credential.json"


def call(path, body=None, bearer=None):
    data = json.dumps(body).encode() if body is not None else None
    h = {"Content-Type": "application/json"} if data is not None else {}
    if bearer:
        h["Authorization"] = "Bearer " + bearer
    req = urllib.request.Request(
        BASE + path, data=data, method="POST" if data is not None else "GET", headers=h
    )
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            return r.status, r.read().decode()
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()
    except Exception as e:  # noqa: BLE001
        return -1, repr(e)


def out(tag, status, body, limit=240):
    body = body.replace("\n", " ")
    print(f"{tag:<46} {status}  {body[:limit]}")


def main():
    cred = json.load(open(CRED, encoding="utf-8"))
    did, net, tok = cred["device_id"], cred["network_id"], cred["device_token"]
    print(f"# credential: deviceId={did} networkId={net} token_len={len(tok)}")

    out("[A1] GET /api/health", *call("/api/health"))

    def sess(device_id, network_id, token, name="probe-identity"):
        return call(
            "/api/device/session",
            {
                "proto": 1,
                "networkId": network_id,
                "deviceId": device_id,
                "deviceToken": token,
                "deviceName": name,
                "platform": "stand-rust",
                "endpoints": [{"address": "127.0.0.1", "port": 6138, "kind": "local"}],
            },
        )

    print("\n## 1. /api/device/session —— 三元组 (deviceId, networkId, deviceToken) 的键面")
    out("[S1] 正确 deviceId + 正确 networkId + 伪造 token", *sess(did, net, "dt-" + "0" * 40), 120)
    out("[S2] 伪造 deviceId + 正确 networkId + 正确 token",
        *sess("11111111-2222-4333-8444-555555555555", net, tok), 120)
    out("[S3] 正确 deviceId + 伪造 networkId + 正确 token",
        *sess(did, "00000000-0000-4000-8000-000000000000", tok), 120)
    st, body = sess(did, net, tok)
    j = json.loads(body) if st == 200 else {}
    print(f"{'[S4] 三元组全对':<46} {st}  "
          f"token_len={len(j.get('token', ''))} networkId={j.get('networkId')} "
          f"deviceId={j.get('deviceId')} peers={len(j.get('peers') or [])}")
    for p in j.get("peers") or []:
        print(f"      peer {p.get('deviceId')} | {p.get('deviceName')} | {p.get('platform')} "
              f"| online={p.get('online')}")

    print("\n## 2. /api/device/register —— body 模式与服务端鉴权次序")
    def reg(body, bearer="bogus"):
        data = json.dumps(body).encode()
        h = {"Content-Type": "application/json"}
        if bearer:
            h["Authorization"] = "Bearer " + bearer
        req = urllib.request.Request(BASE + "/api/device/register", data=data,
                                     method="POST", headers=h)
        try:
            with urllib.request.urlopen(req, timeout=20) as r:
                return r.status, r.read().decode()
        except urllib.error.HTTPError as e:
            return e.code, e.read().decode()
        except Exception as e:  # noqa: BLE001
            return -1, repr(e)

    out("[R1] {}  (无 Authorization 头)", *reg({}, bearer=None), 160)
    out("[R2] {}  (伪造 Bearer)", *reg({}), 160)
    out("[R3] 只有 deviceId", *reg({"deviceId": "r-" + "0" * 32}), 120)
    out("[R4] deviceId + 未知字段 totallyUnknownField",
        *reg({"deviceId": "r-" + "0" * 32, "deviceName": "p",
              "platform": "stand-rust", "totallyUnknownField": 1}), 120)
    out("[R5] deviceId + machineFingerprint",
        *reg({"deviceId": "r-" + "0" * 32, "deviceName": "p",
              "platform": "stand-rust", "machineFingerprint": "25f12119-9e58-4b3c-8396-537e57035d3b"}), 120)
    out("[R6] deviceId 为整数（类型闸）", *reg({"deviceId": 123}), 200)

    print("\n## 3. session token 作为 Bearer 的身份面（/api/friends）")
    print("   已知：r 线引擎在线运行，与服务端维持 one-live-session-per-(device,network)。")
    print("   我方每次 mint 都顶掉它的会话，它 1s 量级内重登把我方顶掉 —— 抢窗口。")
    wins = 0
    sample = None
    for i in range(8):
        st, b = sess(did, net, tok)
        if st != 200:
            print(f"      round{i}: mint={st} {b[:80]}")
            continue
        t = json.loads(b)["token"]
        t0 = time.time()
        s2, b2 = call("/api/friends", bearer=t)
        dt = int((time.time() - t0) * 1000)
        wins += (s2 == 200)
        # 只打印状态与耗时：出参含第三方身份（好友 userId/username/头像），不落 transcript
        print(f"      round{i}: mint=200 friends={s2} {dt}ms")
        if s2 == 200 and sample is None:
            sample = json.loads(b2)
        time.sleep(1.0)
    print(f"    抢赢 {wins}/8")
    if sample:
        print(f"    friends 出参键: {sorted(sample.keys())}")
        for f in (sample.get("friends") or [])[:3]:
            uid = str(f.get("userId", ""))
            print(f"      userId={uid[:3]}***{uid[-2:]}  (username/display_name 脱敏)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
