# CHANGELOG

## V12.0.0 R2
- Bỏ vẽ đường chân trời/`CHÂN TRỜI AUTO` trên camera.
- LONG RANGE FRONT ROI: xen lượt crop trung tâm ở tốc độ cao khi nhiệt máy bình thường để tăng kích thước xe nhỏ 60–100 m.
- Road Core mapping hỗ trợ crop -> full-frame để tracker vẫn dùng đúng tọa độ camera.
- Tăng front priority cho car/bus/truck xa tới ~100 m; 60–100 m hiển thị dạng ước tính.
- Thêm `FollowingDistanceAdvisor`: 35/55/70/100 m theo dải tốc độ của Điều 11/Bảng 3 Thông tư 38/2024/TT-BGTVT.
- Xác nhận đủ cự ly bằng cận dưới `distance - uncertainty`; dwell ~2,5 s trước TTS.
- RangeFusion tăng uncertainty floor theo khoảng cách và RangeQuality; vùng xa không bị biểu diễn quá chính xác.
- TTS mới: `Bạn đã giữ đủ khoảng cách an toàn.` và cảnh báo cự ly chưa đủ theo ngưỡng.
- Thêm nút chính `BIỂN BÁO AI: BẬT/TẮT`; OFF đóng Sign Core/OCR hoàn toàn.
- Sign Core giai đoạn đầu: P.127, R.420, R.421; yêu cầu temporal consensus nhiều frame.
- P.127 đọc số tốc độ, hiển thị limit cạnh GPS; overspeed phải ổn định ~2 s mới TTS.
- R.420/R.421 lưu trạng thái khu đông dân cư trong phiên chạy, không giữ qua restart để tránh stale route state.
- Sign TTS có ưu tiên thấp hơn collision/pedestrian/cut-in.
- Giữ FRONT FIRST, LANE HYBRID, hood exclusion kéo tay, auto range, cut-in TLC/TTC, Thermal Guard, trial 5 phút + key admin và cảnh báo 20/10/5/3/2/1 m.

## V12.0.0 (base)
- FRONT FIRST, LANE HYBRID, hood exclusion, Thermal Guard, offline license.

## V11.0.0
- Tự hiệu chỉnh khoảng cách không cần nhập mốc thật bằng tay.

## V10.0.1
- Hotfix compile UI cho LayoutParams.

## V10.0.0
- Cảnh báo khoảng cách 20 -> 10 -> 5 -> 3 -> 2 -> 1 m, bỏ đọc 4 m; TTC ưu tiên khẩn cấp.
