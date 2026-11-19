# Design

`PromptSections.assembleSystemPrompt` loads the UTF-8 resource `/seed/prompt-prefix.md` from the build classpath, closes the stream, and places the marked content before the agent prompt and conditional blocks. A missing resource is a packaging error, not an invitation to fall back to external mutable state. The separate file-based conditional sections remain unchanged.

The seed is the sole build source; changing it requires rebuilding and deploying. Existing cached sessions keep their existing lifecycle. No live data-root file is installed or removed by this change.
