# Arquitectura Completa — MappingAndroid

> Aplicación Android de video-mapping en tiempo real con soporte multi-dispositivo (Servidor/Cliente), renderizado OpenGL ES 2.0 y sincronización de estado por WebSocket.

---

## Estructura de Módulos Gradle

```mermaid
graph TD
    subgraph Gradle["📦 Proyecto Gradle"]
        APP["**:app**\nAplicación Android principal\n(UI · Renderer · Media · Network)"]
        CORE["**:mapping-core**\nLibrería pura Kotlin\n(Modelos · NetworkManager · Sin deps Android)"]
    end
    APP -->|depends on| CORE
```

---

## Arquitectura General por Capas

```mermaid
flowchart TB
    subgraph UI["🎨 Capa UI — Jetpack Compose"]
        MS["MappingScreen\n+ MappingViewModel"]
        DS["DashboardScreen"]
        FP["FilePicker\n(Local + Remote)"]
        ES["ExerciseSelector"]
    end

    subgraph VM["🧠 Estado Orquestador"]
        MVM["MappingViewModel\n(StateFlow · Coroutines · Hilt)"]
    end

    subgraph GFX["🖥️ Motor Gráfico — OpenGL ES 2.0"]
        MR["MappingRenderer\n(GLSurfaceView.Renderer)"]
        FBO["FBOManager\n(Offscreen Buffers)"]
        SH["Shader Programs\n(25+ shaders GLSL)"]
    end

    subgraph MEDIA["🎬 Media"]
        EXO["ExoPlayer\n(Video Playback)"]
        MJPEG["MjpegStreamController\n(IP Camera streaming)"]
        VC["VideoController\n(Lifecycle wrapper)"]
        CAM["CameraStreamManager\n(Local Camera → MJPEG)"]
    end

    subgraph NET["🌐 Red"]
        NM["MappingNetworkManager\n(mapping-core)"]
        WS_SRV["WebSocket Server\n:8080"]
        WS_CLI["WebSocket Client\n→ :8080"]
        HTTP["HTTP Server (NanoHTTPD)\n:8081"]
        UDP["MappingDiscoveryService\nUDP Broadcast :8888"]
    end

    subgraph DOMAIN["📐 Dominio — mapping-core"]
        STATE["MappingState"]
        CMD["MappingCommand (sealed)"]
        SURF["MappingSurface"]
        SLOT["EffectSlot"]
        DECK["MappingDeck"]
        CLIP["MappingClip"]
    end

    subgraph PERSIST["💾 Persistencia"]
        PREFS["SharedPreferences\ncurrent_full_state_json"]
        CRASH["Crash Recovery\nlast_crash.txt"]
    end

    subgraph APP_ENTRY["🚀 Entrada de la App"]
        MAPP["MappingApplication\n(@HiltAndroidApp · Crash Handler)"]
        MAIN["MainActivity\n(@AndroidEntryPoint)"]
    end

    UI --> VM
    VM --> GFX
    VM --> MEDIA
    VM --> NET
    VM --> PERSIST
    GFX --> DOMAIN
    NET --> DOMAIN
    APP_ENTRY --> VM
```

---

## Modelos de Dominio (mapping-core)

```mermaid
classDiagram
    class MappingState {
        +outputMode: String
        +surfaces: List~MappingSurface~
        +decks: List~MappingDeck~
        +activeDeckIndex: Int
        +screenWidth: Float
        +screenHeight: Float
        +isFullScreen: Boolean
        +targetFPS: Int
        +globalBPM: Float
        +toJSON(): String
        +fromJSON(String): MappingState?
    }

    class MappingSurface {
        +id: String
        +name: String
        +sourceType: SourceType
        +videoPath: String?
        +imagePath: String?
        +shaderId: String?
        +shaderText: String?
        +shaderParameters: Map~String, Float~
        +corners: FloatArray [8]
        +texCoords: FloatArray [8]
        +holes: List~FloatArray~
        +backgroundsSlot: EffectSlot?
        +visualsSlot: EffectSlot?
        +fxSlot: EffectSlot?
        +opacity: Float
        +isVisible: Boolean
        +isBlack: Boolean
        +isNegative: Boolean
        +rotation: Float
        +flipHorizontal: Boolean
        +flipVertical: Boolean
        +isPlaying: Boolean
        +playbackSpeed: Float
        +mediaParams: Map~String, String~
    }

    class EffectSlot {
        +sourceType: SourceType
        +content: String
        +opacity: Float
        +shaderText: String?
        +shaderParameters: Map~String, Float~
    }

    class MappingDeck {
        +id: String
        +name: String
        +layerClips: Map~String, List~MappingClip?~~
    }

    class MappingClip {
        +id: String
        +name: String
        +sourceType: SourceType
        +path: String?
        +shaderText: String?
        +shaderParameters: Map~String, Float~
        +thumbnailPath: String?
        +mediaParams: Map~String, String~
    }

    class SourceType {
        <<enumeration>>
        VIDEO
        SHADER
        IMAGE
        MJPEG_CAMERA
    }

    class MappingCommand {
        <<sealed>>
        +toJSONObject(): JSONObject
        +invert(state): MappingCommand?
    }

    MappingState "1" --> "0..*" MappingSurface
    MappingState "1" --> "0..*" MappingDeck
    MappingSurface --> EffectSlot : backgroundsSlot
    MappingSurface --> EffectSlot : visualsSlot
    MappingSurface --> EffectSlot : fxSlot
    MappingSurface --> SourceType
    MappingDeck "1" --> "0..*" MappingClip
    MappingCommand ..> MappingState : operates on
```

---

## Comandos del Dominio (MappingCommand)

```mermaid
mindmap
  root((MappingCommand))
    Geometría
      UpdateVertex
      UpdateAllCorners
      ScaleSurface
      RotateSurface
      FlipSurface
      MoveLayer
    Superficies
      AddSurface
      RemoveSurface
      RestoreSurface
      SetLayerName
      ToggleVisibility
      ToggleBlackMode
      ToggleNegativeMode
    Contenido
      SetSourceType
      SetVideoPath
      SetImagePath
      SetShaderId
      SetShaderText
      UpdateShaderParameter
      UpdateMediaParam
      TriggerClip
      UpdateClipInSlot
    Reproducción
      SetLayerPlayState
      SetPlaybackSpeed
      SetPlayState
    Global
      SetOutputMode
      ToggleFullScreen
      ClearAll
      SetActiveDeck
      SetTargetFPS
      SetGlobalBPM
    Red
      ClientHello
      ServerHello
      SetMediaSource
```

---

## Capa de Red

```mermaid
flowchart LR
    subgraph SERVER["📡 Modo Servidor (Proyector)"]
        WSS["WebSocket Server\n:8080\norg.java-websocket"]
        HTTPS["HTTP Server\n:8081 NanoHTTPD\nGET /list /info /version\nGET /video.mp4\nPOST /upload\nPOST /update"]
        UDPS["UDP Discovery Listener\n:8888\n'MAPPING_SERVER_DISCOVERY'"]
    end

    subgraph CLIENT["📱 Modo Cliente (Celular)"]
        WSC["WebSocket Client\n→ ws://ip:8080"]
        HTTPC["HTTP Server\n:8081 (mismo)\nSirve archivos locales"]
        UDPC["UDP Discovery Broadcaster\nBroadcast → :8888\n3 intentos × 1s"]
    end

    subgraph MSG["📨 Mensajes"]
        CMD2["MappingCommand JSON\n(type + params)"]
        FULL_STATE["MappingState JSON\n{type: FULL_STATE, ...}"]
    end

    WSS <-->|Commands + State| WSC
    UDPS -->|"MAPPING_SERVER_HERE"| UDPC
    HTTPC -->|"GET /video.mp4"| HTTPS

    CMD2 --> WSS
    FULL_STATE --> WSS
    CMD2 --> WSC
```

---

## Motor OpenGL ES 2.0 (MappingRenderer)

```mermaid
flowchart TB
    subgraph GL["🖥️ GL Thread — MappingRenderer"]
        direction TB
        OSD["onSurfaceDestroyed\nClear textures"]
        OSC["onSurfaceCreated\nInit GL Programs"]
        OSZ["onSurfaceChanged\nViewport · FBOManager init"]
        ODF["onDrawFrame"]

        subgraph PROGRAMS["GL Programs"]
            P1["Main Video Program\nmapping_vertex/fragment_shader\n(SurfaceTexture OES)"]
            P2["Image Program\nimage_fragment_shader\n+ FXType + FXIntensity"]
            P3["Mask Program\nsimple_vertex/fragment_shader\n(Stencil · Holes · Negatives)"]
            P4["Overlay Program\n(EDIT mode: wire outlines)"]
            P5["25+ Procedural Shaders\n(Lazy loaded, 1/sec max)"]
        end

        subgraph FRAME["onDrawFrame Pipeline"]
            F1["1. Process pending SurfaceTextures"]
            F2["2. Update Time + Beat Phase BPM"]
            F3["3. Clear Screen"]
            F4["4. Process deferred shader queue"]
            F5["5. drawSurfaceMultiLayer()"]
            F6["6. drawOverlays() si EDIT mode"]
            F7["7. FPS Throttle (targetFPS)"]
        end

        subgraph MULTILAYER["Multi-Layer Composite Pipeline (FBO)"]
            ML1["FBO 0: Composition Buffer\nbackgroundsSlot → visualsSlot → fxSlot"]
            ML2["FBO 1/2: Scratch Buffers"]
            ML3["FBO 3: UV Local Mask\n(Negative / Hole surfaces)"]
            ML4["Warp Pass: Apply corners\nHomography → screen space"]
        end
    end

    OSC --> PROGRAMS
    ODF --> FRAME
    F5 --> MULTILAYER

    subgraph SHADERS25["🎨 Shader Presets (25+)"]
        SH1["MagicRoots · FireEnergy · LeafStorm"]
        SH2["PlasmaWaves · VoronoiCells · FractalZoom"]
        SH3["LiquidMetal · NeonGrid · StarField"]
        SH4["Kaleidoscope · WaterRipples · AuroraFlow"]
        SH5["GeometricPulse · WatcherEyes · MysticLiquid"]
        SH6["neon_bounce · Arcoiris · BPM_Debug · ..."]
    end

    P5 --- SHADERS25
```

---

## FBOManager — Renderizado Multi-capa

```mermaid
flowchart LR
    subgraph FBO["FBOManager"]
        F0["FBO 0\nComposition Buffer\n(Flat layers)"]
        F1["FBO 1\nScratch / Warp"]
        F2["FBO 2\nScratch B"]
        F3["FBO 3\nUV Mask\n(Negative surfaces)"]
    end

    IN["backgroundsSlot\nvisualsSlot\nfxSlot"] --> F0
    F0 --> F1
    F3 -->|"u_Mask uniform\n(Shape-Aware Shaders)"| F1
    F1 -->|"Corner Warp\n(4-point homography)"| OUT["Screen Output"]
```

---

## MappingViewModel — Orquestador Central

```mermaid
flowchart TB
    subgraph MVM["MappingViewModel (StateFlow + Hilt)"]
        subgraph STATE_FLOWS["StateFlows"]
            SF1["mappingState: MappingState"]
            SF2["networkMode: SERVER/CLIENT/DISCONNECTED"]
            SF3["connectedClients: List"]
            SF4["remoteFiles: List"]
            SF5["uploadProgress: Float"]
            SF6["isFullScreen: Boolean"]
        end

        subgraph ROLES["Modos de Operación"]
            R1["SERVER\nAcepta conexiones\nBroadcast state a clients"]
            R2["CLIENT\nEnvía commands al server\nRecibe state del server"]
            R3["STANDALONE\nLocal only"]
        end

        subgraph HISTORY["Undo/Redo Stack"]
            H1["undoStack: ArrayDeque~MappingCommand~"]
            H2["redoStack: ArrayDeque~MappingCommand~"]
            H3["MAX_UNDO = 50"]
        end

        subgraph RECOVERY["Crash Recovery"]
            CR1["Persiste estado cada operación\nSharedPreferences"]
            CR2["Estado estable cada 30s\ncurrent_full_state_stable"]
            CR3["Lee last_crash.txt\nen Application.onCreate"]
        end

        subgraph EXOPLAYER["ExoPlayer Management"]
            EX1["playerPool: Map~String, ExoPlayer~"]
            EX2["getSurfaceForId() → GL Surface"]
            EX3["VideoController lifecycle"]
            EX4["onVideoNotVisible callback\n(Renderer → ViewModel)"]
        end
    end

    MVM -->|"dispatch(command)"| CMD_FLOW["Command Flow\n1. apply local state\n2. update renderer\n3. send via network (if not standalone)"]
    MVM -->|"updateRenderer(state)"| MR2["MappingRenderer.updateState()"]
    MVM <-->|"NetworkCallback"| NM2["MappingNetworkManager"]
```

---

## Pantallas UI — Jetpack Compose

```mermaid
flowchart TD
    subgraph UI_SCREENS["📱 Pantallas"]
        subgraph HOME["HomeScreen"]
            H1["Selector de modo\n(Standalone · Server · Client)"]
            H2["HomeUiState / HomeEvent"]
        end

        subgraph MAPPING_SCR["MappingScreen (principal)"]
            M1["GLSurfaceView\n(MappingRenderer)"]
            M2["Layer Panel\n(Lista de MappingSurface)"]
            M3["Shape Selector\n(Quad · Triangle · Custom)"]
            M4["Shader Picker\nfrom ShaderPreset registry"]
            M5["Remote Config Dialog\n(WS + UDP auto-discovery\nManual IP tab)"]
            M6["Remote File Browser\n(File list, Thumbnails)"]
            M7["Deck / Clip Grid\n(VJ Pad interface)"]
            M8["Edit / Show mode toggle"]
        end

        subgraph DASHBOARD_SCR["DashboardScreen"]
            D1["Camera FX Panel\n(mediaParams: brightness,\ncontrast, saturation, hue)"]
            D2["BPM Control"]
            D3["FPS Target Slider"]
            D4["Global controls\n(ClearAll · FullScreen)"]
        end

        subgraph COMPONENTS["Componentes Compartidos"]
            C1["FilePicker\n(Local files)\n(Remote HTTP browse)"]
            C2["ExerciseSelector"]
        end
    end

    HOME --> MAPPING_SCR
    MAPPING_SCR --> DASHBOARD_SCR
    MAPPING_SCR --> COMPONENTS
```

---

## Flujo de Datos Completo

```mermaid
sequenceDiagram
    participant User as 👆 Usuario
    participant UI as MappingScreen
    participant VM as MappingViewModel
    participant Net as MappingNetworkManager
    participant Rend as MappingRenderer
    participant EXO as ExoPlayer

    User->>UI: Drag corner / tap button
    UI->>VM: dispatch(MappingCommand)
    VM->>VM: apply(state + command) → newState
    VM->>VM: push to undoStack
    VM->>VM: persist(SharedPreferences)
    VM->>Rend: updateState(newState)
    VM->>Net: sendCommand(command)  [if networked]

    Note over Net: SERVER→broadcast / CLIENT→send
    Net-->>VM: onCommandReceived(cmd) [remote peer]
    VM->>VM: apply remote command
    VM->>Rend: updateState(newState)

    Rend->>EXO: getSurfaceForId(id)
    EXO-->>Rend: Surface (SurfaceTexture)
    Rend->>Rend: onDrawFrame()
    Rend->>Rend: onFrameAvailable → requestRender()
```

---

## Persistencia y Recuperación de Crashes

```mermaid
flowchart LR
    subgraph NORMAL["Flujo Normal"]
        OP["dispatch(command)"] -->|"after each op"| SP1["SharedPreferences\ncurrent_full_state_json"]
        STABLE["Timer 30s"] --> SP2["SharedPreferences\ncurrent_full_state_stable"]
    end

    subgraph CRASH["En caso de Crash"]
        HANDLER["UncaughtExceptionHandler\n(MappingApplication)"] --> CFILE["filesDir/last_crash.txt"]
        HANDLER --> PUBLOG["externalFiles/mapping_last_crash.log"]
    end

    subgraph RECOVERY_FLOW["Recuperación al inicio"]
        START["Application.onCreate()"] -->|"last_crash.txt existe?"| ROLLBACK["Rollback:\nstable → current_full_state_json"]
        ROLLBACK --> VM_LOAD["ViewModel carga estado\n& muestra notificación UI"]
    end
```

---

## Resumen de Tecnologías

| Capa | Tecnología |
|---|---|
| UI | Jetpack Compose + Material 3 |
| State Management | ViewModel + StateFlow + Hilt DI |
| Gráficos | OpenGL ES 2.0 · GLSurfaceView · FBO |
| Video | ExoPlayer (Media3) |
| Cámara IP | MJPEG over HTTP |
| WebSocket | org.java-websocket |
| HTTP Server | NanoHTTPD |
| Discovery | UDP Broadcast (:8888) |
| Persistencia | SharedPreferences |
| Serialización | JSON (org.json) |
| Concurrencia | Coroutines · GL Thread · Main Thread |
| DI | Hilt |
