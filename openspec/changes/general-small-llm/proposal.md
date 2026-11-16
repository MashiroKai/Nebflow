# General small LLM configuration

## Problem and scope

Provide an independently configurable model for lightweight calls, with main-model fallback when not configured. The settings interface must expose the configuration on desktop and mobile. Existing session-category settings remain unchanged.

## Non-goals

Connecting Ask, plugin assignment or entry correction to the accessor is future work. This change does not change model provider behavior or session pin ownership.
