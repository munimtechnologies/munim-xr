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

`munim-xr` gives one typed React Native API to world-tracked augmented reality on iOS and Android. Its Nitro Hybrid View renders the native camera feed and exposes session lifecycle, camera pose, plane detection, hit testing, anchors, light estimates, image tracking, depth frames, face tracking, LiDAR meshes, and snapshots, plus minimal 3D model placement on iOS.

The package uses ARKit on iOS and ARCore on Android. It contains native code, requires React Native's New Architecture, and does not run in Expo Go.

## Features

- Native `XRView` backed by `ARSCNView` on iOS and an ARCore `GLSurfaceView` on Android
- Horizontal and vertical plane detection with add, rate-limited update, and remove callbacks
- Throttled frame callbacks with camera pose, tracking state, and light estimation (ambient intensity, color temperature, main light direction, spherical harmonics)
- Normalized screen-space hit testing and native anchors whose poses follow tracking refinements
- Image tracking from PNG/JPEG reference images with add, update, and remove events
- Depth frames as `ArrayBuffer`s (LiDAR scene depth on iOS, ARCore Depth API on Android)
- Face tracking with a front-camera session (`mode="face"`)
- LiDAR scene reconstruction with mesh anchor events and OBJ export (iOS)
- USDZ/SCN/OBJ model placement with SceneKit (iOS)
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
| Hit testing | Plane raycasts | Plane, depth, point, and estimated hits |
| Native anchors | Yes | Yes |
| Plane classification | ARKit semantic classes | `unknown` (ARCore has no plane semantics) |
| Light estimation | Ambient intensity + color temperature; directional light and spherical harmonics in face mode | Ambient intensity + color correction, or Environmental HDR main light + spherical harmonics |
| Environment texturing | `environmentTexturing` | No |
| Image tracking | `ARReferenceImage` | `AugmentedImageDatabase` |
| Depth frames | Scene Depth (LiDAR) float32 meters, optional smoothing; TrueDepth in face mode | Depth API uint16 millimeters when supported |
| Face tracking | `ARFaceTrackingConfiguration` with blend shapes | Augmented Faces (pose + 468-vertex mesh count) |
| Scene mesh | LiDAR `sceneReconstruction`, mesh events, OBJ export | No |
| 3D models | SceneKit (`addModel`) | Not yet supported (rejects) |
| Snapshot | PNG temporary file | PNG cache file |
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
- `react-native-nitro-modules` 0.36.5 or newer
- iOS 15.1 or newer on an ARKit-capable device
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
  const depth =
    frame?.format === 'float32-meters'
      ? new Float32Array(frame.depth)
      : new Uint16Array(frame!.depth) // millimeters
}
```

Samples are row-major and tightly packed (`width * height`). `confidence` (when present) holds one byte per pixel from 0 (low) to 255 (high). On iOS, `depthSmoothing` switches to `smoothedSceneDepth`. In face mode on devices with a TrueDepth camera, `getDepthFrame()` returns the latest TrueDepth frame, which arrives at a lower rate than the camera.

### Face tracking

Set `mode="face"` to run a front-camera session. `onFaceAdded`, `onFaceUpdated`, and `onFaceRemoved` report the face pose and mesh vertex count. iOS additionally reports these blend shapes: `eyeBlinkLeft`, `eyeBlinkRight`, `jawOpen`, `mouthSmileLeft`, `mouthSmileRight`, `browInnerUp`, `cheekPuff`, `mouthFunnel`, `mouthPucker`, `tongueOut`. Switching modes restarts the session and clears anchors.

### LiDAR mesh (iOS)

```tsx
<XRView sceneReconstruction="mesh-with-classification" onMeshAnchorUpdated={onMesh} {...otherProps} />
const objPath = await xrRef.current?.exportMesh()
```

Mesh anchors report vertex and face counts, and faces per classification when `mesh-with-classification` is used. `exportMesh()` writes all current mesh anchors, in world coordinates, to one OBJ file (the newest 3 exports are kept). On devices without LiDAR the option is ignored and `onError` explains why.

### 3D models (iOS)

```typescript
const modelId = await xrRef.current?.addModel({
  uri: 'https://example.com/chair.usdz',
  anchorId: anchor.id, // optional: follow an anchor or image anchor
  scale: 1,
})
xrRef.current?.setModelTransform(modelId!, pose, 2) // pose relative to the anchor
xrRef.current?.removeModel(modelId!)
```

Models are loaded with `SCNScene(url:)` into the existing `ARSCNView` (USDZ, SCN, and OBJ). With `environmentTexturing="automatic"` they pick up reflections from the environment. On Android these methods reject or throw `not yet supported on Android`.

## API

### Module functions

- `isSupported(): boolean` — returns the native runtime's immediate support result
- `checkAvailability(feature?): Promise<XRAvailability>` — reports `supported`, `unsupported`, `not-installed`, `update-required`, or `unknown`. Pass an `XRFeature` (`world-tracking`, `depth`, `smoothed-depth`, `scene-reconstruction`, `mesh-classification`, `face-tracking`, `image-tracking`, `environmental-hdr`, `models`) to check an optional capability. On Android, feature checks create a short-lived ARCore session, so run them before starting an `XRView`.
- `requestInstall(): Promise<boolean>` — requests ARCore installation/update on Android; returns the current ARKit support result on iOS
- `requestCameraPermission(): Promise<boolean>` — requests Android camera permission; iOS prompts when the session starts
- `getXRPlatform(): string` — returns `arkit` or `arcore`
- `getXRSDKVersion(): string` — returns the native XR SDK description

### `XRView` props

- `planeDetection`: `none`, `horizontal`, `vertical`, or `both`
- `depthEnabled`: enables supported native depth semantics
- `lightEstimationEnabled`: enables light estimates
- `frameCallbackFps`: limits JavaScript frame callback frequency; use `0` to disable
- `mode`: `world` (default, rear camera) or `face` (front camera)
- `trackableUpdateMaxHz`: maximum rate of update events per plane, image, mesh, or face (default `10`, `0` disables updates). Updates are also skipped unless the trackable moved or resized by more than about 1 cm; tracking-state changes are delivered immediately
- `lightEstimationMode`: `ambient-intensity` (default) or `environmental-hdr` (Android main light + spherical harmonics; on iOS the directional estimate is only available in face mode)
- `environmentTexturing` (iOS): `none`, `manual`, or `automatic`
- `depthSmoothing` (iOS): use smoothed scene depth
- `detectionImages`: `{ name, uri, physicalWidthMeters }[]`
- `sceneReconstruction` (iOS LiDAR): `none`, `mesh`, or `mesh-with-classification`
- `onReady`, `onFrame`, `onTrackingStateChange`, `onPlaneDetected`, `onPlaneUpdated`, `onPlaneRemoved`, `onImageAnchorAdded`, `onImageAnchorUpdated`, `onImageAnchorRemoved`, `onMeshAnchorAdded`, `onMeshAnchorUpdated`, `onMeshAnchorRemoved`, `onFaceAdded`, `onFaceUpdated`, `onFaceRemoved`, `onError`

### `XRView` methods

- `start()` and `pause()` control the native session
- `reset()` restarts tracking and removes existing native tracking state
- `hitTest(normalizedX, normalizedY)` returns ordered `XRHitResult` values
- `createAnchor(pose)`, `removeAnchor(id)`, and `getAnchors()` manage native anchors. `getAnchors()` returns the latest refined poses; `tracking` is `false` while the session cannot track the anchor
- `getCameraPose()` returns the latest camera pose when available
- `captureSnapshot()` returns a local PNG path
- `getDepthFrame()` returns the latest `XRDepthFrame`
- `exportMesh()` (iOS) writes the LiDAR mesh to an OBJ file and returns its path
- `addModel(options)`, `removeModel(id)`, `setModelTransform(id, pose, scale?)` (iOS) manage SceneKit models

### Coordinates

- A pose matrix contains 16 column-major values in native AR coordinates, measured in meters.
- `XRPlane.center` is in world space on both platforms. `localCenter` is the same point relative to `pose` (on Android `pose` is the plane's center pose, so `localCenter` is zero). On iOS 16+, `extentRotationY` gives the extent rectangle's rotation around the plane's Y axis.
- Light: iOS ambient intensity is in lumens (about 1000 is neutral) with a color temperature in Kelvin; Android ambient intensity is an average pixel intensity from 0 to 1. Spherical harmonics are 27 values ordered as nine `[r, g, b]` triplets on both platforms.

## Runtime notes

- AR support varies by device. Check availability before exposing an AR-only flow.
- ARCore installation can take the user out of the app. If `requestInstall()` returns `false`, ask them to finish installation and try again.
- Frame callbacks cross the native/JavaScript boundary. Keep `frameCallbackFps` as low as your UI permits.
- Snapshot and mesh-export paths point into temporary app storage (`munim-xr/` under the temporary or cache directory). Only the newest 10 snapshots and 3 mesh exports are kept, so move a file if it must persist.
- On Android the session pauses when the app goes to the background (releasing the camera) and resumes when it returns, if it was running.
- iOS renders through SceneKit (`ARSCNView`). Apple has soft-deprecated SceneKit in favor of RealityKit; a RealityKit migration is a known follow-up and is not part of this release.
- The iOS Simulator can compile the module but does not provide a normal world-tracking ARKit session.

## Development

```bash
npm install
npm run codegen
npm run check
```

Generated Nitro bindings live under `packages/munim-xr/nitrogen/generated` and are committed. Native implementation files live outside that generated directory.

## License

Apache-2.0. ARKit, ARCore, React Native, Expo, and Nitro Modules retain their respective licenses and terms.
