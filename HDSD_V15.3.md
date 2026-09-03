# HDSD TRUNGKIEN V15.3

## Nhận làn khi điện thoại đặt dọc
- Không cần xoay ngang điện thoại.
- Gắn điện thoại cố định, camera nhìn thẳng phía trước.
- Không cần tự chỉnh góc camera.
- AUTO GÓC tự lấy tư thế gắn và V15.3 dùng kết quả này để chọn vùng mặt đường cho AI Lane Core.

## Trạng thái
- `LÀN: LANE CORE ... • sẵn sàng`: model AI đã nạp.
- `LÀN: CORE ...%`: đã nhận cặp làn.
- `LÀN: CORE • trái ...% • phải ...%`: AI đã thấy dấu hiệu vạch nhưng chưa đủ để khóa cặp làn.
- `LÀN: CORE • chưa xác định vạch`: AI chưa thấy vạch đủ tin cậy.

## V15.3 thay đổi
- Không kéo méo toàn bộ ảnh camera dọc vào model làn.
- Tự lấy vùng mặt đường dựa trên AUTO GÓC.
- Tự bù độ nghiêng camera trước khi AI xử lý.
- Quy đổi vạch AI về đúng vị trí trên camera.
- Giữ nguyên UFLD CULane FP32; không INT8/FP16.
