import ARKit
import AVFoundation
import ModelIO
import NitroModules
import SceneKit
import simd
import UIKit

final class HybridXRView: HybridXRViewSpec {
  let view: UIView

  private let sceneView: ARSCNView
  private lazy var sessionDelegate = XRSessionDelegate(owner: self)

  // Guarded by `stateLock`: read from JS-thread methods and written on main.
  private let stateLock = NSLock()
  private var _running = false
  private var userAnchors: [String: ARAnchor] = [:]
  private var _cameraTrackingNormal = false

  // Main-thread only.
  private var deliveredReady = false
  private var lastFrameCallbackTimestamp: TimeInterval = 0
  private var lastTrackingState: XRTrackingState = .unavailable
  private var needsReconfigure = false
  private var activeMode: XRSessionMode?
  private var updateThrottle: [String: ThrottleEntry] = [:]
  private var models: [String: ModelEntry] = [:]
  private var referenceImages: Set<ARReferenceImage> = []
  private var loadedImageSpecs: [ImageSpec] = []
  private var imageLoadGeneration = 0
  private var latestFaceDepth: (data: AVDepthData, timestamp: TimeInterval)?

  private var running: Bool {
    get { stateLock.synchronized { _running } }
    set { stateLock.synchronized { _running = newValue } }
  }

  var planeDetection: XRPlaneDetection = .both {
    didSet { if oldValue != planeDetection { needsReconfigure = true } }
  }

  var depthEnabled = false {
    didSet { if oldValue != depthEnabled { needsReconfigure = true } }
  }

  var lightEstimationEnabled = true {
    didSet { if oldValue != lightEstimationEnabled { needsReconfigure = true } }
  }

  var frameCallbackFps: Double = 15

  var mode: XRSessionMode? {
    didSet { if oldValue != mode { needsReconfigure = true } }
  }

  var trackableUpdateMaxHz: Double?

  var lightEstimationMode: XRLightEstimationMode?

  var environmentTexturing: XREnvironmentTexturing? {
    didSet { if oldValue != environmentTexturing { needsReconfigure = true } }
  }

  var depthSmoothing: Bool? {
    didSet { if oldValue != depthSmoothing { needsReconfigure = true } }
  }

  var detectionImages: [XRDetectionImage]? {
    didSet { detectionImagesChanged() }
  }

  var sceneReconstruction: XRSceneReconstruction? {
    didSet { if oldValue != sceneReconstruction { needsReconfigure = true } }
  }

  var onReady: (() -> Void)?
  var onFrame: ((_ frame: XRFrame) -> Void)?
  var onTrackingStateChange: ((_ state: XRTrackingState) -> Void)?
  var onPlaneDetected: ((_ plane: XRPlane) -> Void)?
  var onPlaneUpdated: ((_ plane: XRPlane) -> Void)?
  var onPlaneRemoved: ((_ planeId: String) -> Void)?
  var onImageAnchorAdded: ((_ anchor: XRImageAnchor) -> Void)?
  var onImageAnchorUpdated: ((_ anchor: XRImageAnchor) -> Void)?
  var onImageAnchorRemoved: ((_ anchor: XRImageAnchor) -> Void)?
  var onMeshAnchorAdded: ((_ mesh: XRMeshAnchor) -> Void)?
  var onMeshAnchorUpdated: ((_ mesh: XRMeshAnchor) -> Void)?
  var onMeshAnchorRemoved: ((_ meshId: String) -> Void)?
  var onFaceAdded: ((_ face: XRFace) -> Void)?
  var onFaceUpdated: ((_ face: XRFace) -> Void)?
  var onFaceRemoved: ((_ faceId: String) -> Void)?
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

  // MARK: - Lifecycle

  func afterUpdate() {
    guard needsReconfigure else { return }
    needsReconfigure = false
    reconfigureIfRunning()
  }

  func onDropView() {
    running = false
    DispatchQueue.main.async {
      self.sceneView.session.pause()
      for entry in self.models.values { entry.container.removeFromParentNode() }
      self.models.removeAll()
      self.updateThrottle.removeAll()
      self.latestFaceDepth = nil
    }
    stateLock.synchronized { userAnchors.removeAll() }
  }

  func start() throws -> Promise<Void> {
    Promise.async {
      let granted = await AVCaptureDevice.requestAccess(for: .video)
      guard granted else { throw MunimXrError.cameraPermissionDenied }

      let specs = await MainActor.run { self.currentImageSpecs() }
      let images = try await self.referenceImagesIfNeeded(for: specs)

      try await MainActor.run {
        if let images {
          self.referenceImages = images
          self.loadedImageSpecs = specs
        }
        try self.startSession(resetTracking: false)
      }
    }
  }

  func pause() throws {
    running = false
    DispatchQueue.main.async {
      self.sceneView.session.pause()
    }
  }

  func reset() throws -> Promise<Void> {
    Promise.async {
      try await MainActor.run {
        try self.startSession(resetTracking: true)
      }
    }
  }

  // MARK: - Hit testing and anchors

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
        let tracking = self.stateLock.synchronized { () -> Bool in
          self.userAnchors[id] = anchor
          return self._cameraTrackingNormal
        }
        return XRAnchor(id: id, tracking: tracking, pose: Self.xrPose(transform))
      }
    }
  }

  func removeAnchor(anchorId: String) throws {
    guard let anchor = stateLock.synchronized({ userAnchors.removeValue(forKey: anchorId) })
    else { return }
    DispatchQueue.main.async {
      self.sceneView.session.remove(anchor: anchor)
    }
  }

  func getAnchors() throws -> [XRAnchor] {
    stateLock.synchronized {
      userAnchors.map { id, anchor in
        XRAnchor(
          id: id,
          tracking: Self.isTracked(anchor, cameraTrackingNormal: _cameraTrackingNormal),
          pose: Self.xrPose(anchor.transform)
        )
      }
    }
  }

  func getCameraPose() throws -> XRPose? {
    guard let frame = sceneView.session.currentFrame else { return nil }
    return Self.xrPose(frame.camera.transform)
  }

  // MARK: - Snapshot, depth, and mesh export

  func captureSnapshot() throws -> Promise<String> {
    Promise.async {
      let image = await MainActor.run { self.sceneView.snapshot() }
      guard let data = image.pngData() else {
        throw MunimXrError.snapshotEncodingFailed
      }
      let url = try XROutputFiles.nextURL(kind: "snapshots", fileExtension: "png", keep: 10)
      try data.write(to: url, options: .atomic)
      return url.path
    }
  }

  func getDepthFrame() throws -> Promise<XRDepthFrame> {
    Promise.async {
      let capture = try await MainActor.run { () throws -> DepthCapture in
        guard self.depthEnabled else { throw MunimXrError.depthDisabled }
        if self.activeMode == .face {
          guard let latest = self.latestFaceDepth else { throw MunimXrError.depthNotYetAvailable }
          let converted = latest.data.depthDataType == kCVPixelFormatType_DepthFloat32
            ? latest.data
            : latest.data.converting(toDepthDataType: kCVPixelFormatType_DepthFloat32)
          return DepthCapture(
            depth: converted.depthDataMap,
            confidence: nil,
            timestamp: latest.timestamp,
            smoothed: false
          )
        }
        guard ARWorldTrackingConfiguration.supportsFrameSemantics(.sceneDepth) else {
          throw MunimXrError.depthUnsupported
        }
        guard let frame = self.sceneView.session.currentFrame else {
          throw MunimXrError.depthNotYetAvailable
        }
        let wantsSmoothed = self.depthSmoothing == true
        let smoothed = wantsSmoothed ? frame.smoothedSceneDepth : nil
        guard let depth = smoothed ?? frame.sceneDepth else {
          throw MunimXrError.depthNotYetAvailable
        }
        return DepthCapture(
          depth: depth.depthMap,
          confidence: depth.confidenceMap,
          timestamp: frame.timestamp,
          smoothed: smoothed != nil
        )
      }
      return try Self.xrDepthFrame(capture)
    }
  }

  func exportMesh() throws -> Promise<String> {
    Promise.async {
      let meshAnchors = await MainActor.run {
        self.sceneView.session.currentFrame?.anchors.compactMap { $0 as? ARMeshAnchor } ?? []
      }
      guard !meshAnchors.isEmpty else { throw MunimXrError.noMeshAvailable }
      let url = try XROutputFiles.nextURL(kind: "meshes", fileExtension: "obj", keep: 3)
      try Self.exportOBJ(meshAnchors, to: url)
      return url.path
    }
  }

  // MARK: - Models

  func addModel(options: XRModelOptions) throws -> Promise<String> {
    let localTransform = try options.pose.map(Self.transform) ?? matrix_identity_float4x4
    let scale = Float(options.scale ?? 1)
    guard scale.isFinite, scale > 0 else { throw MunimXrError.invalidScale }
    let uri = options.uri
    let anchorId = options.anchorId

    return Promise.async {
      let fileURL = try await XRFileLoader.localFileURL(for: uri, kind: "models")
      let content = try Self.loadModelNode(fileURL)
      return try await MainActor.run { () throws -> String in
        if let anchorId, self.anchor(withId: anchorId) == nil {
          throw MunimXrError.anchorNotFound(anchorId)
        }
        let container = SCNNode()
        content.simdScale = SIMD3(repeating: scale)
        container.addChildNode(content)
        let id = "model-\(UUID().uuidString)"
        let entry = ModelEntry(
          container: container,
          content: content,
          anchorId: anchorId,
          localTransform: localTransform
        )
        self.models[id] = entry
        self.applyModelTransform(entry)
        self.sceneView.scene.rootNode.addChildNode(container)
        return id
      }
    }
  }

  func removeModel(modelId: String) throws {
    DispatchQueue.main.async {
      self.models.removeValue(forKey: modelId)?.container.removeFromParentNode()
    }
  }

  func setModelTransform(modelId: String, pose: XRPose, scale: Double?) throws {
    let transform = try Self.transform(pose)
    if let scale {
      guard scale.isFinite, scale > 0 else { throw MunimXrError.invalidScale }
    }
    DispatchQueue.main.async {
      guard let entry = self.models[modelId] else {
        self.onError?(MunimXrError.modelNotFound(modelId).localizedDescription)
        return
      }
      entry.localTransform = transform
      if let scale { entry.content.simdScale = SIMD3(repeating: Float(scale)) }
      self.applyModelTransform(entry)
    }
  }

  // MARK: - ARSessionDelegate (main thread)

  func session(_ session: ARSession, didUpdate frame: ARFrame) {
    let trackingState = Self.trackingState(frame.camera.trackingState)
    if trackingState != lastTrackingState {
      lastTrackingState = trackingState
      stateLock.synchronized { _cameraTrackingNormal = trackingState == .normal }
      onTrackingStateChange?(trackingState)
    }

    if activeMode == .face, depthEnabled, let depthData = frame.capturedDepthData {
      latestFaceDepth = (depthData, frame.capturedDepthDataTimestamp)
    }

    if !deliveredReady {
      deliveredReady = true
      onReady?()
    }

    let callbackInterval = frameCallbackFps > 0 ? 1 / frameCallbackFps : .infinity
    guard frame.timestamp - lastFrameCallbackTimestamp >= callbackInterval else { return }
    lastFrameCallbackTimestamp = frame.timestamp
    let lightEstimate = lightEstimationEnabled
      ? frame.lightEstimate.map(Self.xrLightEstimate)
      : nil
    let xrFrame = XRFrame(
      timestamp: frame.timestamp,
      trackingState: trackingState,
      cameraPose: Self.xrPose(frame.camera.transform),
      lightIntensity: lightEstimate?.ambientIntensity,
      lightEstimate: lightEstimate
    )
    onFrame?(xrFrame)
  }

  func session(_ session: ARSession, didAdd anchors: [ARAnchor]) {
    let now = CACurrentMediaTime()
    for anchor in anchors {
      refreshUserAnchor(anchor)
      let id = anchor.identifier.uuidString
      switch anchor {
      case let plane as ARPlaneAnchor:
        let xrPlane = Self.xrPlane(plane)
        updateThrottle[id] = ThrottleEntry(time: now, signature: Self.signature(xrPlane))
        onPlaneDetected?(xrPlane)
      case let image as ARImageAnchor:
        let xrImage = Self.xrImageAnchor(image)
        updateThrottle[id] = ThrottleEntry(time: now, signature: Self.signature(xrImage))
        onImageAnchorAdded?(xrImage)
      case let mesh as ARMeshAnchor:
        updateThrottle[id] = ThrottleEntry(time: now, signature: Self.meshSignature(mesh))
        onMeshAnchorAdded?(Self.xrMeshAnchor(mesh))
      case let face as ARFaceAnchor:
        let xrFace = Self.xrFace(face)
        updateThrottle[id] = ThrottleEntry(time: now, signature: Self.signature(xrFace))
        onFaceAdded?(xrFace)
      default:
        break
      }
    }
  }

  func session(_ session: ARSession, didUpdate anchors: [ARAnchor]) {
    let now = CACurrentMediaTime()
    for anchor in anchors {
      refreshUserAnchor(anchor)
      updateAttachedModels(for: anchor)
      let id = anchor.identifier.uuidString
      switch anchor {
      case let plane as ARPlaneAnchor:
        guard onPlaneUpdated != nil else { continue }
        let xrPlane = Self.xrPlane(plane)
        if shouldEmitUpdate(id: id, signature: Self.signature(xrPlane), now: now) {
          onPlaneUpdated?(xrPlane)
        }
      case let image as ARImageAnchor:
        guard onImageAnchorUpdated != nil else { continue }
        let xrImage = Self.xrImageAnchor(image)
        if shouldEmitUpdate(
          id: id,
          signature: Self.signature(xrImage),
          now: now,
          forceIndex: 0
        ) {
          onImageAnchorUpdated?(xrImage)
        }
      case let mesh as ARMeshAnchor:
        guard onMeshAnchorUpdated != nil else { continue }
        if shouldEmitUpdate(id: id, signature: Self.meshSignature(mesh), now: now) {
          onMeshAnchorUpdated?(Self.xrMeshAnchor(mesh))
        }
      case let face as ARFaceAnchor:
        guard onFaceUpdated != nil else { continue }
        let xrFace = Self.xrFace(face)
        if shouldEmitUpdate(id: id, signature: Self.signature(xrFace), now: now, forceIndex: 0) {
          onFaceUpdated?(xrFace)
        }
      default:
        break
      }
    }
  }

  func session(_ session: ARSession, didRemove anchors: [ARAnchor]) {
    for anchor in anchors {
      let id = anchor.identifier.uuidString
      updateThrottle.removeValue(forKey: id)
      stateLock.synchronized { _ = userAnchors.removeValue(forKey: id) }
      for entry in models.values where entry.anchorId == id {
        entry.container.isHidden = true
      }
      switch anchor {
      case is ARPlaneAnchor:
        onPlaneRemoved?(id)
      case let image as ARImageAnchor:
        onImageAnchorRemoved?(Self.xrImageAnchor(image))
      case is ARMeshAnchor:
        onMeshAnchorRemoved?(id)
      case is ARFaceAnchor:
        onFaceRemoved?(id)
      default:
        break
      }
    }
  }

  func session(_ session: ARSession, didFailWithError error: Error) {
    onError?(error.localizedDescription)
  }

  // MARK: - Session configuration (main thread)

  private func startSession(resetTracking: Bool) throws {
    let requestedMode = mode ?? .world
    let configuration = try makeConfiguration(mode: requestedMode)
    let modeChanged = activeMode != nil && activeMode != requestedMode

    var options: ARSession.RunOptions = []
    if resetTracking || modeChanged {
      options = [.resetTracking, .removeExistingAnchors]
      stateLock.synchronized { userAnchors.removeAll() }
      updateThrottle.removeAll()
      latestFaceDepth = nil
      deliveredReady = false
    }
    sceneView.session.run(configuration, options: options)
    activeMode = requestedMode
    running = true
  }

  private func makeConfiguration(mode: XRSessionMode) throws -> ARConfiguration {
    switch mode {
    case .face:
      guard ARFaceTrackingConfiguration.isSupported else {
        throw MunimXrError.faceTrackingUnsupported
      }
      let configuration = ARFaceTrackingConfiguration()
      configuration.isLightEstimationEnabled = lightEstimationEnabled
      return configuration

    case .world:
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
      switch environmentTexturing ?? .none {
      case .none: configuration.environmentTexturing = .none
      case .manual: configuration.environmentTexturing = .manual
      case .automatic: configuration.environmentTexturing = .automatic
      }
      if depthEnabled, ARWorldTrackingConfiguration.supportsFrameSemantics(.sceneDepth) {
        configuration.frameSemantics.insert(.sceneDepth)
        if depthSmoothing == true,
           ARWorldTrackingConfiguration.supportsFrameSemantics(.smoothedSceneDepth) {
          configuration.frameSemantics.insert(.smoothedSceneDepth)
        }
      }
      switch sceneReconstruction ?? .none {
      case .none:
        break
      case .mesh:
        if ARWorldTrackingConfiguration.supportsSceneReconstruction(.mesh) {
          configuration.sceneReconstruction = .mesh
        } else {
          onError?(MunimXrError.sceneReconstructionUnsupported.localizedDescription)
        }
      case .meshWithClassification:
        if ARWorldTrackingConfiguration.supportsSceneReconstruction(.meshWithClassification) {
          configuration.sceneReconstruction = .meshWithClassification
        } else if ARWorldTrackingConfiguration.supportsSceneReconstruction(.mesh) {
          configuration.sceneReconstruction = .mesh
        } else {
          onError?(MunimXrError.sceneReconstructionUnsupported.localizedDescription)
        }
      }
      if !referenceImages.isEmpty {
        configuration.detectionImages = referenceImages
        configuration.maximumNumberOfTrackedImages = min(referenceImages.count, 4)
      }
      return configuration
    }
  }

  private func reconfigureIfRunning() {
    DispatchQueue.main.async {
      guard self.running else { return }
      do {
        try self.startSession(resetTracking: false)
      } catch {
        self.onError?(error.localizedDescription)
      }
    }
  }

  // MARK: - Detection images

  private func currentImageSpecs() -> [ImageSpec] {
    (detectionImages ?? []).map {
      ImageSpec(name: $0.name, uri: $0.uri, physicalWidthMeters: $0.physicalWidthMeters)
    }
  }

  /// Returns `nil` when `specs` are already loaded.
  private func referenceImagesIfNeeded(for specs: [ImageSpec]) async throws
    -> Set<ARReferenceImage>?
  {
    let alreadyLoaded = await MainActor.run { self.loadedImageSpecs == specs }
    if alreadyLoaded { return nil }
    return try await Self.loadReferenceImages(specs) { message in
      self.onError?(message)
    }
  }

  private func detectionImagesChanged() {
    let specs = currentImageSpecs()
    imageLoadGeneration += 1
    let generation = imageLoadGeneration
    Task {
      do {
        guard let images = try await self.referenceImagesIfNeeded(for: specs) else { return }
        await MainActor.run {
          guard generation == self.imageLoadGeneration else { return }
          self.referenceImages = images
          self.loadedImageSpecs = specs
          self.reconfigureIfRunning()
        }
      } catch {
        self.onError?(error.localizedDescription)
      }
    }
  }

  private static func loadReferenceImages(
    _ specs: [ImageSpec],
    onWarning: @escaping (String) -> Void
  ) async throws -> Set<ARReferenceImage> {
    var images = Set<ARReferenceImage>()
    for spec in specs {
      guard spec.physicalWidthMeters.isFinite, spec.physicalWidthMeters > 0 else {
        throw MunimXrError.invalidDetectionImage(spec.name)
      }
      let url = try await XRFileLoader.localFileURL(for: spec.uri, kind: "images")
      guard let image = UIImage(contentsOfFile: url.path), let cgImage = image.cgImage else {
        throw MunimXrError.invalidDetectionImage(spec.name)
      }
      let reference = ARReferenceImage(
        cgImage,
        orientation: .up,
        physicalWidth: CGFloat(spec.physicalWidthMeters)
      )
      reference.name = spec.name
      let validationError: Error? = await withCheckedContinuation { continuation in
        reference.validate { error in continuation.resume(returning: error) }
      }
      if let validationError {
        onWarning("Detection image \"\(spec.name)\" was skipped: \(validationError.localizedDescription)")
        continue
      }
      images.insert(reference)
    }
    return images
  }

  // MARK: - Anchor bookkeeping (main thread)

  private func refreshUserAnchor(_ anchor: ARAnchor) {
    // ARKit delivers a new ARAnchor snapshot on every update; replace the stored
    // one so getAnchors() reports the refined pose instead of the creation pose.
    let id = anchor.identifier.uuidString
    stateLock.synchronized {
      if userAnchors[id] != nil { userAnchors[id] = anchor }
    }
  }

  private func anchor(withId id: String) -> ARAnchor? {
    if let anchor = stateLock.synchronized({ userAnchors[id] }) { return anchor }
    return sceneView.session.currentFrame?.anchors.first { $0.identifier.uuidString == id }
  }

  private func updateAttachedModels(for anchor: ARAnchor) {
    let id = anchor.identifier.uuidString
    for entry in models.values where entry.anchorId == id {
      entry.container.simdTransform = anchor.transform * entry.localTransform
      entry.container.isHidden = !Self.isTracked(
        anchor,
        cameraTrackingNormal: lastTrackingState == .normal
      )
    }
  }

  private func applyModelTransform(_ entry: ModelEntry) {
    if let anchorId = entry.anchorId {
      guard let anchor = anchor(withId: anchorId) else {
        entry.container.isHidden = true
        return
      }
      entry.container.simdTransform = anchor.transform * entry.localTransform
    } else {
      entry.container.simdTransform = entry.localTransform
    }
  }

  private func shouldEmitUpdate(
    id: String,
    signature: [Float],
    now: TimeInterval,
    tolerance: Float = 0.01,
    forceIndex: Int? = nil
  ) -> Bool {
    let maxHz = trackableUpdateMaxHz ?? 10
    guard maxHz > 0 else { return false }
    if let last = updateThrottle[id] {
      // A change in a discrete component (e.g. tracked -> not tracked) is
      // delivered immediately; everything else waits for the rate limit.
      let forced = forceIndex.map {
        last.signature.indices.contains($0) && last.signature[$0] != signature[$0]
      } ?? false
      if !forced {
        guard now - last.time >= 1 / maxHz else { return false }
        guard Self.changed(last.signature, signature, tolerance: tolerance) else { return false }
      }
    }
    updateThrottle[id] = ThrottleEntry(time: now, signature: signature)
    return true
  }

  private static func changed(_ lhs: [Float], _ rhs: [Float], tolerance: Float) -> Bool {
    guard lhs.count == rhs.count else { return true }
    return zip(lhs, rhs).contains { abs($0 - $1) > tolerance }
  }

  private static func isTracked(_ anchor: ARAnchor, cameraTrackingNormal: Bool) -> Bool {
    if let trackable = anchor as? ARTrackable { return trackable.isTracked }
    return cameraTrackingNormal
  }

  // MARK: - Conversions

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

  private static func vector(_ value: SIMD3<Float>) -> XRVector3 {
    XRVector3(x: Double(value.x), y: Double(value.y), z: Double(value.z))
  }

  private static func xrLightEstimate(_ estimate: ARLightEstimate) -> XRLightEstimate {
    guard let directional = estimate as? ARDirectionalLightEstimate else {
      return XRLightEstimate(
        ambientIntensity: Double(estimate.ambientIntensity),
        ambientColorTemperature: Double(estimate.ambientColorTemperature),
        colorCorrection: nil,
        mainLightDirection: nil,
        mainLightIntensity: nil,
        sphericalHarmonics: nil
      )
    }
    let intensity = Double(directional.primaryLightIntensity)
    return XRLightEstimate(
      ambientIntensity: Double(estimate.ambientIntensity),
      ambientColorTemperature: Double(estimate.ambientColorTemperature),
      colorCorrection: nil,
      mainLightDirection: vector(directional.primaryLightDirection),
      mainLightIntensity: [intensity, intensity, intensity],
      sphericalHarmonics: sphericalHarmonics(directional.sphericalHarmonicsCoefficients)
    )
  }

  /// ARKit stores 9 coefficients per color channel (channel-major); reorder into
  /// nine `[r, g, b]` triplets to match ARCore's layout.
  private static func sphericalHarmonics(_ data: Data) -> [Double]? {
    let floats: [Float] = data.withUnsafeBytes { Array($0.bindMemory(to: Float.self)) }
    guard floats.count >= 27 else { return nil }
    var result: [Double] = []
    result.reserveCapacity(27)
    for coefficient in 0..<9 {
      for channel in 0..<3 {
        result.append(Double(floats[channel * 9 + coefficient]))
      }
    }
    return result
  }

  private static func xrPlane(_ anchor: ARPlaneAnchor) -> XRPlane {
    let local = anchor.center
    let world = anchor.transform * SIMD4<Float>(local.x, local.y, local.z, 1)
    let extent: XRVector3
    var extentRotationY: Double?
    if #available(iOS 16.0, *) {
      extent = XRVector3(
        x: Double(anchor.planeExtent.width),
        y: 0,
        z: Double(anchor.planeExtent.height)
      )
      extentRotationY = Double(anchor.planeExtent.rotationOnYAxis)
    } else {
      extent = vector(anchor.extent)
    }
    return XRPlane(
      id: anchor.identifier.uuidString,
      alignment: anchor.alignment == .horizontal ? .horizontal : .vertical,
      classification: planeClassification(anchor.classification),
      center: XRVector3(x: Double(world.x), y: Double(world.y), z: Double(world.z)),
      localCenter: vector(local),
      extent: extent,
      extentRotationY: extentRotationY,
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

  private static func planeClassificationCode(_ classification: XRPlaneClassification) -> Int {
    switch classification {
    case .floor: return 1
    case .wall: return 2
    case .ceiling: return 3
    case .table: return 4
    case .seat: return 5
    case .door: return 6
    case .window: return 7
    default: return 0
    }
  }

  private static func xrImageAnchor(_ anchor: ARImageAnchor) -> XRImageAnchor {
    let size = anchor.referenceImage.physicalSize
    let scale = anchor.estimatedScaleFactor
    return XRImageAnchor(
      id: anchor.identifier.uuidString,
      name: anchor.referenceImage.name ?? "",
      tracking: anchor.isTracked,
      pose: xrPose(anchor.transform),
      extent: XRVector3(
        x: Double(size.width * scale),
        y: 0,
        z: Double(size.height * scale)
      )
    )
  }

  private static func xrMeshAnchor(_ anchor: ARMeshAnchor) -> XRMeshAnchor {
    let geometry = anchor.geometry
    var classifications: [XRMeshClassificationCount] = []
    if let source = geometry.classification, source.format == .uchar {
      var counts = [UInt8: Int]()
      let base = source.buffer.contents().advanced(by: source.offset)
      for index in 0..<source.count {
        let value = base.advanced(by: index * source.stride).load(as: UInt8.self)
        counts[value, default: 0] += 1
      }
      classifications = counts.sorted { $0.key < $1.key }.map { key, count in
        XRMeshClassificationCount(
          classification: meshClassification(key),
          faceCount: Double(count)
        )
      }
    }
    return XRMeshAnchor(
      id: anchor.identifier.uuidString,
      pose: xrPose(anchor.transform),
      vertexCount: Double(geometry.vertices.count),
      faceCount: Double(geometry.faces.count),
      classifications: classifications
    )
  }

  private static func meshClassification(_ raw: UInt8) -> XRMeshClassification {
    guard let classification = ARMeshClassification(rawValue: Int(raw)) else { return .none }
    switch classification {
    case .wall: return .wall
    case .floor: return .floor
    case .ceiling: return .ceiling
    case .table: return .table
    case .seat: return .seat
    case .window: return .window
    case .door: return .door
    default: return .none
    }
  }

  private static let reportedBlendShapes: [ARFaceAnchor.BlendShapeLocation] = [
    .eyeBlinkLeft, .eyeBlinkRight, .jawOpen, .mouthSmileLeft, .mouthSmileRight,
    .browInnerUp, .cheekPuff, .mouthFunnel, .mouthPucker, .tongueOut,
  ]

  private static func xrFace(_ anchor: ARFaceAnchor) -> XRFace {
    let shapes = anchor.blendShapes
    return XRFace(
      id: anchor.identifier.uuidString,
      tracking: anchor.isTracked,
      pose: xrPose(anchor.transform),
      vertexCount: Double(anchor.geometry.vertices.count),
      blendShapes: reportedBlendShapes.map { location in
        XRBlendShape(name: location.rawValue, value: shapes[location]?.doubleValue ?? 0)
      }
    )
  }

  private static func poseSignature(_ pose: XRPose) -> [Float] {
    [
      Float(pose.position.x), Float(pose.position.y), Float(pose.position.z),
      Float(pose.orientation.x), Float(pose.orientation.y),
      Float(pose.orientation.z), Float(pose.orientation.w),
    ]
  }

  private static func signature(_ plane: XRPlane) -> [Float] {
    [
      Float(plane.center.x), Float(plane.center.y), Float(plane.center.z),
      Float(plane.extent.x), Float(plane.extent.z),
      Float(plane.extentRotationY ?? 0),
      // Classification codes differ by >= 1, so a change always exceeds tolerance.
      Float(planeClassificationCode(plane.classification)),
    ] + poseSignature(plane.pose)
  }

  private static func signature(_ image: XRImageAnchor) -> [Float] {
    [image.tracking ? 1 : 0, Float(image.extent.x), Float(image.extent.z)]
      + poseSignature(image.pose)
  }

  private static func signature(_ face: XRFace) -> [Float] {
    [face.tracking ? 1 : 0] + poseSignature(face.pose) + face.blendShapes.map { Float($0.value) }
  }

  private static func meshSignature(_ anchor: ARMeshAnchor) -> [Float] {
    let position = anchor.transform.columns.3
    return [
      Float(anchor.geometry.vertices.count),
      Float(anchor.geometry.faces.count),
      position.x, position.y, position.z,
    ]
  }

  static func xrPose(_ transform: simd_float4x4) -> XRPose {
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
    guard pose.matrix.allSatisfy(\.isFinite) else { throw MunimXrError.invalidPoseMatrix }
    let values = pose.matrix.map(Float.init)
    return simd_float4x4(
      SIMD4(values[0], values[1], values[2], values[3]),
      SIMD4(values[4], values[5], values[6], values[7]),
      SIMD4(values[8], values[9], values[10], values[11]),
      SIMD4(values[12], values[13], values[14], values[15])
    )
  }

  // MARK: - Depth conversion

  private static func xrDepthFrame(_ capture: DepthCapture) throws -> XRDepthFrame {
    let depthMap = capture.depth
    guard CVPixelBufferGetPixelFormatType(depthMap) == kCVPixelFormatType_DepthFloat32 else {
      throw MunimXrError.depthUnsupported
    }
    let width = CVPixelBufferGetWidth(depthMap)
    let height = CVPixelBufferGetHeight(depthMap)
    let depth = copyPlane(depthMap, bytesPerPixel: MemoryLayout<Float32>.size)

    var confidence: ArrayBuffer?
    if let confidenceMap = capture.confidence,
       CVPixelBufferGetWidth(confidenceMap) == width,
       CVPixelBufferGetHeight(confidenceMap) == height {
      let buffer = copyPlane(confidenceMap, bytesPerPixel: 1)
      // ARConfidenceLevel is 0 (low), 1 (medium), 2 (high); scale to 0...255.
      let bytes = buffer.data
      for index in 0..<(width * height) {
        bytes[index] = UInt8(min(Int(bytes[index]), 2) * 255 / 2)
      }
      confidence = buffer
    }

    return XRDepthFrame(
      timestamp: capture.timestamp,
      width: Double(width),
      height: Double(height),
      format: .float32Meters,
      depth: depth,
      confidence: confidence,
      smoothed: capture.smoothed
    )
  }

  private static func copyPlane(_ pixelBuffer: CVPixelBuffer, bytesPerPixel: Int) -> ArrayBuffer {
    CVPixelBufferLockBaseAddress(pixelBuffer, .readOnly)
    defer { CVPixelBufferUnlockBaseAddress(pixelBuffer, .readOnly) }
    let width = CVPixelBufferGetWidth(pixelBuffer)
    let height = CVPixelBufferGetHeight(pixelBuffer)
    let rowBytes = width * bytesPerPixel
    let sourceStride = CVPixelBufferGetBytesPerRow(pixelBuffer)
    let buffer = ArrayBuffer.allocate(size: rowBytes * height)
    guard let source = CVPixelBufferGetBaseAddress(pixelBuffer) else { return buffer }
    for row in 0..<height {
      memcpy(buffer.data + row * rowBytes, source + row * sourceStride, rowBytes)
    }
    return buffer
  }

  // MARK: - Mesh export

  private static func exportOBJ(_ anchors: [ARMeshAnchor], to url: URL) throws {
    guard MDLAsset.canExportFileExtension("obj") else { throw MunimXrError.meshExportFailed }
    let allocator = MDLMeshBufferDataAllocator()
    let asset = MDLAsset(bufferAllocator: allocator)

    let descriptor = MDLVertexDescriptor()
    descriptor.attributes[0] = MDLVertexAttribute(
      name: MDLVertexAttributePosition,
      format: .float3,
      offset: 0,
      bufferIndex: 0
    )
    descriptor.layouts[0] = MDLVertexBufferLayout(stride: MemoryLayout<Float>.size * 3)

    for anchor in anchors {
      let geometry = anchor.geometry
      let vertices = geometry.vertices
      var worldVertices = [Float]()
      worldVertices.reserveCapacity(vertices.count * 3)
      let vertexBase = vertices.buffer.contents().advanced(by: vertices.offset)
      for index in 0..<vertices.count {
        let pointer = vertexBase.advanced(by: index * vertices.stride)
        let local = SIMD4<Float>(
          pointer.load(as: Float.self),
          pointer.load(fromByteOffset: 4, as: Float.self),
          pointer.load(fromByteOffset: 8, as: Float.self),
          1
        )
        let world = anchor.transform * local
        worldVertices.append(contentsOf: [world.x, world.y, world.z])
      }
      let vertexData = worldVertices.withUnsafeBytes { Data($0) }
      let vertexBuffer = allocator.newBuffer(with: vertexData, type: .vertex)

      let faces = geometry.faces
      let indexCount = faces.count * faces.indexCountPerPrimitive
      let indexData = Data(
        bytes: faces.buffer.contents(),
        count: indexCount * faces.bytesPerIndex
      )
      let indexBuffer = allocator.newBuffer(with: indexData, type: .index)
      let submesh = MDLSubmesh(
        indexBuffer: indexBuffer,
        indexCount: indexCount,
        indexType: faces.bytesPerIndex == 2 ? .uInt16 : .uInt32,
        geometryType: .triangles,
        material: nil
      )
      let mesh = MDLMesh(
        vertexBuffer: vertexBuffer,
        vertexCount: vertices.count,
        descriptor: descriptor,
        submeshes: [submesh]
      )
      mesh.name = "mesh-\(anchor.identifier.uuidString)"
      asset.add(mesh)
    }
    try asset.export(to: url)
  }

  // MARK: - Models

  private static func loadModelNode(_ url: URL) throws -> SCNNode {
    let scene: SCNScene
    do {
      scene = try SCNScene(url: url, options: [.checkConsistency: true])
    } catch {
      throw MunimXrError.modelLoadFailed(error.localizedDescription)
    }
    let content = SCNNode()
    for child in scene.rootNode.childNodes {
      content.addChildNode(child)
    }
    return content
  }
}

// MARK: - Supporting types

private struct ThrottleEntry {
  let time: TimeInterval
  let signature: [Float]
}

private struct ImageSpec: Equatable {
  let name: String
  let uri: String
  let physicalWidthMeters: Double
}

private struct DepthCapture {
  let depth: CVPixelBuffer
  let confidence: CVPixelBuffer?
  let timestamp: TimeInterval
  let smoothed: Bool
}

private final class ModelEntry {
  let container: SCNNode
  let content: SCNNode
  let anchorId: String?
  var localTransform: simd_float4x4

  init(container: SCNNode, content: SCNNode, anchorId: String?, localTransform: simd_float4x4) {
    self.container = container
    self.content = content
    self.anchorId = anchorId
    self.localTransform = localTransform
  }
}

/// Files produced by the view (snapshots, mesh exports) live under
/// `tmp/munim-xr/<kind>/`; only the newest `keep` files of a kind are retained.
enum XROutputFiles {
  static func directory(_ kind: String) throws -> URL {
    let url = FileManager.default.temporaryDirectory
      .appendingPathComponent("munim-xr", isDirectory: true)
      .appendingPathComponent(kind, isDirectory: true)
    try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
    return url
  }

  static func nextURL(kind: String, fileExtension: String, keep: Int) throws -> URL {
    let folder = try directory(kind)
    prune(folder, keep: max(keep - 1, 0))
    return folder.appendingPathComponent("\(UUID().uuidString).\(fileExtension)")
  }

  private static func prune(_ directory: URL, keep: Int) {
    let fileManager = FileManager.default
    guard let files = try? fileManager.contentsOfDirectory(
      at: directory,
      includingPropertiesForKeys: [.contentModificationDateKey]
    ) else { return }
    let sorted = files.sorted { lhs, rhs in
      let left = (try? lhs.resourceValues(forKeys: [.contentModificationDateKey]))?
        .contentModificationDate ?? .distantPast
      let right = (try? rhs.resourceValues(forKeys: [.contentModificationDateKey]))?
        .contentModificationDate ?? .distantPast
      return left > right
    }
    for file in sorted.dropFirst(keep) {
      try? fileManager.removeItem(at: file)
    }
  }
}

/// Resolves `file://`, absolute, and `http(s)://` URIs to a local file.
enum XRFileLoader {
  static func localFileURL(for uri: String, kind: String) async throws -> URL {
    if uri.hasPrefix("/") { return URL(fileURLWithPath: uri) }
    guard let url = URL(string: uri) else { throw MunimXrError.invalidURI(uri) }
    if url.isFileURL { return url }
    guard url.scheme == "http" || url.scheme == "https" else {
      throw MunimXrError.invalidURI(uri)
    }
    let (downloaded, response) = try await URLSession.shared.download(from: url)
    if let http = response as? HTTPURLResponse, !(200..<300).contains(http.statusCode) {
      throw MunimXrError.downloadFailed(uri, http.statusCode)
    }
    let fileExtension = url.pathExtension.isEmpty ? "bin" : url.pathExtension
    let destination = try XROutputFiles.nextURL(kind: kind, fileExtension: fileExtension, keep: 20)
    try FileManager.default.moveItem(at: downloaded, to: destination)
    return destination
  }
}

enum MunimXrError: LocalizedError {
  case cameraPermissionDenied
  case invalidNormalizedPoint
  case invalidPoseMatrix
  case invalidScale
  case snapshotEncodingFailed
  case unsupportedDevice
  case faceTrackingUnsupported
  case sceneReconstructionUnsupported
  case depthDisabled
  case depthUnsupported
  case depthNotYetAvailable
  case noMeshAvailable
  case meshExportFailed
  case anchorNotFound(String)
  case modelNotFound(String)
  case modelLoadFailed(String)
  case invalidDetectionImage(String)
  case invalidURI(String)
  case downloadFailed(String, Int)

  var errorDescription: String? {
    switch self {
    case .cameraPermissionDenied:
      return "Camera permission is required to start an XR session."
    case .invalidNormalizedPoint:
      return "Hit-test coordinates must be finite values between 0 and 1."
    case .invalidPoseMatrix:
      return "An XR pose matrix must contain exactly 16 finite values."
    case .invalidScale:
      return "A model scale must be a finite value greater than 0."
    case .snapshotEncodingFailed:
      return "The XR view snapshot could not be encoded as PNG."
    case .unsupportedDevice:
      return "This device does not support ARKit world tracking."
    case .faceTrackingUnsupported:
      return "This device does not support ARKit face tracking."
    case .sceneReconstructionUnsupported:
      return "Scene reconstruction requires a LiDAR device; continuing without a mesh."
    case .depthDisabled:
      return "Depth is disabled. Set depthEnabled to true before calling getDepthFrame()."
    case .depthUnsupported:
      return "This device does not provide scene depth (requires LiDAR)."
    case .depthNotYetAvailable:
      return "No depth frame is available yet."
    case .noMeshAvailable:
      return "No scene mesh is available. Enable sceneReconstruction and scan the room first."
    case .meshExportFailed:
      return "The scene mesh could not be exported."
    case .anchorNotFound(let id):
      return "No anchor with id \(id) exists."
    case .modelNotFound(let id):
      return "No model with id \(id) exists."
    case .modelLoadFailed(let reason):
      return "The model could not be loaded: \(reason)"
    case .invalidDetectionImage(let name):
      return "Detection image \"\(name)\" could not be loaded or has an invalid physical width."
    case .invalidURI(let uri):
      return "Unsupported URI: \(uri). Use file://, an absolute path, or http(s)://."
    case .downloadFailed(let uri, let status):
      return "Downloading \(uri) failed with HTTP status \(status)."
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
