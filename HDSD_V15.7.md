# TRUNGKIEN V15.7 — PICODET VEHICLE TEST

- Thay YOLOX-S 640 bằng PP-PicoDet-M 416.
- Detector xe dùng XNNPACK ngay từ đầu, không NNAPI.
- Input 416x416.
- Preprocess RGB + ImageNet mean/std + CHW.
- Model ONNX có sẵn postprocess/NMS.
- Giữ PERSON/BICYCLE/CAR/MOTORCYCLE/BUS/TRUCK.
- Giữ tracking, khoảng cách, cảnh báo và UFLD lane.
- Giữ cơ chế tự khôi phục ROAD CORE hiện tại.

Khi test:
- ROAD runtime phải là PICODET-M416/XNNPACK.
- ROAD USERS phải tiếp tục cập nhật số xe và ms sau 2, 5, 10 phút.
- Nếu ms vẫn cập nhật nhưng không thấy xe: chỉnh threshold/crop.
- Nếu runtime chuyển “đang khôi phục”: lỗi session/runtime.
