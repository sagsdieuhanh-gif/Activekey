# HDSD TRUNGKIEN V15.6 — UFLD REFERENCE TEST

## Mục tiêu
Bản này dùng đúng model UFLD CULane FP32 hiện có nhưng sửa decoder theo logic reference của UFLD/PINTO để kiểm tra chất lượng model thực tế.

## Khác với bản trước
- UFLD là nguồn lane chính khi đã nhận đủ lane trái + lane phải.
- CV chỉ fallback khi UFLD chưa khóa được cặp lane.
- Decoder:
  - class 200 = NO LANE;
  - softmax chỉ trên 200 class vị trí;
  - vị trí lane tính theo expectation của grid 1..200;
  - dùng đúng 18 CULane row anchors;
  - lane được coi là có khi còn hơn 2 anchor point.

## Cách thử
1. Gắn điện thoại cố định.
2. Chạy ban ngày trước, đường thẳng có vạch rõ.
3. Sau đó thử vạch đứt, đường cong, bóng râm và ban đêm.
4. Nếu trạng thái hiển thị nguồn CORE/LANE CORE thì đang xem kết quả UFLD.
5. Nếu chuyển sang CV thì UFLD chưa khóa được cặp lane và app đang fallback.

## Điều cần quan sát
- Hai đường lane có bám đúng vạch sơn không.
- Lane có nhảy ngang giữa các frame không.
- Khi bị xe che một phần, lane có giữ ổn định không.
- Đường cong có bám theo cua hay cắt thẳng.
- Có nhận nhầm mép đường/vỉa hè thành lane không.

## Model
- UFLD CULane FP32
- Input: 1x3x288x800
- Output: 1x201x18x4
