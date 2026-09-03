# TRUNGKIEN CLEAN V1.1 — LANE TEST

Nền: CLEAN V1.0.2 đang nhận diện xe tốt.

## Giữ nguyên
- YOLOX-Tiny 416.
- XNNPACK.
- CameraX 640x480.
- Landscape.
- Box xe/người hiện tại.

## Chỉ thêm lane
- UFLD CULane FP32 chính xác.
- Input `[1,3,288,800]`.
- Output `[1,201,18,4]`.
- RGB + ImageNet normalization.
- Decoder reference:
  - reverse row dimension;
  - class 200 = no-lane;
  - argmax dùng đủ 201 class;
  - softmax chỉ 200 location class;
  - expectation index 1..200;
  - lane phải có >2 anchor.

## Không có
- CV fallback.
- smoothing.
- estimated lane.
- lane departure.
- TTS.
- hiệu chỉnh IMU.
- perspective geometry.
- cảnh báo.

Mục tiêu của V1.1 chỉ là xác định UFLD có bám đúng vạch đường thật trên camera nằm ngang hay không.

## Màu hiển thị
- Hai lane giữa UFLD index 1/2: nét dày màu vàng.
- Hai lane ngoài: nét mảnh màu cyan.
- Box YOLOX: giữ màu xanh.

## Debug
Hiển thị:
- ROAD LIVE / ROAD STALL.
- số XE, NGƯỜI, road ms.
- LANE LIVE / LANE STALL.
- confidence ego lane L/R.
- lane inference ms.
- số inference lane.

Lane chạy mỗi 2 frame phân tích để không làm mất độ ổn định detector xe.
