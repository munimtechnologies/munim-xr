<!-- Munim Technologies banner -->

<p align="center">
  <a href="https://github.com/munimtechnologies/munim-xr">
    <img alt="Munim Technologies" height="128" src="https://raw.githubusercontent.com/munimtechnologies/munim-xr/main/.github/resources/banner.png">
    <h1 align="center">munim-xr</h1>
  </a>
</p>

<p align="center">
  <a href="https://www.npmjs.com/package/munim-xr"><img alt="npm version" src="https://img.shields.io/npm/v/munim-xr.svg?style=flat-square&label=Version&labelColor=000000&color=0066CC" /></a>
  <a href="https://github.com/munimtechnologies/munim-xr/blob/main/LICENSE"><img alt="Apache-2.0 license" src="https://img.shields.io/badge/License-Apache%202.0-success.svg?style=flat-square&color=33CC12" /></a>
  <a href="https://www.npmtrends.com/munim-xr"><img alt="Monthly downloads" src="https://img.shields.io/npm/dm/munim-xr.svg?style=flat-square&labelColor=gray&color=33CC12&label=Downloads" /></a>
  <a href="https://www.npmjs.com/package/munim-xr"><img alt="Total downloads" src="https://img.shields.io/npm/dt/munim-xr.svg?style=flat-square&labelColor=gray&color=0066CC&label=Total%20Downloads" /></a>
</p>

<p align="center">
  <b>React Native</b> &ensp;•&ensp; <b>Expo</b> &ensp;•&ensp; <b>ARKit</b> &ensp;•&ensp; <b>ARCore</b> &ensp;•&ensp; <b>Nitro Modules</b>
</p>

Native ARKit and ARCore sessions for Expo and React Native, powered by Nitro Modules.

`munim-xr` provides a typed Nitro Hybrid View for world tracking, native camera rendering, plane detection, hit testing, anchors, camera poses, light estimation, optional depth, and snapshots on iOS and Android.

## Install

```bash
npm install munim-xr react-native-nitro-modules
```

Expo projects can use:

```bash
npx expo install munim-xr react-native-nitro-modules
```

Then configure and rebuild the native app:

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

```bash
npx expo prebuild
npx expo run:ios
# or: npx expo run:android
```

This package requires React Native's New Architecture and a native development build. It cannot run in Expo Go.

## Usage

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

  async function start() {
    if (!(await requestCameraPermission())) return
    const availability = await checkAvailability()
    if (availability === 'not-installed' || availability === 'update-required') {
      if (!(await requestInstall())) return
    }
    await xrRef.current?.start()
  }

  return (
    <XRView
      depthEnabled={false}
      frameCallbackFps={15}
      hybridRef={hybridRef}
      lightEstimationEnabled
      planeDetection="both"
      style={StyleSheet.absoluteFill}
    />
  )
}
```

Wrap `hybridRef` and event handlers with Nitro's `callback()`. The captured reference exposes `start`, `pause`, `reset`, `hitTest`, anchor management, camera pose, and snapshot methods.

```typescript
const hits = await xrRef.current?.hitTest(0.5, 0.5)
if (hits?.[0]) {
  const anchor = await xrRef.current?.createAnchor(hits[0].pose)
  console.log(anchor?.id)
}
```

## Requirements

- React Native 0.78+ with the New Architecture
- `react-native-nitro-modules` 0.36.5+
- iOS 15.1+ on an ARKit-capable device
- Android API 24+ with Google Play Services for AR
- Expo development build or bare React Native app

See the [complete documentation](https://github.com/munimtechnologies/munim-xr#readme) for the platform matrix, API reference, runtime notes, and development instructions.

## License

Apache-2.0
