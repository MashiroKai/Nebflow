// contentI18n.js — display-only localization for BUNDLED content
// (plugins / agents / projects).
//
// ══ contenti18n batch, 2026-09-15 ══════════════════════════════════════════
//
// SCOPE = the default install set only — the `seed/manifest.json` items
// (3 plugins + 4 agents + 1 project = 8 entries). Keys are `content.<kind>.<id>.<field>`
// with kind ∈ plugin|agent|project and field ∈ name|desc; the values live in
// the SAME dictionaries the rest of the UI uses (js/locales/en.js,
// js/locales/zh-CN.js), so there is one resource layer, not two.
//
// DISPLAY-ONLY — the server-side signal values are deliberately NOT touched:
//   • `plugin.description` / `skill.description` also feed the dispatcher's
//     Plugin Catalog (PluginRegistry.catalogLine → DispatcherContextCatalog),
//     i.e. they are the signal by which the dispatcher picks plugins;
//   • `project.description` also feeds the task-board goal line and the Nebula
//     prompt (ProjectActor.projectGoal).
// Translating those would change dispatch behaviour. So localization happens
// strictly at CLIENT RENDER POINTS: the server keeps sending its original
// values and the client picks a string by locale. Behaviour is untouched by
// construction — no Scala and no `seed/**` byte changes.
//
// LANGUAGE SOURCE OF TRUTH = i18n.js only (localStorage key `nebflow_locale`
// via getLocale()). This module introduces NO second source of truth: it reads
// the current locale from i18n.js and never writes one.
//
// FALLBACK CHAIN = current locale → en → the server-provided source value.
// The default set is fully paired en/zh, so the chain never fires for it; the
// last step is insurance for entries outside the set (e.g. skill descriptions
// of user-installed plugins), and guarantees we never render a blank string,
// a bare key name, or a leftover placeholder.

import zhCN from './locales/zh-CN.js';
import en from './locales/en.js';
import { getLocale } from './i18n.js';

/** @type {Record<string, Record<string, string>>} */
const DICTS = { 'zh-CN': zhCN, en };

/**
 * Resolve the display text of one bundled-content field.
 * @param {'plugin'|'agent'|'project'|'skill'} kind entry kind
 * @param {string} id entry id exactly as the server sent it (plugin package
 *   name / agent name / project name / `<plugin>/<skill>`), used as the key.
 * @param {'name'|'desc'} field which rendered field to resolve.
 * @param {unknown} sourceValue the server-provided value — last resort in the
 *   chain, so an unpaired entry degrades to today's behaviour, never to blank.
 * @returns {string}
 */
export function contentText(kind, id, field, sourceValue) {
  const source = sourceValue == null ? '' : String(sourceValue);
  if (!id) return source;
  const key = `content.${kind}.${id}.${field}`;
  const dict = DICTS[getLocale()];
  const localized = dict ? dict[key] : undefined;
  if (typeof localized === 'string' && localized !== '') return localized;
  const english = en[key];
  if (typeof english === 'string' && english !== '') return english;
  return source;
}
