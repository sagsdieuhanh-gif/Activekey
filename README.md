# TRUNGKIEN V14.0.0 CENTER-FIRST ADAS

Android camera-based driver-assistance experiment focused on:

**near-road lane markings → ego corridor → center lead → range/TTC → cut-in watcher → optional signs**.

## V14 highlights
- NEAR-FIRST lane detection scans the lower road first and weights 55–95% of image height.
- PAINT-FIRST lane scoring prefers white/yellow markings over generic road-edge/kerb gradients.
- LaneHybridFusion lets credible near-road CV markings override Lane Core when geometry disagrees.
- CENTER-FIRST lead selection strongly rewards lane centre/overlap and suppresses adjacent traffic.
- Motorcycles/bicycles beside the car stay in the side watcher unless they genuinely enter ego lane.
- Pedestrians on pavements/shoulders are removed from the main HUD unless they enter the central ego path.
- Side guard shows at most one threat per side and only after inward/cut-in evidence.
- Lead switch hysteresis is stronger to reduce target jumping.
- Monocular 0–100 m range pipeline, conservative long-range uncertainty, TTS, signs and Thermal Guard retained.
- User-adjustable hood exclusion retained.
- 5-minute trial + admin-signed per-device key; V12/V13/V14 key compatibility retained.
- Includes the V13.2 debug-overlay scope hotfix.

## Build
- Android compileSdk / targetSdk: 36
- minSdk: 24
- Java: 17
- GitHub build: Gradle 9.5.0
- versionCode: 1400
- versionName: 14.0.0
- artifact: `TRUNGKIEN-V14-debug-apk`

The Gradle task `prepareCorePackages` downloads/verifies the pinned Road Core and Lane Core packages at build time and bundles them into the APK; runtime model download is disabled.

## Security
The user APK contains only the public license verification key. **Do not put the Admin private key in this repository or APK.** The Admin Key Android app remains separate.

## Important limitation
A phone monocular camera is not radar/LiDAR and V14 is not a certified ADAS. The 60–100 m range is intentionally approximate. Validate distance and lane behavior in controlled conditions before road use.

See `HDSD.md`, `V14_CHANGELOG.md`, `VALIDATION.md`, and `THIRD_PARTY_NOTICES.md`.
