package nebflow.core.tools

import munit.FunSuite

/**
 * 审批触发判据 = 操作风险类（2026-11-18 审批门收窄批）。
 * 只读命令必须空集（这是「正常读文件不再触发审批」的语义承重）；
 * 写/删/出网/凭据必须被识别（「不许把闸放宽到失效」的语义承重）。
 */
class CommandRiskSpec extends FunSuite:

  private val ReadOnly = List(
    "cat README.md",
    "less README.md",
    "ls -la src",
    "head -50 README.md",
    "tail -n 20 README.md",
    "wc -l README.md",
    "rg -n pattern src/",
    "grep -n needle README.md",
    "sed -n p README.md",
    "git log --oneline -5 -- README.md",
    "git diff --stat -- README.md",
    "git status --short",
    "git show HEAD:README.md",
    "git stash list",
    "cat README.md | grep hello",
    "ls; git status -- README.md",
    "find . -name '*.md'",
    "test -f README.md",
    "stat README.md",
    "ls -la 2>/dev/null",
    "somecmd 2>&1 | grep needle"
  )

  test("read-only commands carry no risk class"):
    for command <- ReadOnly do
      assertEquals(CommandRisk.classify(command), Set.empty[CommandRisk], command)
      assert(!CommandRisk.isRisky(command), command)

  test("writes, deletes, egress and credential access are classified"):
    val expected = List(
      "tee README.md" -> Set(CommandRisk.Write),
      "truncate README.md" -> Set(CommandRisk.Write),
      "dd of=README.md" -> Set(CommandRisk.Write),
      "mv a README.md" -> Set(CommandRisk.Write),
      "cp a README.md" -> Set(CommandRisk.Write),
      "install a README.md" -> Set(CommandRisk.Write),
      "patch README.md" -> Set(CommandRisk.Write),
      "chmod 600 README.md" -> Set(CommandRisk.Write),
      "chown user README.md" -> Set(CommandRisk.Write),
      "ln -s a README.md" -> Set(CommandRisk.Write),
      "mkdir -p dir" -> Set(CommandRisk.Write),
      "touch README.md" -> Set(CommandRisk.Write),
      "echo x > README.md" -> Set(CommandRisk.Write),
      "echo x >> README.md" -> Set(CommandRisk.Write),
      "echo x >README.md" -> Set(CommandRisk.Write),
      "sed -i 's/a/b/' README.md" -> Set(CommandRisk.Write),
      "vim README.md" -> Set(CommandRisk.Write),
      "git add README.md" -> Set(CommandRisk.Write),
      "git commit -m msg" -> Set(CommandRisk.Write),
      "git checkout -- README.md" -> Set(CommandRisk.Write),
      "git reset --hard" -> Set(CommandRisk.Write),
      "rm README.md" -> Set(CommandRisk.Delete),
      "rm -rf dir" -> Set(CommandRisk.Delete),
      "unlink README.md" -> Set(CommandRisk.Delete),
      "shred README.md" -> Set(CommandRisk.Delete),
      "find . -name x -delete" -> Set(CommandRisk.Delete),
      "git clean -fd" -> Set(CommandRisk.Write, CommandRisk.Delete),
      "curl -s https://example.com" -> Set(CommandRisk.NetworkEgress),
      "ssh host ls" -> Set(CommandRisk.NetworkEgress),
      "scp a b:tmp/" -> Set(CommandRisk.NetworkEgress),
      "wget https://example.com" -> Set(CommandRisk.NetworkEgress),
      "cat ~/.ssh/id_rsa" -> Set(CommandRisk.CredentialAccess),
      "cat .env" -> Set(CommandRisk.CredentialAccess),
      "cat /home/u/.stand/secrets/token" -> Set(CommandRisk.CredentialAccess),
      "rm ~/.aws/credentials" -> Set(CommandRisk.Delete, CommandRisk.CredentialAccess)
    )
    for (command, classes) <- expected do
      assertEquals(CommandRisk.classify(command), classes, command)

  test("substring look-alikes are not mistaken for risk primitives"):
    for command <- List("echo curling", "ssh-keygen -t ed25519 -f /tmp/k", "ls rmfile.txt", "cat README.md.rm") do
      assertEquals(CommandRisk.classify(command), Set.empty[CommandRisk], command)
end CommandRiskSpec
