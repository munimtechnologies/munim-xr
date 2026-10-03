/**
 * Example-only: turns on React Native's `enableExclusivePropsUpdateAndroid`
 * feature flag, which is how React Native 0.87 behaves by default. With it,
 * React Native 0.86 stops filling `Props::rawProps` in the base constructor,
 * so a Nitro Hybrid View only receives base ViewProps (opacity, transform,
 * testID, accessibility…) on Android if munim-xr's margelo/nitro#1656
 * workaround works. This keeps the example a regression check for that path.
 * Remove once the example moves to React Native 0.87.
 */
const { withMainApplication } = require('expo/config-plugins')

const MARKER = 'munim-xr example: enableExclusivePropsUpdateAndroid'

module.exports = function withExclusivePropsUpdate(config) {
  return withMainApplication(config, (mod) => {
    let source = mod.modResults.contents
    if (source.includes(MARKER)) return mod
    if (mod.modResults.language !== 'kt') {
      throw new Error('withExclusivePropsUpdate: expected a Kotlin MainApplication')
    }
    const anchor = '    loadReactNative(this)\n'
    if (!source.includes(anchor)) {
      throw new Error('withExclusivePropsUpdate: loadReactNative(this) not found in MainApplication')
    }
    source = source.replace(
      anchor,
      `${anchor}    // ${MARKER} (see example/plugins/withExclusivePropsUpdate.js)
    val accessedFlags = com.facebook.react.internal.featureflags.ReactNativeFeatureFlags.dangerouslyForceOverride(
      object : com.facebook.react.internal.featureflags.ReactNativeFeatureFlagsProvider by
        com.facebook.react.internal.featureflags.ReactNativeFeatureFlagsOverrides_RNOSS_Stable_Android() {
        override fun enableExclusivePropsUpdateAndroid(): Boolean = true
      }
    )
    check(accessedFlags == null) { "Feature flags were read before the override: $accessedFlags" }
`
    )
    mod.modResults.contents = source
    return mod
  })
}
