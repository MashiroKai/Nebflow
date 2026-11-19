# State machine

Before:
```mermaid
stateDiagram-v2
  main --> development: branch
  development --> main: MR
  main --> release: author promotion
```

After:
```mermaid
stateDiagram-v2
  feat_base --> development: branch
  development --> feat_base: MR
  feat_base --> main: promotion
  main --> feat_base: sync
  main --> hotfix: bug branch
  hotfix --> main: audited MR and author approval
  main --> release: author promotion unchanged
```
