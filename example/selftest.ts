import { Platform } from 'react-native'
import {
  checkAvailability,
  getXRPlatform,
  getXRSDKVersion,
  isSupported,
  requestCameraPermission,
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
  errors: string[]
}

export interface SelfTestProps {
  mode: XRSessionMode
  depthEnabled: boolean
  environmentTexturing: boolean
}

export interface SelfTestHost {
  view(): XRViewRef | null
  /** Applies props and resolves after React committed them. */
  setProps(props: Partial<SelfTestProps>): Promise<void>
  events: SelfTestEvents
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

  await host.setProps({ mode: 'world', depthEnabled: false, environmentTexturing: false })

  record('module.isSupported', isSupported() ? 'pass' : 'fail', {
    platform: getXRPlatform(),
    sdk: getXRSDKVersion(),
  })
  await attempt('availability.world-tracking', async () => {
    const availability = await checkAvailability()
    record('availability.world-tracking', availability === 'supported' ? 'pass' : 'fail', availability)
  })
  const features: Record<string, string> = {}
  for (const feature of ['depth', 'face-tracking', 'image-tracking', 'environmental-hdr'] as const) {
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
        record('depth.frame', ok ? 'pass' : 'fail', detail)
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
