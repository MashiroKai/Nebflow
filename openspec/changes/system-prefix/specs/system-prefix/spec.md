# System Prefix

## ADDED Requirements

### Requirement: [PREFIX-REQ-001] Build-embedded shared system prefix
At system-prompt assembly, the engine MUST prepend the UTF-8 classpath resource `seed/prompt-prefix.md` under the `--- SYSTEM PREFIX ---` marker before the agent prompt and conditional blocks. The resource is the sole build-time source. The engine MUST NOT read `prompt-prefix.md` from the data root; changing that external file has no effect. Changes to the resource require a new build, and existing cached session prompts retain their lifecycle.

#### Scenario: [PREFIX-SCN-001] Embedded prefix at assembly
- **WHEN** a system prompt is assembled
- **THEN** the marked resource content precedes the agent prompt and conditional blocks

#### Scenario: [PREFIX-SCN-002] External prefix is ignored
- **WHEN** the data root contains a different `prompt-prefix.md`
- **THEN** the assembled prompt still uses the build resource and excludes the external content

### Requirement: [PREFIX-REQ-002] Reviewable CLI template
The build resource MUST describe `stand-recall`, `stand-messages`, `stand-plugins`, `stand-terms`, and `stand-profile`, with examples and their safety boundaries. The terms index is a derived cache and vault documents are the source of truth; profile is read before recall. The template MUST contain only product-level cross-project content true and useful for every agent on any project. Branch policies, CI gates, seed-parity checks and repository-specific scripts, including `stand-branch-guard`, MUST NOT appear. The build MUST NOT install the template into the live data root.

#### Scenario: [PREFIX-SCN-003] Product-wide CLI guidance reaches nodes
- **WHEN** an agent node's system prompt is assembled
- **THEN** the embedded prefix contains the five tools and universality rule, without branch-guard
