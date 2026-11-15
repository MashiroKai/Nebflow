# Design

Use the existing mobile page shell, title and scroll mechanism, preserving page names, accessibility and the real Canvas tab capsule. Inspect `mobileLayer.css:899-986`, `navController.js:270-286,392-422` and `glass.css:135` before any future implementation. Preserve existing navigation and button sizes.

Measure actual SVG shapes as well as their controls and hit targets. Record viewBox, asymmetric ink geometry, visual screenshots and containment. A universal one-pixel ink-center threshold is not an approved product specification. Exercise 320 and 375 widths, both engines and themes, desktop regression, reduced motion and real navigation. Do not replace UI tests with source checks.

The material reference is the actual `#input-bar` in `glass.css:395-400`, with existing fallback and semantic solid buttons retained. Background content, not foreground text, must be indistinguishable. Preserve foreground contrast, states and keyboard focus. Audit the cascade before moving Todo input surface declarations: `mobile-todo-apple.css:168` is not itself necessarily a declaration. Old `mobile-search-flat.spec.mjs:436-458` and `mobile-panel-title.spec.mjs:2-30` encode superseded oracles and need precise updates retaining unrelated protections.

The real-consumer tests in `tests/mobile-search-flat.spec.mjs` cover the settings shell, search/back geometry, Todo glass/input, search/composer material and reduced-motion/lifecycle legs. They do not establish the complete visual or accessibility matrix. The old standalone title and morph suites still encode the superseded capsule oracle and remain to be carefully migrated without deleting unrelated assertions.

The current `glass-spec.md` and `check-glass-unify.mjs` still prescribe 40px/1.4 and alpha 0.45–0.75 for general glass; actual composer tokens are 24px/1.15 and alpha .969/.941. The latest authorized intent requires composer reuse, so the unchanged gate reports this conflict; rule and checker changes need separate author review. No data protocol work belongs here.
