# TRUNGKIEN CLEAN V1.2 — DISTANCE

## Cài song song
Application ID riêng:
`com.trungkien.cleanvehicle.v12distance`

V1.2 cài song song với CLEAN V1.0.x và V1.1.

## Icon
Icon ghi rõ `V1.2` và `DIST`.

## Nhận diện bớt nhạy
- car / bus / truck: 0.22
- motorcycle / bicycle: 0.28
- person: 0.35
- objectness: 0.06
- phương tiện phải ổn định ít nhất 3 frame liên tiếp mới vẽ.

## Khoảng cách
Luôn hiển thị ký hiệu `≈` vì chưa calibration.

Estimator kết hợp:
1. điểm đáy box + horizon giả định;
2. chiều cao box theo loại xe;
3. EMA smoothing.

Giả định ban đầu:
- camera height 1.25 m;
- horizon 0.43;
- vertical FOV 55°.

Giới hạn 2–80 m.

## Xe phía trước
Phương tiện ổn định gần nhất có tâm nằm trong 28–72% chiều ngang màn hình.

## Chưa có
- TTS;
- cảnh báo khoảng cách;
- GPS;
- hiệu chỉnh camera.

Mục tiêu: kiểm độ ổn định box và xem khoảng cách tăng/giảm có hợp lý.
