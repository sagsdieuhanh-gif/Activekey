# TRUNGKIEN V14.0.0 — CENTER-FIRST / NEAR-FIRST

## Mục tiêu
Giảm tracking nhiễu hai bên và khóa đúng vạch kẻ đường/xe phía trước sau phản hồi thực tế từ V13.2.

## Thay đổi chính
- LaneDetector quét phần đường gần từ dưới lên, tăng trọng số 55–95% chiều cao ảnh.
- Paint-first scoring: vạch trắng/vàng được ưu tiên hơn gradient road-edge/curb/barrier.
- Thu hẹp vùng tìm lane và fallback corridor để giảm bám mép đường.
- LaneHybridFusion cho CV marking gần xe override Lane Core khi hai nguồn bất đồng.
- CENTER-FIRST TargetSelector: trọng số tâm lane tăng mạnh, detector confidence/độ gần giảm vai trò.
- Xe máy/xe đạp bên cạnh cần overlap và centrality cao hơn mới được trở thành lead.
- Lead handover chậm hơn, giảm nhảy target.
- Pedestrian gate chỉ còn lõi ego-path; người trên vỉa hè/lề bị loại khỏi HUD.
- SideCollisionMonitor chỉ hiển thị khi có bằng chứng inward/cut-in; bỏ cảnh báo chỉ vì xe bên ở gần.
- Tối đa 1 side threat mỗi bên.
- Giữ long-range 100 m, Thermal Guard, Sign AI, TTS, hood exclusion và license V12/V13.
- Sửa luôn hotfix debug scope của V13.2 trong source nền.

## Build
- versionCode 1400
- versionName 14.0.0
- Android API 36
- Artifact: TRUNGKIEN-V14-debug-apk
