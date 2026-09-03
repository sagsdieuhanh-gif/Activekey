# TRUNGKIEN V15.7B — PICODET VEHICLE TEST

Bản sửa V15.7, không dùng cơ chế patch theo chuỗi cũ.

- PP-PicoDet-M 416 thay YOLOX-S.
- XNNPACK ngay từ đầu, không NNAPI.
- Preprocess 416x416, RGB, ImageNet mean/std, CHW.
- Model postprocessed ONNX.
- Giữ tracking, chọn xe phía trước, tính khoảng cách, cảnh báo, UFLD.
- Workflow luôn ghi PicoDet mới vào offline_models trước khi build.
- build.gradle luôn xóa road_core generated cũ, tránh vô tình đóng gói YOLOX cũ.

Khi test:
- ROAD runtime phải hiện PICODET-M416/XNNPACK.
- Chạy ít nhất 5–10 phút.
- Theo dõi ROAD USERS và thời gian inference.
