# Shader System

## GLSL Shader Files
Located in `app/src/main/res/raw/`. All are `.glsl` files.

### Core Pipeline Shaders
| File | Purpose |
|---|---|
| `mapping_vertex_shader.glsl` | Shared vertex shader for all programs |
| `mapping_fragment_shader.glsl` | Video (OES texture) fragment shader |
| `image_fragment_shader.glsl` | Image fragment with FX uniforms (u_FXType, u_FXIntensity) |
| `simple_vertex_shader.glsl` | Minimal vertex shader for mask/stencil |
| `simple_fragment_shader.glsl` | Solid color fragment for mask/stencil |

### Procedural Shaders (19 registered)
All use `mapping_vertex_shader` as vertex program. Loaded lazily.

| Registry Name | File | Uniform Parameters |
|---|---|---|
| FireEnergy | `shader_fire_energy` | u_intensity, u_flicker, u_flow, u_scale |
| GraffitiMask | `shader_graffiti_mask` | u_Scale, u_Intensity, u_Speed |
| BPM_Debug | `shader_bpm_debug` | u_bpm, u_BeatPhase |
| CosmicPollen | `shader_particle_mist` | u_density, u_scale, u_flow |
| FriendshipAura | `shader_dissolve_ritual` | u_progress, u_edgeSoftness, u_scale |
| AncientPine | `shader_ancient_pine` | u_intensity, u_scale |
| WatcherEyes | `shader_watcher_eyes` | u_speed, u_intensity |
| shader_neon_text | `shader_neon_text` | u_Intensity, u_ColorR/G/B, u_Scale |
| Arcoiris | `shader_arcoiris` | u_nl1, u_nl2, u_nl3 |
| AsciiTunnel | `shader_ascii_tunnel` | u_Speed, u_ColorR/G/B, u_Scale |
| SacredGeometry | `shader_sacred_geometry` | u_Scale, u_intensity, u_Speed, u_bpm, u_BeatPhase |
| FlowerOfLife | `shader_flower_of_life` | u_Scale, u_intensity, u_Speed, u_bpm, u_BeatPhase |
| Kaleidoscopio | `shader_kaleidoscopio` | u_Scale, u_intensity, u_Speed, u_bpm, u_BeatPhase |
| ElectricField | `shader_electric_field` | u_Scale, u_intensity, u_Speed, u_bpm, u_BeatPhase |
| DiscoBall | `shader_disco_ball` | u_Scale, u_intensity, u_Speed, u_bpm, u_BeatPhase |
| PurpleFlower | `shader_purple_flower` | u_Scale, u_intensity, u_Speed, u_bpm, u_BeatPhase |
| MoonHalo | `shader_moon_halo` | u_Scale, u_intensity, u_Speed, u_bpm, u_BeatPhase |
| FlagStone | `shader_flag_stone` | u_Scale, u_intensity, u_Speed, u_bpm, u_BeatPhase |
| shader_nanoleaf | `shader_nanoleaf_v2` | u_pattern, u_panelCount, u_gap, u_rotation |

### Additional (not in registry but present as files)
- `shader_organic_noise.glsl`
- `shader_aura_field.glsl`
- `shader_color_wash.glsl`

## Common Uniforms (injected by Renderer)
| Uniform | Type | Source |
|---|---|---|
| `u_Time` | float | `(elapsedRealtime - startTime) / 1000f` |
| `u_Resolution` | vec2 | `(screenWidth, screenHeight)` |
| `u_bpm` | float | `MappingState.globalBPM` |
| `u_BeatPhase` | float | `fract(time * bpm / 60)` — 0.0 to 1.0 sawtooth |
| `u_Mask` | sampler2D | FBO 3 texture (for aware shaders) |
| `u_NanoleafColors` | vec3[16] | From `NanoleafManager.colorBuffer` |

## Aware Shaders
Shaders in `awareShaderIds` set receive the `u_Mask` uniform from FBO 3:
- `neon_bounce`, `shape_noise`, `aware_particles`

## Adding a New Shader
1. Create `shader_name.glsl` in `app/src/main/res/raw/`
2. Add to `shaderResourceMap` in `MappingRenderer.deferredShaderInit()`
3. Add to `shaderRegistry` in `MappingViewModel` with uniform parameter names
4. If it needs mask awareness, add to `awareShaderIds`
5. Shader will be lazy-compiled on first use
