#!/usr/bin/env python3
"""rcptcode 回归面黑盒探针（R1）：折叠修复**只许**落在好友发送路由。

本批明令「明确不动」的面（考古 §3.4），黑盒逐条确认语义未变形：
  1. POST /api/friends/{uid}/unblock  上游 403 not_blocker ⇒ 网关 **502**（继续折叠）
  2. POST /api/groups/{gid}/messages  上游 403 not_member  ⇒ 网关 **403 逐字**（群腿原样）
  3. GET  /api/friends                上游 403 令牌拒收     ⇒ 网关 **502**（F4 有意保留）

用法：python3 rcptcode-probe-regress.py   （classpath 读 /tmp/nb-rcptcode/cp.txt）
"""
import json, os, re, subprocess, threading, time, urllib.error, urllib.request, urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

CP_FILE = "/tmp/nb-rcptcode/cp.txt"
OUT = "/tmp/nb-rcptcode/probe-regress.json"
WORKTREE = "/Users/kaiyu/Claude code/Nebflow/.nebflow/worktrees/rcptcode-impl"
HOME = "/tmp/nb-rcptcode/home-regress"
GW_PORT = 8095
STUB_PORT = 8495
JAVA = "/Library/Java/JavaVirtualMachines/temurin-23.jdk/Contents/Home/bin/java"
GW = f"http://127.0.0.1:{GW_PORT}"
STUB = f"http://127.0.0.1:{STUB_PORT}"

ST = {"friends_upstream_403": False}   # 阶段 2：GET /api/friends 改答 403（F4 面）
CALLS = []
INSTANCE = {"proc": None}


class Stub(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *a):
        pass

    def _read(self):
        n = int(self.headers.get("Content-Length") or 0)
        return self.rfile.read(n).decode("utf-8", "replace") if n else ""

    def _send(self, status, obj):
        body = json.dumps(obj).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Connection", "close")
        self.close_connection = True
        self.end_headers()
        self.wfile.write(body)

    def _handle(self, method):
        path = urllib.parse.urlparse(self.path).path
        raw = self._read()
        CALLS.append({"method": method, "path": path})
        if path == "/api/device/enroll":
            return self._send(200, {"deviceToken": "tok-stub", "networkId": "n1", "deviceId": "d1",
                                    "userId": "me", "username": "me", "displayName": "Me",
                                    "serverUrl": STUB, "peers": []})
        if path.startswith("/api/device/"):
            return self._send(200, {"token": "tok-stub", "networkId": "n1", "deviceId": "d1",
                                    "peers": [], "expiresAt": 4102444800})
        if path == "/api/friends":
            if ST["friends_upstream_403"]:
                return self._send(403, {"error": "Missing or invalid token"})
            return self._send(200, {"friends": [], "incoming": [], "outgoing": []})
        if re.match(r"^/api/friends/[^/]+/unblock$", path):
            return self._send(403, {"error": "not_blocker"})
        if re.match(r"^/api/groups/[^/]+/messages$", path):
            return self._send(403, {"error": "not_member"})
        if path == "/api/conversations":
            return self._send(200, [])
        return self._send(200, {})

    def do_GET(self):
        self._handle("GET")

    def do_POST(self):
        self._handle("POST")


def gw_call(method, path, token=None, payload=None, timeout=20):
    data = json.dumps(payload).encode() if payload is not None else None
    req = urllib.request.Request(GW + path, data=data, method=method)
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    if data:
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, r.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")
    except Exception as e:
        return None, f"{type(e).__name__}: {e}"


def start_instance():
    cp = open(CP_FILE).read().strip()
    os.makedirs(HOME, exist_ok=True)
    env = dict(os.environ)
    env["JAVA_HOME"] = "/Library/Java/JavaVirtualMachines/temurin-23.jdk/Contents/Home"
    log = open("/tmp/nb-rcptcode/instance-regress.log", "ab")
    p = subprocess.Popen([JAVA, "-Xmx768m", "-cp", cp, "nebflow.Main",
                          "--home", HOME, "--port", str(GW_PORT), "--no-browser"],
                         cwd=WORKTREE, env=env, stdout=log, stderr=subprocess.STDOUT)
    INSTANCE["proc"] = p
    for _ in range(180):
        if p.poll() is not None:
            raise RuntimeError(f"instance exited rc={p.returncode}")
        st, _b = gw_call("GET", "/api/health", timeout=3)
        if st == 200:
            return p
        time.sleep(1)
    raise RuntimeError("instance health timeout")


def stop_instance():
    p = INSTANCE.get("proc")
    if not p or p.poll() is not None:
        return
    p.terminate()
    for _ in range(20):
        if p.poll() is not None:
            break
        time.sleep(0.5)
    if p.poll() is None:
        p.kill()
    p.wait(timeout=10)


def read_token():
    with open(os.path.join(HOME, "auth.json")) as f:
        return json.load(f)


def main():
    stub = ThreadingHTTPServer(("127.0.0.1", STUB_PORT), Stub)
    threading.Thread(target=stub.serve_forever, daemon=True).start()
    out = {"stub": STUB, "gw": GW}
    try:
        start_instance()
        print(f"[1] 实例就绪 {GW}", flush=True)
        token = read_token()
        st, body = gw_call("POST", "/api/neblink/enroll", token, {"server": STUB, "pairCode": "000000"})
        print(f"[2] enroll -> {st}", flush=True)
        stop_instance()
        start_instance()
        token = read_token()
        warm = gw_call("POST", "/api/friends/reg-warm/messages", token, {"body": "w"})
        print(f"[3] warmup -> {warm[0]} {warm[1][:80]}", flush=True)

        print("[4] R1 回归臂", flush=True)
        rows = {}
        st1, b1 = gw_call("POST", "/api/friends/forbidden-guy/unblock", token)
        rows["unblock_403_notblocker"] = {"gateway_status": st1, "gateway_body": b1}
        print(f"  unblock 403 not_blocker -> gateway={st1} {b1[:90]}", flush=True)
        st2, b2 = gw_call("POST", "/api/groups/g1/messages", token, {"body": "hi", "origin": "user"})
        rows["group_send_403_notmember"] = {"gateway_status": st2, "gateway_body": b2}
        print(f"  group 403 not_member    -> gateway={st2} {b2[:90]}", flush=True)
        ST["friends_upstream_403"] = True
        st3, b3 = gw_call("GET", "/api/friends", token)
        rows["get_friends_upstream_403"] = {"gateway_status": st3, "gateway_body": b3}
        print(f"  GET /friends upstream403-> gateway={st3} {b3[:90]}", flush=True)
        out["arms"] = rows
        out["stub_calls"] = CALLS[:20]
        with open(OUT, "w") as f:
            json.dump(out, f, ensure_ascii=False, indent=2)
        print(f"[5] readings -> {OUT}", flush=True)
    finally:
        stop_instance()
        try:
            stub.shutdown()
            stub.server_close()
        except Exception:
            pass
        print("[6] 清理完成", flush=True)


if __name__ == "__main__":
    main()
