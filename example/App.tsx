import { useMemo, useRef, useState } from 'react'
import { StatusBar } from 'expo-status-bar'
import {
  ActivityIndicator,
  LayoutChangeEvent,
  Pressable,
  SafeAreaView,
  StyleSheet,
  Text,
  View,
} from 'react-native'
import { callback } from 'react-native-nitro-modules'
import {
  checkAvailability,
  requestCameraPermission,
  requestInstall,
  XRView,
  type XRFrame,
  type XRViewRef,
} from 'munim-xr'

export default function App() {
  const xrRef = useRef<XRViewRef | null>(null)
  const viewSize = useRef({ width: 1, height: 1 })
  const [running, setRunning] = useState(false)
  const [starting, setStarting] = useState(false)
  const [status, setStatus] = useState('Tap Start XR to begin.')
  const [planeCount, setPlaneCount] = useState(0)
  const [lastFrame, setLastFrame] = useState<XRFrame | null>(null)

  const hybridRef = useMemo(
    () => callback((ref: XRViewRef) => (xrRef.current = ref)),
    []
  )
  const onReady = useMemo(
    () => callback(() => setStatus('XR session ready — move the device slowly.')),
    []
  )
  const onFrame = useMemo(
    () => callback((frame: XRFrame) => setLastFrame(frame)),
    []
  )
  const onTrackingStateChange = useMemo(
    () => callback((state: string) => setStatus(`Tracking: ${state}`)),
    []
  )
  const onPlaneDetected = useMemo(
    () => callback(() => setPlaneCount((count) => count + 1)),
    []
  )
  const onPlaneRemoved = useMemo(
    () => callback(() => setPlaneCount((count) => Math.max(0, count - 1))),
    []
  )
  const onError = useMemo(
    () => callback((message: string) => setStatus(`Error: ${message}`)),
    []
  )

  const start = async () => {
    setStarting(true)
    try {
      const permission = await requestCameraPermission()
      if (!permission) throw new Error('Camera permission was denied.')

      const availability = await checkAvailability()
      if (availability === 'not-installed' || availability === 'update-required') {
        const installed = await requestInstall()
        if (!installed) {
          setStatus('Finish installing Google Play Services for AR, then tap Start again.')
          return
        }
      }
      if (availability === 'unsupported') {
        throw new Error('This device does not support world-tracked XR.')
      }

      await xrRef.current?.start()
      setRunning(true)
      setStatus('Starting native XR session…')
    } catch (error) {
      setStatus(error instanceof Error ? error.message : String(error))
    } finally {
      setStarting(false)
    }
  }

  const pause = () => {
    xrRef.current?.pause()
    setRunning(false)
    setStatus('XR session paused.')
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
        setStatus('No surface found at that point yet.')
        return
      }
      const anchor = await xrRef.current.createAnchor(hits[0].pose)
      setStatus(`Anchor ${anchor.id.slice(-8)} placed.`)
    } catch (error) {
      setStatus(error instanceof Error ? error.message : String(error))
    }
  }

  const position = lastFrame?.cameraPose.position

  return (
    <SafeAreaView style={styles.safeArea}>
      <StatusBar style="light" />
      <View
        onLayout={onLayout}
        onTouchEnd={(event) =>
          void placeAnchor(
            event.nativeEvent.locationX,
            event.nativeEvent.locationY
          )
        }
        style={styles.stage}
      >
        <XRView
          depthEnabled
          frameCallbackFps={6}
          hybridRef={hybridRef}
          lightEstimationEnabled
          onError={onError}
          onFrame={onFrame}
          onPlaneDetected={onPlaneDetected}
          onPlaneRemoved={onPlaneRemoved}
          onReady={onReady}
          onTrackingStateChange={onTrackingStateChange}
          planeDetection="both"
          style={StyleSheet.absoluteFill}
        />

        <View pointerEvents="none" style={styles.header}>
          <Text style={styles.eyebrow}>NITRO XR</Text>
          <Text style={styles.title}>Munim XR</Text>
          <Text style={styles.status}>{status}</Text>
        </View>

        <View pointerEvents="none" style={styles.reticle}>
          <View style={styles.reticleHorizontal} />
          <View style={styles.reticleVertical} />
        </View>

        <View pointerEvents="none" style={styles.telemetry}>
          <Text style={styles.telemetryText}>Planes {planeCount}</Text>
          <Text style={styles.telemetryText}>
            {position
              ? `${position.x.toFixed(2)}, ${position.y.toFixed(2)}, ${position.z.toFixed(2)}`
              : 'Awaiting pose'}
          </Text>
        </View>
      </View>

      <View style={styles.controls}>
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
        <Text style={styles.hint}>Tap a detected surface to create a native anchor.</Text>
      </View>
    </SafeAreaView>
  )
}

const styles = StyleSheet.create({
  safeArea: { flex: 1, backgroundColor: '#07111f' },
  stage: { flex: 1, backgroundColor: '#02050a', overflow: 'hidden' },
  header: { left: 20, position: 'absolute', right: 20, top: 24 },
  eyebrow: { color: '#70e8ff', fontSize: 11, fontWeight: '900', letterSpacing: 2 },
  title: { color: '#f3fbff', fontSize: 38, fontWeight: '800', letterSpacing: -1.2, marginTop: 6 },
  status: { color: '#c3dce6', fontSize: 14, lineHeight: 20, marginTop: 6, maxWidth: 330 },
  reticle: { alignItems: 'center', height: 36, justifyContent: 'center', left: '50%', marginLeft: -18, marginTop: -18, position: 'absolute', top: '50%', width: 36 },
  reticleHorizontal: { backgroundColor: '#70e8ff', height: 1, position: 'absolute', width: 36 },
  reticleVertical: { backgroundColor: '#70e8ff', height: 36, position: 'absolute', width: 1 },
  telemetry: { backgroundColor: 'rgba(7,17,31,0.76)', borderColor: 'rgba(112,232,255,0.3)', borderRadius: 12, borderWidth: 1, bottom: 18, left: 18, paddingHorizontal: 12, paddingVertical: 9, position: 'absolute' },
  telemetryText: { color: '#d8f6ff', fontFamily: 'Courier', fontSize: 11, lineHeight: 17 },
  controls: { backgroundColor: '#07111f', padding: 18, paddingBottom: 26 },
  button: { alignItems: 'center', backgroundColor: '#70e8ff', borderRadius: 14, justifyContent: 'center', minHeight: 52 },
  buttonPressed: { opacity: 0.82, transform: [{ scale: 0.99 }] },
  buttonText: { color: '#07111f', fontSize: 16, fontWeight: '900' },
  hint: { color: '#78929d', fontSize: 12, marginTop: 10, textAlign: 'center' },
})
