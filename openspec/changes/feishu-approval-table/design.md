# Design

Two independent deltas ride this change. Table rendering: ordinary Feishu replies render GFM tables
as narrow-screen readable labeled rows. `src/main/scala/nebflow/social/FeishuTableText.scala` owns the
rewrite and `src/main/scala/nebflow/social/FeishuOutboundAdapter.scala` applies it on the text leg;
when parsing is ambiguous the original table is kept rather than dropped, and Weixin output and the
local transcript are untouched. Permission delivery: a pending tool permission request is delivered
to the bound Feishu conversation as a card
(`src/main/scala/nebflow/social/FeishuPermissionCard.scala`) whose allow/deny callbacks are bound to
approver, chat and message and only flip after the hub acknowledges them. Live delivery in an
isolated instance with actual Feishu traffic is a separate acceptance step and is NOT proved by the
offline specs.
