import { Platform } from 'react-native'
import {
  checkAvailability,
  getXRPlatform,
  getXRSDKVersion,
  isSupported,
  requestCameraPermission,
  type XRCameraImage,
  type XRDepthFrame,
  type XRSessionMode,
  type XRViewRef,
} from 'munim-xr'

/**
 * Unattended device check for the example app. Run it with the
 * `munimxrexample://selftest` link (or the Self-test button) while the device
 * is unlocked and the camera sees something with texture. Results are logged
 * as `MUNIM_XR_CHECK` / `MUNIM_XR_SELFTEST` lines and, by the caller, written
 * to Documents/munim-xr-selftest.json.
 *
 * `skip` marks checks that depend on the scene or hardware (no plane in view,
 * no depth sensor) rather than on munim-xr.
 */
export type CheckStatus = 'pass' | 'fail' | 'skip'

export interface Check {
  name: string
  status: CheckStatus
  detail?: unknown
}

export interface SelfTestReport {
  platform: string
  os: string
  sdk: string
  startedAt: string
  finishedAt: string
  passed: number
  failed: number
  skipped: number
  checks: Check[]
}

export interface SelfTestEvents {
  ready: number
  frames: number
  trackingStates: string[]
  planes: number
  faces: number
  bodies: number
  hitResults: number
  lastHitCount: number
  errors: string[]
}

export interface SelfTestProps {
  mode: XRSessionMode
  depthEnabled: boolean
  environmentTexturing: boolean
  /** Continuous hit test at the centre, 10 Hz. */
  reticle: boolean
}

export interface SelfTestHost {
  view(): XRViewRef | null
  /** Applies props and resolves after React committed them. */
  setProps(props: Partial<SelfTestProps>): Promise<void>
  events: SelfTestEvents
  /** A bundled OBJ model for `addModel()`. */
  modelUri: string
}

const sleep = (ms: number) => new Promise<void>((resolve) => setTimeout(resolve, ms))

async function waitFor(predicate: () => boolean, timeoutMs: number): Promise<boolean> {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    if (predicate()) return true
    await sleep(100)
  }
  return predicate()
}

function message(error: unknown): string {
  return error instanceof Error ? error.message : String(error)
}

const isMatrix = (values: number[]) => values.length === 16 && values.every(Number.isFinite)

/** Mean time of `runs` sequential calls, in milliseconds (for the PR's perf numbers). */
async function timeIt<T>(runs: number, body: () => Promise<T>): Promise<{ ms: number; last: T }> {
  let last!: T
  const started = Date.now()
  for (let i = 0; i < runs; i++) last = await body()
  return { ms: Math.round(((Date.now() - started) / runs) * 10) / 10, last }
}

function cameraImageCheck(image: XRCameraImage) {
  const { width, height } = image
  const lumaOk = image.y.byteLength === width * height
  const chromaOk = image.cbcr.byteLength === (width / 2) * (height / 2) * 2
  const luma = new Uint8Array(image.y)
  let sum = 0
  let min = 255
  let max = 0
  for (let i = 0; i < luma.length; i += 7) {
    const value = luma[i]!
    sum += value
    if (value < min) min = value
    if (value > max) max = value
  }
  const samples = Math.ceil(luma.length / 7)
  const { fx, fy, cx, cy, imageWidth, imageHeight } = image.intrinsics
  const intrinsicsOk =
    fx > 0 && fy > 0 && cx > 0 && cx < imageWidth && cy > 0 && cy < imageHeight
  return {
    // A real camera image has texture: not one flat value.
    ok: lumaOk && chromaOk && intrinsicsOk && max > min,
    detail: {
      size: `${width}x${height}`,
      yBytes: image.y.byteLength,
      cbcrBytes: image.cbcr.byteLength,
      meanLuma: Math.round(sum / samples),
      lumaRange: [min, max],
      intrinsics: { fx, fy, cx, cy, imageWidth, imageHeight },
    },
  }
}

function depthCheck(frame: XRDepthFrame) {
  const pixels = frame.width * frame.height
  const bytesPerPixel = frame.format === 'float32-meters' ? 4 : 2
  const detail: Record<string, unknown> = {
    size: `${frame.width}x${frame.height}`,
    format: frame.format,
    depthBytes: frame.depth.byteLength,
    depthMetersBytes: frame.depthMeters?.byteLength,
  }
  let ok = pixels > 0 && frame.depth.byteLength === pixels * bytesPerPixel
  if (!frame.depthMeters) {
    return { ok: false, detail: { ...detail, reason: 'depthMeters missing' } }
  }
  ok = ok && frame.depthMeters.byteLength === pixels * 4
  const meters = new Float32Array(frame.depthMeters)
  const native =
    frame.format === 'float32-meters'
      ? new Float32Array(frame.depth)
      : new Uint16Array(frame.depth)
  const scale = frame.format === 'float32-meters' ? 1 : 0.001
  // Compare the two encodings where both are valid; they come from the same
  // ARCore depth estimate, so they should agree to about a millimetre.
  let compared = 0
  let maxDiff = 0
  let sum = 0
  for (let i = 0; i < pixels; i++) {
    const a = native[i]! * scale
    const b = meters[i]!
    if (!(a > 0) || !(b > 0) || !Number.isFinite(b)) continue
    compared++
    sum += b
    maxDiff = Math.max(maxDiff, Math.abs(a - b))
  }
  detail.comparedPixels = compared
  detail.maxDiffMeters = Math.round(maxDiff * 10000) / 10000
  detail.meanMeters = compared ? Math.round((sum / compared) * 1000) / 1000 : undefined
  ok = ok && (compared === 0 || maxDiff < 0.01)
  return { ok, detail }
}

export async function runSelfTest(host: SelfTestHost): Promise<SelfTestReport> {
  const startedAt = new Date().toISOString()
  const checks: Check[] = []
  const record = (name: string, status: CheckStatus, detail?: unknown) => {
    checks.push({ name, status, detail })
    console.log(`MUNIM_XR_CHECK ${status.toUpperCase()} ${name} ${JSON.stringify(detail ?? null)}`)
  }
  const attempt = async (name: string, body: () => Promise<void>) => {
    try {
      await body()
    } catch (error) {
      record(name, 'fail', message(error))
    }
  }
  const { events } = host
  const view = () => {
    const ref = host.view()
    if (!ref) throw new Error('XRView ref is not attached')
    return ref
  }

  await host.setProps({
    mode: 'world',
    depthEnabled: false,
    environmentTexturing: false,
    reticle: false,
  })

  record('module.isSupported', isSupported() ? 'pass' : 'fail', {
    platform: getXRPlatform(),
    sdk: getXRSDKVersion(),
  })
  await attempt('availability.world-tracking', async () => {
    const availability = await checkAvailability()
    record('availability.world-tracking', availability === 'supported' ? 'pass' : 'fail', availability)
  })
  const features: Record<string, string> = {}
  for (const feature of [
    'depth',
    'face-tracking',
    'image-tracking',
    'environmental-hdr',
    'models',
    'body-tracking',
    'people-occlusion',
    'world-map',
  ] as const) {
    try {
      features[feature] = await checkAvailability(feature)
    } catch (error) {
      features[feature] = `error: ${message(error)}`
    }
  }
  record('availability.features', 'pass', features)

  await attempt('permission.camera', async () => {
    const granted = await requestCameraPermission()
    record('permission.camera', granted ? 'pass' : 'fail', granted)
  })

  // --- World session -------------------------------------------------------
  await attempt('session.start', async () => {
    await view().start()
    record('session.start', 'pass')
  })
  const ready = await waitFor(() => events.ready > 0, 15000)
  record('session.onReady', ready ? 'pass' : 'fail')

  const framesBefore = events.frames
  const framesArrived = await waitFor(() => events.frames - framesBefore >= 5, 8000)
  record('session.frames', framesArrived ? 'pass' : 'fail', {
    frames: events.frames - framesBefore,
    trackingStates: events.trackingStates,
  })
  // "limited" is fine for a session-start check: it depends on the scene.
  const tracked = events.trackingStates.some((state) => state === 'normal' || state === 'limited')
  record('session.tracking', tracked ? 'pass' : 'fail', events.trackingStates)

  await attempt('camera.pose', async () => {
    const pose = view().getCameraPose()
    const ok = !!pose && pose.matrix.length === 16 && pose.matrix.every(Number.isFinite)
    record('camera.pose', ok ? 'pass' : 'fail', pose?.position)
  })

  await attempt('camera.matrices', async () => {
    const camera = await view().getCamera(0.01, 100)
    const { fx, fy, cx, cy, imageWidth, imageHeight } = camera.intrinsics
    const ok =
      isMatrix(camera.viewMatrix) &&
      isMatrix(camera.projectionMatrix) &&
      isMatrix(camera.pose.matrix) &&
      fx > 0 && fy > 0 && cx > 0 && cy > 0 && cx < imageWidth && cy < imageHeight &&
      // A perspective projection: -1 in the w row of the z column.
      Math.abs(camera.projectionMatrix[11]! + 1) < 1e-3
    record('camera.matrices', ok ? 'pass' : 'fail', {
      trackingState: camera.trackingState,
      reason: camera.trackingStateReason,
      worldMappingStatus: camera.worldMappingStatus,
      intrinsics: { fx, fy, cx, cy, imageWidth, imageHeight },
      projection: camera.projectionMatrix.map((value) => Math.round(value * 1000) / 1000),
    })
  })

  await attempt('camera.image', async () => {
    const { ms, last } = await timeIt(5, () => view().getCameraImage())
    const { ok, detail } = cameraImageCheck(last)
    record('camera.image', ok ? 'pass' : 'fail', { ...detail, meanMs: ms })
  })

  const planesFound = await waitFor(() => events.planes > 0, 15000)
  record('planes.detected', planesFound ? 'pass' : 'skip', {
    planes: events.planes,
    hint: planesFound ? undefined : 'no plane in view (scene-dependent)',
  })

  await attempt('hitTest', async () => {
    let hits = await view().hitTest(0.5, 0.5)
    for (let i = 0; i < 10 && hits.length === 0; i++) {
      await sleep(500)
      hits = await view().hitTest(0.5, 0.5)
    }
    record('hitTest', 'pass', { hits: hits.length, first: hits[0]?.type, distance: hits[0]?.distance })
    if (!hits[0]) {
      record('anchor.lifecycle', 'skip', 'no hit at the centre (scene-dependent)')
      return
    }
    const anchor = await view().createAnchor(hits[0].pose)
    await sleep(500)
    const listed = view().getAnchors().some((item) => item.id === anchor.id)
    view().removeAnchor(anchor.id)
    await sleep(300)
    const removed = !view().getAnchors().some((item) => item.id === anchor.id)
    record('anchor.lifecycle', listed && removed ? 'pass' : 'fail', { listed, removed })
  })

  // --- Continuous hit test (native-side reticle) -----------------------------
  await attempt('hitTest.continuous', async () => {
    const before = events.hitResults
    await host.setProps({ reticle: true })
    const delivered = await waitFor(() => events.hitResults > before, 6000)
    const framesBefore = events.frames
    const resultsBefore = events.hitResults
    await sleep(2000)
    const results = events.hitResults - resultsBefore
    const frames = events.frames - framesBefore
    await host.setProps({ reticle: false })
    if (!delivered) {
      record('hitTest.continuous', planesFound ? 'fail' : 'skip', 'no surface at the centre')
      return
    }
    // At most 10 Hz, and only on change: never more than ~20 in 2 s.
    record('hitTest.continuous', results <= 22 ? 'pass' : 'fail', {
      resultsIn2s: results,
      framesIn2s: frames,
      lastHitCount: events.lastHitCount,
    })
  })

  // --- Models (iOS: SceneKit, Android: OpenGL ES OBJ renderer) ---------------
  await attempt('models.lifecycle', async () => {
    const camera = await view().getCamera(0.01, 100)
    // 50 cm in front of the camera, upright.
    const m = camera.pose.matrix
    const x = m[12]! - m[8]! * 0.5
    const y = m[13]! - m[9]! * 0.5
    const z = m[14]! - m[10]! * 0.5
    const pose = {
      position: { x, y, z },
      orientation: { x: 0, y: 0, z: 0, w: 1 },
      matrix: [1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, x, y, z, 1],
    }
    const errorsBefore = events.errors.length
    const modelId = await view().addModel({ uri: host.modelUri, pose, scale: 1 })
    view().setModelTransform(modelId, pose, 1.5)
    await sleep(1000)
    view().removeModel(modelId)
    await sleep(300)
    const errors = events.errors.slice(errorsBefore)
    record('models.lifecycle', modelId.startsWith('model-') && errors.length === 0 ? 'pass' : 'fail', {
      modelId: modelId.slice(-6),
      errors,
    })
  })

  // --- World maps (iOS only) ------------------------------------------------
  await attempt('worldMap', async () => {
    if (features['world-map'] !== 'supported') {
      try {
        await view().saveWorldMap()
        record('worldMap', 'fail', 'saveWorldMap resolved on a platform without world maps')
      } catch (error) {
        record('worldMap', 'pass', `unsupported, rejected cleanly: ${message(error)}`)
      }
      return
    }
    let path: string
    try {
      path = await view().saveWorldMap()
    } catch (error) {
      // ARKit refuses while too little of the room is mapped (scene-dependent).
      record('worldMap', 'skip', message(error))
      return
    }
    const readyBefore = events.ready
    await view().loadWorldMap(path)
    const restarted = await waitFor(() => events.ready > readyBefore, 8000)
    record('worldMap', restarted ? 'pass' : 'fail', { path, restarted })
  })

  await attempt('snapshot', async () => {
    const path = await view().captureSnapshot()
    record('snapshot', path.endsWith('.png') ? 'pass' : 'fail', path)
  })

  // --- Depth ---------------------------------------------------------------
  await host.setProps({ depthEnabled: true })
  await sleep(3000)
  await attempt('depth.frame', async () => {
    let lastError = ''
    for (let i = 0; i < 12; i++) {
      try {
        const frame = await view().getDepthFrame()
        const { ok, detail } = depthCheck(frame)
        const { ms } = await timeIt(5, () => view().getDepthFrame())
        record('depth.frame', ok ? 'pass' : 'fail', { ...detail, meanMs: ms })
        return
      } catch (error) {
        lastError = message(error)
        // Unsupported hardware is a clean, expected rejection.
        if (/support|LiDAR/i.test(lastError)) {
          record('depth.frame', 'skip', `unsupported, rejected cleanly: ${lastError}`)
          return
        }
        await sleep(500)
      }
    }
    record('depth.frame', 'fail', lastError)
  })
  await host.setProps({ depthEnabled: false })

  // --- Face mode (iOS 27: environment texturing) ----------------------------
  const errorsBeforeFace = events.errors.length
  await host.setProps({ mode: 'face', environmentTexturing: true })
  await sleep(1500)
  const faceFramesBefore = events.frames
  const faceFrames = await waitFor(() => events.frames - faceFramesBefore >= 5, 10000)
  const faceErrors = events.errors.slice(errorsBeforeFace)
  const faceFeature = features['face-tracking']
  if (faceFeature !== 'supported') {
    record('face.session', faceErrors.length > 0 ? 'skip' : 'fail', {
      faceFeature,
      errors: faceErrors,
    })
  } else {
    record('face.session', faceFrames ? 'pass' : 'fail', {
      frames: events.frames - faceFramesBefore,
      faces: events.faces,
      errors: faceErrors,
    })
    if (Platform.OS === 'ios') {
      const unsupported = faceErrors.some((error) => /environmentTexturing/.test(error))
      const major = parseInt(String(Platform.Version), 10)
      record(
        'face.environmentTexturing',
        major >= 27 ? (!unsupported && faceFrames ? 'pass' : 'fail') : unsupported ? 'pass' : 'fail',
        { iOS: Platform.Version, reportedUnsupported: unsupported }
      )
    }
  }
  await host.setProps({ mode: 'world', environmentTexturing: false })
  await sleep(1500)

  // --- Body mode (iOS A12+; Android rejects it) -------------------------------
  const errorsBeforeBody = events.errors.length
  const bodyFramesBefore = events.frames
  await host.setProps({ mode: 'body' })
  await sleep(2500)
  const bodyErrors = events.errors.slice(errorsBeforeBody)
  if (features['body-tracking'] === 'supported') {
    const bodyFrames = await waitFor(() => events.frames - bodyFramesBefore >= 5, 8000)
    record('body.session', bodyFrames && bodyErrors.length === 0 ? 'pass' : 'fail', {
      frames: events.frames - bodyFramesBefore,
      bodies: events.bodies,
      hint: events.bodies ? undefined : 'point the camera at a person to see onBodyAdded',
      errors: bodyErrors,
    })
  } else {
    const rejected = bodyErrors.some((error) => /body/i.test(error))
    record('body.session', rejected ? 'pass' : 'fail', { rejectedCleanly: rejected, errors: bodyErrors })
  }
  await host.setProps({ mode: 'world' })
  await sleep(1500)

  // --- Pause stops frames ----------------------------------------------------
  await attempt('session.pause', async () => {
    view().pause()
    await sleep(800)
    const pausedAt = events.frames
    await sleep(2000)
    const delta = events.frames - pausedAt
    record('session.pause', delta === 0 ? 'pass' : 'fail', { framesWhilePaused: delta })
  })

  const report: SelfTestReport = {
    platform: getXRPlatform(),
    os: `${Platform.OS} ${Platform.Version}`,
    sdk: getXRSDKVersion(),
    startedAt,
    finishedAt: new Date().toISOString(),
    passed: checks.filter((check) => check.status === 'pass').length,
    failed: checks.filter((check) => check.status === 'fail').length,
    skipped: checks.filter((check) => check.status === 'skip').length,
    checks,
  }
  console.log(
    `MUNIM_XR_SELFTEST passed=${report.passed} failed=${report.failed} skipped=${report.skipped}`
  )
  return report
}
