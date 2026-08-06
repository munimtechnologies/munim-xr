const {
  AndroidConfig,
  withAndroidManifest,
  withInfoPlist,
} = require('expo/config-plugins')

function withMunimXr(config, options = {}) {
  const cameraPermission =
    options.cameraPermission ??
    'Allow $(PRODUCT_NAME) to use the camera for augmented reality.'
  const arRequired = options.arRequired === true

  config = withInfoPlist(config, (configWithInfoPlist) => {
    configWithInfoPlist.modResults.NSCameraUsageDescription = cameraPermission
    if (arRequired) {
      const capabilities = new Set(
        configWithInfoPlist.modResults.UIRequiredDeviceCapabilities ?? []
      )
      capabilities.add('arkit')
      configWithInfoPlist.modResults.UIRequiredDeviceCapabilities = [
        ...capabilities,
      ]
    }
    return configWithInfoPlist
  })

  config = withAndroidManifest(config, (configWithManifest) => {
    const manifest = configWithManifest.modResults.manifest
    AndroidConfig.Permissions.addPermission(
      configWithManifest.modResults,
      'android.permission.CAMERA'
    )

    manifest['uses-feature'] = manifest['uses-feature'] ?? []
    const arFeature = manifest['uses-feature'].find(
      (feature) => feature.$?.['android:name'] === 'android.hardware.camera.ar'
    )
    if (arFeature) {
      arFeature.$['android:required'] = String(arRequired)
    } else {
      manifest['uses-feature'].push({
        $: {
          'android:name': 'android.hardware.camera.ar',
          'android:required': String(arRequired),
        },
      })
    }

    const application = AndroidConfig.Manifest.getMainApplicationOrThrow(
      configWithManifest.modResults
    )
    application['meta-data'] = application['meta-data'] ?? []
    const arMetadata = application['meta-data'].find(
      (item) => item.$?.['android:name'] === 'com.google.ar.core'
    )
    if (arMetadata) {
      arMetadata.$['android:value'] = arRequired ? 'required' : 'optional'
    } else {
      application['meta-data'].push({
        $: {
          'android:name': 'com.google.ar.core',
          'android:value': arRequired ? 'required' : 'optional',
        },
      })
    }

    return configWithManifest
  })

  return config
}

module.exports = withMunimXr
module.exports.default = withMunimXr
