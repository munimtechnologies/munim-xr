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

  func checkAvailability() throws -> Promise<XRAvailability> {
    Promise.resolved(
      withResult: ARWorldTrackingConfiguration.isSupported
        ? .supported
        : .unsupported
    )
  }

  func requestInstall() throws -> Promise<Bool> {
    Promise.resolved(withResult: ARWorldTrackingConfiguration.isSupported)
  }
}
