# TRUNGKIEN CLEAN V1

Đây là app mới hoàn toàn để kiểm tra riêng nhận diện phương tiện.

## Không lấy từ app cũ
- Không lane.
- Không tracking.
- Không khoảng cách.
- Không GPS.
- Không TTS.
- Không key.
- Không thermal policy.
- Không center crop / long-range crop.
- Không Night Auto.
- Không watchdog tự restart.

## Pipeline duy nhất
CameraX RGBA 640x480
→ xoay đúng orientation
→ YOLOX official letterbox 416x416
→ BGR/CHW Float32 raw 0..255
→ YOLOX-Tiny ONNX
→ decode grid/stride
→ class-aware NMS
→ vẽ box.

## Runtime
Ưu tiên XNNPACK 2 threads. Nếu XNNPACK không khởi tạo được mới dùng CPU.

## Hiển thị
- AI LIVE: inference vẫn hoàn thành.
- AI STALL: hơn 3 giây không có inference hoàn thành.
- XE: số phương tiện hiện tại.
- NGƯỜI: số người hiện tại.
- ms: thời gian inference.
- #n: số inference đã hoàn thành.

## Cài song song
Application ID riêng:
`com.trungkien.cleanvehicle.v1`

Không đè lên app TrungKien hiện tại.

## Mục đích test
Chạy ít nhất 10–15 phút. Nếu CLEAN V1 vẫn nhận đều thì lỗi của app cũ nằm ở các lớp tích hợp/pipeline, không phải camera hay model cơ bản.
