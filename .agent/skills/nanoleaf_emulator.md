# Nanoleaf Emulator

**File**: `app/.../nanoleaf/NanoleafManager.kt` (217 lines)
Emulates a Nanoleaf device so Numark/Engine OS DJ equipment can send color data via UDP.

## Architecture
```
Numark DJ Controller
  ├─ mDNS Discovery → _nanoleafapi._tcp → finds this device
  ├─ HTTP API → /api/v1/* → pairing + effects + layout
  └─ UDP :60222 → panel color data (16 panels × RGB)
       ↓
NanoleafManager
  ├─ colorBuffer[48] → FloatArray (16 × RGB)
  └─ passed to shader_nanoleaf_v2 as uniform
       ↓
MappingRenderer → renders colors on projected surface
```

## mDNS Registration
- Service type: `_nanoleafapi._tcp`
- Port: 8081 (shares with HTTP server)
- Attributes: `id=LAB-SERIAL-0001`, `nm=NanoLeafMapping-{model}`, `md=NL42`

## UDP Listener (Port 60222)
- Parses Numark color packets: 2-byte panel count header + 8 bytes per panel
- Panel data: `panelId (2B) | R (1B) | G (1B) | B (1B) | ... (3B padding)`
- Maps `panelId - 1` to `colorBuffer[index * 3 + 0..2]`
- Updates `isConnected` StateFlow on first packet

## HTTP API Emulation
Handles requests routed from `MappingNetworkManager.onHttpRequest()`:

| Endpoint | Method | Response |
|---|---|---|
| `/api/v1/.../new` or `/auth` | POST | `{"auth_token": "lab-token-nanoleaf-001"}` |
| `/api/v1/.../effects` | PUT/GET | `{"select": "*extControl*"}` |
| `/api/v1/.../panelLayout/layout` | GET | 16-panel grid layout (4×4, 86px spacing) |
| `/api/v1/{token}/` | GET | Full device state (name, model, effects, panelLayout) |

## Panel Layout
- 16 panels arranged in 4×4 grid
- Panel IDs: 1–16
- Side length: 150
- Position: `(col * 86, row * 86)`, orientation: 0

## Integration with ViewModel
- `MappingViewModel` auto-starts NanoleafManager when any surface uses `shader_nanoleaf`
- Auto-injects `u_panelCount = 16` parameter
- Syncs `colorBuffer` to renderer on every frame via `renderer.nanoleafColors`
- Shuts down when no nanoleaf shader is active or device is CLIENT mode

## UI
- `NanoleafConfigDialog` — configuration dialog
- `NanoleafEditorScreen` — dedicated editor screen
- `isNanoleafConnected` exposed in `MappingUiState`
