package com.margelo.nitro.munimxr

import androidx.annotation.Keep
import com.facebook.proguard.annotations.DoNotStrip
import com.google.ar.core.ArCoreApk
import com.margelo.nitro.NitroModules
import com.margelo.nitro.core.Promise

@Keep
@DoNotStrip
class HybridMunimXr : HybridMunimXrSpec() {
  override val platform: String = "arcore"
  override val sdkVersion: String = "ARCore 1.54.0"

  override fun isSupported(): Boolean {
    return when (ArCoreApk.getInstance().checkAvailability(applicationContext)) {
      ArCoreApk.Availability.SUPPORTED_INSTALLED,
      ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD,
      ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED -> true
      else -> false
    }
  }

  override fun checkAvailability(): Promise<XRAvailability> {
    return Promise.async {
      when (ArCoreApk.getInstance().checkAvailability(applicationContext)) {
        ArCoreApk.Availability.SUPPORTED_INSTALLED -> XRAvailability.SUPPORTED
        ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD -> XRAvailability.UPDATE_REQUIRED
        ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED -> XRAvailability.NOT_INSTALLED
        ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE -> XRAvailability.UNSUPPORTED
        else -> XRAvailability.UNKNOWN
      }
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

  private val applicationContext
    get() = NitroModules.applicationContext
      ?: throw IllegalStateException("No React application context is available.")
}
