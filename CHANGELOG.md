# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Fixed

- iOS Release builds: `checkAvailability()` without a feature could receive a garbage feature in Swift (margelo/nitro#1319, swiftlang/swift#84848) and report the wrong capability. The native method now always receives a feature, and the public function defaults it to `world-tracking`, which is what a missing feature already meant on both platforms.
- iOS Release builds: the optional enum props `mode`, `lightEstimationMode`, `environmentTexturing`, and `sceneReconstruction`, and the optional fields `XRFrame.lightEstimate`, `XRLightEstimate.mainLightDirection`, and `XRModelOptions.pose`, are now read in the generated Swift through Nitro's checked bridge helpers instead of the `.value` projection. `scripts/patch-nitro-optionals.js` applies this after `nitrogen` in `npm run codegen` and fails if an unchecked projection is left.

## [0.3.0] - 2026-09-19

### Added

- `mode: 'world' | 'face'` prop: face tracking with `ARFaceTrackingConfiguration` (pose, mesh vertex count, a blend-shape subset) on iOS and ARCore Augmented Faces (pose, 468-vertex mesh count) on Android, with `onFaceAdded`, `onFaceUpdated`, and `onFaceRemoved`.
- Light estimation: `XRFrame.lightEstimate` with ambient intensity and color temperature (iOS), color correction (Android), and main light direction, intensity, and spherical harmonics (iOS face mode; Android `lightEstimationMode="environmental-hdr"`). New `environmentTexturing` prop on iOS.
- Image tracking: `detectionImages` prop (`name`, `uri`, `physicalWidthMeters`) backed by `ARReferenceImage` and `AugmentedImageDatabase`, with `onImageAnchorAdded`, `onImageAnchorUpdated`, and `onImageAnchorRemoved`.
- Depth frames: `getDepthFrame()` returns depth (and confidence when available) as `ArrayBuffer`s; float32 meters from LiDAR scene depth (optional `depthSmoothing`) or TrueDepth in face mode on iOS, uint16 millimeters from the ARCore Depth API on Android.
- LiDAR scene reconstruction on iOS: `sceneReconstruction` prop, mesh anchor events with vertex, face, and classification counts, and `exportMesh()` to an OBJ file.
- 3D models on iOS: `addModel()`, `removeModel()`, and `setModelTransform()` load USDZ, SCN, or OBJ files into the SceneKit view, optionally attached to an anchor. Android rejects these calls as not yet supported.
- `checkAvailability(feature)` reports support for `depth`, `smoothed-depth`, `scene-reconstruction`, `mesh-classification`, `face-tracking`, `image-tracking`, `environmental-hdr`, and `models`.
- `trackableUpdateMaxHz` prop (default 10) limits per-trackable update events.
- `XRPlane.localCenter` and `XRPlane.extentRotationY`.
- The example app exposes every feature with buttons and logs results with a `MUNIM_XR` prefix.

### Changed

- `XRPlane.center` is now in world space on iOS, matching Android. The previous anchor-relative value is available as `localCenter`.
- Android reports plane `classification` as `unknown`. ARCore has no semantic plane labels, so the previous floor, wall, and ceiling values were guesses based on orientation.
- Snapshots are written to `munim-xr/snapshots/` in the temporary (iOS) or cache (Android) directory. Only the newest 10 are kept.
- Example app moved to Expo 57.0.24 and React Native 0.86.3.

### Fixed

- iOS `getAnchors()` returned creation-time poses and always reported `tracking: true`. It now follows ARKit's anchor updates and reports tracking state. Anchors removed by ARKit are dropped.
- iOS plane extents use `planeExtent` on iOS 16 and later instead of the deprecated `extent`.
- `onPlaneUpdated` fired on every frame for every plane. Updates are now rate-limited per plane and sent only when the plane changes by more than about 1 cm.
- iOS `running` state was read across threads without a lock.
- Android now pauses the ARCore session and releases the camera when the app goes to the background, and resumes it on return.
- Android missed 180° display rotations because display geometry was only updated in `onSurfaceChanged`. A `DisplayListener` now covers them.
- Android `getAnchors()` read anchor poses off the GL thread. Poses are now snapshotted on the GL thread after each frame.

## [0.2.0] - 2026-09-05

### Changed

- ARCore updated to 1.56.0.
- `react-native-nitro-modules` peer range is now `>=0.36.5 <1`. Nitro 0.37.x is held back because it breaks static-library iOS builds on React Native 0.83 and older (margelo/nitro#1573).
- Example app moved to Expo 57.0.20.

### Fixed

- `pause()` on Android stops the render loop before pausing the ARCore session, so a pause no longer surfaces a spurious `onError` from `Session.update()`.

## [0.1.1] - 2026-08-06

### Fixed

- Prevent Android session reconfiguration from racing the ARCore render thread after React updates, which could surface `SessionPausedException` on start or resume.

## [0.1.0] - 2026-08-06

### Added

- Initial Expo and React Native package.
- Nitro Hybrid View implemented with ARKit and ARCore.
- Native camera rendering, world tracking, plane detection, frame and tracking callbacks.
- Hit testing, native anchors, camera poses, light estimation, optional depth, and PNG snapshots.
- Runtime support checks and ARCore install/update flow.
- Expo config plugin, typed example app, native build workflows, and npm provenance publishing.

[Unreleased]: https://github.com/munimtechnologies/munim-xr/compare/v0.3.0...HEAD
[0.3.0]: https://github.com/munimtechnologies/munim-xr/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/munimtechnologies/munim-xr/compare/v0.1.1...v0.2.0
[0.1.1]: https://github.com/munimtechnologies/munim-xr/compare/v0.1.0...v0.1.1
[0.1.0]: https://github.com/munimtechnologies/munim-xr/releases/tag/v0.1.0
