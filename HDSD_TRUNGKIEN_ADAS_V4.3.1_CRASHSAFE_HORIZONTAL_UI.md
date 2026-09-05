# TrungKien ADAS V4.3.1

## Sửa văng app lúc mở
- Startup chỉ nạp SPC + UFLD trước.
- SSD-MobileNet nạp trễ sau 5.5 giây.
- SSD dùng ORT CPU 1 thread, không XNNPACK.
- SSD lỗi thì app vẫn tiếp tục SPC + UFLD.
- Trạng thái SSD hiện trong metrics.

## Sửa parser SSD
- Tìm output theo tên detection_boxes / detection_scores / detection_classes / num_detections.
- Box vẫn ghi rõ loại: Ô TÔ, XE MÁY, XE TẢI, XE BUÝT, XE ĐẠP, NGƯỜI.

## Icon
- Icon ứng dụng đổi sang biểu tượng V4.
- Package/applicationId và signing giữ nguyên.

## Cài đặt
- Bỏ ScrollView dọc.
- Chuyển sang HorizontalScrollView 4 trang:
  LÁI XE / AI-HỆ THỐNG / BẢN QUYỀN / ÂM THANH.
- Vuốt ngang để chuyển trang; có tab bấm nhanh.
- Landscape compact, card tối hiện đại.

## Version
- versionCode 4310
- versionName 4.3.1
