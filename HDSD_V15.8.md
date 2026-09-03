# TRUNGKIEN V15.8 — YOLOX-Tiny 416 Stability Test

PicoDet đã dừng vì V15.7B/V15.7C không cho detection thực tế.

V15.8 quay về pipeline YOLOX đã từng nhận diện được:
- lấy RoadSenseEngine + RoadSensePreprocessor từ bản trước PicoDet;
- đổi input 640 -> 416;
- dùng official YOLOX-Tiny ONNX;
- giữ đúng YOLOX preprocess: BGR raw 0..255, letterbox top-left màu 114;
- giữ decoder grid/stride, objectness, class score và NMS cũ;
- ép XNNPACK ngay từ lần khởi tạo đầu tiên, không NNAPI;
- giữ tracking, khoảng cách, lane UFLD, cảnh báo hiện tại.

Mục tiêu test:
1. Xe phải được nhận diện ngay từ lúc mở app.
2. Chạy liên tục 5–10 phút.
3. ROAD USERS phải tiếp tục cập nhật latency ms.
4. Nếu mất detection sau 1–2 phút nhưng latency vẫn chạy: chỉnh detector/tracking.
5. Nếu latency đứng hẳn: xác nhận runtime/session stall và làm watchdog riêng.
