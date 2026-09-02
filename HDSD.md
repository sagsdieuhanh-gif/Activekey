# HƯỚNG DẪN SỬ DỤNG — TRUNGKIEN V13.2 FULL ADAS

## 1. Gắn điện thoại
- Cố định điện thoại chắc chắn, camera sau nhìn thẳng theo hướng xe.
- Không cần căn theo một “đường chân trời” trên màn hình; V13 không hiển thị horizon.
- Lần đầu hoặc sau khi đổi vị trí gắn, bấm **AUTO GÓC** và chạy thẳng vài giây để hệ thống ổn định.

## 2. Vùng bỏ đầu xe
Vào **CÀI ĐẶT → VÙNG BỎ QUA ĐẦU XE**. Kéo vạch ngang tới ngay phía trên phần capo/đầu xe xuất hiện trong camera rồi bấm **LƯU VÙNG**. Phần dưới vạch không được dùng làm mục tiêu đo. Không có mức 20% cố định.

## 3. Cách đọc giao diện ADAS
- Hai biên xanh ngọc: ego-lane đang theo dõi.
- Vùng xanh nhẹ + chevrons: hành lang/tâm làn.
- Nhãn lớn phía trên xe: lead vehicle hiện tại.
- Xanh ngọc: trạng thái ổn định/an toàn tương đối.
- Vàng: cần chú ý, khoảng cách/lấn làn.
- Đỏ: nguy cơ cao/quá gần/collision.
- Xe hai bên bình thường có thể không được vẽ; chỉ threat/cut-in mới nổi bật.

Khi lane yếu hoặc chỉ đang ước lượng, đường lane sẽ mờ/nét đứt hơn. Đây là chủ ý để không tạo cảm giác chắc chắn giả.

## 4. Khoảng cách 0–100 m
- <10 m: ưu tiên phản ứng nhanh; khi đủ confidence có thể hiển thị chi tiết hơn.
- 10–40 m: vùng đo/tracking chính.
- 40–60 m: far range.
- 60–100 m: long range, UI làm tròn mạnh hơn và thường có dấu `~`.

Camera đơn trên điện thoại không phải radar/LiDAR. Ở 60–100 m V13 dùng khoảng cách ước lượng + biên sai số bảo thủ, mục tiêu chính là phát hiện/ước lượng xe ở xa và không tuyên bố độ chính xác giả.

## 5. Giữ khoảng cách theo tốc độ
Khi GPS và range đủ tin cậy, app đánh giá cự ly tham chiếu theo dải tốc độ đã cấu hình. V13 dùng **cận dưới của khoảng đo** (distance - uncertainty) trước khi thông báo “đủ khoảng cách”, vì vậy số đo không chắc chắn sẽ ở trạng thái đang xác nhận thay vì báo an toàn.

## 6. Xe trái/phải lấn làn
V13 theo dõi motion của xe lane bên:
- bình thường: không làm rối màn hình;
- WATCH: tiến gần biên lane;
- DỰ ĐOÁN LẤN LÀN: xu hướng cắt lane đã đủ bằng chứng;
- ĐANG VÀO LÀN: mép xe đã rất sát/đang cắt vạch.

Cảnh báo dựa vào TLC, khoảng cách, closing speed và lane confidence; không chỉ dựa vào một frame.

## 7. Cảnh báo giọng nói
Ưu tiên giọng Việt, TTS nữ miền Nam nếu engine Android có voice phù hợp. Mốc khoảng cách gồm 20 / 10 / 5 / 4 / 3 / 2 / 1 m. App không xếp hàng hàng loạt câu thấp ưu tiên; cảnh báo nguy hiểm có quyền ngắt câu thông tin.

## 8. BIỂN BÁO AI
Nút **BIỂN BÁO AI** là công tắc riêng. Khi TẮT, pipeline biển báo/OCR không chạy để giảm nhiệt. Khi BẬT, V13 ưu tiên biển tốc độ tối đa và biển bắt đầu/hết khu đông dân cư. Biển phải được xác nhận qua nhiều frame trước khi áp dụng.

## 9. Nhiệt máy
V13 tự chuyển NORMAL → BALANCED → HOT → VERY_HOT dựa trên thermal status và nhiệt độ pin. Khi nóng, app giảm nhịp inference/render trước, ưu tiên giữ lead/lane. Khi xe đứng yên và không có nguy cơ, workload cũng tự hạ.

## 10. DEBUG ADAS (ẩn)
Nhấn giữ chip trạng thái trên góc trên màn hình để bật/tắt DEBUG. Debug chỉ dành cho test, hiển thị Track ID/FPS/lane confidence/TTC/range quality/thermal. Khi DEBUG bật, app ghi CSV 1 Hz vào bộ nhớ riêng của app; không ghi video.

## 11. Bản quyền
- Cài mới: dùng thử 5 phút foreground.
- Hết trial: mở **BẢN QUYỀN / KEY**, gửi MÃ THIẾT BỊ cho Admin.
- Admin tạo key bằng app Admin riêng rồi gửi chuỗi key để kích hoạt.
- V12/V13 dùng chung hệ key hiện tại.

## 12. Lưu ý sử dụng
V13 là công cụ hỗ trợ quan sát thử nghiệm trên điện thoại, không phải hệ thống ADAS/radar được chứng nhận và không điều khiển phanh/vô-lăng. Luôn ưu tiên quan sát thực tế, biển báo thật và điều kiện giao thông.
