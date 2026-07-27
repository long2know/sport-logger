# Squad Team

> Modernizing sport-logger into a modular Wear OS sports tracker and Android companion app.

## Coordinator

| Name | Role | Notes |
|------|------|-------|
| Squad | Coordinator | Routes work, enforces handoffs and reviewer gates. |

## Members

| Name | Role | Charter | Status |
|------|------|---------|--------|
| Tank | Mobile Architect / Lead | `.squad/agents/tank/charter.md` | 🏗️ Active |
| Trinity | Wear OS Engineer | `.squad/agents/trinity/charter.md` | ⌚ Active |
| Switch | Android & Wear UX Engineer | `.squad/agents/switch/charter.md` | ⚛️ Active |
| Neo | Sync & Integrations Engineer | `.squad/agents/neo/charter.md` | 🔄 Active |
| Mouse | Research & Quality Engineer | `.squad/agents/mouse/charter.md` | 🧪 Active |
| Scribe | Session Logger | `.squad/agents/scribe/charter.md` | 📋 Always on |
| Ralph | Work Monitor | `.squad/agents/ralph/charter.md` | 🔄 Always on |
| Rai | RAI Reviewer | `.squad/agents/Rai/charter.md` | 🛡️ Always on |
| Fact Checker | Fact Checker | `.squad/agents/fact-checker/charter.md` | 🔍 Always on |

## Coding Agent

<!-- copilot-auto-assign: false -->

| Name | Role | Charter | Status |
|------|------|---------|--------|
| @copilot | Coding Agent | — | 🤖 Available; auto-assign disabled |

### Capabilities

**🟢 Good fit — auto-route when enabled:**
- Bug fixes with clear reproduction steps
- Test coverage (adding missing tests, fixing flaky tests)
- Lint/format fixes and code style cleanup
- Dependency updates and version bumps
- Small isolated features with clear specs
- Boilerplate/scaffolding generation
- Documentation fixes and README updates

**🟡 Needs review — route to @copilot but flag for squad member PR review:**
- Medium features with clear specs and acceptance criteria
- Refactoring with existing test coverage
- API endpoint additions following established patterns
- Migration scripts with well-defined schemas

**🔴 Not suitable — route to a squad member instead:**
- Architecture decisions and system design
- Multi-system integration requiring coordination
- Ambiguous requirements needing clarification
- Security-critical changes (auth, encryption, access control)
- Performance-critical paths requiring benchmarking
- Changes requiring cross-team discussion

## Project Context

- **Project:** sport-logger
- **Repository:** long2know/sport-logger
- **Requested by:** Stephen Long
- **Created:** 2026-07-25T20:51:18.431-07:00
- **Current stack:** Java, Groovy Gradle, three Android modules (`mobile`, `wear`, `utilities`), Android API 28 support libraries, AGP 3.3.1
- **Current product:** Wear OS tracking for location, heart rate, and steps; Android companion sync; TCX-oriented activity models
- **Target product:** A modern, modular sports platform inspired by Garmin Connect, Strava, Samsung Health, and Google Fit
- **Primary goals:** Current Android/Wear OS tooling, device-capability-aware measurement capture, polished phone/watch UI, reliable watch-to-phone sync, Health Connect integration, Endurain export, and pluggable connectors
- **Research requirement:** Evaluate current open-source Wear OS trackers with Android companion apps and verify licenses, maintenance status, architecture, and reusable patterns
