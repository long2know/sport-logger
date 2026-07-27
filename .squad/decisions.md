# Squad Decisions

## Active Decisions

### 2026-07-25: Use Health Services API for capability-aware measurement capture (ADR-001)
**By:** Tank
**What:** Use `ExerciseClient.getCapabilitiesAsync()` for runtime sensor discovery instead of direct `SensorManager` access. Supplement with Samsung Health Sensor SDK behind the same repository interface only where Health Services is insufficient.
**Why:** Health Services is Google's recommended abstraction for Wear OS fitness apps. It handles battery management, sensor fusion, and provides consistent capability reporting across device OEMs. Direct sensor access requires manual lifecycle management and can't leverage platform-level optimizations.

### 2026-07-25: Protocol Buffers for watch-phone sync (ADR-002)
**By:** Tank
**What:** Replace Java `Serializable` with Protocol Buffers (proto3) for all DataLayer transfers between watch and phone.
**Why:** Proto is compact (critical for BLE-limited bandwidth), forward-compatible (unknown fields preserved across app versions), and generates type-safe Kotlin code. The current Java serialization breaks silently when model classes change.

### 2026-07-25: Room over raw SQLite for all persistence (ADR-003)
**By:** Tank
**What:** Replace raw `SQLiteDatabase` with Room for all local data access. Migrate existing data on first launch.
**Why:** The current `SqlLogger` uses string-concatenated SQL (injection risk), lacks compile-time verification, and has no migration support. Room provides Flow integration, compile-time SQL checks, and automatic migration scaffolding.

### 2026-07-26T04:10:44.720Z: Default team model and reasoning
**By:** Stephen Long via Squad Coordinator
**What:** All sport-logger Squad members use GPT-5.6 Sol (`gpt-5.6-sol`) with max reasoning for all future spawns.
**Why:** Stephen Long established this as the repository-wide default for future Squad spawns.

## Governance

- All meaningful changes require team consensus
- Document architectural decisions here
- Keep history focused on work, decisions focused on direction
