# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.1.1] - 2026-08-06

### Fixed

- Prevent Android session reconfiguration from racing the ARCore render thread after React updates, which could surface `SessionPausedException` on start or resume.

## [0.1.0] - 2026-08-06

### Added

- Initial Expo and React Native package.
- Nitro Hybrid View implemented with ARKit and ARCore.
- Native camera rendering, world tracking, plane detection, frame and tracking callbacks.
- Hit testing, native anchors, camera poses, light estimation, optional depth, and PNG snapshots.
- Runtime support checks and ARCore install/update flow.
- Expo config plugin, typed example app, native build workflows, and npm provenance publishing.

[Unreleased]: https://github.com/munimtechnologies/munim-xr/compare/v0.1.1...HEAD
[0.1.1]: https://github.com/munimtechnologies/munim-xr/compare/v0.1.0...v0.1.1
[0.1.0]: https://github.com/munimtechnologies/munim-xr/releases/tag/v0.1.0
