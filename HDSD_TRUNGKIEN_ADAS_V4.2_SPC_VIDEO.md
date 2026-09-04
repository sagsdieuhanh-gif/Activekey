# TrungKien ADAS V4.2 — SPC CORE VIDEO MODE

Video tham chiếu dài khoảng 66 giây được phân tích theo nhiều mốc. Các đặc điểm chính: khoảng 3–4 FPS, XNNPACK, temporal x2, pair khoảng 200–300 ms, feat 24/24, pitch/yaw, fPx khoảng 890 với ratio 0.695 trên khung rộng 1280, horizon khoảng 399–400 trên khung cao 720, bốn lane probabilities + confidence, hành lang xanh, marker lead màu cam và HUD khoảng cách lớn chỉ khi lead hợp lệ.

V4.2 triển khai:
- CameraX 1280x720 16:9.
- Camera2 intrinsic: ưu tiên LENS_INTRINSIC_CALIBRATION, fallback focal mm/sensor, cuối cùng ratio 0.695.
- Auto calibration 24 mẫu; READY từ 12 mẫu; pitch/yaw/roll/horizon; calibration quality.
- Big virtual warp trước Supercombo.
- Supercombo temporal 2 frame + recurrent state 512 + XNNPACK.
- YOLO bị loại khỏi runtime và workflow; không tải model, không inference, không distance.
- SPC lead threshold chính 45%, hold 650 ms, EMA alpha 0.58 khi tiến gần và 0.40 khi rời xa.
- Closing speed/TTC/HMW/FCW dùng chuỗi SPC distance.
- Dùng đủ 33 điểm path/lane car-space; project bằng focal ratio + horizon; inner lane tạo hành lang xanh; center path lớp mint; lead marker cam là trapezoid trên mặt đường.
- UFLD chỉ bootstrap camera calibration/fallback lane; sau calibration chạy thưa mỗi 8 frame.
- Thermal governor 1/2/3 stride tùy nhiệt, có thể tắt trong Settings để benchmark.
- Technical HUD: FPS, runtime, inference ms x2, pair ms, feat 24/24, pitch/yaw, calibration %, fPx, ratio, horizon, 4 lane probability, confidence.
- Giữ Google TTS, DG12, device code, package com.trungkien.adas, stable signing key và auto update client.

Version 4.2.0 / code 4200.
