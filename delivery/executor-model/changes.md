# Changes

## What

The background external-delegate conversation offers a per-executor default model ID. The value is immediately persisted in `executor.models`, hot-read on a new dispatch, recorded on the delegate, and reused on continuation. Claude Code and Codex receive `--model`; ZCode is marked unsupported. An empty value follows the CLI default.

## Why

External CLI sessions previously inherited a model chosen by the CLI without an owner control in the conversation. Built-in provider model references are not interchangeable with CLI model IDs.

## Impact

Owner-authenticated executor configuration routes and on-disk delegate records gain optional model data. Existing records decode without it; existing CLI default behavior is preserved. Changes affect future dispatches rather than a running session. When the saved value is empty, a later CLI default may differ; Stand does not know or pin the CLI's resolved default model.

Author-reported engine evidence before synchronization: `ExternalExecutorSpec` 26/26 passed; `scalafmtCheckAll` reported baseline Perception files outside this branch. See the branch report for post-sync frontend evidence.
