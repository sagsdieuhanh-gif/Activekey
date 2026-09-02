# HƯỚNG DẪN SỬ DỤNG — TRUNGKIEN V14.0.0 CENTER-FIRST ADAS

## 1. Gắn điện thoại
- Cố định điện thoại chắc chắn, camera sau nhìn thẳng theo hướng xe.
- Không cần căn theo đường chân trời trên màn hình.
- Sau khi đổi vị trí gắn, bấm **AUTO GÓC** và chạy thẳng vài giây để hệ thống ổn định.

## 2. Vùng bỏ đầu xe
Vào **CÀI ĐẶT → VÙNG BỎ QUA ĐẦU XE**. Kéo vạch ngang tới ngay phía trên phần capo/đầu xe xuất hiện trong camera rồi bấm **LƯU VÙNG**. Phần dưới vạch không được dùng làm mục tiêu đo.

## 3. V14 ưu tiên làn gần xe
V14 dùng chiến lược **NEAR-FIRST**:
- ưu tiên vạch trắng/vàng ở phần đường phía dưới ảnh;
- vạch kẻ đường thật được ưu tiên hơn mép vỉa hè, barrier hoặc road edge;
- khi Lane Core và vạch gần xe bất đồng, vạch kẻ đường gần xe được quyền thắng;
- lane yếu/ước lượng được vẽ mờ hoặc nét đứt, không giả vờ đã khóa chắc chắn.

## 4. CENTER-FIRST / LEAD-FIRST
Mục tiêu chính của V14 là phương tiện nằm trong hành lang **chính giữa phía trước xe**.
- xe ô tô/xe máy đúng ego path: ưu tiên tracking, range và TTC;
- xe hai bên bình thường: không được cướp lead;
- xe bên chỉ nổi bật khi có xu hướng lấn/cắt vào lane;
- người đi bộ ở vỉa hè/lề/ngoài lõi ego path được bỏ qua phần lớn.

## 5. Cách đọc giao diện
- Hai biên xanh ngọc: ego-lane đang theo dõi.
- Vùng xanh nhẹ + chevrons: hành lang/tâm làn.
- Nhãn lớn phía trên xe: lead vehicle hiện tại.
- Xanh ngọc: ổn định/an toàn tương đối.
- Vàng: cần chú ý, cut-in hoặc khoảng cách chưa đủ.
- Đỏ: nguy cơ cao/quá gần/collision.

## 6. Khoảng cách 0–100 m
- <10 m: ưu tiên phản ứng nhanh.
- 10–40 m: vùng đo/tracking chính.
- 40–60 m: far range.
- 60–100 m: long range; số đo được làm tròn và thường có dấu `~`.

Camera đơn không phải radar/LiDAR. Ở 60–100 m V14 coi kết quả là ước lượng và dùng biên sai số bảo thủ.

## 7. Xe trái/phải lấn làn
Side Guard của V14 chạy nền. Xe bên chỉ được đưa lên HUD khi:
- có vận tốc ngang hướng vào ego lane;
- TLC giảm đủ rõ;
- hoặc mép xe thực sự bắt đầu cắt biên lane.

Xe chạy song song bình thường không còn tạo cảnh báo chỉ vì ở gần.

## 8. Người đi bộ
V14 chỉ ưu tiên người khi:
- nằm trong lõi ego path;
- hoặc rất gần lõi ego path ở cự ly ngắn.

Người trên vỉa hè/lề/ngoài hành lang chính giữa không được phép chiếm HUD hay cảnh báo chính.

## 9. Cảnh báo giọng nói
Ưu tiên giọng Việt, TTS nữ miền Nam nếu engine Android có voice phù hợp. Mốc khoảng cách gồm 20 / 10 / 5 / 4 / 3 / 2 / 1 m. Cảnh báo nguy hiểm có quyền ngắt câu thông tin thấp ưu tiên.

## 10. BIỂN BÁO AI
Nút **BIỂN BÁO AI** là công tắc riêng. Khi TẮT, pipeline biển báo/OCR không chạy để giảm nhiệt. Khi BẬT, app ưu tiên biển tốc độ tối đa và biển khu dân cư.

## 11. Nhiệt máy
V14 tiếp tục dùng Thermal Guard NORMAL → BALANCED → HOT → VERY_HOT. Khi nóng hoặc xe đứng yên, app tự giảm nhịp inference/render nhưng vẫn ưu tiên lane + lead.

## 12. DEBUG ADAS
Nhấn giữ chip trạng thái phía trên để bật/tắt DEBUG. Debug hiển thị FPS, lane confidence, lead ID, range quality, TTC và thermal; không lưu video.

## 13. Bản quyền
- Cài mới: dùng thử 5 phút foreground.
- Hết trial: mở **BẢN QUYỀN / KEY**, gửi MÃ THIẾT BỊ cho Admin.
- V14 giữ cùng hệ key với V12/V13; app Admin hiện tại tiếp tục cấp key được.

## 14. Lưu ý sử dụng
V14 là công cụ hỗ trợ quan sát thử nghiệm bằng camera điện thoại, không phải ADAS/radar được chứng nhận và không điều khiển phanh/vô-lăng. Luôn ưu tiên quan sát thực tế, biển báo thật và điều kiện giao thông.
