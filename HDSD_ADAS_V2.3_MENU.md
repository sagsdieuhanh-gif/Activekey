# TRUNGKIEN ADAS V2.3 — MENU + HDSD

## Menu
Trong màn hình DRIVE có 2 nút ở góc phải:
- `DRIVE/DEBUG`
- `MENU`

MENU gồm:
1. trạng thái TRIAL/KEY;
2. mã thiết bị;
3. nút SAO CHÉP MÃ THIẾT BỊ;
4. DÁN KEY / KÍCH HOẠT;
5. chuyển DRIVE ↔ DEBUG;
6. Hướng dẫn nhanh cho người mới;
7. giải thích FCW / HMW / LDW / TLC / Lead Start;
8. nút ĐÓNG MENU.

## Mã thiết bị / key
Không đổi cơ chế V2.2.
- vẫn dùng `AdasLicenseManager` hiện tại;
- device-code salt không đổi;
- public key DG12 không đổi;
- mã thiết bị V2.2 và V2.3 trên cùng máy là như nhau;
- key Admin đã cấp cho đúng device code có thể nhập lại trong V2.3.

## Hướng dẫn nhanh trong app

### 1. Gắn điện thoại
- nằm ngang;
- camera sau nhìn thẳng trước xe;
- không để taplo/vật khác che quá nhiều mặt đường.

### 2. Quyền
- CAMERA;
- LOCATION để lấy tốc độ;
- bật Media Volume.

### 3. Auto Calibration
Đi qua đường có lane rõ cho tới khi thấy `CAL`.
Không phải tự nhập góc camera.

### 4. Màu khung
- đỏ: XE PHÍA TRƯỚC / LEAD;
- xanh: phương tiện khác.

### 5. Distance / HMW / TTC
- `≈ xx m`: khoảng cách ước lượng;
- HMW: khoảng thời gian đang bám xe;
- TTC: thời gian va chạm ước lượng nếu đang closing.

### 6. Lead Start
Dừng đèn đỏ:
`BÍP-BÍP` → Google TTS `Xe phía trước di chuyển`.

### 7. LDW
Khi đủ tốc độ và có xu hướng trôi khỏi lane:
beep → Google TTS `Chú ý lệch làn`.

## Bản quyền
Trial 5 phút sử dụng thực tế.
Hết trial:
- xem mã thiết bị;
- sao chép;
- gửi Admin;
- nhận key;
- dán key;
- kích hoạt.

## Phiên bản
- Version: 2.3.0
- Package: `com.trungkien.adas.v23menu`
- Icon: `V2.3 MENU`
- Cài song song bản cũ.

## Chức năng ADAS
Giữ nguyên toàn bộ V2.2/V2.1:
- Smart Lead Tracking;
- lane handoff;
- cut-in;
- auto calibration;
- hood mask;
- distance;
- relative closing speed;
- TTC/FCW;
- HMW;
- Lead Start;
- Google TTS;
- LDW/TLC;
- license DG12.
