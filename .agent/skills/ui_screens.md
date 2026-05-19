# UI Screens

## MappingScreen (Main)
**File**: `app/.../ui/screens/mapping/MappingScreen.kt`
Primary screen with:
- GLSurfaceView viewport (behind Compose)
- Layer panel (list of MappingSurface)
- Shape selector (Quad, Triangle, Custom)
- Shader picker (from ShaderPreset registry)
- Remote config dialog (WS + UDP auto-discovery, manual IP tab)
- Remote file browser (thumbnails, directory navigation)
- Deck/Clip grid (VJ pad interface)
- Edit/Show mode toggle

## DashboardScreen
**File**: `app/.../ui/screens/dashboard/DashboardScreen.kt`
- Camera FX panel (brightness, contrast, saturation, hue via mediaParams)
- BPM control slider
- FPS target slider
- Global controls (ClearAll, FullScreen)
- Nanoleaf connection status

## NanoleafEditorScreen
**File**: `app/.../ui/screens/nanoleaf/NanoleafEditorScreen.kt`
Dedicated Nanoleaf panel configuration editor.

## NanoleafConfigDialog
**File**: `app/.../ui/screens/dashboard/NanoleafConfigDialog.kt`
Nanoleaf connection setup dialog.

## Shared Components
- **FilePicker** (`ui/components/FilePicker.kt`) — Local + remote HTTP file browsing
- **ExerciseSelector** (`ui/components/ExerciseSelector.kt`) — Legacy exercise selector

## Theme
- `Color.kt`, `Theme.kt`, `Type.kt` in `ui/theme/`
- Theme wrapper: `LazyRepsTheme`
- Material 3 based

## State Management Pattern
All screens observe `MappingViewModel.uiState: StateFlow<MappingUiState>`.
User actions call ViewModel functions which dispatch commands.
