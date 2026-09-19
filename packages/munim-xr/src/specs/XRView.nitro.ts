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

/** `world` uses the rear camera; `face` uses the front camera. */
export type XRSessionMode = 'world' | 'face'
export type XRLightEstimationMode = 'ambient-intensity' | 'environmental-hdr'
export type XREnvironmentTexturing = 'none' | 'manual' | 'automatic'
export type XRSceneReconstruction = 'none' | 'mesh' | 'mesh-with-classification'
export type XRMeshClassification =
  'none' | 'wall' | 'floor' | 'ceiling' | 'table' | 'seat' | 'window' | 'door'
export type XRDepthFormat = 'float32-meters' | 'uint16-millimeters'

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
  /** iOS: `float32-meters`. Android: `uint16-millimeters`. */
  format: XRDepthFormat
  /** Row-major, tightly packed depth samples (`width * height`). */
  depth: ArrayBuffer
  /** Optional per-pixel confidence, uint8 from 0 (low) to 255 (high). */
  confidence?: ArrayBuffer
  smoothed: boolean
}

export interface XRModelOptions {
  /** `file://` path, absolute path, or `http(s)://` URL of a USDZ, SCN, or OBJ file. */
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
  /** iOS only. Defaults to `none`. */
  environmentTexturing?: XREnvironmentTexturing
  /** iOS only: enables smoothed scene depth alongside raw depth. */
  depthSmoothing?: boolean
  detectionImages?: XRDetectionImage[]
  /** iOS only (LiDAR). Defaults to `none`. */
  sceneReconstruction?: XRSceneReconstruction
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
  captureSnapshot(): Promise<string>
  /** Latest depth frame. Rejects when depth is disabled or not yet available. */
  getDepthFrame(): Promise<XRDepthFrame>
  /** iOS only: writes the current LiDAR mesh to an OBJ file and returns its path. */
  exportMesh(): Promise<string>
  /** iOS only: loads a 3D model into the scene and returns its id. */
  addModel(options: XRModelOptions): Promise<string>
  removeModel(modelId: string): void
  setModelTransform(modelId: string, pose: XRPose, scale?: number): void
}

export type XRView = HybridView<XRViewProps, XRViewMethods>
