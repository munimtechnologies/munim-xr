import type { HybridObject } from 'react-native-nitro-modules'

export type XRAvailability =
  'supported' | 'unsupported' | 'not-installed' | 'update-required' | 'unknown'

/**
 * Optional capabilities that can be queried with `checkAvailability(feature)`.
 *
 * - `world-tracking`: 6DoF world tracking (the default session)
 * - `depth`: per-pixel depth frames (`getDepthFrame()`)
 * - `smoothed-depth`: temporally smoothed depth (iOS LiDAR only)
 * - `scene-reconstruction`: LiDAR mesh anchors (iOS only)
 * - `mesh-classification`: classified LiDAR mesh faces (iOS only)
 * - `face-tracking`: `mode="face"` sessions
 * - `image-tracking`: `detectionImages`
 * - `environmental-hdr`: main light direction and spherical harmonics
 * - `models`: `addModel()` 3D content rendering
 */
export type XRFeature =
  | 'world-tracking'
  | 'depth'
  | 'smoothed-depth'
  | 'scene-reconstruction'
  | 'mesh-classification'
  | 'face-tracking'
  | 'image-tracking'
  | 'environmental-hdr'
  | 'models'

export interface MunimXr extends HybridObject<{
  ios: 'swift'
  android: 'kotlin'
}> {
  readonly platform: string
  readonly sdkVersion: string

  isSupported(): boolean
  /**
   * Reports whether `feature` is available on this device (`unsupported`
   * when the runtime is present but the device lacks the feature).
   * `world-tracking` reports whether world-tracked XR can run.
   *
   * `feature` is not optional here: optional enum arguments can reach Swift
   * as garbage in iOS Release builds (margelo/nitro#1319). The public
   * `checkAvailability()` in `src/index.ts` defaults it to `world-tracking`.
   */
  checkAvailability(feature: XRFeature): Promise<XRAvailability>
  requestInstall(): Promise<boolean>
}
