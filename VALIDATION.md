# VALIDATION — V12.0.0 R2

Đã kiểm tra trong môi trường đóng gói:

- FollowingDistanceAdvisor pure Kotlin compile + smoke test: PASS.
  - 60 km/h -> 35 m; 70 -> 55 m; 90 -> 70 m; 110 -> 100 m.
  - 112 m ở 110 km/h với biên sai số bảo thủ -> chỉ chuyển SAFE sau cửa sổ ổn định.
  - 105 m ± 8 m ở 110 km/h -> không bị xác nhận SAFE vì cận dưới chưa đạt 100 m.
- RangeFusion / TargetSelector pure core compile: PASS.
- FRONT FIRST smoke simulation: car trung tâm được ưu tiên hơn motorcycle nhỏ/lệch bên: PASS.
- LONG RANGE ROI mapping: central crop được ánh xạ lại về tọa độ toàn khung: static/targeted compile PASS.
- SignSenseEngine + TrafficSignState syntax compile với Android/ML Kit stubs: PASS.
- Road Core preprocessor/engine syntax compile với ImageProxy/runtime stubs: PASS.
- WarningSpeaker syntax compile với Android/domain stubs: PASS.
- LANE HYBRID / Hood Guard / Thermal Guard / LicenseGate validations từ V12 nền được giữ: PASS.
- Overlay: không còn vẽ nhãn/đường `CHÂN TRỜI AUTO`; hình học road-plane nội bộ vẫn được giữ cho phép đo/lane.
- Nút `BIỂN BÁO AI` OFF đóng Sign Core/OCR; ON mới cho phép xử lý P.127, R.420, R.421 với multi-frame confirmation.
- Search `app/src/main` + `app/build.gradle.kts`: không còn tên model upstream trong UI/log/runtime asset identifiers. Attribution bắt buộc vẫn nằm trong `THIRD_PARTY_NOTICES.md`.
- Public source package không chứa private admin signing key.
- V12 version: `versionCode=1201`, `versionName=12.0.0`.

Chưa chạy full `assembleDebug` trong container này do không có Android SDK/Gradle distribution cache phù hợp. Dự án được giữ theo cấu trúc Android Studio build-ready; máy Windows/Android Studio của người dùng là nơi xác nhận cuối cùng toàn bộ Android resources/runtime dependencies.

Các cảnh báo native `Unable to strip ...so` (nếu xuất hiện) là packaging warnings của native runtime, không tự động đồng nghĩa build fail.
