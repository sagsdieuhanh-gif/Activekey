# VALIDATION — TRUNGKIEN V14.0.0 CENTER-FIRST

## Static validation trong môi trường đóng gói
- Pure Kotlin core compile: PASS.
- Synthetic lane test: PASS — central white lane markings win over stronger bright outer road edges.
- Lead test: PASS — center car remains lead while higher-confidence side motorcycle is ignored.
- Pedestrian test: PASS — sidewalk pedestrian ignored; center-path pedestrian retained.
- Side monitor test: PASS — parallel side traffic produces no HUD hazard; inward cut-in is promoted.
- Termux deployment script syntax: PASS.
- Source scan: không có PEM/private key trong app/.github: PASS.
- Workflow: Android API 36 + JDK 17 + Gradle 9.5.0.
- Version: 14.0.0 / code 1400.

## Behaviour encoded in V14
- Lane NEAR-FIRST: scan lower road 54–95% image and weight near points more strongly.
- PAINT-FIRST: white/yellow line evidence > generic gradient/road edge.
- Narrower search/fallback corridor reduces kerb/barrier locking.
- CV marking near vehicle can override Lane Core when sources disagree.
- CENTER-FIRST target score strongly prioritizes centrality + lane overlap.
- Side motorcycle/bicycle needs deep ego-lane overlap before becoming lead.
- Lead switch confirmation increased.
- Pedestrians outside central ego path are suppressed.
- Side traffic is shown only after inward/cut-in evidence; max one threat each side.
- V13.2 debug-overlay scope hotfix included.
- Long range, hood exclusion, TTS, sign AI, thermal and license retained.

## Cần xác nhận thực địa
Test lane/lead ở đường có vạch liền, vạch đứt, đường cong, đường đô thị có curb rõ, xe máy chạy hai bên và các mốc 5/10/20/30/50/70/100 m. Long-range 60–100 m vẫn là approximate.

Full `assembleDebug` được xác nhận cuối trên GitHub Actions sau khi upload source vì Road Core/Lane Core được tải và bundle trong workflow.
