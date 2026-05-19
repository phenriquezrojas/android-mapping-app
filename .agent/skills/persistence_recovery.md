# Persistence & Crash Recovery

## SharedPreferences
- **Prefs**: `mapping_prefs`
- `current_full_state_json` — saved after every dispatch
- `current_full_state_stable` — saved every 30s
- `execution_mode` — SERVER/CLIENT/STANDALONE

## Crash Flow
1. **On crash**: `MappingApplication` writes `last_crash.txt` + public log
2. **On restart**: `Application.onCreate()` rolls back `current_full_state_json` → stable copy
3. **On ViewModel init**: Shows crash notification, deletes `last_crash.txt`

## Forensic Logging
- `logBreadcrumb(step)` writes to `forensic_breadcrumbs.txt` (internal) and Download folder (public with RAM metrics)
- Cleared on each app start
- Viewable in-app via `viewStartupTrail()`, exportable via `exportLogsToDownload()`
