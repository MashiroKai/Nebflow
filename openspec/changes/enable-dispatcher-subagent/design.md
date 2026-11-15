# Design

## Existing path and evolution

Delegate dispatchers use the builtin `nebflow` definition. The first part of this change removed dispatcher-specific Subagent filtering and runtime denial. Its initial five-tool acceptance evidence is historical; the user's follow-up explicitly expands Subagent with Bash.

## Implementation

Add registered Bash to the fixed Subagent tool face. Maintain an independent Ask tool face rather than inheriting the Subagent face. The user's subsequent Ask request explicitly adds Bash and removes the dedicated GitQuery tool. Keep the existing blocking runner, prompt validation, fixed builtin spawn target and result/cleanup path. No new agent type or configuration switch is needed.

Update the canonical Subagent role prompt, builtin descriptions, delegate guidance, tool description and kernel-gate explanation. A shell-capable child is not mechanically read-only. It remains a reconnaissance role by instruction, with completed foreground commands and final findings rather than background work. Dispatcher executable tasks continue through chain nodes. Kernel permits this exact builtin child but still refuses other child definitions.

## Acceptance

Verify the actual child request exposes exactly six tools. Use a scripted LLM that emits a real safe foreground Bash call in the isolated fixture, receives its output and then returns final findings. Assert output delivery, terminal task state and child cleanup. Retain kernel negative-spawn, ordinary caller, prompt validation and Ask effective-tool-face tests.

Compile and run targeted suites with explicit JDK 23 and isolated home. Run repository gates and report actual failures without broadening scope or weakening baselines. Prompt changes remain pending author review; live provider/UI verification and deployment are separate.

## Ask follow-up

The user subsequently requested Bash for Ask and removal of the previous Git-related tool. Remove Ask-only GitQuery registration, implementation and dedicated tests, replacing them with retirement assertions. Keep the two messaging tools Ask-exclusive; Bash remains a shared registered tool. Resolve initial Ask shell cwd from the existing verified file binding to its parent directory through the existing sessionCwd channel. Do not change other root-session cwd behavior. Verify a real safe command and retain Subagent regressions.
