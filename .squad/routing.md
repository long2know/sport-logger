# Work Routing

How to decide who handles what.

## Routing Table

| Work Type | Route To | Examples |
|-----------|----------|----------|
| Architecture and modernization | Tank | Module boundaries, Kotlin/Gradle migration, dependency strategy, ADRs, code review |
| Wear OS exercise tracking | Trinity | Health Services, sensor capability discovery, exercise lifecycle, permissions, battery use |
| Phone and watch UI/UX | Switch | Compose UI, navigation, activity dashboards, design system, accessibility |
| Sync and external integrations | Neo | Wear Data Layer, offline sync, Health Connect, Endurain, connector contracts |
| Open-source and platform research | Mouse | Repository survey, license checks, maintenance signals, API/toolchain comparisons |
| Testing and release quality | Mouse | Unit/integration/device tests, compatibility matrix, acceptance criteria, regression review |
| Cross-cutting technical design | Tank | Trade-offs, sequencing, shared models, migration safety |
| Factual or source verification | Fact Checker | Validate APIs, versions, repositories, licenses, URLs, and architectural claims |
| Health-data privacy and safety | Rai | Permissions, minimization, credentials, PII exposure, responsible-data review |
| Scope and priorities | Tank | Roadmap, milestone decomposition, dependency ordering |
| Work queue monitoring | Ralph | Backlog scanning, blockers, follow-up work, idle watch |
| Session logging | Scribe | Automatic; never needs manual routing |

## Collaboration Paths

| Concern | Primary | Required Partner |
|---------|---------|------------------|
| Watch measurements and persistence schema | Trinity | Tank |
| Watch-to-phone activity transfer | Neo | Trinity |
| Health Connect activity mapping | Neo | Mouse |
| Activity detail and dashboard UX | Switch | Trinity |
| Connector extension points | Neo | Tank |
| Toolchain and API version claims | Tank | Fact Checker |
| Open-source reference adoption | Mouse | Fact Checker |

## Issue Routing

| Label | Action | Who |
|-------|--------|-----|
| `squad` | Triage and assign the correct member label | Tank |
| `squad:tank` | Architecture, modernization, or cross-cutting work | Tank |
| `squad:trinity` | Wear OS tracking and measurement work | Trinity |
| `squad:switch` | Android/Wear UI and product experience | Switch |
| `squad:neo` | Sync, Health Connect, Endurain, and connectors | Neo |
| `squad:mouse` | Research, tests, compatibility, and quality | Mouse |

### How Issue Assignment Works

1. When a GitHub issue gets the `squad` label, Tank triages it and assigns the best `squad:{member}` label.
2. When a `squad:{member}` label is applied, that member owns the issue.
3. Cross-domain issues get one primary owner and named collaborators.
4. Members can recommend reassignment; the Coordinator enforces reviewer lockouts.

## Rules

1. **Eager by default** — start independent architecture, research, UX, integration, and test work in parallel.
2. **Capability claims are verified** — device measurements, Health Connect support, external APIs, versions, and open-source licenses require evidence.
3. **Device support is discovered, not assumed** — model unavailable or unsupported measurements explicitly.
4. **Offline-first sync is treated as a system** — retries, idempotency, ordering, deletion, and conflict behavior must be designed and tested.
5. **Inspired, not copied** — learn from Samsung Health and Google Fit interaction patterns without reproducing protected visual assets.
6. **Scribe always runs** after substantial work in background mode and never blocks.
7. **Quick facts go directly through the Coordinator**; domain judgment goes to the responsible member.
