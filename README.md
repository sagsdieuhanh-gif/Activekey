# TRUNGKIEN V15.1.0

Bản tập trung tối đa cho camera điện thoại:
1. Lane ổn định.
2. Nhận xe phía trước + đo khoảng cách.

Ngoại lệ giọng nói: khi xe mình đã dừng sau một xe phía trước ổn định và xe đó bắt đầu đi, app nói **“Xe phía trước di chuyển.”**

Không còn đọc biển báo/OCR, pedestrian, cut-in, side warning hay TTS khác. TTS khoảng cách chỉ đọc mốc 50/30/20/10/5/4/3/2/1 m.

Lane model vẫn là dedicated UFLD CULane, nhưng V15.1 thêm lớp LaneStabilityGate để không cho output model nhảy trực tiếp lên UI. Nếu không đủ bằng chứng, app hiển thị không có lane thay vì dựng lane giả.
