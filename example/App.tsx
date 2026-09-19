import { useCallback, useMemo, useRef, useState } from 'react'
import { StatusBar } from 'expo-status-bar'
import {
  ActivityIndicator,
  Image,
  LayoutChangeEvent,
  Platform,
  Pressable,
  SafeAreaView,
  ScrollView,
  StyleSheet,
  Text,
  View,
} from 'react-native'
import { callback } from 'react-native-nitro-modules'
import {
  checkAvailability,
  getXRPlatform,
  getXRSDKVersion,
  requestCameraPermission,
  requestInstall,
  XRView,
  type XRDepthFrame,
  type XRDetectionImage,
  type XRFace,
  type XRFeature,
  type XRFrame,
  type XRImageAnchor,
  type XRMeshAnchor,
  type XRPlane,
  type XRPose,
  type XRSessionMode,
  type XRViewRef,
} from 'munim-xr'

const TAG = 'MUNIM_XR'
const FEATURES: XRFeature[] = [
  'world-tracking',
  'depth',
  'smoothed-depth',
  'scene-reconstruction',
  'mesh-classification',
  'face-tracking',
  'image-tracking',
  'environmental-hdr',
  'models',
]
// Print xr-target.png (or show it on another screen) at this width.
const TARGET_WIDTH_METERS = 0.15
const TARGET_URI = Image.resolveAssetSource(require('./assets/xr-target.png')).uri
const CUBE_URI = Image.resolveAssetSource(require('./assets/cube.obj')).uri
// Keep per-trackable update logs readable in Metro/logcat.
const UPDATE_HZ = 2

function describe(value: unknown): string {
  if (value === undefined) return ''
  try {
    return JSON.stringify(value, (_key, item) =>
      typeof item === 'number' ? Math.round(item * 1000) / 1000 : item
    )
  } catch {
    return String(value)
  }
}

function brief(pose: XRPose) {
  const { x, y, z } = pose.position
  return { x, y, z }
}

function depthSummary(frame: XRDepthFrame) {
  const values =
    frame.format === 'float32-meters'
      ? new Float32Array(frame.depth)
      : new Uint16Array(frame.depth)
  const toMeters = frame.format === 'float32-meters' ? 1 : 0.001
  let valid = 0
  let sum = 0
  let min = Number.POSITIVE_INFINITY
  let max = 0
  for (let i = 0; i < values.length; i++) {
    const meters = values[i]! * toMeters
    if (!(meters > 0) || !Number.isFinite(meters)) continue
    valid++
    sum += meters
    if (meters < min) min = meters
    if (meters > max) max = meters
  }
  const center =
    values[Math.floor(frame.height / 2) * frame.width + Math.floor(frame.width / 2)]! *
    toMeters
  let confidenceMean: number | undefined
  if (frame.confidence) {
    const confidence = new Uint8Array(frame.confidence)
    let total = 0
    for (let i = 0; i < confidence.length; i++) total += confidence[i]!
    confidenceMean = confidence.length ? total / confidence.length : undefined
  }
  return {
    size: `${frame.width}x${frame.height}`,
    format: frame.format,
    smoothed: frame.smoothed,
    bytes: frame.depth.byteLength,
    centerMeters: center,
    minMeters: valid ? min : undefined,
    maxMeters: valid ? max : undefined,
    meanMeters: valid ? sum / valid : undefined,
    validPixels: valid,
    confidenceMean,
  }
}

export default function App() {
  const xrRef = useRef<XRViewRef | null>(null)
  const viewSize = useRef({ width: 1, height: 1 })
  const frameCount = useRef(0)
  const lastAnchorId = useRef<string | null>(null)
  const lastImageAnchorId = useRef<string | null>(null)
  const modelIds = useRef<string[]>([])
  const [running, setRunning] = useState(false)
  const [starting, setStarting] = useState(false)
  const [mode, setMode] = useState<XRSessionMode>('world')
  const [depthEnabled, setDepthEnabled] = useState(false)
  const [hdr, setHdr] = useState(false)
  const [environmentTexturing, setEnvironmentTexturing] = useState(false)
  const [meshEnabled, setMeshEnabled] = useState(false)
  const [imagesEnabled, setImagesEnabled] = useState(false)
  const [planeCount, setPlaneCount] = useState(0)
  const [lastFrame, setLastFrame] = useState<XRFrame | null>(null)
  const [logLines, setLogLines] = useState<string[]>([])

  const log = useCallback((event: string, data?: unknown) => {
    const line = `${event} ${describe(data)}`.trim()
    console.log(`${TAG} ${line}`)
    setLogLines((lines) => [line, ...lines].slice(0, 8))
  }, [])

  const fail = useCallback(
    (event: string, error: unknown) =>
      log(`${event}.error`, error instanceof Error ? error.message : String(error)),
    [log]
  )

  const detectionImages = useMemo<XRDetectionImage[]>(
    () =>
      imagesEnabled
        ? [{ name: 'xr-target', uri: TARGET_URI, physicalWidthMeters: TARGET_WIDTH_METERS }]
        : [],
    [imagesEnabled]
  )

  const hybridRef = useMemo(
    () => callback((ref: XRViewRef) => (xrRef.current = ref)),
    []
  )
  const onReady = useMemo(() => callback(() => log('ready')), [log])
  const onFrame = useMemo(
    () =>
      callback((frame: XRFrame) => {
        setLastFrame(frame)
        // Log light estimation roughly every 3 s at frameCallbackFps=6.
        if (frameCount.current++ % 18 === 0) {
          log('frame.light', {
            lightIntensity: frame.lightIntensity,
            ...frame.lightEstimate,
            sphericalHarmonics: frame.lightEstimate?.sphericalHarmonics?.length,
          })
        }
      }),
    [log]
  )
  const onTrackingStateChange = useMemo(
    () => callback((state: string) => log('tracking', state)),
    [log]
  )
  const onPlaneDetected = useMemo(
    () =>
      callback((plane: XRPlane) => {
        setPlaneCount((count) => count + 1)
        log('plane.detected', {
          id: plane.id.slice(-6),
          alignment: plane.alignment,
          classification: plane.classification,
          center: plane.center,
          localCenter: plane.localCenter,
          extent: plane.extent,
        })
      }),
    [log]
  )
  const onPlaneUpdated = useMemo(
    () =>
      callback((plane: XRPlane) =>
        log('plane.updated', {
          id: plane.id.slice(-6),
          classification: plane.classification,
          center: plane.center,
          extent: plane.extent,
          extentRotationY: plane.extentRotationY,
        })
      ),
    [log]
  )
  const onPlaneRemoved = useMemo(
    () =>
      callback((planeId: string) => {
        setPlaneCount((count) => Math.max(0, count - 1))
        log('plane.removed', planeId.slice(-6))
      }),
    [log]
  )
  const imageEvent = useCallback(
    (event: string, anchor: XRImageAnchor) => {
      lastImageAnchorId.current = anchor.id
      log(event, {
        id: anchor.id.slice(-6),
        name: anchor.name,
        tracking: anchor.tracking,
        position: brief(anchor.pose),
        extent: anchor.extent,
      })
    },
    [log]
  )
  const onImageAnchorAdded = useMemo(
    () => callback((anchor: XRImageAnchor) => imageEvent('image.added', anchor)),
    [imageEvent]
  )
  const onImageAnchorUpdated = useMemo(
    () => callback((anchor: XRImageAnchor) => imageEvent('image.updated', anchor)),
    [imageEvent]
  )
  const onImageAnchorRemoved = useMemo(
    () =>
      callback((anchor: XRImageAnchor) => {
        imageEvent('image.removed', anchor)
        lastImageAnchorId.current = null
      }),
    [imageEvent]
  )
  const meshEvent = useCallback(
    (event: string, mesh: XRMeshAnchor) =>
      log(event, {
        id: mesh.id.slice(-6),
        vertices: mesh.vertexCount,
        faces: mesh.faceCount,
        classes: mesh.classifications
          .map((item) => `${item.classification}:${item.faceCount}`)
          .join(','),
      }),
    [log]
  )
  const onMeshAnchorAdded = useMemo(
    () => callback((mesh: XRMeshAnchor) => meshEvent('mesh.added', mesh)),
    [meshEvent]
  )
  const onMeshAnchorUpdated = useMemo(
    () => callback((mesh: XRMeshAnchor) => meshEvent('mesh.updated', mesh)),
    [meshEvent]
  )
  const onMeshAnchorRemoved = useMemo(
    () => callback((meshId: string) => log('mesh.removed', meshId.slice(-6))),
    [log]
  )
  const faceEvent = useCallback(
    (event: string, face: XRFace) => {
      const shapes = Object.fromEntries(
        face.blendShapes
          .filter((shape) => shape.value > 0.2)
          .map((shape) => [shape.name, shape.value])
      )
      log(event, {
        id: face.id.slice(-6),
        tracking: face.tracking,
        vertices: face.vertexCount,
        position: brief(face.pose),
        blendShapes: shapes,
      })
    },
    [log]
  )
  const onFaceAdded = useMemo(
    () => callback((face: XRFace) => faceEvent('face.added', face)),
    [faceEvent]
  )
  const onFaceUpdated = useMemo(
    () => callback((face: XRFace) => faceEvent('face.updated', face)),
    [faceEvent]
  )
  const onFaceRemoved = useMemo(
    () => callback((faceId: string) => log('face.removed', faceId.slice(-6))),
    [log]
  )
  const onError = useMemo(
    () => callback((message: string) => log('onError', message)),
    [log]
  )

  const checkFeatures = async () => {
    log('platform', { platform: getXRPlatform(), sdk: getXRSDKVersion() })
    log('availability', await checkAvailability())
    for (const feature of FEATURES) {
      try {
        log(`feature.${feature}`, await checkAvailability(feature))
      } catch (error) {
        fail(`feature.${feature}`, error)
      }
    }
  }

  const start = async () => {
    setStarting(true)
    try {
      const permission = await requestCameraPermission()
      if (!permission) throw new Error('Camera permission was denied.')

      const availability = await checkAvailability()
      log('availability', availability)
      if (availability === 'not-installed' || availability === 'update-required') {
        const installed = await requestInstall()
        if (!installed) {
          log('install', 'Finish installing Google Play Services for AR, then tap Start again.')
          return
        }
      }
      if (availability === 'unsupported') {
        throw new Error('This device does not support world-tracked XR.')
      }

      await xrRef.current?.start()
      setRunning(true)
      log('start.ok', { mode })
    } catch (error) {
      fail('start', error)
    } finally {
      setStarting(false)
    }
  }

  const pause = () => {
    xrRef.current?.pause()
    setRunning(false)
    log('pause')
  }

  const reset = async () => {
    try {
      await xrRef.current?.reset()
      modelIds.current = []
      lastAnchorId.current = null
      lastImageAnchorId.current = null
      setPlaneCount(0)
      log('reset.ok')
    } catch (error) {
      fail('reset', error)
    }
  }

  const listAnchors = () => {
    try {
      const anchors = xrRef.current?.getAnchors() ?? []
      log('anchors', {
        count: anchors.length,
        anchors: anchors.map((anchor) => ({
          id: anchor.id.slice(-6),
          tracking: anchor.tracking,
          position: brief(anchor.pose),
        })),
      })
    } catch (error) {
      fail('anchors', error)
    }
  }

  const snapshot = async () => {
    try {
      log('snapshot.ok', await xrRef.current?.captureSnapshot())
    } catch (error) {
      fail('snapshot', error)
    }
  }

  const depthFrame = async () => {
    try {
      const frame = await xrRef.current?.getDepthFrame()
      if (frame) log('depth.ok', depthSummary(frame))
    } catch (error) {
      fail('depth', error)
    }
  }

  const exportMesh = async () => {
    try {
      log('mesh.export.ok', await xrRef.current?.exportMesh())
    } catch (error) {
      fail('mesh.export', error)
    }
  }

  const centerAnchor = async () => {
    const hits = await xrRef.current?.hitTest(0.5, 0.5)
    if (!hits?.[0]) throw new Error('No surface at the reticle yet; scan a plane first.')
    const anchor = await xrRef.current!.createAnchor(hits[0].pose)
    lastAnchorId.current = anchor.id
    return anchor.id
  }

  const addModel = async () => {
    try {
      // Prefer a detected image, otherwise anchor to the surface under the reticle.
      const anchorId = lastImageAnchorId.current ?? (await centerAnchor())
      const modelId = await xrRef.current!.addModel({ uri: CUBE_URI, anchorId, scale: 1 })
      modelIds.current.push(modelId)
      log('model.added', { modelId: modelId.slice(-6), anchorId: anchorId.slice(-6) })
    } catch (error) {
      fail('model.add', error)
    }
  }

  const scaleModel = () => {
    const modelId = modelIds.current[modelIds.current.length - 1]
    if (!modelId) return log('model.scale.error', 'Add a model first.')
    try {
      const identity = [1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0.05, 0, 1]
      xrRef.current?.setModelTransform(
        modelId,
        {
          position: { x: 0, y: 0.05, z: 0 },
          orientation: { x: 0, y: 0, z: 0, w: 1 },
          matrix: identity,
        },
        2
      )
      log('model.scaled', { modelId: modelId.slice(-6), scale: 2, liftMeters: 0.05 })
    } catch (error) {
      fail('model.scale', error)
    }
  }

  const removeModel = () => {
    const modelId = modelIds.current.pop()
    if (!modelId) return log('model.remove.error', 'No model to remove.')
    try {
      xrRef.current?.removeModel(modelId)
      log('model.removed', modelId.slice(-6))
    } catch (error) {
      fail('model.remove', error)
    }
  }

  const onLayout = (event: LayoutChangeEvent) => {
    viewSize.current = event.nativeEvent.layout
  }

  const placeAnchor = async (locationX: number, locationY: number) => {
    if (!running || !xrRef.current) return
    try {
      const hits = await xrRef.current.hitTest(
        locationX / viewSize.current.width,
        locationY / viewSize.current.height
      )
      if (!hits[0]) {
        log('hit.none')
        return
      }
      const anchor = await xrRef.current.createAnchor(hits[0].pose)
      lastAnchorId.current = anchor.id
      log('anchor.created', {
        id: anchor.id.slice(-6),
        hit: hits[0].type,
        tracking: anchor.tracking,
        position: brief(anchor.pose),
      })
    } catch (error) {
      fail('anchor', error)
    }
  }

  const toggle = (name: string, value: boolean, set: (next: boolean) => void) => {
    set(!value)
    log(`prop.${name}`, !value)
  }

  const position = lastFrame?.cameraPose.position

  const buttons: { label: string; onPress: () => void; active?: boolean }[] = [
    { label: 'Reset', onPress: () => void reset() },
    {
      label: `Mode: ${mode}`,
      onPress: () => {
        const next = mode === 'world' ? 'face' : 'world'
        setMode(next)
        log('prop.mode', next)
      },
      active: mode === 'face',
    },
    { label: 'Features', onPress: () => void checkFeatures() },
    { label: 'Anchors', onPress: listAnchors },
    { label: 'Snapshot', onPress: () => void snapshot() },
    {
      label: `Depth ${depthEnabled ? 'on' : 'off'}`,
      onPress: () => toggle('depthEnabled', depthEnabled, setDepthEnabled),
      active: depthEnabled,
    },
    { label: 'Depth frame', onPress: () => void depthFrame() },
    {
      label: `Light ${hdr ? 'HDR' : 'ambient'}`,
      onPress: () => toggle('lightEstimationMode.hdr', hdr, setHdr),
      active: hdr,
    },
    {
      label: `Env tex ${environmentTexturing ? 'auto' : 'none'}`,
      onPress: () =>
        toggle('environmentTexturing.automatic', environmentTexturing, setEnvironmentTexturing),
      active: environmentTexturing,
    },
    {
      label: `Images ${imagesEnabled ? 'on' : 'off'}`,
      onPress: () => toggle('detectionImages', imagesEnabled, setImagesEnabled),
      active: imagesEnabled,
    },
    {
      label: `Mesh ${meshEnabled ? 'on' : 'off'}`,
      onPress: () => toggle('sceneReconstruction', meshEnabled, setMeshEnabled),
      active: meshEnabled,
    },
    { label: 'Export mesh', onPress: () => void exportMesh() },
    { label: 'Add model', onPress: () => void addModel() },
    { label: 'Model x2', onPress: scaleModel },
    { label: 'Remove model', onPress: removeModel },
  ]

  return (
    <SafeAreaView style={styles.safeArea}>
      <StatusBar style="light" />
      <View
        onLayout={onLayout}
        onTouchEnd={(event) =>
          void placeAnchor(event.nativeEvent.locationX, event.nativeEvent.locationY)
        }
        style={styles.stage}
      >
        <XRView
          depthEnabled={depthEnabled}
          depthSmoothing={depthEnabled}
          detectionImages={detectionImages}
          environmentTexturing={environmentTexturing ? 'automatic' : 'none'}
          frameCallbackFps={6}
          hybridRef={hybridRef}
          lightEstimationEnabled
          lightEstimationMode={hdr ? 'environmental-hdr' : 'ambient-intensity'}
          mode={mode}
          onError={onError}
          onFaceAdded={onFaceAdded}
          onFaceRemoved={onFaceRemoved}
          onFaceUpdated={onFaceUpdated}
          onFrame={onFrame}
          onImageAnchorAdded={onImageAnchorAdded}
          onImageAnchorRemoved={onImageAnchorRemoved}
          onImageAnchorUpdated={onImageAnchorUpdated}
          onMeshAnchorAdded={onMeshAnchorAdded}
          onMeshAnchorRemoved={onMeshAnchorRemoved}
          onMeshAnchorUpdated={onMeshAnchorUpdated}
          onPlaneDetected={onPlaneDetected}
          onPlaneRemoved={onPlaneRemoved}
          onPlaneUpdated={onPlaneUpdated}
          onReady={onReady}
          onTrackingStateChange={onTrackingStateChange}
          planeDetection="both"
          sceneReconstruction={meshEnabled ? 'mesh-with-classification' : 'none'}
          style={StyleSheet.absoluteFill}
          trackableUpdateMaxHz={UPDATE_HZ}
        />

        <View pointerEvents="none" style={styles.header}>
          <Text style={styles.title}>Munim XR</Text>
          {logLines.map((line, index) => (
            <Text key={`${index}-${line}`} numberOfLines={2} style={styles.logLine}>
              {line}
            </Text>
          ))}
        </View>

        <View pointerEvents="none" style={styles.reticle}>
          <View style={styles.reticleHorizontal} />
          <View style={styles.reticleVertical} />
        </View>

        <View pointerEvents="none" style={styles.telemetry}>
          <Text style={styles.telemetryText}>
            {Platform.OS} · {mode} · planes {planeCount}
          </Text>
          <Text style={styles.telemetryText}>
            {position
              ? `${position.x.toFixed(2)}, ${position.y.toFixed(2)}, ${position.z.toFixed(2)}`
              : 'Awaiting pose'}
          </Text>
        </View>
      </View>

      <View style={styles.controls}>
        <ScrollView
          contentContainerStyle={styles.buttonGrid}
          style={styles.buttonScroll}
        >
          {buttons.map((button) => (
            <Pressable
              key={button.label}
              onPress={button.onPress}
              style={({ pressed }) => [
                styles.chip,
                button.active && styles.chipActive,
                pressed && styles.buttonPressed,
              ]}
            >
              <Text style={[styles.chipText, button.active && styles.chipTextActive]}>
                {button.label}
              </Text>
            </Pressable>
          ))}
        </ScrollView>
        <Pressable
          disabled={starting}
          onPress={running ? pause : start}
          style={({ pressed }) => [styles.button, pressed && styles.buttonPressed]}
        >
          {starting ? (
            <ActivityIndicator color="#07111f" />
          ) : (
            <Text style={styles.buttonText}>{running ? 'Pause XR' : 'Start XR'}</Text>
          )}
        </Pressable>
        <Text style={styles.hint}>
          Tap a surface to anchor. Logs are prefixed {TAG} in Metro and logcat.
        </Text>
      </View>
    </SafeAreaView>
  )
}

const styles = StyleSheet.create({
  safeArea: { flex: 1, backgroundColor: '#07111f' },
  stage: { flex: 1, backgroundColor: '#02050a', overflow: 'hidden' },
  header: { left: 16, position: 'absolute', right: 16, top: 16 },
  title: { color: '#f3fbff', fontSize: 24, fontWeight: '800', letterSpacing: -0.6, marginBottom: 6 },
  logLine: {
    backgroundColor: 'rgba(7,17,31,0.6)',
    color: '#c3dce6',
    fontFamily: Platform.select({ ios: 'Courier', default: 'monospace' }),
    fontSize: 10,
    lineHeight: 14,
    marginTop: 2,
    paddingHorizontal: 4,
  },
  reticle: { alignItems: 'center', height: 36, justifyContent: 'center', left: '50%', marginLeft: -18, marginTop: -18, position: 'absolute', top: '50%', width: 36 },
  reticleHorizontal: { backgroundColor: '#70e8ff', height: 1, position: 'absolute', width: 36 },
  reticleVertical: { backgroundColor: '#70e8ff', height: 36, position: 'absolute', width: 1 },
  telemetry: { backgroundColor: 'rgba(7,17,31,0.76)', borderColor: 'rgba(112,232,255,0.3)', borderRadius: 12, borderWidth: 1, bottom: 12, left: 12, paddingHorizontal: 12, paddingVertical: 8, position: 'absolute' },
  telemetryText: { color: '#d8f6ff', fontFamily: Platform.select({ ios: 'Courier', default: 'monospace' }), fontSize: 11, lineHeight: 17 },
  controls: { backgroundColor: '#07111f', paddingBottom: 18, paddingHorizontal: 14, paddingTop: 10 },
  buttonScroll: { maxHeight: 132, marginBottom: 10 },
  buttonGrid: { flexDirection: 'row', flexWrap: 'wrap', gap: 6 },
  chip: { borderColor: 'rgba(112,232,255,0.45)', borderRadius: 10, borderWidth: 1, paddingHorizontal: 10, paddingVertical: 8 },
  chipActive: { backgroundColor: '#70e8ff' },
  chipText: { color: '#d8f6ff', fontSize: 12, fontWeight: '700' },
  chipTextActive: { color: '#07111f' },
  button: { alignItems: 'center', backgroundColor: '#70e8ff', borderRadius: 14, justifyContent: 'center', minHeight: 48 },
  buttonPressed: { opacity: 0.82, transform: [{ scale: 0.99 }] },
  buttonText: { color: '#07111f', fontSize: 16, fontWeight: '900' },
  hint: { color: '#78929d', fontSize: 11, marginTop: 8, textAlign: 'center' },
})
