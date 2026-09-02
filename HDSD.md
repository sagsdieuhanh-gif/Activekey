# HƯỚNG DẪN SỬ DỤNG — TRUNGKIEN V12.0.0 (R2)

## 1. Lần chạy đầu
1. Cài app và cấp CAMERA; nên cấp VỊ TRÍ CHÍNH XÁC để có GPS speed.
2. Bản dùng thử chạy tối đa 5 phút foreground.
3. Nếu muốn kích hoạt ngay: `CÀI ĐẶT -> BẢN QUYỀN / KEY`, gửi `MÃ THIẾT BỊ` cho admin, nhận key và dán vào ô kích hoạt.

## 2. Gắn điện thoại
- Gắn chắc chắn, camera sau nhìn thẳng về phía trước.
- Tránh để điện thoại rung hoặc nghiêng thay đổi liên tục.
- Nhập đúng chiều cao camera nếu cần trong `HÌNH HỌC CAMERA`.
- Bấm `AUTO GÓC` sau khi cố định điện thoại và cho xe/điện thoại hướng theo đường vài giây.
- V12 R2 không còn vẽ đường chân trời trên camera. Phần đo dùng hình học mặt đường, điểm chân xe, lane và góc camera/IMU ở bên trong.

## 3. Loại phần đầu xe/ca-pô
1. Vào `CÀI ĐẶT -> VÙNG BỎ QUA ĐẦU XE`.
2. Trên camera xuất hiện vạch kéo ngang.
3. Kéo vạch tới ngay phía trên phần đầu xe/ca-pô nhìn thấy trong hình.
4. Vùng phủ phía dưới là vùng loại trừ.
5. Bấm `LƯU VÙNG`.
6. App tự xóa thang bù cũ nếu biên thay đổi đáng kể và tự học lại.

`RESET VÙNG ĐẦU XE` trả về mốc mặc định để chỉnh lại.

## 4. LANE HYBRID
- `LÀN OK`: lane đủ tin cậy.
- `LÀN ƯỚC LƯỢNG`: chỉ có một biên hoặc lane đang được giữ ngắn hạn; dùng để hỗ trợ tracking nhưng không tự phát cảnh báo lệch làn mạnh.
- `LÀN: CHƯA NHẬN DIỆN`: chưa có hình học đủ tin cậy.

## 5. FRONT FIRST + LONG RANGE 100 m
- Khung/target đo chính ưu tiên ô tô nằm trong hành lang trước mặt.
- Xe máy/xe đạp bên trái/phải không được phép giành target chính chỉ vì ở gần hơn.
- Khi tốc độ từ khoảng 60 km/h trở lên và máy không nóng, V12 luân phiên một lượt `LONG 100m` phóng lớn vùng trung tâm để tăng khả năng bắt xe nhỏ ở 60–100 m.
- 60–100 m chủ yếu là vùng phát hiện sớm: số đo hiển thị dạng `~80 m`, `~100 m`, không coi là số đo tuyệt đối.
- 1–30 m vẫn là vùng được ưu tiên cao nhất cho TTC/cảnh báo va chạm.

## 6. Khoảng cách an toàn theo tốc độ
Trong điều kiện đường khô, tầm nhìn tốt và không có biển quy định cự ly khác, V12 dùng bảng tham chiếu:
- V = 60 km/h: 35 m.
- 60 < V ≤ 80 km/h: 55 m.
- 80 < V ≤ 100 km/h: 70 m.
- 100 < V ≤ 120 km/h: 100 m.
- Dưới 60 km/h: không ép một trị số pháp lý cố định; tiếp tục dùng TTC/time-gap động.

App không xác nhận đủ khoảng cách chỉ từ số đo trung tâm. Điều kiện xác nhận là:

`khoảng cách đo - sai số ước tính >= khoảng cách yêu cầu`

Phải ổn định khoảng 2,5 giây mới đọc: `Bạn đã giữ đủ khoảng cách an toàn.`

Nếu toàn bộ dải sai số vẫn thấp hơn yêu cầu, app có thể đọc: `Khoảng cách chưa an toàn, cần tối thiểu ... mét.`

Sai số được tăng theo khoảng cách/độ tin cậy; vùng 60–100 m thường có biên lớn hơn vùng gần.

## 7. ĐỌC BIỂN BÁO AI
Có nút riêng trên màn hình chính:
- `BIỂN BÁO AI: TẮT`: module sign/OCR đóng hoàn toàn, không chạy nền.
- `BIỂN BÁO AI: BẬT`: mới chạy nhận diện biển báo đơn giản.

V12 R2 ưu tiên:
- P.127 tốc độ tối đa: đọc số 20/30/40/50/60/70/80/90/100/110/120.
- R.420 bắt đầu khu đông dân cư.
- R.421 hết khu đông dân cư.

Kết quả phải lặp qua nhiều frame mới được xác nhận. Khi đọc P.127, giới hạn được hiện cạnh tốc độ GPS; nếu GPS vượt giới hạn ổn định, app đọc `Bạn đang vượt tốc độ cho phép.` Khi trở lại phù hợp sau trạng thái vượt tốc, app đọc một lần `Tốc độ đã phù hợp.`

Tắt `BIỂN BÁO AI` không ảnh hưởng xe phía trước, khoảng cách, lane, cut-in hoặc TTC.

## 8. Cut-in trái/phải
- `THEO DÕI LẤN LÀN`: bắt đầu có xu hướng vào lane.
- `DỰ ĐOÁN LẤN LÀN`: quỹ đạo dự kiến cắt biên lane trong thời gian gần.
- `ĐANG VÀO LÀN`: nguy cơ cao hơn, ưu tiên cảnh báo.
- TLC = thời gian dự kiến tới lúc cắt biên làn; TTC = thời gian va chạm ước tính theo tốc độ áp sát.

## 9. Tự hiệu chỉnh khoảng cách
Không nhập mốc thật bằng tay. App chỉ học khi nhiều điều kiện cùng đạt: target ổn định, lane tin cậy, nguồn metric độc lập mới và tương thích, không phải vùng đầu xe, không phải detection dự đoán.

Bấm `AUTO SAI SỐ` để xem số mẫu/hệ số hoặc xóa dữ liệu tự học.

## 10. Cảnh báo khoảng cách gần
- >20 m: chủ yếu hiển thị, không nói liên tục.
- 20 m: chú ý.
- 10 m: cảnh báo khoảng cách dưới 10 m.
- 5 m: cảnh báo còn 5 m.
- 4 m: không đọc riêng.
- 3 m: quá gần.
- 2 m: nguy cơ va chạm.
- 1 m: nguy hiểm, phanh ngay.
- TTC có quyền nâng cấp cảnh báo sớm hơn mốc khoảng cách.

## 11. Giảm nóng máy
- THERMAL GUARD tự hạ tần suất xử lý khi máy nóng.
- LONG 100m chỉ chạy luân phiên khi máy ở trạng thái bình thường.
- `BIỂN BÁO AI` tắt thì Sign Core/OCR không chạy.
- Khi nguy cơ tăng, app tạm tăng nhịp cho pipeline an toàn.
- `CÀI ĐẶT -> MÀN HÌNH TIẾT KIỆM` giảm sáng khi chạy lâu.
- Khi Android báo `RẤT NÓNG`, app tự hạ sáng tạm thời và ưu tiên các chức năng an toàn chính.

## 12. Kích hoạt key
- Mở `CÀI ĐẶT -> BẢN QUYỀN / KEY`.
- Gửi `MÃ THIẾT BỊ` cho admin.
- Dán chuỗi key được cấp -> `KÍCH HOẠT`.
- Key sai thiết bị, sai chữ ký hoặc hết hạn sẽ bị từ chối.

## 13. Lưu ý an toàn
Ứng dụng là công cụ hỗ trợ thử nghiệm, không thay thế quan sát của người lái, biển báo thực tế, phanh tự động hay hệ thống ADAS được chứng nhận. Điều kiện mưa/sương mù/đường trơn/đèo dốc cần khoảng cách lớn hơn mức tham chiếu. Không thao tác kéo/cài đặt khi xe đang chạy.
