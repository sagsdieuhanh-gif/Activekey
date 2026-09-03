# TrungKien ADAS V2.4 — STABLE

## Từ V2.4 là nhánh chính thức
- Tên ứng dụng: `TrungKien ADAS`
- Package cố định: `com.trungkien.adas`
- Signing key cố định qua GitHub Secrets.
- Các bản V2.5/V3.x sau này phải giữ package + signing key này.
- Bản mới sẽ cập nhật lên TrungKien ADAS hiện tại thay vì tạo app mới.
- Trial, key, mã thiết bị và cấu hình được giữ khi cập nhật.

## Mã thiết bị
Cơ chế DG12 và salt giữ nguyên từ V2.2.

Do V2.4 chuyển từ debug signing không ổn định sang signing key cố định, mã thiết bị có thể đổi một lần ở lần chuyển sang V2.4.
Sau V2.4, cùng signing key + cùng Android user/device sẽ giữ cùng ANDROID_ID scoped value và do đó giữ mã thiết bị.

## Giao diện
- Camera toàn màn hình.
- Chỉ có nút `⚙ CÀI ĐẶT`.
- Không còn nút/chữ DRIVE hoặc DEBUG.
- Góc trái: tốc độ.
- Khung cam-đỏ: Smart Lead.
- Xe khác: khung mảnh.
- Status nhỏ phía dưới.

## Lane kiểu ADAS
Chế độ thường:
- không vẽ raw vàng/cyan;
- biên vàng khi đang học calibration;
- biên xanh khi calibration ổn định;
- tô hành lang làn mờ;
- có đường tâm mờ;
- khi LDW, đúng biên nguy hiểm chuyển đỏ.

Thông tin kỹ thuật:
- có thể bật trong Cài đặt;
- raw UFLD vàng/cyan;
- horizon và hood.

## Calibration
Khi `locked` lần đầu:
- popup/banner `HIỆU CHỈNH CAMERA THÀNH CÔNG`;
- Google TTS đọc `Hiệu chỉnh camera thành công`;
- chỉ đọc khi trạng thái chuyển từ chưa hiệu chỉnh sang thành công.

## Cài đặt
- trạng thái Trial/Key;
- mã thiết bị;
- sao chép mã thiết bị;
- dán/kích hoạt key;
- hiện/ẩn thông tin kỹ thuật;
- hướng dẫn người mới;
- nghe thử cảnh báo bằng Google TTS.

## Nghe thử giọng
- Hiệu chỉnh camera thành công
- Xe phía trước di chuyển
- Nguy cơ va chạm
- Khoảng cách quá gần
- Chú ý lệch làn

## ADAS giữ nguyên
- Smart Lead Tracking + handoff/cut-in
- AutoCal + Hood Mask
- Distance
- Closing speed
- TTC/FCW
- HMW
- Lead Start
- LDW/TLC
- Google TTS
- Trial 5 phút + DG12
