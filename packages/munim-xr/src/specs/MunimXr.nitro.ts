import type { HybridObject } from 'react-native-nitro-modules'

export type XRAvailability =
  'supported' | 'unsupported' | 'not-installed' | 'update-required' | 'unknown'

export interface MunimXr extends HybridObject<{
  ios: 'swift'
  android: 'kotlin'
}> {
  readonly platform: string
  readonly sdkVersion: string

  isSupported(): boolean
  checkAvailability(): Promise<XRAvailability>
  requestInstall(): Promise<boolean>
}
