# TRUNGKIEN V14.1.0 NIGHT/CENTER ADAS

Android camera-based driver-assistance experiment focused on:

**night/near-road lane markings → ego corridor or center fallback → lead vehicle → range/TTC → cut-in watcher → optional signs**.

## V14.1 highlights
- Automatic NIGHT AUTO detection no longer relies on a very low average-brightness threshold; it also uses dark-pixel ratio so street/head lamps do not hide a true night scene.
- Stronger but bounded gamma lift for Road Core in low light.
- Night lane detector uses local reflective-paint contrast, denser lower-road sampling and relaxed one-side acquisition.
- Lane Core can no longer suppress a credible reflective near-road CV lane just because broad road-edge geometry is stronger.
- Lead detection is decoupled from lane lock: when lane confidence is weak, V14.1 periodically runs a center-focus detector crop.
- Four-wheel night detections in the center funnel use lower detector floors and faster temporal confirmation.
- CENTER-FIRST/LEAD-FIRST behavior remains: side vehicles do not steal lead unless they actually cut in.
- Pedestrians outside the central ego path remain strongly suppressed.
- Traffic-sign UI marks remembered rule state with `ĐÃ NHỚ` when the original observation has expired but the session rule is still active.
- Monocular 0–100 m range, conservative long-range uncertainty, TTS, Thermal Guard and user-adjustable hood exclusion retained.
- 5-minute trial + admin-signed per-device key; V12/V13/V14/V14.1 key compatibility retained.

## Build
- Android compileSdk / targetSdk: 36
- minSdk: 24
- Java: 17
- GitHub build: Gradle 9.5.0
- versionCode: 1410
- versionName: 14.1.0
- artifact: `TRUNGKIEN-V14.1-debug-apk`

The Gradle task `prepareCorePackages` downloads/verifies the pinned Road Core and Lane Core packages at build time and bundles them into the APK; runtime model download is disabled.

## Security
The user APK contains only the public license verification key. **Do not put the Admin private key in this repository or APK.** The Admin Key Android app remains separate.

## Important limitation
A phone monocular camera is not radar/LiDAR and V14.1 is not a certified ADAS. Night detection and 60–100 m range remain camera estimates and must be validated in controlled conditions before road use.

See `HDSD.md`, `V14_1_CHANGELOG.md`, `VALIDATION.md`, and `THIRD_PARTY_NOTICES.md`.
