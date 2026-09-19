import { PermissionsAndroid, Platform } from 'react-native'
import {
  getHostComponent,
  NitroModules,
  type HybridRef,
} from 'react-native-nitro-modules'
import XRViewConfig from '../nitrogen/generated/shared/json/XRViewConfig.json'
import type {
  MunimXr as MunimXrSpec,
  XRAvailability,
  XRFeature,
} from './specs/MunimXr.nitro'
import type {
  XRAnchor,
  XRBlendShape,
  XRDepthFormat,
  XRDepthFrame,
  XRDetectionImage,
  XREnvironmentTexturing,
  XRFace,
  XRFrame,
  XRHitResult,
  XRHitType,
  XRImageAnchor,
  XRLightEstimate,
  XRLightEstimationMode,
  XRMeshAnchor,
  XRMeshClassification,
  XRMeshClassificationCount,
  XRModelOptions,
  XRPlane,
  XRPlaneAlignment,
  XRPlaneClassification,
  XRPlaneDetection,
  XRPose,
  XRQuaternion,
  XRSceneReconstruction,
  XRSessionMode,
  XRTrackingState,
  XRVector3,
  XRViewMethods,
  XRViewProps,
} from './specs/XRView.nitro'

const MunimXr = NitroModules.createHybridObject<MunimXrSpec>('MunimXr')

export const XRView = getHostComponent<XRViewProps, XRViewMethods>(
  'XRView',
  () => XRViewConfig
)

export type XRViewRef = HybridRef<XRViewProps, XRViewMethods>

export async function requestCameraPermission(): Promise<boolean> {
  if (Platform.OS !== 'android') return true

  const result = await PermissionsAndroid.request(
    PermissionsAndroid.PERMISSIONS.CAMERA
  )
  return result === PermissionsAndroid.RESULTS.GRANTED
}

export function isSupported(): boolean {
  return MunimXr.isSupported()
}

/**
 * Without an argument, reports whether world-tracked XR can run. Pass a
 * feature to check an optional capability such as `depth` or `face-tracking`.
 *
 * On Android, feature checks briefly create an ARCore session; call them
 * before starting an `XRView`, not while one is running.
 */
export function checkAvailability(
  feature?: XRFeature
): Promise<XRAvailability> {
  // `world-tracking` is exactly what native code reported for a missing feature.
  return MunimXr.checkAvailability(feature ?? 'world-tracking')
}

export function requestInstall(): Promise<boolean> {
  return MunimXr.requestInstall()
}

export function getXRPlatform(): string {
  return MunimXr.platform
}

export function getXRSDKVersion(): string {
  return MunimXr.sdkVersion
}

export type {
  MunimXrSpec,
  XRAnchor,
  XRAvailability,
  XRBlendShape,
  XRDepthFormat,
  XRDepthFrame,
  XRDetectionImage,
  XREnvironmentTexturing,
  XRFace,
  XRFeature,
  XRFrame,
  XRHitResult,
  XRHitType,
  XRImageAnchor,
  XRLightEstimate,
  XRLightEstimationMode,
  XRMeshAnchor,
  XRMeshClassification,
  XRMeshClassificationCount,
  XRModelOptions,
  XRPlane,
  XRPlaneAlignment,
  XRPlaneClassification,
  XRPlaneDetection,
  XRPose,
  XRQuaternion,
  XRSceneReconstruction,
  XRSessionMode,
  XRTrackingState,
  XRVector3,
  XRViewMethods,
  XRViewProps,
}

export default MunimXr
