# HƯỚNG DẪN SỬ DỤNG — TRUNGKIEN V14.1.0 NIGHT/CENTER ADAS

## 1. Gắn điện thoại
- Cố định điện thoại chắc chắn, camera sau nhìn thẳng theo hướng xe.
- Không cần căn theo đường chân trời trên màn hình.
- Sau khi đổi vị trí gắn, bấm **AUTO GÓC** và chạy thẳng vài giây để hệ thống ổn định.

## 2. Vùng bỏ đầu xe
Vào **CÀI ĐẶT → VÙNG BỎ QUA ĐẦU XE**. Kéo vạch ngang tới ngay phía trên phần capo/đầu xe xuất hiện trong camera rồi bấm **LƯU VÙNG**. Phần dưới vạch không được dùng làm mục tiêu đo.

## 3. NIGHT AUTO
V14.1 tự nhận biết cảnh thiếu sáng/ban đêm, không cần nút bật riêng.
- Hệ thống dùng cả độ sáng trung bình và tỷ lệ vùng tối, vì đèn đường/đèn pha có thể làm trung bình ảnh sáng giả.
- Khi NIGHT AUTO hoạt động, Road Core được nâng vùng tối có kiểm soát.
- Giao diện trạng thái lane có thể hiện `NIGHT` hoặc `NIGHT CENTER` khi đang dùng cơ chế dự phòng ban đêm.

## 4. Lane NEAR-FIRST ban đêm
V14.1 vẫn ưu tiên vạch gần xe nhưng thêm xử lý riêng cho thiếu sáng:
- quét dày hơn vùng khoảng 50–96% chiều cao ảnh;
- ưu tiên **độ tương phản cục bộ của vạch phản sáng so với mặt đường**, không bắt vạch phải trắng sáng tuyệt đối;
- cho phép khóa một bên vạch ở confidence thấp hơn rồi suy ra biên còn lại ở mức **ƯỚC LƯỢNG**;
- lane yếu/ước lượng vẫn vẽ mờ hoặc nét đứt, không giả vờ đã khóa chắc chắn;
- khi Lane Core và vạch phản sáng gần xe bất đồng, vạch gần xe có thể được quyền ưu tiên.

## 5. CENTER FALLBACK khi lane chưa khóa
Đây là thay đổi quan trọng của V14.1:
- **mất lane không còn đồng nghĩa mất luôn xe phía trước**;
- khi lane yếu/đang tìm, app định kỳ chạy một crop tập trung vào vùng chính giữa phía trước;
- xe ô tô/bus/truck ổn định trong phễu giữa có thể trở thành LEAD dù lane chưa đạt confidence khóa;
- khi lane ổn định trở lại, hệ thống tự chuyển về ego-lane corridor bình thường.

## 6. CENTER-FIRST / LEAD-FIRST
- phương tiện đúng chính giữa phía trước được ưu tiên tracking/range/TTC;
- xe hai bên bình thường không được cướp lead;
- xe máy/xe đạp bên chỉ nổi lên khi thật sự tiến vào ego corridor/có bằng chứng cut-in;
- người đi bộ ở vỉa hè/lề/ngoài lõi ego path tiếp tục bị bỏ qua phần lớn.

## 7. Cách đọc giao diện
- Hai biên xanh ngọc: ego-lane đang theo dõi.
- Vùng xanh nhẹ + chevrons: hành lang/tâm làn.
- Nhãn lớn phía trên xe: lead vehicle hiện tại.
- `LÀN: ĐANG TÌM • NIGHT CENTER`: lane chưa khóa nhưng lead detector đang dùng phễu trung tâm ban đêm.
- Xanh ngọc: ổn định/an toàn tương đối.
- Vàng: cần chú ý, cut-in hoặc khoảng cách chưa đủ.
- Đỏ: nguy cơ cao/quá gần/collision.

## 8. Khoảng cách 0–100 m
- <10 m: ưu tiên phản ứng nhanh.
- 10–40 m: vùng đo/tracking chính.
- 40–60 m: far range.
- 60–100 m: long range; số đo được làm tròn và thường có dấu `~`.

Camera đơn không phải radar/LiDAR. Ở 60–100 m và ban đêm, V14.1 coi kết quả là ước lượng và dùng biên sai số bảo thủ.

## 9. Xe trái/phải lấn làn
Side Guard chạy nền. Xe bên chỉ được đưa lên HUD khi:
- có vận tốc ngang hướng vào ego lane;
- TLC giảm đủ rõ;
- hoặc mép xe thực sự bắt đầu cắt biên lane.

Xe chạy song song bình thường không tạo cảnh báo chỉ vì ở gần.

## 10. Người đi bộ
V14.1 chỉ ưu tiên người khi:
- nằm trong lõi ego path;
- hoặc rất gần lõi ego path ở cự ly ngắn.

Người trên vỉa hè/lề/ngoài hành lang chính giữa không được phép chiếm HUD hay cảnh báo chính.

## 11. BIỂN BÁO AI
Khi TẮT, pipeline biển báo/OCR không chạy. Khi BẬT, app ưu tiên biển tốc độ và biển khu dân cư.
- Nếu trạng thái luật đang được **nhớ từ lần đọc trước** nhưng observation trực tiếp đã hết hạn, nút hiển thị thêm `ĐÃ NHỚ`.
- Điều này giúp phân biệt trạng thái đang áp dụng với biển vừa được camera đọc trong frame hiện tại.

## 12. Nhiệt máy
V14.1 tiếp tục dùng Thermal Guard NORMAL → BALANCED → HOT → VERY_HOT. Center-focus ban đêm chạy xen kẽ, không chạy đồng thời với full-frame, để hạn chế tăng tải quá mức.

## 13. DEBUG ADAS
Nhấn giữ chip trạng thái phía trên để bật/tắt DEBUG. Debug hiển thị FPS, lane confidence, lead ID, range quality, TTC và thermal; không lưu video.

## 14. Bản quyền
- Cài mới: dùng thử 5 phút foreground.
- Hết trial: mở **BẢN QUYỀN / KEY**, gửi MÃ THIẾT BỊ cho Admin.
- V14.1 giữ cùng hệ key với V12/V13/V14; app Admin hiện tại tiếp tục cấp key được.

## 15. Lưu ý sử dụng
V14.1 là công cụ hỗ trợ quan sát thử nghiệm bằng camera điện thoại, không phải ADAS/radar được chứng nhận và không điều khiển phanh/vô-lăng. Luôn ưu tiên quan sát thực tế, biển báo thật và điều kiện giao thông.
