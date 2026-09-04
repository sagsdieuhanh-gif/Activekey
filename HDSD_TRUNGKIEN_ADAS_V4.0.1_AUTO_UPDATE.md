# TrungKien ADAS V4.0.1 — FIX + AUTO UPDATE

## V4 compile fixes
- Khai báo `SC_DISTANCE_MIN_PROB = 0.55f`.
- Sửa chuỗi xuống dòng trong `metricsText()` thành `\n` hợp lệ.

## Auto update client
Từ V4.0.1:
- app tự kiểm tra GitHub Release sau khi mở;
- kiểm tra lại tối đa khoảng 3 giờ/lần;
- Wi-Fi/unmetered: tự tải;
- mạng tính phí: hỏi trước;
- APK phải đúng package `com.trungkien.adas`;
- versionCode phải mới hơn;
- signing certificate phải khớp app stable đang cài;
- sau đó app mở Android Package Installer.

Android app thường không được silent-install. Người dùng vẫn phải xác nhận cài đặt, và lần đầu có thể phải bật “Cho phép từ nguồn này”.

## Release
Workflow tạo release tag:
`trungkien-adas-<versionCode>-v<versionName>`

V4.0.1:
`trungkien-adas-4001-v4.0.1`

Các bản sau chỉ cần tăng versionCode/versionName; không đổi package, stable signer, DG12 hay salt V22.
