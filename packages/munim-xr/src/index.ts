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
} from './specs/MunimXr.nitro'
import type {
  XRAnchor,
  XRFrame,
  XRHitResult,
  XRHitType,
  XRPlane,
  XRPlaneAlignment,
  XRPlaneClassification,
  XRPlaneDetection,
  XRPose,
  XRQuaternion,
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

export function checkAvailability(): Promise<XRAvailability> {
  return MunimXr.checkAvailability()
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
  XRFrame,
  XRHitResult,
  XRHitType,
  XRPlane,
  XRPlaneAlignment,
  XRPlaneClassification,
  XRPlaneDetection,
  XRPose,
  XRQuaternion,
  XRTrackingState,
  XRVector3,
  XRViewMethods,
  XRViewProps,
}

export default MunimXr
