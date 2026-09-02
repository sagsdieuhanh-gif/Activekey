# TRUNGKIEN V12.0.0 — R2

V12 R2 giữ nền FRONT FIRST / LANE HYBRID / hood guard / auto range / thermal guard / license của V12 và bổ sung LONG RANGE 100 m, tư vấn cự ly theo tốc độ có biên sai số bảo thủ, cùng nút `ĐỌC BIỂN BÁO AI` có thể tắt hẳn để giảm tải.

## V12 R2 có gì mới

### LONG RANGE 100 m
- Detector toàn khung vẫn là lượt chính.
- Khi GPS từ khoảng 60 km/h trở lên và Thermal Guard ở NORMAL, cứ vài lượt app xen một lượt crop trung tâm `LONG 100m`; cùng ngân sách 640×640 nhưng xe xa chiếm nhiều pixel hơn.
- Detection từ crop được ánh xạ lại đúng tọa độ toàn camera và đi qua cùng temporal tracker/FRONT FIRST.
- 60–100 m hiển thị dạng ước tính `~xx m`; app không đọc TTS khoảng cách xa liên tục.

### CỰ LY AN TOÀN THEO TỐC ĐỘ + SAI SỐ
- Dùng GPS speed và bảng Điều 11/Bảng 3 của Thông tư 38/2024/TT-BGTVT trong điều kiện chuẩn.
- Chỉ xác nhận `ĐỦ CỰ LY` khi cận dưới `distance - uncertainty` vẫn đạt mức yêu cầu.
- Phải ổn định khoảng 2,5 giây mới đọc `Bạn đã giữ đủ khoảng cách an toàn.`
- Khoảng 60–100 m dùng sai số bảo thủ lớn hơn; RangeQuality thấp sẽ nới biên thêm thay vì giả vờ chính xác.

### ĐỌC BIỂN BÁO AI — NÚT RIÊNG
- Nút trên màn hình chính: `BIỂN BÁO AI: BẬT/TẮT`.
- OFF: Sign Core + OCR được đóng hoàn toàn, không chạy nền.
- ON: nhận diện đơn giản P.127 (tốc độ tối đa), R.420 (bắt đầu khu đông dân cư), R.421 (hết khu đông dân cư).
- P.127 dùng đề xuất hình dạng/màu trước, chỉ gọi OCR khi có ứng viên hợp lý; kết quả phải lặp nhiều frame mới xác nhận.
- Khi có speed limit, GPS được so với giới hạn và chỉ TTS sau hysteresis chống jitter.
- Cảnh báo va chạm/TTC luôn ưu tiên cao hơn TTS biển báo.

### BỎ ĐƯỜNG CHÂN TRỜI TRÊN UI
- Không còn vẽ `CHÂN TRỜI AUTO` trên camera.
- Auto góc vẫn dùng IMU + hình học lane/điểm tụ nội bộ để cập nhật road-plane calibration; người dùng không cần canh một đường chân trời trên màn hình.

### Các phần V12 trước được giữ
- FRONT FIRST + Track ID + hysteresis.
- LANE HYBRID + one-side estimate + short hold.
- Vùng bỏ đầu xe kéo chỉnh được.
- AUTO RANGE SELF-CALIBRATION.
- Cut-in trái/phải + TLC/TTC.
- Thermal Guard + màn hình tiết kiệm.
- Trial 5 phút + key admin ECDSA theo mã thiết bị.
- Cảnh báo khoảng cách 20 / 10 / 5 / 3 / 2 / 1 m; mốc 4 m không đọc.

## Build
Mở dự án bằng Android Studio và build `app`. `prepareCorePackages` chuẩn bị/verify Road Core và Lane Core trước `preBuild`. V12 R2 thêm thư viện OCR Latin dạng bundled `com.google.mlkit:text-recognition:16.0.1`, nên không cần tải OCR ở runtime.

Nếu môi trường build không có mạng, cần cache/dependency Gradle đầy đủ và đặt hai core package đã verify vào:
- `app/offline_models/road_core.dat`
- `app/offline_models/lane_core.dat`

## Bản quyền
Private signing key **không nằm trong ZIP source/app phát cho người dùng**. Giữ riêng gói admin license tool.

## Tên công nghệ lõi
UI, asset và log runtime dùng tên trung tính `ROAD CORE`, `LANE CORE`, `SIGN CORE`. Thông tin giấy phép bên thứ ba vẫn phải giữ trong `THIRD_PARTY_NOTICES.md`; không loại bỏ attribution bắt buộc.
