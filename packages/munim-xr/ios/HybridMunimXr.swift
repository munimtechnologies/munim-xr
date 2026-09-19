import ARKit
import NitroModules

final class HybridMunimXr: HybridMunimXrSpec {
  var platform: String { "arkit" }

  var sdkVersion: String {
    "ARKit / iOS \(ProcessInfo.processInfo.operatingSystemVersionString)"
  }

  func isSupported() throws -> Bool {
    ARWorldTrackingConfiguration.isSupported
  }

  func checkAvailability(feature: XRFeature?) throws -> Promise<XRAvailability> {
    Promise.resolved(withResult: Self.isAvailable(feature) ? .supported : .unsupported)
  }

  func requestInstall() throws -> Promise<Bool> {
    Promise.resolved(withResult: ARWorldTrackingConfiguration.isSupported)
  }

  private static func isAvailable(_ feature: XRFeature?) -> Bool {
    let world = ARWorldTrackingConfiguration.self
    guard let feature else { return world.isSupported }
    switch feature {
    case .worldTracking, .imageTracking:
      return world.isSupported
    case .depth:
      return world.isSupported && world.supportsFrameSemantics(.sceneDepth)
    case .smoothedDepth:
      return world.isSupported && world.supportsFrameSemantics(.smoothedSceneDepth)
    case .sceneReconstruction:
      return world.isSupported && world.supportsSceneReconstruction(.mesh)
    case .meshClassification:
      return world.isSupported && world.supportsSceneReconstruction(.meshWithClassification)
    case .faceTracking:
      return ARFaceTrackingConfiguration.isSupported
    case .environmentalHdr:
      // ARKit only reports a directional light estimate in face-tracking sessions.
      return ARFaceTrackingConfiguration.isSupported
    case .models:
      return world.isSupported || ARFaceTrackingConfiguration.isSupported
    @unknown default:
      return false
    }
  }
}
