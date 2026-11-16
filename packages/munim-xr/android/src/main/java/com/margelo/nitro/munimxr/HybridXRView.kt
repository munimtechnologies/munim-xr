package com.margelo.nitro.munimxr

import android.Manifest
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.media.Image
import android.hardware.display.DisplayManager
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
import com.google.ar.core.Camera
import com.google.ar.core.CameraIntrinsics
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
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.DeadlineExceededException
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
import kotlin.math.sqrt

@Keep
@DoNotStrip
class HybridXRView(
  private val context: ThemedReactContext,
) : HybridXRViewSpec(), GLSurfaceView.Renderer, LifecycleEventListener {
  override val view: GLSurfaceView = XRSurfaceView(context) { displayGeometryDirty = true }.apply {
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
  override var peopleOcclusion: Boolean? = null
  override var continuousHitTest: XRContinuousHitTest? = null
    set(value) {
      if (value != field) {
        // Run at once; when (re-)enabled, JS starts from "nothing hit".
        hitTestRestartRequested = true
        if (field == null) hitTestSnapshotResetRequested = true
      }
      field = value
    }
  override var instantPlacement: Boolean? = null
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
  override var onBodyAdded: ((body: XRBody) -> Unit)? = null
  override var onBodyUpdated: ((body: XRBody) -> Unit)? = null
  override var onBodyRemoved: ((bodyId: String) -> Unit)? = null
  override var onHitTestResult: ((hits: Array<XRHitResult>) -> Unit)? = null
  override var onError: ((message: String) -> Unit)? = null

  @Volatile private var session: Session? = null
  @Volatile private var latestFrame: Frame? = null
  // ARCore poses are immutable; converting them to XRPose is deferred to the
  // (rare) getter calls instead of being paid on every frame.
  @Volatile private var latestCameraPose: Pose? = null
  @Volatile private var running = false
  @Volatile private var displayGeometryDirty = true
  @Volatile private var anchorSnapshot: Map<String, AnchorState> = emptyMap()
  @Volatile private var hitTestRestartRequested = false
  @Volatile private var hitTestSnapshotResetRequested = false
  // UI thread: report an unsupported body mode once per switch, not per prop update.
  private var bodyModeReported = false
  private var pausedByHost = false
  @Volatile private var cameraTextureId = 0
  private var shaderProgram = 0
  private var positionAttribute = -1
  private var texCoordAttribute = -1
  private var textureUniform = -1
  // GL thread: the session whose camera texture is bound, and whether the
  // background quad's texture coordinates match the current display geometry.
  private var textureBoundSession: Session? = null
  private var textureCoordinatesValid = false
  @Volatile private var instantPlacementActive = false
  // `Session.getConfig()` builds a new Config over JNI on every call, so the
  // configured light mode is remembered instead of read per delivered frame.
  @Volatile private var hdrLightActive = false
  @Volatile private var surfaceWidth = 0
  @Volatile private var surfaceHeight = 0
  private var deliveredReady = false
  private var lastFrameCallbackTimestamp = 0.0
  private var lastTrackingState = XRTrackingState.UNAVAILABLE
  private var configuredKey: ConfigKey? = null
  @Volatile private var metersDepthAvailable = true
  // UI thread only.
  private var lastResumeUptime = 0L
  private var reconfigurePending = false
  private val mainHandler = Handler(Looper.getMainLooper())
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
  private var lastHitTestTime = 0.0
  private var lastHitSnapshot = HitSnapshot.NOT_FOUND

  // GL-thread only (and the UI thread while the GL thread is paused).
  private val models = LinkedHashMap<String, XRModelEntry>()
  private val modelRenderer = XRModelRenderer()
  private val projectionMatrix = FloatArray(16)
  private val viewMatrix = FloatArray(16)
  private val viewProjectionMatrix = FloatArray(16)
  private val anchorMatrix = FloatArray(16)
  private val modelMatrix = FloatArray(16)

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
      lastResumeUptime = SystemClock.uptimeMillis()
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
    mainHandler.removeCallbacksAndMessages(null)
    context.runOnUiQueueThread {
      closeSession()
      // The GL thread is paused now; the EGL context frees the GL buffers.
      models.clear()
    }
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
        val frame = latestFrame ?: throw IllegalStateException(FRAME_NOT_READY)
        promise.resolve(hitResults(frame, normalizedX, normalizedY))
      } catch (error: Throwable) {
        promise.reject(error)
      }
    }
    return promise
  }

  /**
   * GL thread. Planes, depth and feature points; with `instantPlacement`, when
   * nothing is hit, ARCore Instant Placement estimates a point about 1 m away
   * (`estimated`, like ARKit's estimated planes) that ARCore refines later.
   */
  private fun hitResults(
    frame: Frame,
    normalizedX: Double,
    normalizedY: Double,
    allowInstantPlacement: Boolean = true,
  ): Array<XRHitResult> {
    val x = (normalizedX * surfaceWidth).toFloat()
    val y = (normalizedY * surfaceHeight).toFloat()
    var hits = frame.hitTest(x, y)
    if (
      hits.isEmpty() && allowInstantPlacement && instantPlacementActive &&
      frame.camera.trackingState == TrackingState.TRACKING
    ) {
      hits = frame.hitTestInstantPlacement(x, y, INSTANT_PLACEMENT_DISTANCE_METERS)
    }
    return hits.map { hit ->
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
        anchorSnapshot = anchorSnapshot + (id to AnchorState.of(nativeAnchor))
        promise.resolve(xrAnchor(id, nativeAnchor))
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
    return snapshot.filterKeys { it in liveIds }
      .map { (id, state) -> XRAnchor(id, state.tracking, xrPose(state.pose)) }
      .toTypedArray()
  }

  override fun getCameraPose(): XRPose? = latestCameraPose?.let(::xrPose)

  override fun getCamera(zNear: Double, zFar: Double): Promise<XRCamera> {
    require(zNear.isFinite() && zFar.isFinite() && zNear > 0 && zFar > zNear) {
      "Clip planes must be finite, with 0 < zNear < zFar."
    }
    val promise = Promise<XRCamera>()
    view.queueEvent {
      try {
        val frame = latestFrame ?: throw IllegalStateException(FRAME_NOT_READY)
        val camera = frame.camera
        val projection = FloatArray(16)
        val viewTransform = FloatArray(16)
        camera.getProjectionMatrix(projection, 0, zNear.toFloat(), zFar.toFloat())
        camera.getViewMatrix(viewTransform, 0)
        promise.resolve(
          XRCamera(
            timestamp = frame.timestamp / 1_000_000_000.0,
            trackingState = xrTrackingState(camera.trackingState),
            trackingStateReason = xrTrackingStateReason(camera),
            pose = xrPose(camera.pose),
            viewMatrix = viewTransform.toDoubleArray(),
            projectionMatrix = projection.toDoubleArray(),
            intrinsics = xrIntrinsics(camera.imageIntrinsics),
            // ARCore has no world map API.
            worldMappingStatus = XRWorldMappingStatus.NOT_AVAILABLE,
          ),
        )
      } catch (error: Throwable) {
        promise.reject(error)
      }
    }
    return promise
  }

  override fun getCameraImage(): Promise<XRCameraImage> {
    val promise = Promise<XRCameraImage>()
    view.queueEvent {
      try {
        val frame = latestFrame ?: throw IllegalStateException(FRAME_NOT_READY)
        val intrinsics = xrIntrinsics(frame.camera.imageIntrinsics)
        frame.acquireCameraImage().use { image -> promise.resolve(xrCameraImage(image, intrinsics)) }
      } catch (_: NotYetAvailableException) {
        promise.reject(IllegalStateException("No camera image is available yet."))
      } catch (error: Throwable) {
        promise.reject(error)
      }
    }
    return promise
  }

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

  override fun saveWorldMap(): Promise<String> {
    return Promise.rejected(UnsupportedOperationException(WORLD_MAP_UNSUPPORTED))
  }

  override fun loadWorldMap(path: String): Promise<Unit> {
    return Promise.rejected(UnsupportedOperationException(WORLD_MAP_UNSUPPORTED))
  }

  override fun addModel(options: XRModelOptions): Promise<String> {
    options.pose?.let(::validatePose)
    val localTransform = options.pose?.let(::poseMatrix) ?: identityMatrix()
    val scale = options.scale ?: 1.0
    require(scale.isFinite() && scale > 0) { "A model scale must be a finite value greater than 0." }
    val uri = options.uri
    val extension = uri.substringBefore('?').substringBefore('#').substringAfterLast('.', "").lowercase()
    require(extension !in IOS_ONLY_MODEL_EXTENSIONS) {
      "Android loads OBJ models; .$extension is iOS-only (SceneKit)."
    }
    val anchorId = options.anchorId
    val promise = Promise<String>()
    Thread {
      try {
        val mesh = XRFiles.open(context, uri).use { ObjParser.parse(it) }
        view.queueEvent {
          try {
            if (anchorId != null && !anchorExists(anchorId)) {
              throw IllegalArgumentException("No anchor with id $anchorId exists.")
            }
            val id = "model-${UUID.randomUUID()}"
            models[id] = XRModelEntry(mesh, anchorId, localTransform, scale.toFloat())
            promise.resolve(id)
          } catch (error: Throwable) {
            promise.reject(error)
          }
        }
      } catch (error: Throwable) {
        promise.reject(IllegalArgumentException("The model could not be loaded: ${error.message ?: error.javaClass.simpleName}", error))
      }
    }.start()
    return promise
  }

  override fun removeModel(modelId: String) {
    view.queueEvent { models.remove(modelId)?.let(modelRenderer::release) }
  }

  override fun setModelTransform(modelId: String, pose: XRPose, scale: Double?) {
    validatePose(pose)
    if (scale != null) {
      require(scale.isFinite() && scale > 0) { "A model scale must be a finite value greater than 0." }
    }
    val transform = poseMatrix(pose)
    view.queueEvent {
      val entry = models[modelId]
      if (entry == null) {
        onError?.invoke("No model with id $modelId exists.")
      } else {
        entry.localTransform = transform
        if (scale != null) entry.scale = scale.toFloat()
      }
    }
  }

  // region GLSurfaceView.Renderer

  override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
    cameraTextureId = createExternalTexture()
    shaderProgram = XRGL.createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
    positionAttribute = GLES20.glGetAttribLocation(shaderProgram, "a_Position")
    texCoordAttribute = GLES20.glGetAttribLocation(shaderProgram, "a_TexCoord")
    textureUniform = GLES20.glGetUniformLocation(shaderProgram, "sTexture")
    // A new EGL context: rebind the camera texture and re-upload model meshes.
    textureBoundSession = null
    modelRenderer.onSurfaceCreated(models.values)
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
      if (textureBoundSession !== activeSession) {
        // Once per session (and EGL context) instead of on every frame.
        activeSession.setCameraTextureNames(intArrayOf(cameraTextureId))
        textureBoundSession = activeSession
        textureCoordinatesValid = false
      }
      if (displayGeometryDirty && surfaceWidth > 0 && surfaceHeight > 0) {
        displayGeometryDirty = false
        // The view may rotate or resize at any time: since targetSdk 36, large
        // screens (sw >= 600dp) ignore orientation and resizability locks, and
        // multi-window / foldables resize without recreating the Activity.
        activeSession.setDisplayGeometry(view.display?.rotation ?: 0, surfaceWidth, surfaceHeight)
      }
      val frame = activeSession.update()
      latestFrame = frame
      if (!textureCoordinatesValid || frame.hasDisplayGeometryChanged()) {
        frame.transformCoordinates2d(
          Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,
          quadCoordinates,
          Coordinates2d.TEXTURE_NORMALIZED,
          textureCoordinates,
        )
        textureCoordinatesValid = true
      }
      drawCameraBackground()
      if (models.isNotEmpty()) drawModels(frame)
      snapshotAnchors()
      deliverFrame(frame)
      val now = System.nanoTime() / 1_000_000_000.0
      deliverContinuousHitTest(frame, now)
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
    if (requestedMode == XRSessionMode.BODY) throw UnsupportedOperationException(BODY_UNSUPPORTED)
    if (session != null && sessionMode != requestedMode) closeSession()
    val activeSession = session ?: createSession(activity, requestedMode).also {
      session = it
      sessionMode = requestedMode
    }
    configure(activeSession)
    if (cameraTextureId != 0) activeSession.setCameraTextureNames(intArrayOf(cameraTextureId))
    displayGeometryDirty = true
    activeSession.resume()
    lastResumeUptime = SystemClock.uptimeMillis()
    view.onResume()
    pausedByHost = false
    running = true
    if ((sceneReconstruction ?: XRSceneReconstruction.NONE) != XRSceneReconstruction.NONE) {
      onError?.invoke("sceneReconstruction is iOS-only (LiDAR) and is ignored on Android.")
    }
    if (peopleOcclusion == true) {
      onError?.invoke("peopleOcclusion is iOS-only (ARKit person segmentation) and is ignored on Android.")
    }
  }

  private fun createSession(activity: android.app.Activity, sessionMode: XRSessionMode): Session {
    return when (sessionMode) {
      XRSessionMode.FACE -> Session(activity, EnumSet.of(Session.Feature.FRONT_CAMERA))
      XRSessionMode.WORLD -> Session(activity)
      XRSessionMode.BODY -> throw UnsupportedOperationException(BODY_UNSUPPORTED)
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
    config.instantPlacementMode = if (!face && instantPlacement == true) {
      Config.InstantPlacementMode.LOCAL_Y_UP
    } else {
      Config.InstantPlacementMode.DISABLED
    }
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
      { c: Config -> c.instantPlacementMode != Config.InstantPlacementMode.DISABLED } to { c: Config ->
        c.instantPlacementMode = Config.InstantPlacementMode.DISABLED
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
    instantPlacementActive = config.instantPlacementMode != Config.InstantPlacementMode.DISABLED
    hdrLightActive = config.lightEstimationMode == Config.LightEstimationMode.ENVIRONMENTAL_HDR
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
    instantPlacement = instantPlacement == true,
    imagesVersion = synchronized(imageLock) { imagesVersion },
  )

  private fun reconfigureSession() {
    context.runOnUiQueueThread {
      if (reconfigurePending) return@runOnUiQueueThread
      // ARCore opens the camera asynchronously after Session.resume(). Pausing
      // or closing the session before that finishes can leave the camera
      // device open ("Too many cameras already open" for the next session), so
      // give a fresh resume time to settle. The delayed run reads the latest
      // props, so prop changes made meanwhile are coalesced into it.
      val wait = lastResumeUptime + RESUME_SETTLE_MS - SystemClock.uptimeMillis()
      if (wait > 0) {
        reconfigurePending = true
        mainHandler.postDelayed({
          reconfigurePending = false
          reconfigureSession()
        }, wait)
        return@runOnUiQueueThread
      }
      val activeSession = session ?: return@runOnUiQueueThread
      if (!running) return@runOnUiQueueThread
      try {
        if (mode == XRSessionMode.BODY) {
          // Keep the running session in its current mode instead of stopping it.
          if (!bodyModeReported) onError?.invoke(BODY_UNSUPPORTED)
          bodyModeReported = true
          return@runOnUiQueueThread
        }
        bodyModeReported = false
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
        lastResumeUptime = SystemClock.uptimeMillis()
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
    lastHitTestTime = 0.0
    // Earlier hits belong to the closed session: tell JS they are gone.
    if (lastHitSnapshot.found) onHitTestResult?.invoke(emptyArray())
    lastHitSnapshot = HitSnapshot.NOT_FOUND
    textureBoundSession = null
    instantPlacementActive = false
    try { session?.close() } catch (_: Throwable) { }
    session = null
    sessionMode = null
    configuredKey = null
    latestFrame = null
    latestCameraPose = null
    anchorSnapshot = emptyMap()
    deliveredReady = false
    lastFrameCallbackTimestamp = 0.0
    lastTrackingState = XRTrackingState.UNAVAILABLE
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
      anchors.mapValues { (_, anchor) -> AnchorState.of(anchor) }
    }
  }

  private fun deliverFrame(frame: Frame) {
    val camera = frame.camera
    val trackingState = xrTrackingState(camera.trackingState)
    val cameraPose = camera.pose
    latestCameraPose = cameraPose
    if (trackingState != lastTrackingState) {
      lastTrackingState = trackingState
      onTrackingStateChange?.invoke(trackingState)
    }
    if (!deliveredReady) {
      deliveredReady = true
      onReady?.invoke()
    }

    val callback = onFrame ?: return
    val timestampSeconds = frame.timestamp / 1_000_000_000.0
    // A new session (e.g. switching to the front camera) can use a different
    // camera clock whose timestamps are smaller than the last delivered one.
    if (timestampSeconds < lastFrameCallbackTimestamp) lastFrameCallbackTimestamp = 0.0
    val interval = if (frameCallbackFps > 0) 1.0 / frameCallbackFps else Double.POSITIVE_INFINITY
    if (timestampSeconds - lastFrameCallbackTimestamp < interval) return
    lastFrameCallbackTimestamp = timestampSeconds
    val lightEstimate = if (lightEstimationEnabled) xrLightEstimate(frame.lightEstimate) else null
    val legacyIntensity = if (
      lightEstimationEnabled && lightEstimationMode != XRLightEstimationMode.ENVIRONMENTAL_HDR
    ) lightEstimate?.ambientIntensity else null
    callback(XRFrame(timestampSeconds, trackingState, xrPose(cameraPose), legacyIntensity, lightEstimate))
  }

  /**
   * Repeats `continuousHitTest` at most `maxHz` times per second and reports
   * only meaningful changes, so JS is not called per frame for a reticle.
   */
  private fun deliverContinuousHitTest(frame: Frame, now: Double) {
    if (hitTestRestartRequested) {
      hitTestRestartRequested = false
      lastHitTestTime = 0.0
    }
    if (hitTestSnapshotResetRequested) {
      hitTestSnapshotResetRequested = false
      lastHitSnapshot = HitSnapshot.NOT_FOUND
    }
    val request = continuousHitTest ?: return
    val callback = onHitTestResult ?: return
    if (!request.maxHz.isFinite() || request.maxHz <= 0) return
    if (!request.x.isFinite() || !request.y.isFinite() || request.x !in 0.0..1.0 || request.y !in 0.0..1.0) return
    if (now - lastHitTestTime < 1.0 / request.maxHz) return
    lastHitTestTime = now
    // No Instant Placement here: each such hit creates a new ARCore trackable,
    // which a 10 Hz reticle would pile up for the whole session.
    val hits = hitResults(frame, request.x, request.y, allowInstantPlacement = false)
    val snapshot = HitSnapshot.of(hits.firstOrNull())
    if (!snapshot.differs(lastHitSnapshot)) return
    lastHitSnapshot = snapshot
    callback(hits)
  }

  private fun drawModels(frame: Frame) {
    val camera = frame.camera
    if (camera.trackingState != TrackingState.TRACKING) return
    camera.getProjectionMatrix(projectionMatrix, 0, MODEL_NEAR_METERS, MODEL_FAR_METERS)
    camera.getViewMatrix(viewMatrix, 0)
    Matrix.multiplyMM(viewProjectionMatrix, 0, projectionMatrix, 0, viewMatrix, 0)
    for (entry in models.values) {
      // Models on an anchor that is lost or not tracked are hidden, as on iOS.
      if (!writeAnchorMatrix(entry.anchorId, anchorMatrix)) continue
      Matrix.multiplyMM(modelMatrix, 0, anchorMatrix, 0, entry.localTransform, 0)
      Matrix.scaleM(modelMatrix, 0, entry.scale, entry.scale, entry.scale)
      modelRenderer.draw(entry, modelMatrix, viewProjectionMatrix)
    }
  }

  /** World matrix of [anchorId] (identity for world-space models); false when not tracked. */
  private fun writeAnchorMatrix(anchorId: String?, out: FloatArray): Boolean {
    if (anchorId == null) {
      Matrix.setIdentityM(out, 0)
      return true
    }
    val pose = trackedAnchorPose(anchorId) ?: return false
    pose.toMatrix(out, 0)
    return true
  }

  /**
   * User anchors, image anchors, planes and faces can all carry models. The id
   * prefix picks the one map to search. GL thread.
   */
  private fun trackedAnchorPose(id: String): Pose? {
    when {
      id.startsWith("image-") -> imageIds.entries.firstOrNull { it.value == id }?.key?.let { image ->
        val tracked = image.trackingState == TrackingState.TRACKING && image !in removedImages
        return if (tracked) image.centerPose else null
      }
      id.startsWith("plane-") -> planeIds.entries.firstOrNull { it.value == id }?.key?.let { plane ->
        val tracked = plane.trackingState == TrackingState.TRACKING &&
          plane.subsumedBy == null && plane !in removedPlanes
        return if (tracked) plane.centerPose else null
      }
      id.startsWith("face-") -> faceIds.entries.firstOrNull { it.value == id }?.key?.let { face ->
        val tracked = face.trackingState == TrackingState.TRACKING && face !in removedFaces
        return if (tracked) face.centerPose else null
      }
      else -> anchors[id]?.let { anchor ->
        return if (anchor.trackingState == TrackingState.TRACKING) anchor.pose else null
      }
    }
    return null
  }

  private fun anchorExists(id: String): Boolean = when {
    id.startsWith("image-") -> imageIds.containsValue(id)
    id.startsWith("plane-") -> planeIds.containsValue(id)
    id.startsWith("face-") -> faceIds.containsValue(id)
    else -> anchors.containsKey(id)
  }

  private fun xrLightEstimate(estimate: LightEstimate): XRLightEstimate? {
    if (estimate.state != LightEstimate.State.VALID) return null
    return if (hdrLightActive) {
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
        depthMeters = readDepthMeters(frame, width, height),
      )
    }
  }

  /**
   * ARCore 1.56+ float32 depth in metres (`Frame.acquireDepthImageMeters()`),
   * same resolution as the 16-bit image. `null` when this frame or the
   * installed Google Play Services for AR cannot provide it.
   */
  private fun readDepthMeters(frame: Frame, width: Int, height: Int): ArrayBuffer? {
    if (!metersDepthAvailable) return null
    return try {
      frame.acquireDepthImageMeters().use { image ->
        if (image.width != width || image.height != height) return@use null
        val plane = image.planes[0]
        if (plane.pixelStride != Float.SIZE_BYTES) return@use null
        copyPlane(plane.buffer, plane.rowStride, plane.pixelStride, width, height, bytesPerPixel = Float.SIZE_BYTES)
      }
    } catch (_: NotYetAvailableException) {
      null
    } catch (_: DeadlineExceededException) {
      null
    } catch (error: Throwable) {
      // Older ARCore services (or a missing native symbol) cannot produce it;
      // stop asking instead of failing every getDepthFrame() call.
      metersDepthAvailable = false
      onError?.invoke("Float depth in metres is unavailable: ${error.message ?: error.javaClass.simpleName}")
      null
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
    val target = arrayBuffer.getBuffer(false)
    target.clear()
    val input = source.duplicate()
    val rowBytes = width * bytesPerPixel
    if (pixelStride == bytesPerPixel) {
      // Buffer-to-buffer bulk copies (a memcpy for direct buffers), without
      // staging each row in a heap ByteArray.
      if (rowStride == rowBytes && input.capacity() >= rowBytes * height) {
        input.position(0)
        input.limit(rowBytes * height)
        target.put(input)
      } else {
        for (y in 0 until height) {
          val start = y * rowStride
          input.limit(start + rowBytes)
          input.position(start)
          target.put(input)
        }
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

  private fun xrCameraImage(image: Image, intrinsics: XRCameraIntrinsics): XRCameraImage {
    check(image.format == ImageFormat.YUV_420_888) { "Unexpected camera image format ${image.format}." }
    val width = image.width
    val height = image.height
    val luma = image.planes[0]
    return XRCameraImage(
      timestamp = image.timestamp / 1_000_000_000.0,
      width = width.toDouble(),
      height = height.toDouble(),
      y = copyPlane(luma.buffer, luma.rowStride, luma.pixelStride, width, height, bytesPerPixel = 1),
      cbcr = interleaveChroma(image.planes[1], image.planes[2], width / 2, height / 2),
      intrinsics = intrinsics,
    )
  }

  /** YUV_420_888 U and V planes (any pixel stride) to interleaved Cb, Cr (NV12 order). */
  private fun interleaveChroma(u: Image.Plane, v: Image.Plane, width: Int, height: Int): ArrayBuffer {
    val arrayBuffer = ArrayBuffer.allocate(width * height * 2)
    val target = arrayBuffer.getBuffer(false)
    target.clear()
    val uBuffer = u.buffer.duplicate()
    val vBuffer = v.buffer.duplicate()
    val uStride = u.pixelStride
    val vStride = v.pixelStride
    val uRow = ByteArray((width - 1) * uStride + 1)
    val vRow = ByteArray((width - 1) * vStride + 1)
    val row = ByteArray(width * 2)
    for (y in 0 until height) {
      uBuffer.position(y * u.rowStride)
      uBuffer.get(uRow, 0, uRow.size)
      vBuffer.position(y * v.rowStride)
      vBuffer.get(vRow, 0, vRow.size)
      for (x in 0 until width) {
        row[2 * x] = uRow[x * uStride]
        row[2 * x + 1] = vRow[x * vStride]
      }
      target.put(row)
    }
    return arrayBuffer
  }

  // endregion

  // region Conversions

  private fun xrTrackingState(state: TrackingState): XRTrackingState = when (state) {
    TrackingState.TRACKING -> XRTrackingState.NORMAL
    TrackingState.PAUSED -> XRTrackingState.LIMITED
    TrackingState.STOPPED -> XRTrackingState.UNAVAILABLE
  }

  private fun xrTrackingStateReason(camera: Camera): XRTrackingStateReason {
    if (camera.trackingState == TrackingState.TRACKING) return XRTrackingStateReason.NONE
    return when (camera.trackingFailureReason) {
      // Not tracking yet, and nothing is wrong: ARCore is still initializing.
      TrackingFailureReason.NONE -> XRTrackingStateReason.INITIALIZING
      TrackingFailureReason.BAD_STATE -> XRTrackingStateReason.BAD_STATE
      TrackingFailureReason.INSUFFICIENT_LIGHT -> XRTrackingStateReason.INSUFFICIENT_LIGHT
      TrackingFailureReason.EXCESSIVE_MOTION -> XRTrackingStateReason.EXCESSIVE_MOTION
      TrackingFailureReason.INSUFFICIENT_FEATURES -> XRTrackingStateReason.INSUFFICIENT_FEATURES
      TrackingFailureReason.CAMERA_UNAVAILABLE -> XRTrackingStateReason.CAMERA_UNAVAILABLE
      else -> XRTrackingStateReason.NONE
    }
  }

  private fun xrIntrinsics(intrinsics: CameraIntrinsics): XRCameraIntrinsics {
    val focalLength = intrinsics.focalLength
    val principalPoint = intrinsics.principalPoint
    val dimensions = intrinsics.imageDimensions
    return XRCameraIntrinsics(
      fx = focalLength[0].toDouble(),
      fy = focalLength[1].toDouble(),
      cx = principalPoint[0].toDouble(),
      cy = principalPoint[1].toDouble(),
      imageWidth = dimensions[0].toDouble(),
      imageHeight = dimensions[1].toDouble(),
    )
  }

  /** `XRPose.matrix` is column-major, which is also what OpenGL expects. */
  private fun poseMatrix(pose: XRPose): FloatArray = FloatArray(16) { pose.matrix[it].toFloat() }

  private fun identityMatrix(): FloatArray = FloatArray(16).also { Matrix.setIdentityM(it, 0) }

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
    val position = positionAttribute
    val texCoord = texCoordAttribute
    val texture = textureUniform
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

  // endregion

  private data class ConfigKey(
    val mode: XRSessionMode,
    val planeDetection: XRPlaneDetection,
    val depthEnabled: Boolean,
    val lightEstimationEnabled: Boolean,
    val lightEstimationMode: XRLightEstimationMode,
    val instantPlacement: Boolean,
    val imagesVersion: Int,
  )

  private data class ImageSpec(val name: String, val uri: String, val physicalWidthMeters: Double)

  private class LoadedImage(val name: String, val bitmap: Bitmap, val physicalWidthMeters: Double)

  private class ThrottleEntry(val time: Double, val signature: DoubleArray)

  private class AnchorState(val pose: Pose, val tracking: Boolean) {
    companion object {
      fun of(anchor: Anchor) = AnchorState(anchor.pose, anchor.trackingState == TrackingState.TRACKING)
    }
  }

  /** The part of a continuous hit test result that decides whether JS hears about it. */
  private class HitSnapshot(
    val found: Boolean,
    val type: XRHitType?,
    val planeId: String?,
    val x: Double,
    val y: Double,
    val z: Double,
  ) {
    /** Found/lost, a different surface, or moved more than 1 cm. */
    fun differs(other: HitSnapshot): Boolean {
      if (found != other.found || type != other.type || planeId != other.planeId) return true
      val dx = x - other.x
      val dy = y - other.y
      val dz = z - other.z
      return sqrt(dx * dx + dy * dy + dz * dz) > 0.01
    }

    companion object {
      val NOT_FOUND = HitSnapshot(false, null, null, 0.0, 0.0, 0.0)

      fun of(hit: XRHitResult?): HitSnapshot {
        if (hit == null) return NOT_FOUND
        val position = hit.pose.position
        return HitSnapshot(true, hit.type, hit.planeId, position.x, position.y, position.z)
      }
    }
  }

  companion object {
    private const val DEFAULT_TRACKABLE_UPDATE_HZ = 10.0
    private const val RESUME_SETTLE_MS = 1000L
    private const val INSTANT_PLACEMENT_DISTANCE_METERS = 1.0f
    private const val MODEL_NEAR_METERS = 0.01f
    private const val MODEL_FAR_METERS = 100f
    private const val FRAME_NOT_READY = "No XR frame is available yet. Start the view and wait for onReady."
    private const val BODY_UNSUPPORTED =
      "Body tracking (mode=\"body\") needs ARKit; ARCore has no body tracking."
    private const val WORLD_MAP_UNSUPPORTED =
      "World maps (saveWorldMap/loadWorldMap) need ARKit; ARCore has no local world map API."
    private val IOS_ONLY_MODEL_EXTENSIONS = setOf("usdz", "usd", "usda", "usdc", "scn", "reality")

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
