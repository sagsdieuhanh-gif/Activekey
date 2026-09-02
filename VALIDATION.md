# VALIDATION — TRUNGKIEN V13.2.0 FULL

## Static validation trong môi trường đóng gói
- Pure Kotlin ADAS core compile: PASS
- Pure smoke test: PASS (lead-first car vs side motorcycle, mature Track ID coast/reacquire, 90 m uncertainty floor, side cut-in trend promotion)
  - Models / Calibration / DistanceCorrection
  - FollowingDistanceAdvisor
  - GroundPlaneDistanceEstimator / TargetSelector
  - LaneDetector / LaneHybridFusion
  - RangeFusion / DistanceTracker
  - SideCollisionMonitor
  - RoadUserTemporalFilter V13.2
  - RiskStabilizer / AdasConfidence
- Android-dependent edited files: syntax parse check, không có `expecting`, `unclosed`, `unexpected tokens`, `missing }`: PASS.
- Source scan: không có PEM/private key: PASS.
- Workflow: Android API 36 + JDK 17 + Gradle 9.5.0: configured.
- Version: 13.2.0 / code 1320.

## Behaviour encoded in V13.2
- Long-range >=60 m cần lead confirmation mạnh hơn và uncertainty floor bảo thủ.
- >=85 m không được quảng bá HIGH range quality.
- Lead handover có hysteresis; motorcycle/bicycle lane bên bị penalty mạnh.
- Tracker coasts/reacquires mature Track ID qua occlusion ngắn.
- Lane confidence thấp/estimated được render yếu/nét đứt.
- Cut-in TLC dùng mép vehicle gần lane.
- Collision risk immediate; lower risk temporal-confirmed.
- TTS startup warm-up + queue guard + milestone 5/4/3/2/1 m.
- Thermal 4 mode + battery temperature + stationary low-power.
- Sign runtime persistence có TTL.
- Debug logging không chứa camera frame.

## Cần xác nhận trên xe/thực địa
Không có test bench camera/radar chuẩn trong container. Trước khi coi khoảng cách là đã hiệu chỉnh thực tế, phải test các mốc 5 / 10 / 20 / 30 / 50 / 70 / 100 m với mốc đo độc lập, nhiều loại xe, đường thẳng/cong, ngày/đêm. Long-range 60–100 m phải được xem là approximate.

Full `assembleDebug` được xác nhận cuối trên GitHub Actions sau khi upload source, vì model AI được tải/bundle trong workflow build.
