# TrungKien ADAS V4.3.0

## Lane rendering
- Không dùng Supercombo laneLines để vẽ vạch xanh nữa.
- Vạch xanh lấy trực tiếp UFLD CULane ego lane 1/2 theo tọa độ ảnh.
- Chỉ vẽ khi cả 2 inner lane confidence >= 0.42.
- Kiểm tra chiều rộng/cân tâm ở y=0.72; bất hợp lý thì ẩn lane thay vì vẽ nhầm dải phân cách.
- SPC vẫn vẽ center/path dự đoán màu nhạt và vẫn giữ lead distance.

## Object class boxes
- Thêm SSD-MobileNetV1-12 INT8.
- Nhận: NGƯỜI, XE ĐẠP, Ô TÔ, XE MÁY, XE BUÝT, XE TẢI.
- Box luôn ghi tên loại + confidence.
- Chỉ box khớp VERIFIED SPC lead mới được gắn LEAD + khoảng cách.
- Box khác không tự gán khoảng cách.
- SSD chạy mỗi khoảng 4 frame; máy nóng khoảng 8 frame.

## Kiến trúc
- SPC: lead distance / path / TTC / HMW / FCW.
- UFLD: lane hiển thị.
- SSD-MobileNet: class + bounding box.
- YOLO vẫn bị loại.

versionCode 4300
versionName 4.3.0
