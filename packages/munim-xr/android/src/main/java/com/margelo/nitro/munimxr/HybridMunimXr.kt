package com.margelo.nitro.munimxr

import androidx.annotation.Keep
import com.facebook.proguard.annotations.DoNotStrip
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.exceptions.UnsupportedConfigurationException
import com.margelo.nitro.NitroModules
import com.margelo.nitro.core.Promise
import java.util.EnumSet

@Keep
@DoNotStrip
class HybridMunimXr : HybridMunimXrSpec() {
  override val platform: String = "arcore"
  override val sdkVersion: String = "ARCore 1.56.0"

  override fun isSupported(): Boolean {
    return when (ArCoreApk.getInstance().checkAvailability(applicationContext)) {
      ArCoreApk.Availability.SUPPORTED_INSTALLED,
      ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD,
      ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED -> true
      else -> false
    }
  }

  override fun checkAvailability(feature: XRFeature?): Promise<XRAvailability> {
    return Promise.async {
      val base = when (ArCoreApk.getInstance().checkAvailability(applicationContext)) {
        ArCoreApk.Availability.SUPPORTED_INSTALLED -> XRAvailability.SUPPORTED
        ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD -> XRAvailability.UPDATE_REQUIRED
        ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED -> XRAvailability.NOT_INSTALLED
        ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE -> XRAvailability.UNSUPPORTED
        else -> XRAvailability.UNKNOWN
      }
      if (feature == null || base != XRAvailability.SUPPORTED) return@async base
      if (featureSupported(feature)) XRAvailability.SUPPORTED else XRAvailability.UNSUPPORTED
    }
  }

  override fun requestInstall(): Promise<Boolean> {
    val promise = Promise<Boolean>()
    val context = applicationContext
    context.runOnUiQueueThread {
      try {
        val activity = context.currentActivity
          ?: throw IllegalStateException("No foreground Activity is available.")
        val status = ArCoreApk.getInstance().requestInstall(activity, true)
        promise.resolve(status == ArCoreApk.InstallStatus.INSTALLED)
      } catch (error: Throwable) {
        promise.reject(error)
      }
    }
    return promise
  }

  /** Probes a short-lived, never-resumed ARCore session; the camera is not opened. */
  private fun featureSupported(feature: XRFeature): Boolean {
    return when (feature) {
      XRFeature.WORLD_TRACKING, XRFeature.IMAGE_TRACKING -> true
      // ARCore's full-resolution depth image is already temporally smoothed.
      XRFeature.DEPTH, XRFeature.SMOOTHED_DEPTH -> probe(frontCamera = false) {
        it.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
      }
      XRFeature.ENVIRONMENTAL_HDR -> probe(frontCamera = false) { session ->
        configures(session, Config(session).setLightEstimationMode(Config.LightEstimationMode.ENVIRONMENTAL_HDR))
      }
      XRFeature.FACE_TRACKING -> probe(frontCamera = true) { session ->
        configures(session, Config(session).setAugmentedFaceMode(Config.AugmentedFaceMode.MESH3D))
      }
      XRFeature.SCENE_RECONSTRUCTION, XRFeature.MESH_CLASSIFICATION, XRFeature.MODELS -> false
    }
  }

  private fun configures(session: Session, config: Config): Boolean {
    return try {
      session.configure(config)
      true
    } catch (_: UnsupportedConfigurationException) {
      false
    }
  }

  private fun probe(frontCamera: Boolean, check: (Session) -> Boolean): Boolean {
    var session: Session? = null
    return try {
      session = if (frontCamera) {
        Session(applicationContext, EnumSet.of(Session.Feature.FRONT_CAMERA))
      } else {
        Session(applicationContext)
      }
      check(session)
    } catch (_: Throwable) {
      false
    } finally {
      try { session?.close() } catch (_: Throwable) { }
    }
  }

  private val applicationContext
    get() = NitroModules.applicationContext
      ?: throw IllegalStateException("No React application context is available.")
}
