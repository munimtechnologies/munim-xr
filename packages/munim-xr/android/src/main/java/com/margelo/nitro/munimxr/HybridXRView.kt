package com.margelo.nitro.munimxr

import android.Manifest
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.annotation.Keep
import androidx.core.content.ContextCompat
import com.facebook.proguard.annotations.DoNotStrip
import com.facebook.react.bridge.LifecycleEventListener
import com.facebook.react.uimanager.ThemedReactContext
import com.google.ar.core.Anchor
import com.google.ar.core.ArCoreApk
import com.google.ar.core.AugmentedFace
import com.google.ar.core.AugmentedImage
import com.google.ar.core.AugmentedImageDatabase
import com.google.ar.core.Config
import com.google.ar.core.Coordinates2d
import com.google.ar.core.DepthPoint
import com.google.ar.core.Frame
import com.google.ar.core.InstantPlacementPoint
import com.google.ar.core.LightEstimate
import com.google.ar.core.Plane
import com.google.ar.core.Point
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.ImageInsufficientQualityException
import com.google.ar.core.exceptions.NotYetAvailableException
import com.google.ar.core.exceptions.UnsupportedConfigurationException
import com.margelo.nitro.core.ArrayBuffer
import com.margelo.nitro.core.Promise
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.Collections
import java.util.EnumSet
import java.util.IdentityHashMap
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs

@Keep
@DoNotStrip
class HybridXRView(
  private val context: ThemedReactContext,
) : HybridXRViewSpec(), GLSurfaceView.Renderer, LifecycleEventListener {
  override val view: GLSurfaceView = GLSurfaceView(context).apply {
    setEGLContextClientVersion(2)
    preserveEGLContextOnPause = true
    setRenderer(this@HybridXRView)
    renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
  }

  override var planeDetection: XRPlaneDetection = XRPlaneDetection.BOTH
  override var depthEnabled: Boolean = false
  override var lightEstimationEnabled: Boolean = true
  override var frameCallbackFps: Double = 15.0
  override var mode: XRSessionMode? = null
  override var trackableUpdateMaxHz: Double? = null
  override var lightEstimationMode: XRLightEstimationMode? = null
  override var environmentTexturing: XREnvironmentTexturing? = null
  override var depthSmoothing: Boolean? = null
  override var detectionImages: Array<XRDetectionImage>? = null
  override var sceneReconstruction: XRSceneReconstruction? = null
  override var onReady: (() -> Unit)? = null
  override var onFrame: ((frame: XRFrame) -> Unit)? = null
  override var onTrackingStateChange: ((state: XRTrackingState) -> Unit)? = null
  override var onPlaneDetected: ((plane: XRPlane) -> Unit)? = null
  override var onPlaneUpdated: ((plane: XRPlane) -> Unit)? = null
  override var onPlaneRemoved: ((planeId: String) -> Unit)? = null
  override var onImageAnchorAdded: ((anchor: XRImageAnchor) -> Unit)? = null
  override var onImageAnchorUpdated: ((anchor: XRImageAnchor) -> Unit)? = null
  override var onImageAnchorRemoved: ((anchor: XRImageAnchor) -> Unit)? = null
  override var onMeshAnchorAdded: ((mesh: XRMeshAnchor) -> Unit)? = null
  override var onMeshAnchorUpdated: ((mesh: XRMeshAnchor) -> Unit)? = null
  override var onMeshAnchorRemoved: ((meshId: String) -> Unit)? = null
  override var onFaceAdded: ((face: XRFace) -> Unit)? = null
  override var onFaceUpdated: ((face: XRFace) -> Unit)? = null
  override var onFaceRemoved: ((faceId: String) -> Unit)? = null
  override var onError: ((message: String) -> Unit)? = null

  @Volatile private var session: Session? = null
  @Volatile private var latestFrame: Frame? = null
  @Volatile private var latestCameraPose: XRPose? = null
  @Volatile private var running = false
  @Volatile private var displayGeometryDirty = true
  @Volatile private var anchorSnapshot: Map<String, XRAnchor> = emptyMap()
  private var pausedByHost = false
  private var cameraTextureId = 0
  private var shaderProgram = 0
  @Volatile private var surfaceWidth = 0
  @Volatile private var surfaceHeight = 0
  private var deliveredReady = false
  private var lastFrameCallbackTimestamp = 0.0
  private var lastTrackingState = XRTrackingState.UNAVAILABLE
  private var configuredKey: ConfigKey? = null
  private var sessionMode: XRSessionMode? = null

  // GL-thread only (and the UI thread while the GL thread is paused).
  private val planeIds = IdentityHashMap<Plane, String>()
  private val removedPlanes = Collections.newSetFromMap(IdentityHashMap<Plane, Boolean>())
  private val imageIds = IdentityHashMap<AugmentedImage, String>()
  private val removedImages = Collections.newSetFromMap(IdentityHashMap<AugmentedImage, Boolean>())
  private val faceIds = IdentityHashMap<AugmentedFace, String>()
  private val removedFaces = Collections.newSetFromMap(IdentityHashMap<AugmentedFace, Boolean>())
  private val updateThrottle = HashMap<String, ThrottleEntry>()
  private val nextTrackableId = AtomicLong(1)

  private val anchors = Collections.synchronizedMap(mutableMapOf<String, Anchor>())

  // Detection images: loaded off the UI thread, consumed in configure().
  private val imageLock = Any()
  private var loadedImageSpecs: List<ImageSpec>? = null
  private var loadedImages: List<LoadedImage> = emptyList()
  private var imagesVersion = 0
  private var imageLoadGeneration = 0

  private val displayManager = context.getSystemService(DisplayManager::class.java)
  private val displayListener = object : DisplayManager.DisplayListener {
    override fun onDisplayAdded(displayId: Int) = Unit
    override fun onDisplayRemoved(displayId: Int) = Unit
    override fun onDisplayChanged(displayId: Int) {
      // Fires on 180° rotations too, which do not trigger onSurfaceChanged.
      displayGeometryDirty = true
    }
  }

  private val quadCoordinates = floatBufferOf(
    -1f, -1f,
    1f, -1f,
    -1f, 1f,
    1f, 1f,
  )
  private val textureCoordinates = floatBufferOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)

  init {
    context.addLifecycleEventListener(this)
    displayManager?.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
  }

  override fun afterUpdate() {
    if (detectionImagesChanged()) loadDetectionImagesAsync()
    if (running && configuredKey != currentConfigKey()) reconfigureSession()
  }

  // region Lifecycle

  override fun onHostResume() {
    if (!pausedByHost) return
    pausedByHost = false
    val activeSession = session ?: return
    try {
      activeSession.resume()
      view.onResume()
      displayGeometryDirty = true
      running = true
    } catch (error: Throwable) {
      emitError(error)
    }
  }

  override fun onHostPause() {
    if (!running) return
    pausedByHost = true
    running = false
    try { view.onPause() } catch (_: Throwable) { }
    try { session?.pause() } catch (_: Throwable) { }
  }

  override fun onHostDestroy() {
    closeSession()
  }

  override fun onDropView() {
    context.removeLifecycleEventListener(this)
    displayManager?.unregisterDisplayListener(displayListener)
    context.runOnUiQueueThread { closeSession() }
  }

  // endregion

  override fun start(): Promise<Unit> {
    val promise = Promise<Unit>()
    val specs = currentImageSpecs()
    Thread {
      try {
        ensureImagesLoaded(specs)
      } catch (error: Throwable) {
        emitError(error)
      }
      context.runOnUiQueueThread {
        try {
          startOnUiThread()
          promise.resolve(Unit)
        } catch (error: Throwable) {
          promise.reject(error)
          emitError(error)
        }
      }
    }.start()
    return promise
  }

  override fun pause() {
    context.runOnUiQueueThread {
      try {
        // Stop the render loop before pausing ARCore: onDrawFrame calling
        // Session.update() on a paused session throws and would surface a
        // spurious onError for a pause the app asked for.
        running = false
        pausedByHost = false
        view.onPause()
        session?.pause()
      } catch (error: Throwable) {
        emitError(error)
      }
    }
  }

  override fun reset(): Promise<Unit> {
    val promise = Promise<Unit>()
    context.runOnUiQueueThread {
      try {
        closeSession()
        startOnUiThread()
        promise.resolve(Unit)
      } catch (error: Throwable) {
        promise.reject(error)
        emitError(error)
      }
    }
    return promise
  }

  override fun hitTest(normalizedX: Double, normalizedY: Double): Promise<Array<XRHitResult>> {
    require(normalizedX.isFinite() && normalizedY.isFinite() && normalizedX in 0.0..1.0 && normalizedY in 0.0..1.0) {
      "Hit-test coordinates must be finite values between 0 and 1."
    }
    val promise = Promise<Array<XRHitResult>>()
    view.queueEvent {
      try {
        val frame = latestFrame ?: throw IllegalStateException("No XR frame is available yet.")
        val x = (normalizedX * surfaceWidth).toFloat()
        val y = (normalizedY * surfaceHeight).toFloat()
        val results = frame.hitTest(x, y).map { hit ->
          val trackable = hit.trackable
          val type = when (trackable) {
            is Plane -> XRHitType.PLANE
            is DepthPoint -> XRHitType.DEPTH
            is Point -> XRHitType.FEATURE_POINT
            is InstantPlacementPoint -> XRHitType.ESTIMATED
            else -> XRHitType.ESTIMATED
          }
          XRHitResult(
            type = type,
            distance = hit.distance.toDouble(),
            pose = xrPose(hit.hitPose),
            planeId = (trackable as? Plane)?.let(::planeId),
          )
        }.toTypedArray()
        promise.resolve(results)
      } catch (error: Throwable) {
        promise.reject(error)
      }
    }
    return promise
  }

  override fun createAnchor(pose: XRPose): Promise<XRAnchor> {
    validatePose(pose)
    val promise = Promise<XRAnchor>()
    view.queueEvent {
      try {
        val activeSession = session ?: throw IllegalStateException("The XR session is not running.")
        val nativeAnchor = activeSession.createAnchor(nativePose(pose))
        val id = "anchor-${UUID.randomUUID()}"
        anchors[id] = nativeAnchor
        val anchor = xrAnchor(id, nativeAnchor)
        anchorSnapshot = anchorSnapshot + (id to anchor)
        promise.resolve(anchor)
      } catch (error: Throwable) {
        promise.reject(error)
      }
    }
    return promise
  }

  override fun removeAnchor(anchorId: String) {
    val anchor = anchors.remove(anchorId) ?: return
    anchorSnapshot = anchorSnapshot - anchorId
    view.queueEvent { anchor.detach() }
  }

  /** Returns poses captured on the GL thread after the most recent `Session.update()`. */
  override fun getAnchors(): Array<XRAnchor> {
    val snapshot = anchorSnapshot
    val liveIds = synchronized(anchors) { anchors.keys.toSet() }
    return snapshot.filterKeys { it in liveIds }.values.toTypedArray()
  }

  override fun getCameraPose(): XRPose? = latestCameraPose

  override fun captureSnapshot(): Promise<String> {
    val promise = Promise<String>()
    context.runOnUiQueueThread {
      try {
        require(view.width > 0 && view.height > 0) { "The XR view has no drawable size." }
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        PixelCopy.request(
          view,
          bitmap,
          { result ->
            if (result != PixelCopy.SUCCESS) {
              bitmap.recycle()
              promise.reject(IllegalStateException("PixelCopy failed with code $result."))
              return@request
            }
            try {
              val file = outputFile("snapshots", "png", keep = 10)
              FileOutputStream(file).use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                  "The XR snapshot could not be encoded as PNG."
                }
              }
              promise.resolve(file.absolutePath)
            } catch (error: Throwable) {
              promise.reject(error)
            } finally {
              bitmap.recycle()
            }
          },
          Handler(Looper.getMainLooper()),
        )
      } catch (error: Throwable) {
        promise.reject(error)
      }
    }
    return promise
  }

  override fun getDepthFrame(): Promise<XRDepthFrame> {
    val promise = Promise<XRDepthFrame>()
    if (!depthEnabled) {
      promise.reject(IllegalStateException("Depth is disabled. Set depthEnabled to true before calling getDepthFrame()."))
      return promise
    }
    view.queueEvent {
      try {
        val activeSession = session ?: throw IllegalStateException("The XR session is not running.")
        val frame = latestFrame ?: throw IllegalStateException("No depth frame is available yet.")
        if (activeSession.config.depthMode == Config.DepthMode.DISABLED) {
          throw UnsupportedOperationException("This device does not support the ARCore Depth API.")
        }
        promise.resolve(readDepthFrame(frame))
      } catch (_: NotYetAvailableException) {
        promise.reject(IllegalStateException("No depth frame is available yet."))
      } catch (error: Throwable) {
        promise.reject(error)
      }
    }
    return promise
  }

  override fun exportMesh(): Promise<String> {
    return Promise.rejected(
      UnsupportedOperationException("exportMesh() requires LiDAR scene reconstruction, which is iOS-only."),
    )
  }

  override fun addModel(options: XRModelOptions): Promise<String> {
    return Promise.rejected(UnsupportedOperationException(MODELS_UNSUPPORTED))
  }

  override fun removeModel(modelId: String) {
    throw UnsupportedOperationException(MODELS_UNSUPPORTED)
  }

  override fun setModelTransform(modelId: String, pose: XRPose, scale: Double?) {
    throw UnsupportedOperationException(MODELS_UNSUPPORTED)
  }

  // region GLSurfaceView.Renderer

  override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
    cameraTextureId = createExternalTexture()
    shaderProgram = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
    session?.setCameraTextureNames(intArrayOf(cameraTextureId))
    GLES20.glClearColor(0f, 0f, 0f, 1f)
  }

  override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
    surfaceWidth = width
    surfaceHeight = height
    GLES20.glViewport(0, 0, width, height)
    displayGeometryDirty = true
  }

  override fun onDrawFrame(gl: GL10?) {
    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
    val activeSession = session ?: return
    if (!running || cameraTextureId == 0) return

    try {
      if (displayGeometryDirty && surfaceWidth > 0 && surfaceHeight > 0) {
        displayGeometryDirty = false
        activeSession.setDisplayGeometry(view.display?.rotation ?: 0, surfaceWidth, surfaceHeight)
      }
      activeSession.setCameraTextureNames(intArrayOf(cameraTextureId))
      val frame = activeSession.update()
      latestFrame = frame
      frame.transformCoordinates2d(
        Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,
        quadCoordinates,
        Coordinates2d.TEXTURE_NORMALIZED,
        textureCoordinates,
      )
      drawCameraBackground()
      snapshotAnchors()
      deliverFrame(frame)
      val now = System.nanoTime() / 1_000_000_000.0
      deliverPlaneChanges(frame, now)
      deliverImageChanges(frame, now)
      deliverFaceChanges(frame, now)
    } catch (error: Throwable) {
      running = false
      emitError(error)
    }
  }

  // endregion

  // region Session

  private fun startOnUiThread() {
    check(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
      "Camera permission is required. Call requestCameraPermission() before XRView.start()."
    }
    val activity = context.currentActivity
      ?: throw IllegalStateException("No foreground Activity is available.")
    val installStatus = ArCoreApk.getInstance().requestInstall(activity, true)
    check(installStatus == ArCoreApk.InstallStatus.INSTALLED) {
      "Google Play Services for AR installation was requested. Call start() again after returning to the app."
    }

    val requestedMode = mode ?: XRSessionMode.WORLD
    if (session != null && sessionMode != requestedMode) closeSession()
    val activeSession = session ?: createSession(activity, requestedMode).also {
      session = it
      sessionMode = requestedMode
    }
    configure(activeSession)
    if (cameraTextureId != 0) activeSession.setCameraTextureNames(intArrayOf(cameraTextureId))
    displayGeometryDirty = true
    activeSession.resume()
    view.onResume()
    pausedByHost = false
    running = true
    if ((sceneReconstruction ?: XRSceneReconstruction.NONE) != XRSceneReconstruction.NONE) {
      onError?.invoke("sceneReconstruction is iOS-only (LiDAR) and is ignored on Android.")
    }
  }

  private fun createSession(activity: android.app.Activity, sessionMode: XRSessionMode): Session {
    return when (sessionMode) {
      XRSessionMode.FACE -> Session(activity, EnumSet.of(Session.Feature.FRONT_CAMERA))
      XRSessionMode.WORLD -> Session(activity)
    }
  }

  private fun configure(activeSession: Session) {
    val key = currentConfigKey()
    val config = Config(activeSession)
    val face = sessionMode == XRSessionMode.FACE
    config.planeFindingMode = if (face) {
      Config.PlaneFindingMode.DISABLED
    } else {
      when (planeDetection) {
        XRPlaneDetection.NONE -> Config.PlaneFindingMode.DISABLED
        XRPlaneDetection.HORIZONTAL -> Config.PlaneFindingMode.HORIZONTAL
        XRPlaneDetection.VERTICAL -> Config.PlaneFindingMode.VERTICAL
        XRPlaneDetection.BOTH -> Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
      }
    }
    config.lightEstimationMode = when {
      !lightEstimationEnabled -> Config.LightEstimationMode.DISABLED
      lightEstimationMode == XRLightEstimationMode.ENVIRONMENTAL_HDR -> Config.LightEstimationMode.ENVIRONMENTAL_HDR
      else -> Config.LightEstimationMode.AMBIENT_INTENSITY
    }
    config.depthMode = if (
      !face && depthEnabled && activeSession.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
    ) {
      Config.DepthMode.AUTOMATIC
    } else {
      Config.DepthMode.DISABLED
    }
    config.augmentedFaceMode = if (face) Config.AugmentedFaceMode.MESH3D else Config.AugmentedFaceMode.DISABLED
    if (!face) {
      val images = synchronized(imageLock) { loadedImages }
      if (images.isNotEmpty()) config.augmentedImageDatabase = buildImageDatabase(activeSession, images)
    }

    // Degrade gracefully when the camera (e.g. the front camera) cannot provide
    // a requested feature, instead of failing the whole session.
    val fallbacks = listOf<Pair<(Config) -> Boolean, (Config) -> Unit>>(
      { c: Config -> c.lightEstimationMode == Config.LightEstimationMode.ENVIRONMENTAL_HDR } to { c: Config ->
        c.lightEstimationMode = Config.LightEstimationMode.AMBIENT_INTENSITY
        onError?.invoke("Environmental HDR light estimation is not supported with this camera; using ambient intensity.")
      },
      { c: Config -> c.lightEstimationMode != Config.LightEstimationMode.DISABLED } to { c: Config ->
        c.lightEstimationMode = Config.LightEstimationMode.DISABLED
        onError?.invoke("Light estimation is not supported with this camera; it is disabled.")
      },
      { c: Config -> c.depthMode != Config.DepthMode.DISABLED } to { c: Config ->
        c.depthMode = Config.DepthMode.DISABLED
      },
    )
    var remaining = fallbacks
    while (true) {
      try {
        activeSession.configure(config)
        break
      } catch (error: UnsupportedConfigurationException) {
        val next = remaining.indexOfFirst { (applies, _) -> applies(config) }
        if (next < 0) throw error
        remaining[next].second(config)
        remaining = remaining.drop(next + 1)
      }
    }
    configuredKey = key
  }

  private fun buildImageDatabase(activeSession: Session, images: List<LoadedImage>): AugmentedImageDatabase {
    val database = AugmentedImageDatabase(activeSession)
    for (image in images) {
      try {
        database.addImage(image.name, image.bitmap, image.physicalWidthMeters.toFloat())
      } catch (error: ImageInsufficientQualityException) {
        onError?.invoke("Detection image \"${image.name}\" was skipped: not enough visual features to track.")
      } catch (error: Throwable) {
        onError?.invoke("Detection image \"${image.name}\" was skipped: ${error.message ?: error.javaClass.simpleName}")
      }
    }
    return database
  }

  private fun currentConfigKey() = ConfigKey(
    mode = mode ?: XRSessionMode.WORLD,
    planeDetection = planeDetection,
    depthEnabled = depthEnabled,
    lightEstimationEnabled = lightEstimationEnabled,
    lightEstimationMode = lightEstimationMode ?: XRLightEstimationMode.AMBIENT_INTENSITY,
    imagesVersion = synchronized(imageLock) { imagesVersion },
  )

  private fun reconfigureSession() {
    context.runOnUiQueueThread {
      val activeSession = session ?: return@runOnUiQueueThread
      if (!running) return@runOnUiQueueThread
      try {
        if (sessionMode != (mode ?: XRSessionMode.WORLD)) {
          // Front/rear camera switches need a new Session with a different feature set.
          closeSession()
          startOnUiThread()
          return@runOnUiQueueThread
        }
        running = false
        view.onPause()
        activeSession.pause()
        configure(activeSession)
        activeSession.resume()
        view.onResume()
        running = true
      } catch (error: Throwable) {
        emitError(error)
      }
    }
  }

  private fun closeSession() {
    running = false
    pausedByHost = false
    // GLSurfaceView.onPause() blocks until the GL thread has paused, so no
    // onDrawFrame() is in flight while the session and trackables are released.
    try { view.onPause() } catch (_: Throwable) { }
    try { session?.pause() } catch (_: Throwable) { }
    synchronized(anchors) {
      anchors.values.forEach { it.detach() }
      anchors.clear()
    }
    planeIds.clear()
    removedPlanes.clear()
    imageIds.clear()
    removedImages.clear()
    faceIds.clear()
    removedFaces.clear()
    updateThrottle.clear()
    try { session?.close() } catch (_: Throwable) { }
    session = null
    sessionMode = null
    configuredKey = null
    latestFrame = null
    latestCameraPose = null
    anchorSnapshot = emptyMap()
    deliveredReady = false
  }

  // endregion

  // region Detection images

  private fun currentImageSpecs(): List<ImageSpec> =
    detectionImages?.map { ImageSpec(it.name, it.uri, it.physicalWidthMeters) } ?: emptyList()

  private fun detectionImagesChanged(): Boolean =
    (synchronized(imageLock) { loadedImageSpecs } ?: emptyList()) != currentImageSpecs()

  private fun loadDetectionImagesAsync() {
    val specs = currentImageSpecs()
    val generation = synchronized(imageLock) { ++imageLoadGeneration }
    Thread {
      try {
        val images = specs.mapNotNull(::loadImage)
        synchronized(imageLock) {
          if (generation != imageLoadGeneration) return@Thread
          commitImages(specs, images)
        }
        if (running) reconfigureSession()
      } catch (error: Throwable) {
        emitError(error)
      }
    }.start()
  }

  /** Blocking; call off the UI thread. */
  private fun ensureImagesLoaded(specs: List<ImageSpec>) {
    if ((synchronized(imageLock) { loadedImageSpecs } ?: emptyList()) == specs) return
    val images = specs.mapNotNull(::loadImage)
    synchronized(imageLock) { commitImages(specs, images) }
  }

  private fun commitImages(specs: List<ImageSpec>, images: List<LoadedImage>) {
    loadedImageSpecs = specs
    loadedImages = images
    imagesVersion += 1
  }

  private fun loadImage(spec: ImageSpec): LoadedImage? {
    require(spec.physicalWidthMeters.isFinite() && spec.physicalWidthMeters > 0) {
      "Detection image \"${spec.name}\" needs a physicalWidthMeters greater than 0."
    }
    val bitmap = XRFiles.loadBitmap(context, spec.uri)
    if (bitmap == null) {
      onError?.invoke("Detection image \"${spec.name}\" could not be decoded from ${spec.uri}.")
      return null
    }
    return LoadedImage(spec.name, bitmap, spec.physicalWidthMeters)
  }

  // endregion

  // region Frame delivery (GL thread)

  private fun snapshotAnchors() {
    if (anchors.isEmpty()) {
      if (anchorSnapshot.isNotEmpty()) anchorSnapshot = emptyMap()
      return
    }
    anchorSnapshot = synchronized(anchors) {
      anchors.mapValues { (id, anchor) -> xrAnchor(id, anchor) }
    }
  }

  private fun deliverFrame(frame: Frame) {
    val camera = frame.camera
    val trackingState = when (camera.trackingState) {
      TrackingState.TRACKING -> XRTrackingState.NORMAL
      TrackingState.PAUSED -> XRTrackingState.LIMITED
      TrackingState.STOPPED -> XRTrackingState.UNAVAILABLE
    }
    val cameraPose = xrPose(camera.pose)
    latestCameraPose = cameraPose
    if (trackingState != lastTrackingState) {
      lastTrackingState = trackingState
      onTrackingStateChange?.invoke(trackingState)
    }
    if (!deliveredReady) {
      deliveredReady = true
      onReady?.invoke()
    }

    val timestampSeconds = frame.timestamp / 1_000_000_000.0
    val interval = if (frameCallbackFps > 0) 1.0 / frameCallbackFps else Double.POSITIVE_INFINITY
    if (timestampSeconds - lastFrameCallbackTimestamp < interval) return
    lastFrameCallbackTimestamp = timestampSeconds
    val lightEstimate = if (lightEstimationEnabled) xrLightEstimate(frame.lightEstimate) else null
    val legacyIntensity = if (
      lightEstimationEnabled && lightEstimationMode != XRLightEstimationMode.ENVIRONMENTAL_HDR
    ) lightEstimate?.ambientIntensity else null
    onFrame?.invoke(XRFrame(timestampSeconds, trackingState, cameraPose, legacyIntensity, lightEstimate))
  }

  private fun xrLightEstimate(estimate: LightEstimate): XRLightEstimate? {
    if (estimate.state != LightEstimate.State.VALID) return null
    val mode = session?.config?.lightEstimationMode
    return if (mode == Config.LightEstimationMode.ENVIRONMENTAL_HDR) {
      val direction = estimate.environmentalHdrMainLightDirection
      XRLightEstimate(
        ambientIntensity = null,
        ambientColorTemperature = null,
        colorCorrection = null,
        mainLightDirection = XRVector3(direction[0].toDouble(), direction[1].toDouble(), direction[2].toDouble()),
        mainLightIntensity = estimate.environmentalHdrMainLightIntensity.toDoubleArray(),
        sphericalHarmonics = estimate.environmentalHdrAmbientSphericalHarmonics.toDoubleArray(),
      )
    } else {
      val correction = FloatArray(4)
      estimate.getColorCorrection(correction, 0)
      XRLightEstimate(
        ambientIntensity = estimate.pixelIntensity.toDouble(),
        ambientColorTemperature = null,
        colorCorrection = correction.toDoubleArray(),
        mainLightDirection = null,
        mainLightIntensity = null,
        sphericalHarmonics = null,
      )
    }
  }

  private fun deliverPlaneChanges(frame: Frame, now: Double) {
    frame.getUpdatedTrackables(Plane::class.java).forEach { plane ->
      val known = planeIds.containsKey(plane)
      val id = planeId(plane)
      if (plane.trackingState == TrackingState.STOPPED || plane.subsumedBy != null) {
        if (removedPlanes.add(plane)) {
          updateThrottle.remove(id)
          onPlaneRemoved?.invoke(id)
        }
      } else if (known) {
        if (onPlaneUpdated == null) return@forEach
        val xrPlane = xrPlane(plane)
        if (shouldEmitUpdate(id, planeSignature(xrPlane), now)) onPlaneUpdated?.invoke(xrPlane)
      } else {
        val xrPlane = xrPlane(plane)
        updateThrottle[id] = ThrottleEntry(now, planeSignature(xrPlane))
        onPlaneDetected?.invoke(xrPlane)
      }
    }
  }

  private fun deliverImageChanges(frame: Frame, now: Double) {
    frame.getUpdatedTrackables(AugmentedImage::class.java).forEach { image ->
      val known = imageIds.containsKey(image)
      val id = imageIds.getOrPut(image) { "image-${nextTrackableId.getAndIncrement()}" }
      when {
        image.trackingState == TrackingState.STOPPED -> {
          if (known && removedImages.add(image)) {
            updateThrottle.remove(id)
            onImageAnchorRemoved?.invoke(xrImageAnchor(id, image))
          }
        }
        !known -> {
          val anchor = xrImageAnchor(id, image)
          updateThrottle[id] = ThrottleEntry(now, imageSignature(anchor))
          onImageAnchorAdded?.invoke(anchor)
        }
        else -> {
          if (onImageAnchorUpdated == null) return@forEach
          val anchor = xrImageAnchor(id, image)
          if (shouldEmitUpdate(id, imageSignature(anchor), now, forceIndex = 0)) {
            onImageAnchorUpdated?.invoke(anchor)
          }
        }
      }
    }
  }

  private fun deliverFaceChanges(frame: Frame, now: Double) {
    if (sessionMode != XRSessionMode.FACE) return
    frame.getUpdatedTrackables(AugmentedFace::class.java).forEach { face ->
      val known = faceIds.containsKey(face)
      val id = faceIds.getOrPut(face) { "face-${nextTrackableId.getAndIncrement()}" }
      when {
        face.trackingState == TrackingState.STOPPED -> {
          if (known && removedFaces.add(face)) {
            updateThrottle.remove(id)
            onFaceRemoved?.invoke(id)
          }
        }
        !known -> {
          val xrFace = xrFace(id, face)
          updateThrottle[id] = ThrottleEntry(now, faceSignature(xrFace))
          onFaceAdded?.invoke(xrFace)
        }
        else -> {
          if (onFaceUpdated == null) return@forEach
          val xrFace = xrFace(id, face)
          if (shouldEmitUpdate(id, faceSignature(xrFace), now, forceIndex = 0)) {
            onFaceUpdated?.invoke(xrFace)
          }
        }
      }
    }
  }

  private fun shouldEmitUpdate(
    id: String,
    signature: DoubleArray,
    now: Double,
    tolerance: Double = 0.01,
    forceIndex: Int? = null,
  ): Boolean {
    val maxHz = trackableUpdateMaxHz ?: DEFAULT_TRACKABLE_UPDATE_HZ
    if (maxHz <= 0) return false
    val last = updateThrottle[id]
    if (last != null) {
      // Discrete changes (tracking gained/lost) skip the rate limit.
      val forced = forceIndex != null &&
        forceIndex < last.signature.size &&
        forceIndex < signature.size &&
        last.signature[forceIndex] != signature[forceIndex]
      if (!forced) {
        if (now - last.time < 1.0 / maxHz) return false
        if (!changed(last.signature, signature, tolerance)) return false
      }
    }
    updateThrottle[id] = ThrottleEntry(now, signature)
    return true
  }

  // endregion

  // region Depth (GL thread)

  private fun readDepthFrame(frame: Frame): XRDepthFrame {
    frame.acquireDepthImage16Bits().use { depthImage ->
      val width = depthImage.width
      val height = depthImage.height
      val plane = depthImage.planes[0]
      val depth = copyPlane(plane.buffer, plane.rowStride, plane.pixelStride, width, height, bytesPerPixel = 2)

      var confidence: ArrayBuffer? = null
      try {
        frame.acquireRawDepthConfidenceImage().use { confidenceImage ->
          if (confidenceImage.width == width && confidenceImage.height == height) {
            val confidencePlane = confidenceImage.planes[0]
            confidence = copyPlane(
              confidencePlane.buffer,
              confidencePlane.rowStride,
              confidencePlane.pixelStride,
              width,
              height,
              bytesPerPixel = 1,
            )
          }
        }
      } catch (_: Throwable) {
        // Confidence is optional; raw depth may not be ready on this frame.
      }

      return XRDepthFrame(
        timestamp = depthImage.timestamp / 1_000_000_000.0,
        width = width.toDouble(),
        height = height.toDouble(),
        format = XRDepthFormat.UINT16_MILLIMETERS,
        depth = depth,
        confidence = confidence,
        smoothed = true,
      )
    }
  }

  private fun copyPlane(
    source: ByteBuffer,
    rowStride: Int,
    pixelStride: Int,
    width: Int,
    height: Int,
    bytesPerPixel: Int,
  ): ArrayBuffer {
    val arrayBuffer = ArrayBuffer.allocate(width * height * bytesPerPixel)
    val target = arrayBuffer.getBuffer(false).order(ByteOrder.nativeOrder())
    target.clear()
    val input = source.duplicate().order(ByteOrder.nativeOrder())
    val rowBytes = width * bytesPerPixel
    if (pixelStride == bytesPerPixel) {
      val row = ByteArray(rowBytes)
      for (y in 0 until height) {
        input.position(y * rowStride)
        input.get(row, 0, rowBytes)
        target.put(row)
      }
    } else {
      for (y in 0 until height) {
        for (x in 0 until width) {
          val offset = y * rowStride + x * pixelStride
          for (b in 0 until bytesPerPixel) target.put(input.get(offset + b))
        }
      }
    }
    return arrayBuffer
  }

  // endregion

  // region Conversions

  private fun planeId(plane: Plane): String {
    return planeIds.getOrPut(plane) { "plane-${nextTrackableId.getAndIncrement()}" }
  }

  private fun xrPlane(plane: Plane): XRPlane {
    val alignment = when (plane.type) {
      Plane.Type.VERTICAL -> XRPlaneAlignment.VERTICAL
      Plane.Type.HORIZONTAL_UPWARD_FACING,
      Plane.Type.HORIZONTAL_DOWNWARD_FACING -> XRPlaneAlignment.HORIZONTAL
    }
    // ARCore planes carry no semantic label (only facing direction), so report
    // `unknown` instead of guessing floor/wall/ceiling from orientation.
    val center = plane.centerPose
    return XRPlane(
      id = planeId(plane),
      alignment = alignment,
      classification = XRPlaneClassification.UNKNOWN,
      center = XRVector3(
        center.tx().toDouble(),
        center.ty().toDouble(),
        center.tz().toDouble(),
      ),
      // `pose` is the plane's center pose, so the center is its local origin.
      localCenter = XRVector3(0.0, 0.0, 0.0),
      extent = XRVector3(plane.extentX.toDouble(), 0.0, plane.extentZ.toDouble()),
      extentRotationY = null,
      pose = xrPose(center),
    )
  }

  private fun xrImageAnchor(id: String, image: AugmentedImage): XRImageAnchor {
    return XRImageAnchor(
      id = id,
      name = image.name ?: "",
      tracking = image.trackingState == TrackingState.TRACKING &&
        image.trackingMethod == AugmentedImage.TrackingMethod.FULL_TRACKING,
      pose = xrPose(image.centerPose),
      extent = XRVector3(image.extentX.toDouble(), 0.0, image.extentZ.toDouble()),
    )
  }

  private fun xrFace(id: String, face: AugmentedFace): XRFace {
    return XRFace(
      id = id,
      tracking = face.trackingState == TrackingState.TRACKING,
      pose = xrPose(face.centerPose),
      vertexCount = (face.meshVertices.limit() / 3).toDouble(),
      blendShapes = emptyArray(),
    )
  }

  private fun xrAnchor(id: String, anchor: Anchor): XRAnchor {
    return XRAnchor(id, anchor.trackingState == TrackingState.TRACKING, xrPose(anchor.pose))
  }

  private fun xrPose(pose: Pose): XRPose {
    val translation = pose.translation
    val rotation = pose.rotationQuaternion
    val matrix = FloatArray(16)
    pose.toMatrix(matrix, 0)
    return XRPose(
      position = XRVector3(
        translation[0].toDouble(),
        translation[1].toDouble(),
        translation[2].toDouble(),
      ),
      orientation = XRQuaternion(
        rotation[0].toDouble(),
        rotation[1].toDouble(),
        rotation[2].toDouble(),
        rotation[3].toDouble(),
      ),
      matrix = DoubleArray(16) { matrix[it].toDouble() },
    )
  }

  private fun nativePose(pose: XRPose): Pose {
    return Pose(
      floatArrayOf(
        pose.position.x.toFloat(),
        pose.position.y.toFloat(),
        pose.position.z.toFloat(),
      ),
      floatArrayOf(
        pose.orientation.x.toFloat(),
        pose.orientation.y.toFloat(),
        pose.orientation.z.toFloat(),
        pose.orientation.w.toFloat(),
      ),
    )
  }

  private fun validatePose(pose: XRPose) {
    require(pose.matrix.size == 16) { "An XR pose matrix must contain exactly 16 values." }
    require(pose.matrix.all(Double::isFinite)) { "An XR pose matrix must contain only finite values." }
  }

  private fun emitError(error: Throwable) {
    onError?.invoke(error.message ?: error.javaClass.simpleName)
  }

  private fun outputFile(kind: String, extension: String, keep: Int): File {
    return XRFiles.nextOutputFile(context.cacheDir, kind, extension, keep)
  }

  // endregion

  // region GL

  private fun drawCameraBackground() {
    GLES20.glDisable(GLES20.GL_DEPTH_TEST)
    GLES20.glUseProgram(shaderProgram)
    val position = GLES20.glGetAttribLocation(shaderProgram, "a_Position")
    val texCoord = GLES20.glGetAttribLocation(shaderProgram, "a_TexCoord")
    val texture = GLES20.glGetUniformLocation(shaderProgram, "sTexture")
    quadCoordinates.position(0)
    textureCoordinates.position(0)
    GLES20.glEnableVertexAttribArray(position)
    GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, quadCoordinates)
    GLES20.glEnableVertexAttribArray(texCoord)
    GLES20.glVertexAttribPointer(texCoord, 2, GLES20.GL_FLOAT, false, 0, textureCoordinates)
    GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
    GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId)
    GLES20.glUniform1i(texture, 0)
    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    GLES20.glDisableVertexAttribArray(position)
    GLES20.glDisableVertexAttribArray(texCoord)
    GLES20.glEnable(GLES20.GL_DEPTH_TEST)
  }

  private fun createExternalTexture(): Int {
    val textures = IntArray(1)
    GLES20.glGenTextures(1, textures, 0)
    GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textures[0])
    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    return textures[0]
  }

  private fun createProgram(vertexSource: String, fragmentSource: String): Int {
    val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
    val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
    val program = GLES20.glCreateProgram()
    GLES20.glAttachShader(program, vertexShader)
    GLES20.glAttachShader(program, fragmentShader)
    GLES20.glLinkProgram(program)
    val status = IntArray(1)
    GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
    check(status[0] == GLES20.GL_TRUE) { "XR shader link failed: ${GLES20.glGetProgramInfoLog(program)}" }
    GLES20.glDeleteShader(vertexShader)
    GLES20.glDeleteShader(fragmentShader)
    return program
  }

  private fun compileShader(type: Int, source: String): Int {
    val shader = GLES20.glCreateShader(type)
    GLES20.glShaderSource(shader, source)
    GLES20.glCompileShader(shader)
    val status = IntArray(1)
    GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
    check(status[0] == GLES20.GL_TRUE) { "XR shader compile failed: ${GLES20.glGetShaderInfoLog(shader)}" }
    return shader
  }

  // endregion

  private data class ConfigKey(
    val mode: XRSessionMode,
    val planeDetection: XRPlaneDetection,
    val depthEnabled: Boolean,
    val lightEstimationEnabled: Boolean,
    val lightEstimationMode: XRLightEstimationMode,
    val imagesVersion: Int,
  )

  private data class ImageSpec(val name: String, val uri: String, val physicalWidthMeters: Double)

  private class LoadedImage(val name: String, val bitmap: Bitmap, val physicalWidthMeters: Double)

  private class ThrottleEntry(val time: Double, val signature: DoubleArray)

  companion object {
    private const val DEFAULT_TRACKABLE_UPDATE_HZ = 10.0
    private const val MODELS_UNSUPPORTED =
      "3D models (addModel/removeModel/setModelTransform) are not yet supported on Android."

    private const val VERTEX_SHADER = """
      attribute vec4 a_Position;
      attribute vec2 a_TexCoord;
      varying vec2 v_TexCoord;
      void main() {
        gl_Position = a_Position;
        v_TexCoord = a_TexCoord;
      }
    """

    private const val FRAGMENT_SHADER = """
      #extension GL_OES_EGL_image_external : require
      precision mediump float;
      uniform samplerExternalOES sTexture;
      varying vec2 v_TexCoord;
      void main() {
        gl_FragColor = texture2D(sTexture, v_TexCoord);
      }
    """

    private fun floatBufferOf(vararg values: Float): FloatBuffer {
      return ByteBuffer.allocateDirect(values.size * Float.SIZE_BYTES)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
          put(values)
          position(0)
        }
    }

    private fun FloatArray.toDoubleArray() = DoubleArray(size) { this[it].toDouble() }

    private fun changed(lhs: DoubleArray, rhs: DoubleArray, tolerance: Double): Boolean {
      if (lhs.size != rhs.size) return true
      return lhs.indices.any { abs(lhs[it] - rhs[it]) > tolerance }
    }

    private fun poseSignature(pose: XRPose) = doubleArrayOf(
      pose.position.x, pose.position.y, pose.position.z,
      pose.orientation.x, pose.orientation.y, pose.orientation.z, pose.orientation.w,
    )

    private fun planeSignature(plane: XRPlane) =
      doubleArrayOf(plane.center.x, plane.center.y, plane.center.z, plane.extent.x, plane.extent.z) +
        poseSignature(plane.pose)

    private fun imageSignature(image: XRImageAnchor) =
      doubleArrayOf(if (image.tracking) 1.0 else 0.0, image.extent.x, image.extent.z) +
        poseSignature(image.pose)

    private fun faceSignature(face: XRFace) =
      doubleArrayOf(if (face.tracking) 1.0 else 0.0) + poseSignature(face.pose)
  }
}
