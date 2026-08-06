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

`munim-xr` gives one typed React Native API to world-tracked augmented reality on iOS and Android. Its Nitro Hybrid View renders the native camera feed and exposes session lifecycle, camera pose, plane detection, hit testing, anchors, light estimates, optional depth, and snapshots.

The package uses ARKit on iOS and ARCore on Android. It contains native code, requires React Native's New Architecture, and does not run in Expo Go.

## Features

- Native `XRView` backed by `ARSCNView` on iOS and an ARCore `GLSurfaceView` on Android
- Horizontal and vertical plane detection with add, update, and remove callbacks
- Throttled frame callbacks with camera pose, tracking state, and ambient light
- Normalized screen-space hit testing and persistent native anchors
- Optional scene depth when supported by the device
- PNG snapshots written to a temporary or cache path
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
| Ambient light estimate | Yes | Yes |
| Optional depth | Scene Depth when supported | Automatic Depth when supported |
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

## API

### Module functions

- `isSupported(): boolean` — returns the native runtime's immediate support result
- `checkAvailability(): Promise<XRAvailability>` — reports `supported`, `unsupported`, `not-installed`, `update-required`, or `unknown`
- `requestInstall(): Promise<boolean>` — requests ARCore installation/update on Android; returns the current ARKit support result on iOS
- `requestCameraPermission(): Promise<boolean>` — requests Android camera permission; iOS prompts when the session starts
- `getXRPlatform(): string` — returns `arkit` or `arcore`
- `getXRSDKVersion(): string` — returns the native XR SDK description

### `XRView` props

- `planeDetection`: `none`, `horizontal`, `vertical`, or `both`
- `depthEnabled`: enables supported native depth semantics
- `lightEstimationEnabled`: enables ambient light estimates
- `frameCallbackFps`: limits JavaScript frame callback frequency; use `0` to disable
- `onReady`, `onFrame`, `onTrackingStateChange`, `onPlaneDetected`, `onPlaneUpdated`, `onPlaneRemoved`, `onError`

### `XRView` methods

- `start()` and `pause()` control the native session
- `reset()` restarts tracking and removes existing native tracking state
- `hitTest(normalizedX, normalizedY)` returns ordered `XRHitResult` values
- `createAnchor(pose)`, `removeAnchor(id)`, and `getAnchors()` manage native anchors
- `getCameraPose()` returns the latest camera pose when available
- `captureSnapshot()` returns a local PNG path

## Runtime notes

- AR support varies by device. Check availability before exposing an AR-only flow.
- ARCore installation can take the user out of the app. If `requestInstall()` returns `false`, ask them to finish installation and try again.
- A pose matrix contains 16 column-major values in native AR coordinates, measured in meters.
- Frame callbacks cross the native/JavaScript boundary. Keep `frameCallbackFps` as low as your UI permits.
- A snapshot path points into temporary app storage; move the file if it must persist.
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
