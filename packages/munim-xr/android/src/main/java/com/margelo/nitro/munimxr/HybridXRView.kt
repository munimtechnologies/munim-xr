package com.margelo.nitro.munimxr

import android.Manifest
import android.graphics.Bitmap
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.annotation.Keep
import androidx.core.content.ContextCompat
import com.facebook.proguard.annotations.DoNotStrip
import com.facebook.react.uimanager.ThemedReactContext
import com.google.ar.core.Anchor
import com.google.ar.core.ArCoreApk
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
import com.margelo.nitro.core.Promise
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.Collections
import java.util.IdentityHashMap
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

@Keep
@DoNotStrip
class HybridXRView(
  private val context: ThemedReactContext,
) : HybridXRViewSpec(), GLSurfaceView.Renderer {
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
  override var onReady: (() -> Unit)? = null
  override var onFrame: ((frame: XRFrame) -> Unit)? = null
  override var onTrackingStateChange: ((state: XRTrackingState) -> Unit)? = null
  override var onPlaneDetected: ((plane: XRPlane) -> Unit)? = null
  override var onPlaneUpdated: ((plane: XRPlane) -> Unit)? = null
  override var onPlaneRemoved: ((planeId: String) -> Unit)? = null
  override var onError: ((message: String) -> Unit)? = null

  @Volatile private var session: Session? = null
  @Volatile private var latestFrame: Frame? = null
  @Volatile private var latestCameraPose: XRPose? = null
  @Volatile private var running = false
  private var cameraTextureId = 0
  private var shaderProgram = 0
  private var surfaceWidth = 0
  private var surfaceHeight = 0
  private var deliveredReady = false
  private var lastFrameCallbackTimestamp = 0.0
  private var lastTrackingState = XRTrackingState.UNAVAILABLE
  private val planeIds = IdentityHashMap<Plane, String>()
  private val removedPlaneIds = Collections.newSetFromMap(IdentityHashMap<Plane, Boolean>())
  private val nextPlaneId = AtomicLong(1)
  private val anchors = Collections.synchronizedMap(mutableMapOf<String, Anchor>())

  private val quadCoordinates = floatBufferOf(
    -1f, -1f,
    1f, -1f,
    -1f, 1f,
    1f, 1f,
  )
  private val textureCoordinates = floatBufferOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)

  override fun afterUpdate() {
    if (running) reconfigureSession()
  }

  override fun start(): Promise<Unit> {
    val promise = Promise<Unit>()
    context.runOnUiQueueThread {
      try {
        startOnUiThread()
        promise.resolve(Unit)
      } catch (error: Throwable) {
        promise.reject(error)
        onError?.invoke(error.message ?: error.javaClass.simpleName)
      }
    }
    return promise
  }

  override fun pause() {
    context.runOnUiQueueThread {
      try {
        view.onPause()
        session?.pause()
        running = false
      } catch (error: Throwable) {
        onError?.invoke(error.message ?: error.javaClass.simpleName)
      }
    }
  }

  override fun reset(): Promise<Unit> {
    val promise = Promise<Unit>()
    context.runOnUiQueueThread {
      try {
        closeSession()
        deliveredReady = false
        planeIds.clear()
        removedPlaneIds.clear()
        anchors.clear()
        startOnUiThread()
        promise.resolve(Unit)
      } catch (error: Throwable) {
        promise.reject(error)
        onError?.invoke(error.message ?: error.javaClass.simpleName)
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
        promise.resolve(XRAnchor(id, true, xrPose(nativeAnchor.pose)))
      } catch (error: Throwable) {
        promise.reject(error)
      }
    }
    return promise
  }

  override fun removeAnchor(anchorId: String) {
    val anchor = anchors.remove(anchorId) ?: return
    view.queueEvent { anchor.detach() }
  }

  override fun getAnchors(): Array<XRAnchor> {
    return synchronized(anchors) {
      anchors.map { (id, anchor) ->
        XRAnchor(id, anchor.trackingState == TrackingState.TRACKING, xrPose(anchor.pose))
      }.toTypedArray()
    }
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
              promise.reject(IllegalStateException("PixelCopy failed with code $result."))
              return@request
            }
            try {
              val file = File(context.cacheDir, "munim-xr-${UUID.randomUUID()}.png")
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

  override fun onDropView() {
    context.runOnUiQueueThread { closeSession() }
  }

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
    val rotation = view.display?.rotation ?: 0
    session?.setDisplayGeometry(rotation, width, height)
  }

  override fun onDrawFrame(gl: GL10?) {
    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
    val activeSession = session ?: return
    if (!running || cameraTextureId == 0) return

    try {
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
      deliverFrame(frame)
      deliverPlaneChanges(frame)
    } catch (error: Throwable) {
      running = false
      onError?.invoke(error.message ?: error.javaClass.simpleName)
    }
  }

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

    val activeSession = session ?: Session(activity).also { session = it }
    configure(activeSession)
    if (cameraTextureId != 0) activeSession.setCameraTextureNames(intArrayOf(cameraTextureId))
    if (surfaceWidth > 0 && surfaceHeight > 0) {
      val rotation = view.display?.rotation ?: 0
      activeSession.setDisplayGeometry(rotation, surfaceWidth, surfaceHeight)
    }
    activeSession.resume()
    view.onResume()
    running = true
  }

  private fun configure(activeSession: Session) {
    val config = Config(activeSession)
    config.planeFindingMode = when (planeDetection) {
      XRPlaneDetection.NONE -> Config.PlaneFindingMode.DISABLED
      XRPlaneDetection.HORIZONTAL -> Config.PlaneFindingMode.HORIZONTAL
      XRPlaneDetection.VERTICAL -> Config.PlaneFindingMode.VERTICAL
      XRPlaneDetection.BOTH -> Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
    }
    config.lightEstimationMode = if (lightEstimationEnabled) {
      Config.LightEstimationMode.AMBIENT_INTENSITY
    } else {
      Config.LightEstimationMode.DISABLED
    }
    config.depthMode = if (
      depthEnabled && activeSession.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
    ) {
      Config.DepthMode.AUTOMATIC
    } else {
      Config.DepthMode.DISABLED
    }
    activeSession.configure(config)
  }

  private fun reconfigureSession() {
    context.runOnUiQueueThread {
      val activeSession = session ?: return@runOnUiQueueThread
      try {
        activeSession.pause()
        configure(activeSession)
        activeSession.resume()
      } catch (error: Throwable) {
        onError?.invoke(error.message ?: error.javaClass.simpleName)
      }
    }
  }

  private fun closeSession() {
    running = false
    try { view.onPause() } catch (_: Throwable) { }
    try { session?.pause() } catch (_: Throwable) { }
    synchronized(anchors) {
      anchors.values.forEach { it.detach() }
      anchors.clear()
    }
    session?.close()
    session = null
    latestFrame = null
    latestCameraPose = null
  }

  private fun deliverFrame(frame: Frame) {
    val camera = frame.camera
    val trackingState = when (camera.trackingState) {
      TrackingState.TRACKING -> XRTrackingState.NORMAL
      TrackingState.PAUSED -> XRTrackingState.LIMITED
      TrackingState.STOPPED -> XRTrackingState.UNAVAILABLE
    }
    latestCameraPose = xrPose(camera.pose)
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
    val intensity = if (
      lightEstimationEnabled && frame.lightEstimate.state == LightEstimate.State.VALID
    ) frame.lightEstimate.pixelIntensity.toDouble() else null
    onFrame?.invoke(XRFrame(timestampSeconds, trackingState, latestCameraPose!!, intensity))
  }

  private fun deliverPlaneChanges(frame: Frame) {
    frame.getUpdatedTrackables(Plane::class.java).forEach { plane ->
      val known = planeIds.containsKey(plane)
      val id = planeId(plane)
      if (plane.trackingState == TrackingState.STOPPED || plane.subsumedBy != null) {
        if (removedPlaneIds.add(plane)) onPlaneRemoved?.invoke(id)
      } else if (known) {
        onPlaneUpdated?.invoke(xrPlane(plane))
      } else {
        onPlaneDetected?.invoke(xrPlane(plane))
      }
    }
  }

  private fun planeId(plane: Plane): String {
    return planeIds.getOrPut(plane) { "plane-${nextPlaneId.getAndIncrement()}" }
  }

  private fun xrPlane(plane: Plane): XRPlane {
    val alignment = when (plane.type) {
      Plane.Type.VERTICAL -> XRPlaneAlignment.VERTICAL
      Plane.Type.HORIZONTAL_UPWARD_FACING,
      Plane.Type.HORIZONTAL_DOWNWARD_FACING -> XRPlaneAlignment.HORIZONTAL
    }
    val classification = when (plane.type) {
      Plane.Type.VERTICAL -> XRPlaneClassification.WALL
      Plane.Type.HORIZONTAL_UPWARD_FACING -> XRPlaneClassification.FLOOR
      Plane.Type.HORIZONTAL_DOWNWARD_FACING -> XRPlaneClassification.CEILING
    }
    val center = plane.centerPose
    return XRPlane(
      id = planeId(plane),
      alignment = alignment,
      classification = classification,
      center = XRVector3(
        center.tx().toDouble(),
        center.ty().toDouble(),
        center.tz().toDouble(),
      ),
      extent = XRVector3(plane.extentX.toDouble(), 0.0, plane.extentZ.toDouble()),
      pose = xrPose(center),
    )
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

  companion object {
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
  }
}
