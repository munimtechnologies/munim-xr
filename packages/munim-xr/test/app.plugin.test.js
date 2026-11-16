/**
 * Unit tests for the Expo config plugin. The `with*` mod helpers are replaced
 * by functions that run the plugin's callback on in-memory Info.plist and
 * AndroidManifest objects, so no native project is needed. The Android
 * manifest helpers (`AndroidConfig.*`) are Expo's real implementations.
 */
jest.mock('expo/config-plugins', () => {
  const actual = jest.requireActual('expo/config-plugins')
  const run = (key) => (config, action) => {
    const result = action({ ...config, modResults: config.mods[key] })
    config.mods[key] = result.modResults
    return config
  }
  return {
    ...actual,
    withInfoPlist: run('infoPlist'),
    withAndroidManifest: run('androidManifest'),
  }
})

const withMunimXr = require('../app.plugin')

function project() {
  return {
    name: 'test',
    slug: 'test',
    mods: {
      infoPlist: {},
      androidManifest: {
        manifest: {
          $: { 'xmlns:android': 'http://schemas.android.com/apk/res/android' },
          application: [{ $: { 'android:name': '.MainApplication' } }],
        },
      },
    },
  }
}

const manifestOf = (config) => config.mods.androidManifest.manifest
const arFeatures = (config) =>
  (manifestOf(config)['uses-feature'] ?? []).filter(
    (feature) => feature.$['android:name'] === 'android.hardware.camera.ar'
  )
const arMetadata = (config) =>
  (manifestOf(config).application[0]['meta-data'] ?? []).filter(
    (item) => item.$['android:name'] === 'com.google.ar.core'
  )

describe('munim-xr config plugin', () => {
  it('declares optional AR support by default', () => {
    const config = withMunimXr(project())

    expect(config.mods.infoPlist.NSCameraUsageDescription).toMatch(/camera/)
    expect(config.mods.infoPlist.UIRequiredDeviceCapabilities).toBeUndefined()

    const permissions = (manifestOf(config)['uses-permission'] ?? []).map(
      (item) => item.$['android:name']
    )
    expect(permissions).toContain('android.permission.CAMERA')
    expect(arFeatures(config)).toEqual([
      {
        $: {
          'android:name': 'android.hardware.camera.ar',
          'android:required': 'false',
        },
      },
    ])
    expect(arMetadata(config)).toEqual([
      {
        $: {
          'android:name': 'com.google.ar.core',
          'android:value': 'optional',
          'tools:replace': 'android:value',
        },
      },
    ])
    expect(manifestOf(config).$['xmlns:tools']).toBe(
      'http://schemas.android.com/tools'
    )
  })

  it('marks AR as required with arRequired: true', () => {
    const config = withMunimXr(project(), {
      arRequired: true,
      cameraPermission: 'Scan the room',
    })

    expect(config.mods.infoPlist.NSCameraUsageDescription).toBe('Scan the room')
    expect(config.mods.infoPlist.UIRequiredDeviceCapabilities).toEqual([
      'arkit',
    ])
    expect(arFeatures(config)[0].$['android:required']).toBe('true')
    expect(arMetadata(config)[0].$['android:value']).toBe('required')
  })

  it('is idempotent across repeated prebuilds', () => {
    let config = withMunimXr(project(), { arRequired: true })
    config = withMunimXr(config, { arRequired: true })
    config = withMunimXr(config, { arRequired: false })

    expect(config.mods.infoPlist.UIRequiredDeviceCapabilities).toEqual([
      'arkit',
    ])
    expect(arFeatures(config)).toHaveLength(1)
    expect(arFeatures(config)[0].$['android:required']).toBe('false')
    expect(arMetadata(config)).toHaveLength(1)
    expect(arMetadata(config)[0].$['android:value']).toBe('optional')
    const cameraPermissions = manifestOf(config)['uses-permission'].filter(
      (item) => item.$['android:name'] === 'android.permission.CAMERA'
    )
    expect(cameraPermissions).toHaveLength(1)
  })
})
