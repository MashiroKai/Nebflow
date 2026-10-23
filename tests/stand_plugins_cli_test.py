#!/usr/bin/env python3
# stand_plugins_cli_test.py — stand-plugins CLI 单测（stand 仓 tests/）
#
# 钉三条命令的输出形状（作者令 2026-10-23 验收项「CLI 单测：list/search/mounts
# 输出形状」），全部走 subprocess 驱动真 CLI + 临时夹具数据根（零网络零守护）：
#   list   — 清单形状（json 键面 / text 状态标签与尾行计数）
#   search — 检索形状（命中 matched_on / 多词 AND / 无命中 "(no hits)"）
#   mounts — 挂载面形状（声明集 flow-map + scope / 分配台账 delegate-spawn +
#            step-plugins / --project 过滤与未挂载显式错）
# 另钉只读纪律：整轮跑完前后夹具树 mtime+清单零变化（只读零副作用）。
#
# 运行：python3 tests/stand_plugins_cli_test.py -v

import glob
import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
CLI = os.path.join(HERE, "..", "tools", "stand-plugins")

CANONICAL = "https://agent-plugins.org/schemas/1.0.0/plugin.schema.json"


def write(path, content):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        f.write(content)


def manifest(name, version="1.0.0", description="", schema=CANONICAL, keywords=None):
    m = {"$schema": schema, "name": name, "version": version, "description": description,
         "author": {"name": "fixture"}}
    if keywords is not None:
        m["keywords"] = keywords
    return json.dumps(m, ensure_ascii=False)


def skill(path, name, description):
    write(path, f"---\nname: {name}\ndescription: {description}\n---\n\n# {name}\nbody\n")


class StandPluginsCliTest(unittest.TestCase):
    tmp = None
    root = None

    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.mkdtemp(prefix="stand-plugins-test-")
        root = cls.root = os.path.join(cls.tmp, "dataroot")
        plugins = os.path.join(root, "plugins")

        # alpha：全能力面（skills + mcp + tools + Plugin.md + keywords）
        d = os.path.join(plugins, "alpha")
        write(os.path.join(d, "plugin.json"),
              manifest("alpha", "1.0.0", "alpha web scraping pack", keywords=["scraping"]))
        skill(os.path.join(d, "skills", "scraper", "SKILL.md"), "scraper", "polite page scraping")
        write(os.path.join(d, "mcp.json"), json.dumps(
            {"mcpServers": {"fetcher": {"type": "stdio", "command": "fetch"}}}))
        write(os.path.join(d, "org.nebflow", "tools.json"), json.dumps({"tools": ["WebSearch"]}))
        write(os.path.join(d, "Plugin.md"), "# alpha\n\n## usage guide\n")

        # beta：仅 skills；描述含中文关键词（检索命中面）
        d = os.path.join(plugins, "beta")
        write(os.path.join(d, "plugin.json"),
              manifest("beta", "0.2.0", "论文下载与文献整理包"))
        skill(os.path.join(d, "skills", "paper", "SKILL.md"), "paper", "论文逐篇下载工作流")

        # gamma：被封禁（revoked）
        d = os.path.join(plugins, "gamma")
        write(os.path.join(d, "plugin.json"), manifest("gamma", "9.9.9", "blocked fixture"))
        skill(os.path.join(d, "skills", "s", "SKILL.md"), "s", "s skill")

        # delta：派发被作者关闭（dispatch.authorEnabled=false）
        d = os.path.join(plugins, "delta")
        write(os.path.join(d, "plugin.json"), manifest("delta", "0.1.0", "dispatch-off fixture"))
        skill(os.path.join(d, "skills", "s", "SKILL.md"), "s", "s skill")

        # 三种拒载形状：坏 manifest / 非 canonical $schema / 无 skills 且无 mcp
        write(os.path.join(plugins, "broken", "plugin.json"), "{not json")
        write(os.path.join(plugins, "noschema", "plugin.json"),
              manifest("noschema", schema="https://example.org/other.json"))
        write(os.path.join(plugins, "hollow", "plugin.json"),
              manifest("hollow", description="no skills no mcp"))

        # 配置面：封禁 + 派发关闭 + 审计记录（不影响 trust——无审批批「扫到即信任」）
        write(os.path.join(root, "nebflow.json"), json.dumps({"plugins": {
            "revoked": {"gamma": {"at": 1, "by": "author", "reason": "deny fixture"}},
            "dispatch": {"delta": {"authorEnabled": False, "authorDecidedAt": 1, "authorDecidedBy": "author"}},
            "trust": {"alpha": {"sha256": "x", "approvedAt": 123}},
        }}))

        # 挂载面：项目 demo（声明集主图 + scope 图）+ 分配台账两行
        ws = os.path.join(root, "ws-demo")
        write(os.path.join(root, "projects", "demo", "project.json"),
              json.dumps({"name": "demo", "workspace": ws, "createdAt": 1}))
        write(os.path.join(ws, ".nebflow", "flow-map.json"), json.dumps({"v": 1, "project": "demo", "nodes": {
            "n1": {"id": "n1", "plugins": ["alpha", "beta"], "status": "completed"},
            "n2": {"id": "n2", "status": "completed"},
        }}))
        write(os.path.join(ws, ".nebflow", "flow-maps", "scope-a", "flow-map.json"),
              json.dumps({"v": 1, "nodes": {"n3": {"id": "n3", "plugins": ["gamma"]}}}))
        write(os.path.join(root, "chains", "t1.json"), json.dumps({"taskId": "t1", "entries": [
            {"at": 1, "kind": "delegate-spawn", "payload": {
                "delegateId": "delegate-x", "project": "demo",
                "plugins": ["alpha", "beta"], "allocation": "jev"}},
            {"at": 2, "kind": "step-plugins", "payload": {
                "nodeId": "n9", "plugins": ["alpha"], "allocation": "llm-fallback"}},
        ]}))
        write(os.path.join(root, "chains", "t2.json"), json.dumps({"taskId": "t2", "entries": [
            {"at": 1, "kind": "delegate-spawn", "payload": {
                "delegateId": "delegate-y", "project": "", "plugins": [], "allocation": "fail-open"}},
        ]}))

    @classmethod
    def tearDownClass(cls):
        shutil.rmtree(cls.tmp, ignore_errors=True)

    # ── 驱动 ──────────────────────────────────────────────────────────────

    def run_cli(self, *args):
        env = {k: v for k, v in os.environ.items()
               if k not in ("STAND_DATA_ROOT", "STAND_HOME", "NEBFLOW_HOME")}
        return subprocess.run(
            [sys.executable, CLI, "--data-root", self.root, *args],
            capture_output=True, text=True, env=env, timeout=60,
        )

    def snapshot_tree(self):
        out = []
        for p in sorted(glob.glob(os.path.join(self.root, "**", "*"), recursive=True)):
            try:
                out.append((p, os.path.getmtime(p), os.path.getsize(p)))
            except OSError:
                out.append((p, None, None))
        return out

    def setUp(self):
        self.tree_before = self.snapshot_tree()

    def tearDown(self):
        self.assertEqual(self.snapshot_tree(), self.tree_before,
                         "the CLI must be read-only: fixture tree unchanged")

    # ── list：形状 ────────────────────────────────────────────────────────

    def test_list_json_shape(self):
        r = self.run_cli("list", "--json")
        self.assertEqual(r.returncode, 0, r.stderr)
        out = json.loads(r.stdout)
        self.assertEqual(out["data_root"], self.root)
        names = [p["name"] for p in out["plugins"]]
        self.assertEqual(names, ["alpha", "beta", "delta", "gamma"])
        self.assertEqual([x["name"] for x in out["rejected"]],
                         ["broken", "hollow", "noschema"], "rejected dirs are named with reasons")
        alpha = out["plugins"][0]
        self.assertEqual(alpha["version"], "1.0.0")
        self.assertEqual([s["id"] for s in alpha["skills"]], ["alpha/scraper"])
        self.assertEqual([m["name"] for m in alpha["mcp_servers"]], ["fetcher"])
        self.assertEqual(alpha["tools"], ["WebSearch"])
        self.assertTrue(alpha["has_plugin_md"])
        self.assertTrue(alpha["trusted"] and alpha["dispatchable"], "default-allow: scanned = trusted")
        self.assertEqual(alpha["approved_at"], 123, "audit record supplies approvedAt")
        gamma = out["plugins"][3]
        self.assertTrue(gamma["blocked"])
        self.assertEqual(gamma["blocked_reason"], "deny fixture")
        self.assertFalse(gamma["trusted"], "revoked is the single deny")
        delta = out["plugins"][2]
        self.assertFalse(delta["dispatchable"])
        self.assertFalse(delta["dispatch_author_enabled"])

    def test_list_text_shape(self):
        r = self.run_cli("list")
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertIn("alpha 1.0.0 [dispatchable]", r.stdout)
        self.assertIn("skills: alpha/scraper", r.stdout)
        self.assertIn("mcp: fetcher · tools: WebSearch", r.stdout)
        self.assertIn("gamma 9.9.9 [blocked: deny fixture]", r.stdout)
        self.assertIn("delta 0.1.0 [dispatch-off]", r.stdout)
        self.assertIn("— 4 plugins (2 dispatchable, 1 blocked, 1 dispatch-off)", r.stdout)
        self.assertIn("rejected: broken(manifest missing/unparseable)", r.stdout)

    # ── search：形状 ──────────────────────────────────────────────────────

    def test_search_hits_name_description_skill_and_and_semantics(self):
        r = self.run_cli("search", "--json", "论文")
        self.assertEqual(r.returncode, 0, r.stderr)
        out = json.loads(r.stdout)
        self.assertEqual([h["name"] for h in out["hits"]], ["beta"])
        self.assertTrue(out["hits"][0]["matched_on"][0].startswith("description:"), out["hits"][0])

        r = self.run_cli("search", "--json", "scraper")
        out = json.loads(r.stdout)
        self.assertEqual([h["name"] for h in out["hits"]], ["alpha"])
        self.assertTrue(out["hits"][0]["matched_on"][0].startswith("skill:"), out["hits"][0])

        # 多词 AND：两词各须命中 → alpha 同中；跨包词组合 → 空
        out = json.loads(self.run_cli("search", "--json", "alpha scraping").stdout)
        self.assertEqual([h["name"] for h in out["hits"]], ["alpha"])
        out = json.loads(self.run_cli("search", "--json", "scraping 论文").stdout)
        self.assertEqual(out["count"], 0, "AND across packages must not merge hits")

        # 封禁/关闭包也可见（查询面不裁剪——可见性归面板/注册表）
        out = json.loads(self.run_cli("search", "--json", "blocked fixture").stdout)
        self.assertEqual([h["name"] for h in out["hits"]], ["gamma"])

    def test_search_text_no_hits(self):
        r = self.run_cli("search", "zzz-no-such-capability")
        self.assertEqual(r.returncode, 0)
        self.assertIn("(no hits)", r.stdout)
        r = self.run_cli("search", "论文")
        self.assertIn("beta 0.2.0 [dispatchable]", r.stdout)
        self.assertIn("matched:", r.stdout)
        self.assertIn("— 1 hit(s) for 论文", r.stdout)

    # ── mounts：形状 ──────────────────────────────────────────────────────

    def test_mounts_json_shape(self):
        r = self.run_cli("mounts", "--json")
        self.assertEqual(r.returncode, 0, r.stderr)
        out = json.loads(r.stdout)
        self.assertEqual(out["data_root"], self.root)
        self.assertEqual([p["project"] for p in out["projects"]], ["demo"], "only demo carries mounts")
        proj = out["projects"][0]
        self.assertEqual(proj["project"], "demo")
        declared = {d["node"]: d for d in proj["declared"]}
        self.assertEqual(declared["n1"]["plugins"], ["alpha", "beta"])
        self.assertEqual(declared["n1"]["where"], "flow-map")
        self.assertEqual(declared["n3"]["where"], "scope:scope-a", "delegate-scope maps are scanned")
        self.assertNotIn("n2", declared, "empty-declaration nodes are not mounts")
        alloc = {(a["subject"], a["kind"]): a for a in proj["allocated"]}
        self.assertEqual(alloc[("delegate-x", "delegate-spawn")]["plugins"], ["alpha", "beta"])
        self.assertEqual(alloc[("delegate-x", "delegate-spawn")]["allocation"], "jev")
        # step-plugins 行无项目字段 → 不进项目桶，落 unattributed 段（连同无项目 spawn 行）
        self.assertNotIn(("n9", "step-plugins"), alloc)
        unattr = {(a["subject"], a["kind"]): a for a in out["unattributed"]}
        self.assertEqual(unattr[("n9", "step-plugins")]["allocation"], "llm-fallback")
        self.assertIn(("delegate-y", "delegate-spawn"), unattr, "empty-project spawn rows are unattributed")

    def test_mounts_project_filter_and_unknown_error(self):
        r = self.run_cli("mounts", "--json", "--project", "demo")
        self.assertEqual(r.returncode, 0, r.stderr)
        out = json.loads(r.stdout)
        self.assertEqual([p["project"] for p in out["projects"]], ["demo"])
        # 过滤视图：step-plugins 行无法归项目 → 省略；delegate-spawn 按 payload.project 匹配
        kinds = {(a["subject"], a["kind"]) for a in out["projects"][0]["allocated"]}
        self.assertIn(("delegate-x", "delegate-spawn"), kinds)
        self.assertNotIn(("n9", "step-plugins"), kinds)

        r = self.run_cli("mounts", "--project", "nope")
        self.assertEqual(r.returncode, 1)
        self.assertIn("not mounted", r.stderr)
        self.assertIn("demo", r.stderr, "candidates listed")

        # 空项目挂载面 = 显式空态，不是错误
        r = self.run_cli("mounts")
        self.assertEqual(r.returncode, 0)
        self.assertIn("project: demo", r.stdout)
        self.assertIn("n1: alpha, beta", r.stdout)
        self.assertIn("delegate-x [delegate-spawn · jev]: alpha, beta", r.stdout)
        self.assertIn("project: (unattributed)", r.stdout, "unattributed ledger rows get their own bucket")
        self.assertIn("n9 [step-plugins · llm-fallback]: alpha", r.stdout)
        self.assertIn("— 1 projects scanned, 2 with mounts", r.stdout)

    def test_no_subcommand_is_usage_error(self):
        r = self.run_cli()
        self.assertNotEqual(r.returncode, 0)
        self.assertIn("list", r.stderr + r.stdout)


if __name__ == "__main__":
    unittest.main(verbosity=2)
