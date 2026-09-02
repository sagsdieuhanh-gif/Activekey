# HƯỚNG DẪN — TRUNGKIEN V15.1

## Chức năng
V15.1 chỉ tập trung vào **LANE** và **XE PHÍA TRƯỚC / KHOẢNG CÁCH**.

## Gắn camera
Gắn điện thoại chắc chắn, nhìn thẳng theo hướng xe. Dùng **AUTO GÓC** sau khi đổi vị trí gắn. Không cần căn đường chân trời thủ công.

## Lane
Lane phải lặp ổn định qua nhiều frame mới được vẽ. Lane mới nhảy xa không được thay ngay lane đang có. Khi không đủ bằng chứng, app không vẽ lane.

## Xe phía trước
Chỉ target chính giữa/ego path được hiển thị và dùng cho khoảng cách.

## Giọng nói
Chỉ có 2 loại:
- khoảng cách xe phía trước ở các mốc 50, 30, 20, 10, 5, 4, 3, 2, 1 m;
- **“Xe phía trước di chuyển.”**

Move-off không đọc đèn tín hiệu. Nó hoạt động bằng điều kiện: GPS xác nhận xe mình gần như đứng yên, cùng một xe phía trước ổn định ít nhất 3,5 giây, rồi khoảng cách tới xe đó tăng liên tục qua nhiều frame trong khi xe mình vẫn đứng. Vì vậy cũng có thể hoạt động khi dừng trong ùn xe, không chỉ riêng đèn đỏ.

## Đã bỏ
Biển báo/OCR, người đi bộ, xe bên/cut-in và toàn bộ cảnh báo giọng nói khác.
