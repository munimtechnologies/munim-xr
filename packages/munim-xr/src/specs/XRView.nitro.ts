import type {
  HybridView,
  HybridViewMethods,
  HybridViewProps,
} from 'react-native-nitro-modules'

export type XRPlaneDetection = 'none' | 'horizontal' | 'vertical' | 'both'
export type XRTrackingState = 'unavailable' | 'limited' | 'normal'
export type XRPlaneAlignment = 'horizontal' | 'vertical' | 'unknown'
export type XRPlaneClassification =
  | 'floor'
  | 'wall'
  | 'ceiling'
  | 'table'
  | 'seat'
  | 'door'
  | 'window'
  | 'unknown'
export type XRHitType = 'plane' | 'depth' | 'feature-point' | 'estimated'

/**
 * `world` uses the rear camera; `face` uses the front camera. `body` (iOS
 * only, A12 or newer) tracks a person's 3D skeleton with the rear camera.
 */
export type XRSessionMode = 'world' | 'face' | 'body'
export type XRLightEstimationMode = 'ambient-intensity' | 'environmental-hdr'
export type XREnvironmentTexturing = 'none' | 'manual' | 'automatic'
export type XRSceneReconstruction = 'none' | 'mesh' | 'mesh-with-classification'
export type XRMeshClassification =
  'none' | 'wall' | 'floor' | 'ceiling' | 'table' | 'seat' | 'window' | 'door'
export type XRDepthFormat = 'float32-meters' | 'uint16-millimeters'
/** Why tracking is `limited` (or `unavailable`); `none` while it is `normal`. */
export type XRTrackingStateReason =
  | 'none'
  | 'initializing'
  | 'relocalizing'
  | 'excessive-motion'
  | 'insufficient-features'
  | 'insufficient-light'
  | 'camera-unavailable'
  | 'bad-state'
/**
 * iOS world sessions: how much of the surroundings ARKit has mapped. Save a
 * world map once this is `extending` or `mapped`. Always `not-available` on
 * Android and in face/body sessions.
 */
export type XRWorldMappingStatus =
  'not-available' | 'limited' | 'extending' | 'mapped'

export interface XRVector3 {
  x: number
  y: number
  z: number
}

export interface XRQuaternion {
  x: number
  y: number
  z: number
  w: number
}

export interface XRPose {
  position: XRVector3
  orientation: XRQuaternion
  matrix: number[]
}

export interface XRLightEstimate {
  /**
   * iOS: ambient intensity in lumens (about 1000 is neutral).
   * Android `ambient-intensity` mode: average pixel intensity from 0 to 1.
   */
  ambientIntensity?: number
  /** iOS only: ambient color temperature in Kelvin (6500 is neutral). */
  ambientColorTemperature?: number
  /** Android `ambient-intensity` mode: RGBA color correction, gamma space. */
  colorCorrection?: number[]
  /** Main light direction in world space, as reported by ARKit/ARCore. */
  mainLightDirection?: XRVector3
  /**
   * Main light intensity as `[r, g, b]`. Android: linear RGB.
   * iOS (face mode): the scalar intensity in lumens, repeated per channel.
   */
  mainLightIntensity?: number[]
  /**
   * 27 second-order ambient spherical-harmonics coefficients, ordered as
   * nine `[r, g, b]` triplets.
   */
  sphericalHarmonics?: number[]
}

export interface XRFrame {
  timestamp: number
  trackingState: XRTrackingState
  cameraPose: XRPose
  /** Legacy scalar light value; prefer `lightEstimate.ambientIntensity`. */
  lightIntensity?: number
  lightEstimate?: XRLightEstimate
}

export interface XRPlane {
  id: string
  alignment: XRPlaneAlignment
  classification: XRPlaneClassification
  /** Center of the plane in world space, in meters. */
  center: XRVector3
  /** Center of the plane relative to `pose` (anchor space). */
  localCenter: XRVector3
  /** Width (`x`) and depth (`z`) of the plane in its own space, in meters. */
  extent: XRVector3
  /**
   * iOS 16+: rotation of the extent rectangle around the plane's Y axis,
   * in radians, relative to `pose`. Undefined elsewhere (treat as 0).
   */
  extentRotationY?: number
  pose: XRPose
}

export interface XRHitResult {
  type: XRHitType
  distance: number
  pose: XRPose
  planeId?: string
}

export interface XRAnchor {
  id: string
  tracking: boolean
  pose: XRPose
}

export interface XRDetectionImage {
  /** Unique name reported back in `XRImageAnchor.name`. */
  name: string
  /** `file://` path, absolute path, or `http(s)://` URL of a PNG/JPEG. */
  uri: string
  /** Physical width of the printed or displayed image, in meters. */
  physicalWidthMeters: number
}

export interface XRImageAnchor {
  id: string
  name: string
  tracking: boolean
  pose: XRPose
  /** Estimated width (`x`) and height (`z`) of the image, in meters. */
  extent: XRVector3
}

export interface XRMeshClassificationCount {
  classification: XRMeshClassification
  faceCount: number
}

export interface XRMeshAnchor {
  id: string
  pose: XRPose
  vertexCount: number
  faceCount: number
  /** Faces per classification; empty unless `mesh-with-classification`. */
  classifications: XRMeshClassificationCount[]
}

export interface XRBlendShape {
  name: string
  value: number
}

export interface XRFace {
  id: string
  tracking: boolean
  pose: XRPose
  vertexCount: number
  /** iOS only: a subset of ARKit blend shapes (0 to 1). Empty on Android. */
  blendShapes: XRBlendShape[]
}

export interface XRDepthFrame {
  timestamp: number
  width: number
  height: number
  /**
   * Format of `depth`. iOS: `float32-meters`. Android: `uint16-millimeters`
   * (use `depthMeters` for float metres on both platforms).
   */
  format: XRDepthFormat
  /** Row-major, tightly packed depth samples (`width * height`). */
  depth: ArrayBuffer
  /** Optional per-pixel confidence, uint8 from 0 (low) to 255 (high). */
  confidence?: ArrayBuffer
  smoothed: boolean
  /**
   * The same depth as row-major float32 metres (`width * height`), so one
   * code path reads both platforms. iOS: the same memory as `depth`.
   * Android: ARCore 1.56+ `acquireDepthImageMeters()`; undefined when the
   * installed Google Play Services for AR cannot provide it.
   */
  depthMeters?: ArrayBuffer
}

/**
 * Pinhole intrinsics of the camera image (`getCameraImage()`), in pixels of
 * that image. Both platforms report them for the sensor's native (landscape)
 * orientation, not for the view's orientation.
 */
export interface XRCameraIntrinsics {
  fx: number
  fy: number
  cx: number
  cy: number
  imageWidth: number
  imageHeight: number
}

export interface XRCamera {
  timestamp: number
  trackingState: XRTrackingState
  trackingStateReason: XRTrackingStateReason
  pose: XRPose
  /** World-to-camera matrix for the view's current orientation, column-major. */
  viewMatrix: number[]
  /**
   * Projection matrix for the view's current size and orientation and the
   * requested clip planes, column-major (OpenGL clip space). Together with
   * `viewMatrix` it lets you draw your own content (e.g. with expo-gl / three.js)
   * aligned with the camera feed.
   */
  projectionMatrix: number[]
  intrinsics: XRCameraIntrinsics
  worldMappingStatus: XRWorldMappingStatus
}

/**
 * One CPU camera frame as 8-bit YUV 4:2:0 (NV12 layout), in the sensor's
 * native (landscape) orientation. iOS: ARKit's captured image (typically
 * 1920x1440). Android: ARCore's CPU image (typically 640x480).
 */
export interface XRCameraImage {
  timestamp: number
  width: number
  height: number
  /** Luma, `width * height` bytes, row-major, tightly packed. */
  y: ArrayBuffer
  /**
   * Chroma at half resolution in both axes, interleaved Cb, Cr:
   * `(width / 2) * (height / 2) * 2` bytes, row-major, tightly packed.
   */
  cbcr: ArrayBuffer
  intrinsics: XRCameraIntrinsics
}

/**
 * A hit test the native side repeats on its own (for example under a
 * placement reticle), so JavaScript is not called every frame. Results arrive
 * through `onHitTestResult`, at most `maxHz` times per second and only when
 * they change (moved more than 1 cm, changed surface, or found/lost).
 */
export interface XRContinuousHitTest {
  /** Normalized view coordinate from 0 (left) to 1 (right). */
  x: number
  /** Normalized view coordinate from 0 (top) to 1 (bottom). */
  y: number
  /** Maximum result rate in Hz. `0` stops the hit test. */
  maxHz: number
}

export interface XRBodyJoint {
  /** ARKit joint name, e.g. `head_joint`, `left_hand_joint`, `root`. */
  name: string
  /** Whether ARKit tracks this joint directly (otherwise it is inferred). */
  tracked: boolean
  /** Joint position in world space, in meters. */
  position: XRVector3
}

export interface XRBody {
  id: string
  tracking: boolean
  /** Pose of the body's root (hip) joint in world space. */
  pose: XRPose
  /** Ratio between the person's estimated height and ARKit's default skeleton. */
  estimatedScaleFactor: number
  /** All skeleton joints of ARKit's 3D body definition (91 joints). */
  joints: XRBodyJoint[]
}

export interface XRModelOptions {
  /**
   * `file://` path, absolute path, or `http(s)://` URL. iOS: USDZ, SCN, or
   * OBJ. Android: OBJ (also `content://` and bundled asset names), drawn with
   * a neutral lit material.
   */
  uri: string
  /** Attach the model to this anchor (from `createAnchor` or an image anchor). */
  anchorId?: string
  /** World pose, or pose relative to `anchorId` when given. */
  pose?: XRPose
  /** Uniform scale; defaults to 1. */
  scale?: number
}

export interface XRViewProps extends HybridViewProps {
  planeDetection: XRPlaneDetection
  depthEnabled: boolean
  lightEstimationEnabled: boolean
  frameCallbackFps: number
  /** Defaults to `world`. */
  mode?: XRSessionMode
  /**
   * Maximum rate of per-trackable update events (planes, images, meshes,
   * faces), in Hz. Defaults to 10. `0` disables update events.
   */
  trackableUpdateMaxHz?: number
  /** Defaults to `ambient-intensity`. */
  lightEstimationMode?: XRLightEstimationMode
  /**
   * iOS only. Defaults to `none`. In `face` mode it needs iOS 27 (automatic
   * texturing only, so `manual` behaves like `automatic`); older iOS versions
   * report it through `onError` and continue without it.
   */
  environmentTexturing?: XREnvironmentTexturing
  /** iOS only: enables smoothed scene depth alongside raw depth. */
  depthSmoothing?: boolean
  detectionImages?: XRDetectionImage[]
  /** iOS only (LiDAR). Defaults to `none`. */
  sceneReconstruction?: XRSceneReconstruction
  /**
   * iOS only (A12 or newer): hide virtual content behind people in front of
   * it, using ARKit person segmentation with depth. Defaults to `false`.
   */
  peopleOcclusion?: boolean
  /** Repeats a hit test natively and reports it through `onHitTestResult`. */
  continuousHitTest?: XRContinuousHitTest
  /**
   * Android only (ARCore Instant Placement): when `hitTest()` hits no plane,
   * depth or feature point, return an `estimated` hit about 1 m away that
   * ARCore refines later. Defaults to `false`. iOS hit tests already fall
   * back to estimated planes.
   */
  instantPlacement?: boolean
  onReady?: () => void
  onFrame?: (frame: XRFrame) => void
  onTrackingStateChange?: (state: XRTrackingState) => void
  onPlaneDetected?: (plane: XRPlane) => void
  onPlaneUpdated?: (plane: XRPlane) => void
  onPlaneRemoved?: (planeId: string) => void
  onImageAnchorAdded?: (anchor: XRImageAnchor) => void
  onImageAnchorUpdated?: (anchor: XRImageAnchor) => void
  onImageAnchorRemoved?: (anchor: XRImageAnchor) => void
  onMeshAnchorAdded?: (mesh: XRMeshAnchor) => void
  onMeshAnchorUpdated?: (mesh: XRMeshAnchor) => void
  onMeshAnchorRemoved?: (meshId: string) => void
  onFaceAdded?: (face: XRFace) => void
  onFaceUpdated?: (face: XRFace) => void
  onFaceRemoved?: (faceId: string) => void
  /** iOS `mode="body"` only. Rate-limited by `trackableUpdateMaxHz`. */
  onBodyAdded?: (body: XRBody) => void
  onBodyUpdated?: (body: XRBody) => void
  onBodyRemoved?: (bodyId: string) => void
  /**
   * Results of `continuousHitTest`, nearest first. An empty array means the
   * hit test no longer finds a surface.
   */
  onHitTestResult?: (hits: XRHitResult[]) => void
  onError?: (message: string) => void
}

export interface XRViewMethods extends HybridViewMethods {
  start(): Promise<void>
  pause(): void
  reset(): Promise<void>
  hitTest(normalizedX: number, normalizedY: number): Promise<XRHitResult[]>
  createAnchor(pose: XRPose): Promise<XRAnchor>
  removeAnchor(anchorId: string): void
  getAnchors(): XRAnchor[]
  getCameraPose(): XRPose | undefined
  /**
   * Camera of the latest frame: pose, view/projection matrices (for the given
   * clip planes, in meters), intrinsics and tracking details. Rejects before
   * the first frame.
   */
  getCamera(zNear: number, zFar: number): Promise<XRCamera>
  /** Latest CPU camera image as YUV 4:2:0 `ArrayBuffer`s. */
  getCameraImage(): Promise<XRCameraImage>
  captureSnapshot(): Promise<string>
  /** Latest depth frame. Rejects when depth is disabled or not yet available. */
  getDepthFrame(): Promise<XRDepthFrame>
  /** iOS only: writes the current LiDAR mesh to an OBJ file and returns its path. */
  exportMesh(): Promise<string>
  /**
   * iOS only (world mode): saves ARKit's current world map, including anchors
   * from `createAnchor()`, and returns the file path. Wait until
   * `getCamera().worldMappingStatus` is `extending` or `mapped`.
   */
  saveWorldMap(): Promise<string>
  /**
   * iOS only (world mode): restarts tracking from a saved world map. Once the
   * device relocalizes (tracking returns to `normal`), the saved anchors are
   * back in `getAnchors()` with their original ids. When the view is not
   * running, the map is used by the next `start()`.
   */
  loadWorldMap(path: string): Promise<void>
  /** Loads a 3D model into the scene and returns its id. */
  addModel(options: XRModelOptions): Promise<string>
  removeModel(modelId: string): void
  setModelTransform(modelId: string, pose: XRPose, scale?: number): void
}

export type XRView = HybridView<XRViewProps, XRViewMethods>
