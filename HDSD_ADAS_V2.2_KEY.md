# TRUNGKIEN ADAS V2.2 — KEY 5 MIN

## Trial
- Dùng thử 5 phút.
- Tính theo thời gian app đang foreground, dùng `SystemClock.elapsedRealtime`.
- Đóng app thì trial dừng đếm.
- Hết trial sẽ không khởi chạy Camera/AI nữa.

## Màn hình hết hạn
- MÃ THIẾT BỊ dạng `XXXX-XXXX-XXXX-XXXX`.
- Nút `SAO CHÉP MÃ THIẾT BỊ`.
- Ô dán key.
- Nút `DÁN KEY`.
- Nút `KÍCH HOẠT`.

## License
Tương thích `TRUNGKIEN ADMIN KEY V1.1`.

Payload:
`DG12|DEVICE|EXPIRY_EPOCH_DAY|SERIAL`

Chữ ký:
ECDSA P-256 + SHA256withECDSA.

App chỉ chứa public key, không chứa private key.

## Key có hạn
Admin hiện hỗ trợ:
- Vĩnh viễn
- 30 ngày
- 90 ngày
- 365 ngày
- Đến ngày cụ thể

## Phiên bản
- 2.2.0
- Package `com.trungkien.adas.v22key`
- Icon `V2.2 KEY`
- Cài song song các bản cũ.

## ADAS
Toàn bộ Smart Lead V2.1 và FCW/HMW/LDW/TLC/Lead Start/Google TTS/AutoCal/Hood Mask giữ nguyên.
