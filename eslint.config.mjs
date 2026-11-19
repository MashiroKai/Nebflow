import js from '@eslint/js';
import globals from 'globals';

// Lint the engineering tooling only; application-wide migration is separate.
export default [{
  files: ['eslint.config.mjs', 'scripts/check-js-types.mjs', 'scripts/run-checks.mjs', 'scripts/check-sdd.mjs', 'scripts/check-openspec.mjs', 'scripts/tests/**/*.mjs'],
  languageOptions: { ecmaVersion: 2022, sourceType: 'module', globals: globals.node },
  rules: js.configs.recommended.rules,
}];
