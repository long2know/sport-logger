# Model Selection Reference

### Per-Agent Model Selection

Before spawning an agent, determine which model to use. Check these layers in order — first match wins:

**Layer 0 — Persistent Config (`.squad/config.json`):** On session start, read `.squad/config.json`. If `agentModelOverrides.{agentName}` exists, use that model for this specific agent. Otherwise, if `defaultModel` exists, use it for ALL agents. This layer survives across sessions — the user set it once and it sticks.

- **When user says "always use X" / "use X for everything" / "default to X":** Write `defaultModel` to `.squad/config.json`. Acknowledge: `✅ Model preference saved: {model} — all future sessions will use this until changed.`
- **When user says "use X for {agent}":** Write to `agentModelOverrides.{agent}` in `.squad/config.json`. Acknowledge: `✅ {Agent} will always use {model} — saved to config.`
- **When user says "switch back to automatic" / "clear model preference":** Remove `defaultModel` (and optionally `agentModelOverrides`) from `.squad/config.json`. Acknowledge: `✅ Model preference cleared — returning to automatic selection.`

**Reasoning effort:** Valid values are `low`, `medium`, `high`, `xhigh`, `max`, and `auto`. Resolve `agentReasoningEffortOverrides.{agentName}` before `defaultReasoningEffort`. Keep that preference independent from model selection, but pass a configured value such as `max` unchanged only when the selected model advertises reasoning-effort support. For an incompatible fallback, omit `reasoning_effort` for that attempt and retain the stored preference for future compatible spawns.

**Layer 1 — Session Directive:** Did the user specify a model for this session? ("use opus for this session", "save costs"). If yes, use that model. Session-wide directives persist until the session ends or contradicted.

**Layer 2 — Charter Preference:** Does the agent's charter have a `## Model` section with `Preferred` set to a specific model (not `auto`)? If yes, use that model.

**Layer 3 — Task-Aware Auto-Selection:** Use the governing principle: **cost first, unless code is being written.** Match the agent's task to determine output type, then select accordingly:

| Task Output | Model | Tier | Rule |
|-------------|-------|------|------|
| Writing code (implementation, refactoring, test code, bug fixes) | `claude-sonnet-4.6` | Standard | Quality and accuracy matter for code. Use standard tier. |
| Writing prompts or agent designs (structured text that functions like code) | `claude-sonnet-4.6` | Standard | Prompts are executable — treat like code. |
| NOT writing code (docs, planning, triage, logs, changelogs, mechanical ops) | `claude-haiku-4.5` | Fast | Cost first. Haiku handles non-code tasks. |
| Visual/design work requiring image analysis | `claude-opus-4.5` | Premium | Vision capability required. Overrides cost rule. |

**Role-to-model mapping** (applying cost-first principle):

| Role | Default Model | Why | Override When |
|------|--------------|-----|---------------|
| Core Dev / Backend / Frontend | `claude-sonnet-4.6` | Writes code — quality first | Heavy code gen → `gpt-5.3-codex` |
| Tester / QA | `claude-sonnet-4.6` | Writes test code — quality first | Simple test scaffolding → `claude-haiku-4.5` |
| Lead / Architect | auto (per-task) | Mixed: code review needs quality, planning needs cost | Architecture proposals → premium; triage/planning → haiku |
| Prompt Engineer | auto (per-task) | Mixed: prompt design is like code, research is not | Prompt architecture → sonnet; research/analysis → haiku |
| Copilot SDK Expert | `claude-sonnet-4.6` | Technical analysis that often touches code | Pure research → `claude-haiku-4.5` |
| Designer / Visual | `claude-opus-4.5` | Vision-capable model required | — (never downgrade — vision is non-negotiable) |
| DevRel / Writer | `claude-haiku-4.5` | Docs and writing — not code | — |
| Scribe / Logger | `claude-haiku-4.5` | Mechanical file ops — cheapest possible | — (never bump Scribe) |
| Git / Release | `claude-haiku-4.5` | Mechanical ops — changelogs, tags, version bumps | — (never bump mechanical ops) |

**Task complexity adjustments** (apply at most ONE — no cascading):
- **Bump UP to premium:** architecture proposals, reviewer gates, security audits, multi-agent coordination (output feeds 3+ agents)
- **Bump DOWN to fast/cheap:** typo fixes, renames, boilerplate, scaffolding, changelogs, version bumps
- **Switch to code specialist (`gpt-5.3-codex`):** large multi-file refactors, complex implementation from spec, heavy code generation (500+ lines)
- **Switch to analytical diversity (`gemini-3-pro-preview`):** code reviews where a second perspective helps, security reviews, architecture reviews after a rejection

**Layer 4 — Default:** If nothing else matched, use `claude-haiku-4.5`. Cost wins when in doubt, unless code is being produced.

**Fallback chains — when a model is unavailable:**

If a spawn fails because the selected model is unavailable (plan restriction, org policy, rate limit, deprecation, or any other reason), silently retry with the next model in the chain. Do NOT tell the user about fallback attempts. Maximum 3 retries before jumping to the nuclear fallback.

```
Repository default: gpt-5.6-sol → gpt-5.4 → claude-sonnet-4.6 → claude-sonnet-4.5 → (omit model and reasoning_effort)
Premium:  claude-opus-4.6 → claude-opus-4.5 → claude-sonnet-4.6 → claude-sonnet-4.5 → (omit model and reasoning_effort)
Standard: claude-sonnet-4.6 → claude-sonnet-4.5 → gpt-5.4 → gpt-5.3-codex → claude-sonnet-4 → (omit model and reasoning_effort)
Fast:     claude-haiku-4.5 → gpt-5.4-mini → gpt-5.1-codex-mini → gpt-4.1 → (omit model and reasoning_effort)
```

Runtime model metadata is authoritative for effort capability. `claude-sonnet-4.5` and `claude-haiku-4.5` do not accept `reasoning_effort`; omit it when either is selected. Treat any model whose capability is unknown or unadvertised the same way rather than guessing.

`(omit model and reasoning_effort)` = call the `task` tool WITHOUT either parameter. The platform uses its built-in defaults. This is the nuclear fallback — it always works.

**Fallback rules:**
- If the user specified a provider ("use Claude"), fall back within that provider only before hitting nuclear
- Never fall back UP in tier — a fast/cheap task should not land on a premium model
- Re-check effort support for every retry; an incompatible retry omits `reasoning_effort` without changing persistent config
- Log fallbacks to the orchestration log for debugging, but never surface to the user unless asked

**Passing model and effort to spawns:**

For a normal (non-nuclear) spawn, pass the resolved model and include effort only when capability allows:

```
agent_type: "general-purpose"
model: "{resolved_model}"
# Include only when supported and not auto/unset:
reasoning_effort: "{resolved_reasoning_effort}"
mode: "background"
name: "{name}"
description: "{emoji} {Name}: {brief task summary}"
prompt: |
  ...
```

Only omit `model` when the resolved choice is the runtime's actual platform default. Always pass a persistent configured value such as `gpt-5.6-sol` explicitly; never substitute a hardcoded alternative.

Pass `reasoning_effort` only when the resolved value is not `auto` or unset and the current model advertises support. A configured `max` value stays `max`; if the current fallback cannot accept it, omit the parameter for that attempt instead of coercing the value.

If you've exhausted the fallback chain and reached nuclear fallback, omit both `model` and `reasoning_effort`.

**Spawn output format — show the model choice:**

When spawning, include the model in your acknowledgment:

```
🔄 Neo (gpt-5.6-sol · max) — revising integration automation
🎨 Redfoot (claude-opus-4.5 · vision) — designing color system
📋 Scribe (claude-haiku-4.5 · fast) — logging session
⚡ Keaton (claude-opus-4.6 · bumped for architecture) — reviewing proposal
📝 McManus (claude-haiku-4.5 · fast) — updating docs
```

Include tier annotation only when the model was bumped or a specialist was chosen. Default-tier spawns just show the model name.

**Valid models (current platform catalog):**

Repository-approved default: `gpt-5.6-sol` (supports reasoning effort `max`)
Premium: `claude-opus-4.6`, `claude-opus-4.6-1m` (Internal only), `claude-opus-4.5`
Standard: `claude-sonnet-4.6`, `claude-sonnet-4.5` (no reasoning effort), `claude-sonnet-4`, `gpt-5.4`, `gpt-5.3-codex`, `gpt-5.2-codex`, `gpt-5.2`, `gpt-5.1-codex-max`, `gpt-5.1-codex`, `gpt-5.1`, `gemini-3-pro-preview`
Fast/Cheap: `claude-haiku-4.5` (no reasoning effort), `gpt-5.4-mini`, `gpt-5.1-codex-mini`, `gpt-5-mini`, `gpt-4.1`
