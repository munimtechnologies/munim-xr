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

export interface XRFrame {
  timestamp: number
  trackingState: XRTrackingState
  cameraPose: XRPose
  lightIntensity?: number
}

export interface XRPlane {
  id: string
  alignment: XRPlaneAlignment
  classification: XRPlaneClassification
  center: XRVector3
  extent: XRVector3
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

export interface XRViewProps extends HybridViewProps {
  planeDetection: XRPlaneDetection
  depthEnabled: boolean
  lightEstimationEnabled: boolean
  frameCallbackFps: number
  onReady?: () => void
  onFrame?: (frame: XRFrame) => void
  onTrackingStateChange?: (state: XRTrackingState) => void
  onPlaneDetected?: (plane: XRPlane) => void
  onPlaneUpdated?: (plane: XRPlane) => void
  onPlaneRemoved?: (planeId: string) => void
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
}

export type XRView = HybridView<XRViewProps, XRViewMethods>
