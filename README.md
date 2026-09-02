# TRUNGKIEN V13.2.0 FULL ADAS

Android camera-based driver-assistance experiment focused on:

**ego lane → lead vehicle → side cut-in → range/risk → optional signs**.

## V13.2 highlights
- Curved cyan ego-lane corridor + lane-bound chevrons.
- Lead-first target selection with lane overlap/center bias and switch hysteresis.
- Persistent lightweight Track IDs with short occlusion prediction/reacquisition.
- Side/cut-in prediction using lane-relative lateral motion and TLC.
- Monocular 0–100 m range pipeline with long-range central ROI and conservative uncertainty.
- Following-distance advice uses lower confidence bound instead of point estimate alone.
- TTS warning priority, startup warm-up and 20/10/5/4/3/2/1 m milestones.
- Optional P.127 / R.420 / R.421 sign reader; OFF closes the sign/OCR pipeline.
- Four-level thermal controller with Android thermal status + battery temperature.
- User-adjustable hood exclusion; no fixed 20% crop.
- 5-minute trial + admin-signed per-device key; V12/V13 key pair compatibility retained.
- Hidden debug HUD + app-private 1 Hz CSV; no camera frames are logged/uploaded.

## Build
- Android compileSdk / targetSdk: 36
- minSdk: 24
- Java: 17
- GitHub build: Gradle 9.5.0
- versionCode: 1320
- versionName: 13.2.0

The Gradle task `prepareCorePackages` downloads/verifies the pinned Road Core and Lane Core packages at build time and bundles them into the APK; runtime model download is disabled.

## Security
The user APK contains only the public license verification key. **Do not put the Admin private key in this repository or APK.** The separate Admin Key Android app imports the private key locally and protects it with Android Keystore.

## Important limitation
A phone monocular camera is not radar/LiDAR and V13 is not a certified ADAS. In the 60–100 m region, range is intentionally presented as approximate with a conservative uncertainty envelope. Validate against independent measured distances before relying on calibration results.

See `HDSD.md`, `V13_CHANGELOG.md`, `VALIDATION.md`, and `THIRD_PARTY_NOTICES.md`.
