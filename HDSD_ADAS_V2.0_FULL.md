# TRUNGKIEN ADAS V2.0.1 FULL


## V2.0.1 — Build fix
- Không thay đổi logic ADAS.
- Sửa Kotlin receiver scope ở nút DRIVE/DEBUG.
- Trong `TextView.apply`, `overlay` bị hiểu là `View.overlay`.
- Đã đổi thành `this@MainActivity.overlay.setDebugMode(debugMode)`.
- Package riêng: `com.trungkien.adas.v201full`.
- Icon riêng: `V2.0.1 ADAS`.

## Cài song song
- Application ID: `com.trungkien.adas.v201full`
- Version: `2.0.1`
- Icon: `V2.0.1 ADAS`
- Không ghi đè V1.x.

## Nền AI
- YOLOX-Tiny 416 / XNNPACK: phương tiện.
- UFLD CULane 800x288 / XNNPACK: lane.
- Camera nằm ngang, CameraX 640x480.

## 1. Auto calibration
- UFLD lane 1/2 được fit theo x(y).
- Giao điểm lane tạo horizon.
- Tự học lane-center, lane-width, roll.
- 12 mẫu tốt -> `CAL`.
- Sau khi lock vẫn cập nhật rất chậm.
- Calibration được lưu local và tái sử dụng lần sau.

## 2. Hood mask
- Vùng đầu xe nằm khoảng 84–92% đáy ảnh tùy roll.
- Detection nhỏ nằm gần hoàn toàn trong vùng này bị loại.
- DEBUG mode vẽ đường hood/horizon; DRIVE mode ẩn.

## 3. Tracking + anti false alarm
- Track mới cần confidence cao hơn.
- Phương tiện phải sống >=3 frame mới hiện.
- Mất 1–4 frame ngắn hạn vẫn giữ track.
- CAR/BUS/TRUCK được phép match chéo khi AI đổi class.
- Khoảng cách và closing speed đều EMA.

## 4. Lead vehicle
- Nếu ego-lane đủ tin cậy: chỉ xe có tâm đáy nằm trong ego-lane mới là lead.
- Khi lane yếu: fallback corridor giữa 28–72% màn hình.
- Lead gần nhất được chọn.

## 5. Distance
- Ground geometry: bottom-box + horizon + camera height + FOV.
- Box-size chỉ là tín hiệu phụ.
- Giá trị luôn là ước lượng camera đơn mắt.

## 6. Relative speed
`closing = (distance_previous - distance_current) / dt`
- Không dùng GPS speed thay closing speed.
- EMA filtering.
- TTC chỉ tính khi closing >=0.70 m/s.

## 7. TTC / FCW
`TTC = distance / closing`

Mức:
- >6s: im lặng
- 4–6s: FCW1
- 2.8–4s: FCW2
- 1.8–2.8s: FCW3
- <=1.8s: FCW4

Có rise persistence 2 frame, fall persistence 4 frame.
FCW3+ có Google TTS: `Nguy cơ va chạm`.
Voice cooldown 7 giây.

## 8. HMW
`HMW = distance / ego_speed`
- áp dụng khi GPS >=30 km/h;
- HMW <0.90s liên tục >=3 frame -> cảnh báo `BÁM XE QUÁ GẦN`;
- chỉ beep nhẹ khi chưa có FCW.

## 9. Lead Start / đèn đỏ
ARM khi:
- GPS <=3 km/h;
- cùng lead track đứng >=2 giây.

Trigger khi:
- khoảng cách tăng >=1.2m;
- đồng thời box nhỏ đi hoặc đáy box nhích lên;
- đủ 2 frame.

Cảnh báo một lần:
`BÍP-BÍP` -> Google TTS `Xe phía trước di chuyển`.

## 10. LDW + TLC
Chỉ chạy khi:
- lane confidence >=42%;
- GPS >=35 km/h.

Tính lateral offset so với center ego lane.
Tính xu hướng offset và:
`TLC = remaining_to_boundary / lateral_rate`

Warning khi:
- |offset| > 0.70 lane-half;
hoặc
- |offset| >0.38 và TLC <1.20s;
- đủ >=3 frame.

Cảnh báo:
- 2 beep riêng;
- Google TTS `Chú ý lệch làn`;
- voice cooldown 7 giây.

## 11. Audio
FCW:
- level1: ~1.10s/tít
- level2: ~0.70s/tít
- level3: ~0.39s/tít
- level4: ~0.21s/tít, TONE_PROP_BEEP2 sắc hơn

Google engine ưu tiên:
`com.google.android.tts`

## 12. DRIVE / DEBUG
Nút góc phải:
- DRIVE: giao diện gọn.
- DEBUG: hiện inference ms, horizon, roll, hood, track counters.

## Lưu ý an toàn
Đây là thử nghiệm ADAS camera đơn mắt trên điện thoại.
Khoảng cách, TTC, HMW, TLC và cảnh báo không thay thế hệ thống ADAS/FCW được hiệu chuẩn của xe.
