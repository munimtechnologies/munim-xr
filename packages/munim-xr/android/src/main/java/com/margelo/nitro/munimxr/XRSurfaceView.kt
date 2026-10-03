package com.margelo.nitro.munimxr

import android.content.Context
import android.content.res.Configuration
import android.opengl.GLSurfaceView

/**
 * The camera view must follow rotation and resizing without the Activity being
 * recreated: apps targeting SDK 36+ cannot lock orientation or resizability on
 * large screens (sw >= 600dp), and multi-window, desktop windowing and foldables
 * resize the view at any time. Every such change marks ARCore's display geometry
 * dirty so the next frame calls `Session.setDisplayGeometry()` with the new
 * rotation and size (180° turns change neither the size nor the configuration,
 * so the owner also listens to `DisplayManager`).
 */
internal class XRSurfaceView(
  context: Context,
  private val onGeometryChanged: () -> Unit,
) : GLSurfaceView(context) {
  override fun onConfigurationChanged(newConfig: Configuration?) {
    super.onConfigurationChanged(newConfig)
    onGeometryChanged()
  }

  override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
    super.onSizeChanged(w, h, oldw, oldh)
    onGeometryChanged()
  }

  override fun onAttachedToWindow() {
    super.onAttachedToWindow()
    // Re-attached views may now be on another display (e.g. a foldable's cover screen).
    onGeometryChanged()
  }
}
