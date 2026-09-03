# TRUNGKIEN CLEAN V1.4 — AUTO CAL + HOOD + LEAD MOVE

## Cài song song
Application ID:
`com.trungkien.cleanvehicle.v14auto`

Icon:
`V1.4 AUTO`

## Auto camera calibration
UFLD ego lane index 1/2 được fit theo x(y).
Giao điểm 2 lane cho horizon/vanishing point.
Lane center phía dưới cho tâm camera.
Roll được ước lượng chậm và giới hạn ±8°.

Khi đủ 12 mẫu lane hợp lệ, debug hiện AUTO LOCK.

## Hood / đầu xe
V1.4 dùng vùng đáy ảnh bảo thủ, khoảng 84–92% chiều cao tùy roll.
Detection nhỏ nằm gần như toàn bộ trong vùng này bị loại để tránh nhận chính đầu xe.
Đường cam ngang trên màn hình là ranh giới hood mask.

## Xe phía trước di chuyển khi dừng đèn đỏ
Chỉ ARM khi:
- GPS <= 3 km/h;
- cùng một track xe phía trước tồn tại ít nhất 2 giây.

Cảnh báo khi:
- khoảng cách tăng ít nhất ~1.2 m;
- đồng thời box nhỏ đi hoặc đáy box nhích lên;
- điều kiện tồn tại 2 frame liên tiếp.

Khi kích hoạt:
- hiện `XE PHÍA TRƯỚC ĐÃ DI CHUYỂN`;
- phát 2 tiếng `bíp-bíp` sắc, một lần;
- không lặp lại cho đến khi xe mình di chuyển và chu kỳ dừng mới bắt đầu.

## TTC beep
Mức nguy hiểm nhất dùng `TONE_PROP_BEEP2`, sắc hơn và dồn ~0.21 giây/tít.

## Lưu ý
Auto-calibration, distance, TTC và lead-move đều là thử nghiệm camera đơn mắt.
Không thay thế ADAS/FCW sản xuất của xe.
