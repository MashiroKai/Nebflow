# Design

Reuse the existing term popover, Note morph, canvas close event, sidebar and search selection state. Invalidate each superseded term request even if the same link is reopened. Diff closure is keyed by canvas tab id and must not close a subsequently opened batch. Selection mode is explicit and limited to files; existing media routing and Note storage are unchanged.
