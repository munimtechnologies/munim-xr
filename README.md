<!-- Munim Technologies banner -->

<p align="center">
  <a href="https://github.com/munimtechnologies/munim-xr">
    <img alt="Munim Technologies" height="128" src="./.github/resources/banner.png">
    <h1 align="center">munim-xr</h1>
  </a>
</p>

<p align="center">
  Native ARKit and ARCore sessions for Expo and React Native, powered by Nitro Modules.
</p>

<p align="center">
  <a href="https://www.npmjs.com/package/munim-xr"><img alt="npm version" src="https://img.shields.io/npm/v/munim-xr.svg?style=flat-square&label=Version&labelColor=000000&color=0066CC" /></a>
  <a href="https://github.com/munimtechnologies/munim-xr/blob/main/LICENSE"><img alt="Apache-2.0 license" src="https://img.shields.io/badge/License-Apache%202.0-success.svg?style=flat-square&color=33CC12" /></a>
  <a href="https://www.npmtrends.com/munim-xr"><img alt="Monthly downloads" src="https://img.shields.io/npm/dm/munim-xr.svg?style=flat-square&labelColor=gray&color=33CC12&label=Downloads" /></a>
  <a href="https://www.npmjs.com/package/munim-xr"><img alt="Total downloads" src="https://img.shields.io/npm/dt/munim-xr.svg?style=flat-square&labelColor=gray&color=0066CC&label=Total%20Downloads" /></a>
</p>

<p align="center">
  <a href="https://docs.expo.dev/develop/development-builds/introduction/"><b>Works with Expo development builds</b></a>
  &ensp;•&ensp;
  <a href="https://www.munimtech.com/opensource/munim-xr">Documentation</a>
  &ensp;•&ensp;
  <a href="https://github.com/munimtechnologies/munim-xr/issues">Issues</a>
</p>

<p align="center">
  <img alt="React Native" src="https://img.shields.io/badge/React%20Native-20232A?style=flat-square&logo=react&logoColor=61DAFB" />
  <img alt="Expo" src="https://img.shields.io/badge/Expo-000020?style=flat-square&logo=expo&logoColor=white" />
  <img alt="ARKit" src="https://img.shields.io/badge/ARKit-iOS-111111?style=flat-square&logo=apple&logoColor=white" />
  <img alt="ARCore" src="https://img.shields.io/badge/ARCore-Android-3DDC84?style=flat-square&logo=android&logoColor=white" />
  <img alt="Nitro Modules" src="https://img.shields.io/badge/Nitro%20Modules-New%20Architecture-7B61FF?style=flat-square" />
</p>

## Why munim-xr

`munim-xr` gives one typed React Native API to world-tracked augmented reality on iOS and Android. Its Nitro Hybrid View renders the native camera feed and exposes session lifecycle, camera pose, view/projection matrices and intrinsics, the CPU camera image, plane detection, hit testing (including a native continuous hit test for placement reticles), anchors, light estimates, image tracking, depth frames, face tracking, 3D model placement, and snapshots on both platforms, plus body tracking, world maps, people occlusion, and LiDAR meshes on iOS.

The package uses ARKit on iOS and ARCore on Android. It contains native code, requires React Native's New Architecture, and does not run in Expo Go.

## Features

- Native `XRView` backed by `ARSCNView` on iOS and an ARCore `GLSurfaceView` on Android
- Horizontal and vertical plane detection with add, rate-limited update, and remove callbacks
- Throttled frame callbacks with camera pose, tracking state, and light estimation (ambient intensity, color temperature, main light direction, spherical harmonics)
- Normalized screen-space hit testing and native anchors whose poses follow tracking refinements
- Native continuous hit test (`continuousHitTest` + `onHitTestResult`) for placement reticles, rate-limited and change-filtered so JavaScript is not called per frame
- Opt-in ARCore Instant Placement fallback for hit tests on Android (`instantPlacement`, like ARKit's estimated planes)
- Camera view/projection matrices, intrinsics, and tracking-state reasons (`getCamera()`) to draw your own content in step with the camera
- CPU camera frames as YUV 4:2:0 `ArrayBuffer`s with intrinsics (`getCameraImage()`) for computer vision
- Image tracking from PNG/JPEG reference images with add, update, and remove events
- Depth frames as `ArrayBuffer`s (LiDAR scene depth on iOS, ARCore Depth API on Android)
- Face tracking with a front-camera session (`mode="face"`)
- 3D body tracking with all 91 skeleton joints (`mode="body"`, iOS)
- World map save/restore with persistent anchors (iOS)
- People occlusion (iOS)
- LiDAR scene reconstruction with mesh anchor events and OBJ export (iOS)
- 3D models: USDZ/SCN/OBJ with SceneKit (iOS), OBJ with OpenGL ES (Android)
- PNG snapshots written to a temporary or cache path (the newest 10 are kept)
- ARKit/ARCore availability and install helpers
- Expo config plugin for camera permission and optional/required AR capabilities
- Fully typed TypeScript API generated through Nitro Modules

## Platform support

| Capability | iOS | Android |
| --- | --- | --- |
| World tracking | ARKit | ARCore |
| Camera background | `ARSCNView` | OpenGL ES external texture |
| Horizontal/vertical planes | Yes | Yes |
| Hit testing | Plane raycasts, then estimated planes | Plane, depth, and point hits, then Instant Placement (`estimated`) with `instantPlacement` |
| Continuous hit test (`continuousHitTest`) | Yes | Yes |
| Camera matrices + intrinsics (`getCamera`) | Yes | Yes |
| Camera image (`getCameraImage`) | YUV 4:2:0, typically 1920x1440 | YUV 4:2:0, typically 640x480 |
| Tracking-state reason | initializing, relocalizing, excessive motion, insufficient features | initializing, insufficient light, excessive motion, insufficient features, camera unavailable, bad state |
| Native anchors | Yes | Yes |
| Plane classification | ARKit semantic classes | `unknown` (ARCore has no plane semantics) |
| Light estimation | Ambient intensity + color temperature; directional light and spherical harmonics in face mode | Ambient intensity + color correction, or Environmental HDR main light + spherical harmonics |
| Environment texturing | `environmentTexturing` (world mode; face mode on iOS 27+) | No |
| Image tracking | `ARReferenceImage` | `AugmentedImageDatabase` |
| Depth frames | Scene Depth (LiDAR) float32 meters, optional smoothing; TrueDepth in face mode | Depth API uint16 millimeters, plus float32 meters (`depthMeters`, ARCore 1.56+) when supported |
| Face tracking | `ARFaceTrackingConfiguration` with blend shapes | Augmented Faces (pose + 468-vertex mesh count) |
| Body tracking (`mode="body"`) | `ARBodyTrackingConfiguration`, 91 joints (A12+) | No (ARCore has no body tracking; reported through `onError`) |
| People occlusion | Person segmentation with depth (A12+) | No (ignored, reported through `onError`) |
| World maps | `saveWorldMap` / `loadWorldMap` | No (rejects) |
| Scene mesh | LiDAR `sceneReconstruction`, mesh events, OBJ export | No |
| 3D models | SceneKit: USDZ, SCN, OBJ | OpenGL ES: OBJ geometry, neutral lit material |
| Snapshot | PNG temporary file | PNG cache file |
| Rotation / resizing | `ARSCNView` | Display geometry follows rotation, multi-window and large-screen resizing |
| Expo Go | No | No |

## Installation

```bash
npm install munim-xr react-native-nitro-modules
```

With Expo:

```bash
npx expo install munim-xr react-native-nitro-modules
```

Add the config plugin when your Expo project manages plugins explicitly:

```json
{
  "expo": {
    "plugins": [
      [
        "munim-xr",
        {
          "cameraPermission": "Allow $(PRODUCT_NAME) to use the camera for augmented reality.",
          "arRequired": false
        }
      ]
    ]
  }
}
```

`arRequired: false` keeps the app installable on devices without AR support. Set it to `true` only when the rest of your app cannot work without AR.

Create a native build after installation:

```bash
npx expo prebuild
npx expo run:ios
# or
npx expo run:android
```

For bare React Native on iOS, run `pod install` in the `ios` directory.

### Requirements

- React Native 0.78 or newer with the New Architecture enabled
- `react-native-nitro-modules` 0.37.2 or newer (munim-xr 0.5.x works with 0.37.1, 0.4.x with 0.36.5)
- iOS 15.1 or newer on an ARKit-capable device (body tracking and people occlusion need an A12 chip or newer; depth and scene meshes need LiDAR)
- Android API 24 or newer with Google Play Services for AR
- A physical device for normal runtime testing

## Quick start

Nitro callbacks must be wrapped with `callback()`. Capture the Hybrid View reference through `hybridRef` to call native methods.

```tsx
import { useMemo, useRef } from 'react'
import { StyleSheet } from 'react-native'
import { callback } from 'react-native-nitro-modules'
import {
  checkAvailability,
  requestCameraPermission,
  requestInstall,
  XRView,
  type XRViewRef,
} from 'munim-xr'

export function ARScene() {
  const xrRef = useRef<XRViewRef | null>(null)
  const hybridRef = useMemo(
    () => callback((ref: XRViewRef) => (xrRef.current = ref)),
    []
  )
  const onReady = useMemo(
    () => callback(() => console.log('XR ready')),
    []
  )

  async function start() {
    if (!(await requestCameraPermission())) return

    const availability = await checkAvailability()
    if (availability === 'not-installed' || availability === 'update-required') {
      if (!(await requestInstall())) return
    }
    if (availability === 'unsupported') return

    await xrRef.current?.start()
  }

  return (
    <XRView
      depthEnabled={false}
      frameCallbackFps={15}
      hybridRef={hybridRef}
      lightEstimationEnabled
      onReady={onReady}
      planeDetection="both"
      style={StyleSheet.absoluteFill}
    />
  )
}
```

Call `hitTest(x, y)` with normalized coordinates from `0` to `1`. An anchor can be created directly from a hit pose:

```typescript
const hits = await xrRef.current?.hitTest(0.5, 0.5)
if (hits?.[0]) {
  const anchor = await xrRef.current?.createAnchor(hits[0].pose)
  console.log(anchor?.id)
}
```

### Image tracking

```tsx
const images = useMemo(
  () => [{ name: 'poster', uri: 'https://example.com/poster.jpg', physicalWidthMeters: 0.3 }],
  []
)

<XRView
  detectionImages={images}
  onImageAnchorAdded={useMemo(() => callback((a: XRImageAnchor) => console.log(a.name, a.pose)), [])}
  {...otherProps}
/>
```

Reference images need plenty of high-contrast detail. Low-quality images are skipped and reported through `onError`. `uri` accepts `file://` paths, absolute paths, and `http(s)://` URLs (Android also accepts `content://`). Up to four images are tracked simultaneously on iOS.

### Depth

```typescript
if ((await checkAvailability('depth')) === 'supported') {
  // with depthEnabled on the XRView
  const frame = await xrRef.current?.getDepthFrame()
  if (frame?.depthMeters) {
    const meters = new Float32Array(frame.depthMeters) // both platforms
  } else if (frame) {
    const depth =
      frame.format === 'float32-meters'
        ? new Float32Array(frame.depth)
        : new Uint16Array(frame.depth) // millimeters
  }
}
```

Samples are row-major and tightly packed (`width * height`). `depthMeters` carries the same depth as float32 metres on both platforms: on iOS it is the same memory as `depth`; on Android it comes from ARCore 1.56's `acquireDepthImageMeters()` and is `undefined` if the installed Google Play Services for AR cannot provide it (then use the `uint16-millimeters` `depth`). `confidence` (when present) holds one byte per pixel from 0 (low) to 255 (high). On iOS, `depthSmoothing` switches to `smoothedSceneDepth`. In face mode on devices with a TrueDepth camera, `getDepthFrame()` returns the latest TrueDepth frame, which arrives at a lower rate than the camera.

### Face tracking

Set `mode="face"` to run a front-camera session. On iOS 27 and later, `environmentTexturing` other than `none` also turns on automatic environment texturing for face sessions (`ARFaceTrackingConfiguration.isEnvironmentTexturingEnabled`); earlier iOS versions report through `onError` that it is unavailable and continue without it. `onFaceAdded`, `onFaceUpdated`, and `onFaceRemoved` report the face pose and mesh vertex count. iOS additionally reports these blend shapes: `eyeBlinkLeft`, `eyeBlinkRight`, `jawOpen`, `mouthSmileLeft`, `mouthSmileRight`, `browInnerUp`, `cheekPuff`, `mouthFunnel`, `mouthPucker`, `tongueOut`. Switching modes restarts the session and clears anchors.

### LiDAR mesh (iOS)

```tsx
<XRView sceneReconstruction="mesh-with-classification" onMeshAnchorUpdated={onMesh} {...otherProps} />
const objPath = await xrRef.current?.exportMesh()
```

Mesh anchors report vertex and face counts, and faces per classification when `mesh-with-classification` is used. `exportMesh()` writes all current mesh anchors, in world coordinates, to one OBJ file (the newest 3 exports are kept). On devices without LiDAR the option is ignored and `onError` explains why.

### Placement reticle (continuous hit test)

A reticle needs a hit test on every frame. Instead of calling `hitTest()` from JavaScript in a loop, let the native side repeat it:

```tsx
const reticle = useMemo(() => ({ x: 0.5, y: 0.5, maxHz: 10 }), [])
const onHitTestResult = useMemo(
  () => callback((hits: XRHitResult[]) => setSurface(hits[0] ?? null)),
  []
)

<XRView continuousHitTest={reticle} onHitTestResult={onHitTestResult} {...otherProps} />
```

The hit test runs at most `maxHz` times per second on the AR thread, and `onHitTestResult` fires only when the result changes: a surface is found or lost (an empty array), the hit surface or hit type changes, or the hit point moves by more than 1 cm. Pass a memoized object; set `maxHz` to `0` or remove the prop to stop it. When the prop is set again, reporting restarts from "nothing hit", and a `reset()`, mode switch or `loadWorldMap()` reports an empty array if a surface was hit before.

On Android, set `instantPlacement` to let `hitTest()` fall back to ARCore Instant Placement when no plane, depth, or feature point is hit: it returns an `estimated` hit about 1 m away that ARCore refines later, so content can be placed before a plane is found (iOS hit tests already fall back to estimated planes). The continuous hit test does not use it, because every Instant Placement hit creates a new ARCore trackable.

### Camera matrices and camera image

```typescript
const camera = await xrRef.current?.getCamera(0.01, 100) // near/far clip planes in meters
camera.viewMatrix // world-to-camera, column-major, for the view's current orientation
camera.projectionMatrix // column-major, for the view's size and orientation
camera.intrinsics // { fx, fy, cx, cy, imageWidth, imageHeight } in camera-image pixels
camera.trackingStateReason // e.g. 'excessive-motion' while tracking is limited
camera.worldMappingStatus // iOS world mode: 'limited' | 'extending' | 'mapped'

const image = await xrRef.current?.getCameraImage()
const luma = new Uint8Array(image.y) // width * height
const chroma = new Uint8Array(image.cbcr) // (width / 2) * (height / 2) * [Cb, Cr]
```

`viewMatrix` and `projectionMatrix` let you render your own content (for example with expo-gl and three.js) aligned with the camera feed. `getCameraImage()` copies the latest camera frame once per call into two tightly packed `ArrayBuffer`s in NV12 order (no JSON, no per-pixel bridge calls). Images and intrinsics are in the sensor's native landscape orientation. iOS returns ARKit's captured image (typically 1920x1440, about 4 MB per call); Android returns ARCore's CPU image (typically 640x480).

### Body tracking (iOS)

```tsx
<XRView mode="body" onBodyUpdated={onBody} {...otherProps} />
```

`onBodyAdded`, `onBodyUpdated` (rate-limited by `trackableUpdateMaxHz`, skeleton conversion only when an update is due), and `onBodyRemoved` report the body's root pose, estimated scale factor, and all 91 joints of ARKit's 3D skeleton with world positions and whether each joint is tracked directly. Body tracking needs an A12 chip or newer (`checkAvailability('body-tracking')`). Android has no body tracking: `start()` rejects in body mode, and switching a running view to body mode reports through `onError` and keeps the current session.

### World maps (iOS)

```typescript
const path = await xrRef.current?.saveWorldMap() // once worldMappingStatus is 'extending' or 'mapped'
// later, in a new session:
await xrRef.current?.loadWorldMap(path)
```

`saveWorldMap()` archives ARKit's current world map, including anchors from `createAnchor()`, to `Library/Application Support/munim-xr/world-maps/` and returns the path. Saved maps are never pruned; delete files you no longer need. If a stored path no longer exists because the app container moved (app update or restore), `loadWorldMap()` finds the map by file name in that folder. `loadWorldMap(path)` restarts tracking from that map; the saved anchors are back in `getAnchors()` with their original ids, and their poses become valid once the device relocalizes (`trackingStateReason` is `relocalizing` until then). If the view is not running, the map is used by the next `start()`. Android rejects both methods (ARCore has no local world map API).

### People occlusion (iOS)

`peopleOcclusion` turns on ARKit person segmentation with depth, so people in front of virtual content hide it. It needs an A12 chip or newer (`checkAvailability('people-occlusion')`); elsewhere, and on Android, the prop is ignored and `onError` says so.

### 3D models

```typescript
const modelId = await xrRef.current?.addModel({
  uri: 'https://example.com/chair.usdz', // Android: an .obj file
  anchorId: anchor.id, // optional: follow an anchor, image anchor, plane, or face
  scale: 1,
})
xrRef.current?.setModelTransform(modelId!, pose, 2) // pose relative to the anchor
xrRef.current?.removeModel(modelId!)
```

iOS loads models with `SCNScene(url:)` into the existing `ARSCNView` (USDZ, SCN, and OBJ); with `environmentTexturing="automatic"` they pick up reflections from the environment. Android loads OBJ geometry (`v`, `vn`, `f`; polygons are triangulated, faces without normals get flat normals) and draws it with OpenGL ES over the camera feed, depth-tested, with a neutral lit material; materials and textures are not loaded, and USDZ/SCN URIs reject with a clear error. Android accepts the same URIs as detection images, including bundled `require()` assets in release builds. Models attached to an anchor are hidden while that anchor is not tracked.

## API

### Module functions

- `isSupported(): boolean` — returns the native runtime's immediate support result
- `checkAvailability(feature?): Promise<XRAvailability>` — reports `supported`, `unsupported`, `not-installed`, `update-required`, or `unknown`. Pass an `XRFeature` (`world-tracking`, `depth`, `smoothed-depth`, `scene-reconstruction`, `mesh-classification`, `face-tracking`, `image-tracking`, `environmental-hdr`, `models`, `body-tracking`, `people-occlusion`, `world-map`) to check an optional capability. On Android, feature checks create a short-lived ARCore session, so run them before starting an `XRView`.
- `requestInstall(): Promise<boolean>` — requests ARCore installation/update on Android; returns the current ARKit support result on iOS
- `requestCameraPermission(): Promise<boolean>` — requests Android camera permission; iOS prompts when the session starts
- `getXRPlatform(): string` — returns `arkit` or `arcore`
- `getXRSDKVersion(): string` — returns the native XR SDK description

### `XRView` props

- `planeDetection`: `none`, `horizontal`, `vertical`, or `both`
- `depthEnabled`: enables supported native depth semantics
- `lightEstimationEnabled`: enables light estimates
- `frameCallbackFps`: limits JavaScript frame callback frequency; use `0` to disable
- `mode`: `world` (default, rear camera), `face` (front camera), or `body` (iOS, rear camera with 3D body tracking)
- `trackableUpdateMaxHz`: maximum rate of update events per plane, image, mesh, or face (default `10`, `0` disables updates). Updates are also skipped unless the trackable moved or resized by more than about 1 cm; tracking-state changes are delivered immediately
- `lightEstimationMode`: `ambient-intensity` (default) or `environmental-hdr` (Android main light + spherical harmonics; on iOS the directional estimate is only available in face mode)
- `environmentTexturing` (iOS): `none`, `manual`, or `automatic`. Face mode needs iOS 27 and only supports automatic texturing
- `depthSmoothing` (iOS): use smoothed scene depth
- `detectionImages`: `{ name, uri, physicalWidthMeters }[]`
- `sceneReconstruction` (iOS LiDAR): `none`, `mesh`, or `mesh-with-classification`
- `peopleOcclusion` (iOS, A12+): hide virtual content behind people
- `continuousHitTest`: `{ x, y, maxHz }` native hit test reported through `onHitTestResult`
- `instantPlacement` (Android): `hitTest()` falls back to ARCore Instant Placement (`estimated` hits)
- `onReady`, `onFrame`, `onTrackingStateChange`, `onPlaneDetected`, `onPlaneUpdated`, `onPlaneRemoved`, `onImageAnchorAdded`, `onImageAnchorUpdated`, `onImageAnchorRemoved`, `onMeshAnchorAdded`, `onMeshAnchorUpdated`, `onMeshAnchorRemoved`, `onFaceAdded`, `onFaceUpdated`, `onFaceRemoved`, `onBodyAdded`, `onBodyUpdated`, `onBodyRemoved`, `onHitTestResult`, `onError`

### `XRView` methods

- `start()` and `pause()` control the native session
- `reset()` restarts tracking and removes existing native tracking state
- `hitTest(normalizedX, normalizedY)` returns ordered `XRHitResult` values
- `createAnchor(pose)`, `removeAnchor(id)`, and `getAnchors()` manage native anchors. `getAnchors()` returns the latest refined poses; `tracking` is `false` while the session cannot track the anchor
- `getCameraPose()` returns the latest camera pose when available
- `getCamera(zNear, zFar)` returns the latest `XRCamera`: pose, view and projection matrices, intrinsics, tracking state and reason, and world mapping status
- `getCameraImage()` returns the latest `XRCameraImage` (YUV 4:2:0 `ArrayBuffer`s and intrinsics)
- `captureSnapshot()` returns a local PNG path
- `getDepthFrame()` returns the latest `XRDepthFrame`
- `exportMesh()` (iOS) writes the LiDAR mesh to an OBJ file and returns its path
- `saveWorldMap()` and `loadWorldMap(path)` (iOS) save and restore ARKit world maps
- `addModel(options)`, `removeModel(id)`, `setModelTransform(id, pose, scale?)` manage 3D models (SceneKit on iOS, OpenGL ES OBJ on Android)

### Coordinates

- A pose matrix contains 16 column-major values in native AR coordinates, measured in meters.
- `XRPlane.center` is in world space on both platforms. `localCenter` is the same point relative to `pose` (on Android `pose` is the plane's center pose, so `localCenter` is zero). On iOS 16+, `extentRotationY` gives the extent rectangle's rotation around the plane's Y axis.
- Light: iOS ambient intensity is in lumens (about 1000 is neutral) with a color temperature in Kelvin; Android ambient intensity is an average pixel intensity from 0 to 1. Spherical harmonics are 27 values ordered as nine `[r, g, b]` triplets on both platforms.

## Runtime notes

- AR support varies by device. Check availability before exposing an AR-only flow.
- ARCore installation can take the user out of the app. If `requestInstall()` returns `false`, ask them to finish installation and try again.
- Every optional `XRView` prop, callbacks included, can be removed or passed as `null` or `undefined` (for example `continuousHitTest={enabled ? config : undefined}`); native code then treats it as unset.
- Frame callbacks cross the native/JavaScript boundary. Keep `frameCallbackFps` as low as your UI permits, and use `continuousHitTest` rather than calling `hitTest()` per frame.
- Snapshot and mesh-export paths point into temporary app storage (`munim-xr/` under the temporary or cache directory). Only the newest 10 snapshots and 3 mesh exports are kept, so move a file if it must persist.
- On Android the session pauses when the app goes to the background (releasing the camera) and resumes when it returns, if it was running.
- Android rotation and resizing: apps targeting SDK 36 or later cannot lock orientation or resizability on large screens (smallest width 600 dp and up), so an `XRView` can rotate or resize even in a portrait-only app. The view updates ARCore's display geometry on every rotation (including 180° turns) and size change. Keep the host Activity handling configuration changes itself (`android:configChanges` with `orientation|screenSize|smallestScreenSize|screenLayout`, the Expo and React Native default); if the Activity is recreated instead, the session is closed and must be started again.
- Android `targetSdkVersion`: ARCore's library manifest (1.56 and later; munim-xr uses 1.57) declares `targetSdkVersion 37`. Set your app's own `targetSdkVersion` explicitly (Expo: `expo-build-properties` → `android.targetSdkVersion`) so the value you test against is the one that ships. The library defaults to `compileSdkVersion 37`, `targetSdkVersion 37`, `minSdkVersion 24`, and Kotlin 2.2.0 when the app does not provide them.
- The iOS Simulator can compile the module but does not provide a normal world-tracking ARKit session.

## Known issues and roadmap

- **SceneKit is deprecated (iOS 26+).** iOS renders through SceneKit (`ARSCNView`), which Apple deprecated in iOS 26 in favour of RealityKit. It still works, and apps building munim-xr see the deprecation warnings. Moving `XRView` and the model APIs to RealityKit's `ARView` is on the roadmap; it changes the rendering stack and the model APIs, so it is planned as a separate release.
- **Fixed by Nitro 0.37.2:** Android base view props on React Native 0.87+ (margelo/nitro#1656; munim-xr no longer patches the generated code) and Xcode 27 launch crashes on iOS 17 and earlier (margelo/nitro#1652). Both need `react-native-nitro-modules` 0.37.2 or newer, which munim-xr now requires.
- **Xcode 27 apps need the UIScene lifecycle.** Apps built with the iOS 27 SDK crash at launch without it (TN3187). Expo SDK 58 and newer adopt it for you; on Expo 57 set `expo-build-properties` → `ios.enableSceneSupport: true`.
- **Nitro 0.37 on React Native 0.83 and older (margelo/nitro#1573).** `react-native-nitro-modules` 0.37 does not build for iOS in apps on React Native 0.83 or older that use static CocoaPods (no `use_frameworks!`), and munim-xr 0.5 and newer need Nitro 0.37 for their view bindings. Stay on munim-xr 0.4.x with Nitro 0.36.5 there until the upstream fix ships.
- **Not covered yet:** ARCore Geospatial / ARKit geo anchors, Cloud Anchors, Scene Semantics, Streetscape Geometry, ARKit object detection, and Android XR headsets (Jetpack XR). Geospatial and Cloud Anchors need Google Cloud credentials (and ARCore's iOS SDK for cross-platform anchors), and ARKit geo tracking needs GPS in a supported city.

## Development

```bash
npm install
npm run codegen
npm run check
```

Generated Nitro bindings live under `packages/munim-xr/nitrogen/generated` and are committed. `npm run codegen` runs `nitrogen` and then three patch scripts that fail loudly if the generated code no longer matches what they expect: `patch-nitro-optionals.js` (margelo/nitro#1319, iOS Release builds), `patch-nitro-double-vectors.js` (Xcode 26 cannot `.map` a `std::vector<double>`), and `patch-nitro-nullable-props.js` (React sends `null` for a removed prop, which Nitro's optional converter rejects with a fatal "Value is null" error; the patch treats `null` as unset). Native implementation files live outside that generated directory. `npm test` runs the Jest unit tests for the Expo config plugin.

### Device self-test

The example app runs an unattended check when opened with `munimxrexample://selftest` (or the **Self-test** button): session start, frames, tracking, camera matrices and intrinsics, camera image (with mean call time), planes, hit test, anchor lifecycle, continuous hit test rate, model add/transform/remove, world map save/load (iOS; a clean rejection on Android), snapshot, depth (`depthMeters` against `depth`, with mean call time), face mode (iOS 27 environment texturing), body mode (iOS; a clean rejection on Android), and pause. Results are logged as `MUNIM_XR_CHECK` lines and written to `Documents/munim-xr-selftest.json`. While it runs the view is drawn at 50% opacity over a magenta stage, with `testID="munim-xr-view"`, to show that base view props reach the native view on Android.

```bash
# iOS (Release build installed on a device)
xcrun devicectl device process launch --device <udid> --payload-url munimxrexample://selftest com.sheehanmunim.munimxrexample
# Android
adb shell am start -a android.intent.action.VIEW -d munimxrexample://selftest
```

## License

Apache-2.0. ARKit, ARCore, React Native, Expo, and Nitro Modules retain their respective licenses and terms.
