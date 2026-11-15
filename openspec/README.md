# Stand specifications

`specs/<capability>/spec.md` contains accepted current contracts. `changes/<change>/` contains a proposal, design, implementation checklist, capability deltas, and `trace.json`. Proposals may add or modify scenarios without changing accepted current specs. This foundation intentionally initializes current specs with its approved target contract while integration remains open; that bootstrap choice is not a promotion rule for later proposals or evidence that every task passed.

## Contribution workflow

1. Read the affected current capability and search for existing requirement/scenario IDs before adding intent. Keep IDs stable across edits; do not renumber or reuse IDs for unrelated behavior.
2. For a future behavioral or engineering-contract change, write `proposal.md`, `design.md`, `tasks.md`, and `specs/<capability>/spec.md` under a descriptive change directory before implementation. Keep the amount of prose proportional to risk. A wording correction without contract changes can update existing prose directly.
3. Use OpenSpec specification headings: current specs use `## Purpose` and `## Requirements`; deltas use `## ADDED Requirements`, `## MODIFIED Requirements`, or `## REMOVED Requirements`. Use `### Requirement: [EW-REQ-001] Title` and `#### Scenario: [EW-SCN-001] Title`, normative SHALL/MUST language, and explicit WHEN/THEN acceptance. IDs accept an uppercase alphanumeric/hyphenated capability prefix, `REQ` or `SCN`, and at least three digits. A new capability needs no checker code change; choose a globally unused prefix. Scenario prefixes match their owning requirement. Modified/removed requirements retain stable IDs. A normal REMOVED entry may omit scenarios and instead include `**Reason**:` and `**Migration**:`.
4. Map every delta scenario in `trace.json` to its owning requirement, implementation paths, and named tests or a reasoned manual exception. For a scenario-free removal, map the requirement and omit `scenario`. Leave accepted current specs unchanged during proposal work. If current specs are changed with an explicit base, every added/modified/removed requirement must match the relevant delta by ID, capability, and full requirement content; existing file-level links do not authorize new scenarios or unrelated prose changes. Do not complete tasks merely because mappings exist.
5. Run link validation and actual relevant tests separately. Record the actual command and outcome in review evidence. Run the pinned upstream OpenSpec validator separately; it checks OpenSpec structure, not implementation correctness.
6. After acceptance, reconcile current requirements with the accepted ADDED/MODIFIED/REMOVED deltas and move the change to `changes/archive/YYYY-MM-DD-<change>/`. Keep `trace.change` unchanged. Two explicit-base cases are supported. If the base contains the active change, its directory must disappear and every artifact must move unchanged except completed task checkboxes; coverage is limited to those moves and associated current reconciliation. Alternatively, a change may be proposed, implemented, and archived in one review diff: the base must contain neither its active directory nor a same-name archive, every archive artifact must be introduced by this diff, and its trace must resolve live implementation/test links. This new archive may cover only matching changed implementation paths from its trace plus its own artifacts and current reconciliation. Both cases require at least one checked task, no unchecked tasks, exact accepted non-removed requirements, and absent removed requirements. Neither case requires a recursive bookkeeping change. Archives already present in the base cannot authorize later code edits. Old archives still validate structure and safe paths without requiring historical files to exist.

## Query and verification

From the repository root, after installing the repository's locked dependencies through the normal contributor setup:

```sh
npm run check:sdd
node --test scripts/tests/check-sdd.test.mjs
npm run check:sdd -- --base <explicit-base-commit>
node scripts/check-sdd.mjs --root /absolute/fixture/root --base <fixture-base>
node scripts/run-checks.mjs --profile static --list
```

The standalone checker accepts only `--base <git-ref>` and `--root <directory>`. Without a base it validates artifacts and links, even outside Git, and explicitly skips diff coverage. With a base it requires a Git repository root and a resolvable commit, then compares that commit directly to the working tree plus nonignored untracked files. This is not an implicit merge-base or HEAD comparison; select the actual review base explicitly. Unknown/missing bases and Git failures are errors.

The explicit initial batch scope lives in `openspec/tooling-scope.json`: OpenSpec artifacts, SDD/type/shared-runner scripts and script tests, lint configuration, package manifests, Makefile, the three modified CI definitions, contributor guides, and specifically listed stale seed files. Entries are exact files or directory prefixes ending in `/`. With a base, the union of the base registry and current registry applies, so deleting a scope entry cannot silently exempt the same change.

Other application and historical paths remain outside mechanical enforcement. Adding tooling to the gate requires a reviewed registry edit and trace mapping, not checker code changes or a whole-tree backfill. New requirements/scenarios in current specs need associated deltas even when the file already had a mapping. No-base checks cannot compare against history and do not claim to do so.

The official tool is pinned in `openspec/toolchain.json`, including platform archive SHA-256 values. `npm run check:openspec -- --download` downloads the verified binary to a temporary directory, validates using an isolated home, and removes it. It requires network access; download failure is an error. To use an already verified binary, run `npm run check:openspec -- --binary /absolute/path/to/openspec`. The wrapper checks the exact version and runs strict validation. Neither form invokes initialization or modifies AGENTS.md.

For an upstream OpenSpec **2.0.0** CLI installed in an isolated tool environment, use its read-only query/validation commands, not `init` or `update`:

```sh
openspec list
openspec list --specs
openspec show establish-sdd-foundation
openspec validate --all --strict
```

Do not confuse upstream validation with the local trace checker. The isolated CLI installation and its actual result belong to integration verification.

Search current intent by ID or words, then inspect the matching delta:

```sh
rg 'EW-REQ-003|QG-SCN-004' openspec
rg '^### Requirement:|^#### Scenario:' openspec/specs
```

## Trace format (version 1)

One `trace.json` per change avoids parsing acceptance links out of arbitrary prose. It contains IDs and links, not a second copy of requirement text:

```json
{
  "version": 1,
  "change": "example",
  "mappings": [
    {
      "requirement": "EW-REQ-001",
      "scenario": "EW-SCN-001",
      "implementation": ["scripts/example.mjs"],
      "tests": [{ "path": "scripts/tests/example.test.mjs", "name": "specific acceptance test" }]
    }
  ]
}
```

Paths are repository-relative files, never URLs, absolute paths, traversal segments, or escaping symlinks. Each test name must be a nonempty literal found in that file; this verifies a link, not test registration, assertion quality, execution, or success. `tests` is always an array. If it is empty, supply `manual: { "reason": "A substantive explanation of why automated acceptance is unsuitable", "steps": "Repeatable review procedure", "expected": "Observable acceptance outcome" }`. The reason must have at least 20 trimmed characters; this threshold is a completeness check, not a judgement that the exception is justified. Optional manual review may also supplement automated tests. Unknown fields, including test commands, are rejected.

For a deletion, use `{ "path": "old/file.mjs", "deleted": true }` in `implementation`. The file must be absent. With `--base`, a new claim must appear as deleted in that diff. An existing active claim may persist when the path was already absent at the base and the same base trace file already contains that exact deletion under the same requirement/scenario mapping. A changed trace may retain such claims while updating other links; absence alone, a different mapping, or another change's trace is not deletion evidence. New same-diff archives require their deletion claims to appear in that diff. Without a base, absence is checked but deletion history is not verified. For a rename include both that deletion entry and the new file path. In-scope changes, including README, tasks, specs and trace itself, require active mappings when an explicit base is supplied, except the narrowly verified acceptance/archive transition described above. Unstaged renames may appear as delete/add; both sides are still covered.

## Engineering guidance: one source

The independent [Stand dev source repository](https://git.ustc.edu.cn/kaiyu/stand-dev) owns the reusable engineering guidance. Start at its [skill entry](https://git.ustc.edu.cn/kaiyu/stand-dev/-/blob/main/skills/stand-dev/SKILL.md), then load only the relevant reference, such as [SDD](https://git.ustc.edu.cn/kaiyu/stand-dev/-/blob/main/skills/stand-dev/references/methods/sdd.md), [testing acceptance](https://git.ustc.edu.cn/kaiyu/stand-dev/-/blob/main/skills/stand-dev/references/qa/testing-acceptance.md), [verification rigor](https://git.ustc.edu.cn/kaiyu/stand-dev/-/blob/main/skills/stand-dev/references/qa/verification-rigor.md), or [code style](https://git.ustc.edu.cn/kaiyu/stand-dev/-/blob/main/skills/stand-dev/references/engine/code-style.md). Repository access may require authentication. Source guidance is independently versioned; it is not proof of an installed plugin update. This directory does not replace that rulebook or authorize rewriting `AGENTS.md`.

## Evidence limits

A passing SDD check means links and declared diff coverage are complete, not that behavior is correct. A passing static profile means the selected checkout-only commands succeeded, not that browser, Scala/backend, live-resource, release, or manual acceptance passed. Report baseline gate failures and unexecuted verification explicitly; do not raise baselines or mark tasks complete to manufacture a green result.
