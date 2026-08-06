import ARKit
import AVFoundation
import NitroModules
import SceneKit
import simd
import UIKit

final class HybridXRView: HybridXRViewSpec {
  let view: UIView

  private let sceneView: ARSCNView
  private var running = false
  private var deliveredReady = false
  private var lastFrameCallbackTimestamp: TimeInterval = 0
  private var lastTrackingState: XRTrackingState = .unavailable
  private var anchors: [String: ARAnchor] = [:]
  private let stateLock = NSLock()
  private lazy var sessionDelegate = XRSessionDelegate(owner: self)

  var planeDetection: XRPlaneDetection = .both {
    didSet { reconfigureIfRunning() }
  }

  var depthEnabled = false {
    didSet { reconfigureIfRunning() }
  }

  var lightEstimationEnabled = true {
    didSet { reconfigureIfRunning() }
  }

  var frameCallbackFps: Double = 15
  var onReady: (() -> Void)?
  var onFrame: ((_ frame: XRFrame) -> Void)?
  var onTrackingStateChange: ((_ state: XRTrackingState) -> Void)?
  var onPlaneDetected: ((_ plane: XRPlane) -> Void)?
  var onPlaneUpdated: ((_ plane: XRPlane) -> Void)?
  var onPlaneRemoved: ((_ planeId: String) -> Void)?
  var onError: ((_ message: String) -> Void)?

  override init() {
    let arView = ARSCNView(frame: .zero)
    arView.automaticallyUpdatesLighting = true
    arView.scene = SCNScene()
    sceneView = arView
    view = arView
    super.init()
    arView.session.delegate = sessionDelegate
  }

  func start() throws -> Promise<Void> {
    Promise.async {
      let granted = await AVCaptureDevice.requestAccess(for: .video)
      guard granted else { throw MunimXrError.cameraPermissionDenied }

      try await MainActor.run {
        try self.startSession(resetTracking: false)
      }
    }
  }

  func pause() throws {
    DispatchQueue.main.async {
      self.sceneView.session.pause()
      self.running = false
    }
  }

  func reset() throws -> Promise<Void> {
    Promise.async {
      try await MainActor.run {
        try self.startSession(resetTracking: true)
      }
    }
  }

  func hitTest(normalizedX: Double, normalizedY: Double) throws -> Promise<[XRHitResult]> {
    guard normalizedX.isFinite, normalizedY.isFinite,
          (0...1).contains(normalizedX), (0...1).contains(normalizedY)
    else {
      throw MunimXrError.invalidNormalizedPoint
    }

    return Promise.async {
      await MainActor.run {
        let point = CGPoint(
          x: self.sceneView.bounds.width * normalizedX,
          y: self.sceneView.bounds.height * normalizedY
        )
        let existingPlaneResults = self.sceneView.raycastQuery(
          from: point,
          allowing: .existingPlaneGeometry,
          alignment: .any
        ).map(self.sceneView.session.raycast) ?? []
        let results: [ARRaycastResult]
        if existingPlaneResults.isEmpty {
          results = self.sceneView.raycastQuery(
            from: point,
            allowing: .estimatedPlane,
            alignment: .any
          ).map(self.sceneView.session.raycast) ?? []
        } else {
          results = existingPlaneResults
        }
        let cameraPosition = self.sceneView.session.currentFrame?.camera.transform.columns.3
        return results.map { result in
          XRHitResult(
            type: result.target == .estimatedPlane ? .estimated : .plane,
            distance: Self.distance(from: cameraPosition, to: result.worldTransform.columns.3),
            pose: Self.xrPose(result.worldTransform),
            planeId: result.anchor?.identifier.uuidString
          )
        }
      }
    }
  }

  func createAnchor(pose: XRPose) throws -> Promise<XRAnchor> {
    let transform = try Self.transform(pose)
    return Promise.async {
      await MainActor.run {
        let anchor = ARAnchor(transform: transform)
        self.sceneView.session.add(anchor: anchor)
        let id = anchor.identifier.uuidString
        self.stateLock.synchronized { self.anchors[id] = anchor }
        return XRAnchor(id: id, tracking: true, pose: Self.xrPose(transform))
      }
    }
  }

  func removeAnchor(anchorId: String) throws {
    guard let anchor = stateLock.synchronized({ anchors.removeValue(forKey: anchorId) })
    else { return }
    DispatchQueue.main.async {
      self.sceneView.session.remove(anchor: anchor)
    }
  }

  func getAnchors() throws -> [XRAnchor] {
    stateLock.synchronized {
      anchors.map { id, anchor in
        XRAnchor(id: id, tracking: true, pose: Self.xrPose(anchor.transform))
      }
    }
  }

  func getCameraPose() throws -> XRPose? {
    guard let frame = sceneView.session.currentFrame else { return nil }
    return Self.xrPose(frame.camera.transform)
  }

  func captureSnapshot() throws -> Promise<String> {
    Promise.async {
      let image = await MainActor.run { self.sceneView.snapshot() }
      guard let data = image.pngData() else {
        throw MunimXrError.snapshotEncodingFailed
      }
      let url = FileManager.default.temporaryDirectory
        .appendingPathComponent("munim-xr-\(UUID().uuidString).png")
      try data.write(to: url, options: .atomic)
      return url.path
    }
  }

  func session(_ session: ARSession, didUpdate frame: ARFrame) {
    let trackingState = Self.trackingState(frame.camera.trackingState)
    if trackingState != lastTrackingState {
      lastTrackingState = trackingState
      onTrackingStateChange?(trackingState)
    }

    if !deliveredReady {
      deliveredReady = true
      onReady?()
    }

    let callbackInterval = frameCallbackFps > 0 ? 1 / frameCallbackFps : .infinity
    guard frame.timestamp - lastFrameCallbackTimestamp >= callbackInterval else { return }
    lastFrameCallbackTimestamp = frame.timestamp
    var lightIntensity: Double?
    if lightEstimationEnabled, let ambientIntensity = frame.lightEstimate?.ambientIntensity {
      lightIntensity = Double(ambientIntensity)
    }
    let xrFrame = XRFrame(
      timestamp: frame.timestamp,
      trackingState: trackingState,
      cameraPose: Self.xrPose(frame.camera.transform),
      lightIntensity: lightIntensity
    )
    onFrame?(xrFrame)
  }

  func session(_ session: ARSession, didAdd anchors: [ARAnchor]) {
    for anchor in anchors {
      if let plane = anchor as? ARPlaneAnchor {
        onPlaneDetected?(Self.xrPlane(plane))
      }
    }
  }

  func session(_ session: ARSession, didUpdate anchors: [ARAnchor]) {
    for anchor in anchors {
      if let plane = anchor as? ARPlaneAnchor {
        onPlaneUpdated?(Self.xrPlane(plane))
      }
    }
  }

  func session(_ session: ARSession, didRemove anchors: [ARAnchor]) {
    for anchor in anchors where anchor is ARPlaneAnchor {
      onPlaneRemoved?(anchor.identifier.uuidString)
    }
  }

  func session(_ session: ARSession, didFailWithError error: Error) {
    onError?(error.localizedDescription)
  }

  private func startSession(resetTracking: Bool) throws {
    guard ARWorldTrackingConfiguration.isSupported else {
      throw MunimXrError.unsupportedDevice
    }

    let configuration = ARWorldTrackingConfiguration()
    switch planeDetection {
    case .none:
      configuration.planeDetection = []
    case .horizontal:
      configuration.planeDetection = [.horizontal]
    case .vertical:
      configuration.planeDetection = [.vertical]
    case .both:
      configuration.planeDetection = [.horizontal, .vertical]
    }
    configuration.isLightEstimationEnabled = lightEstimationEnabled
    if #available(iOS 13.0, *), depthEnabled,
       ARWorldTrackingConfiguration.supportsFrameSemantics(.sceneDepth) {
      configuration.frameSemantics.insert(.sceneDepth)
    }

    var options: ARSession.RunOptions = []
    if resetTracking {
      options = [.resetTracking, .removeExistingAnchors]
      stateLock.synchronized { anchors.removeAll() }
      deliveredReady = false
    }
    sceneView.session.run(configuration, options: options)
    running = true
  }

  private func reconfigureIfRunning() {
    guard running else { return }
    DispatchQueue.main.async {
      do {
        try self.startSession(resetTracking: false)
      } catch {
        self.onError?(error.localizedDescription)
      }
    }
  }

  private static func trackingState(_ state: ARCamera.TrackingState) -> XRTrackingState {
    switch state {
    case .notAvailable: return .unavailable
    case .limited: return .limited
    case .normal: return .normal
    }
  }

  private static func distance(from start: SIMD4<Float>?, to end: SIMD4<Float>) -> Double {
    guard let start else { return 0 }
    return Double(simd_distance(
      SIMD3(start.x, start.y, start.z),
      SIMD3(end.x, end.y, end.z)
    ))
  }

  private static func xrPlane(_ anchor: ARPlaneAnchor) -> XRPlane {
    XRPlane(
      id: anchor.identifier.uuidString,
      alignment: anchor.alignment == .horizontal ? .horizontal : .vertical,
      classification: planeClassification(anchor.classification),
      center: XRVector3(
        x: Double(anchor.center.x),
        y: Double(anchor.center.y),
        z: Double(anchor.center.z)
      ),
      extent: XRVector3(
        x: Double(anchor.extent.x),
        y: Double(anchor.extent.y),
        z: Double(anchor.extent.z)
      ),
      pose: xrPose(anchor.transform)
    )
  }

  private static func planeClassification(
    _ classification: ARPlaneAnchor.Classification
  ) -> XRPlaneClassification {
    switch classification {
    case .floor: return .floor
    case .wall: return .wall
    case .ceiling: return .ceiling
    case .table: return .table
    case .seat: return .seat
    case .door: return .door
    case .window: return .window
    case .none: return .unknown
    @unknown default: return .unknown
    }
  }

  private static func xrPose(_ transform: simd_float4x4) -> XRPose {
    let position = transform.columns.3
    let quaternion = simd_quatf(transform)
    return XRPose(
      position: XRVector3(
        x: Double(position.x),
        y: Double(position.y),
        z: Double(position.z)
      ),
      orientation: XRQuaternion(
        x: Double(quaternion.vector.x),
        y: Double(quaternion.vector.y),
        z: Double(quaternion.vector.z),
        w: Double(quaternion.vector.w)
      ),
      matrix: [
        Double(transform.columns.0.x), Double(transform.columns.0.y),
        Double(transform.columns.0.z), Double(transform.columns.0.w),
        Double(transform.columns.1.x), Double(transform.columns.1.y),
        Double(transform.columns.1.z), Double(transform.columns.1.w),
        Double(transform.columns.2.x), Double(transform.columns.2.y),
        Double(transform.columns.2.z), Double(transform.columns.2.w),
        Double(transform.columns.3.x), Double(transform.columns.3.y),
        Double(transform.columns.3.z), Double(transform.columns.3.w),
      ]
    )
  }

  private static func transform(_ pose: XRPose) throws -> simd_float4x4 {
    guard pose.matrix.count == 16 else { throw MunimXrError.invalidPoseMatrix }
    let values = pose.matrix.map(Float.init)
    return simd_float4x4(
      SIMD4(values[0], values[1], values[2], values[3]),
      SIMD4(values[4], values[5], values[6], values[7]),
      SIMD4(values[8], values[9], values[10], values[11]),
      SIMD4(values[12], values[13], values[14], values[15])
    )
  }
}

private enum MunimXrError: LocalizedError {
  case cameraPermissionDenied
  case invalidNormalizedPoint
  case invalidPoseMatrix
  case snapshotEncodingFailed
  case unsupportedDevice

  var errorDescription: String? {
    switch self {
    case .cameraPermissionDenied:
      return "Camera permission is required to start an XR session."
    case .invalidNormalizedPoint:
      return "Hit-test coordinates must be finite values between 0 and 1."
    case .invalidPoseMatrix:
      return "An XR pose matrix must contain exactly 16 values."
    case .snapshotEncodingFailed:
      return "The XR view snapshot could not be encoded as PNG."
    case .unsupportedDevice:
      return "This device does not support ARKit world tracking."
    }
  }
}

private final class XRSessionDelegate: NSObject, ARSessionDelegate {
  weak var owner: HybridXRView?

  init(owner: HybridXRView) {
    self.owner = owner
    super.init()
  }

  func session(_ session: ARSession, didUpdate frame: ARFrame) {
    owner?.session(session, didUpdate: frame)
  }

  func session(_ session: ARSession, didAdd anchors: [ARAnchor]) {
    owner?.session(session, didAdd: anchors)
  }

  func session(_ session: ARSession, didUpdate anchors: [ARAnchor]) {
    owner?.session(session, didUpdate: anchors)
  }

  func session(_ session: ARSession, didRemove anchors: [ARAnchor]) {
    owner?.session(session, didRemove: anchors)
  }

  func session(_ session: ARSession, didFailWithError error: Error) {
    owner?.session(session, didFailWithError: error)
  }
}

private extension NSLock {
  func synchronized<T>(_ body: () throws -> T) rethrows -> T {
    lock()
    defer { unlock() }
    return try body()
  }
}
