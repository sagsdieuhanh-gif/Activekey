# TrungKien ADAS V3.0 — AI LAB / Supercombo A-B Test

## Mục tiêu
V3 không mặc định kết luận Supercombo tốt hơn. Bản này cho người dùng tự A/B test trên cùng điện thoại/cùng cung đường.

## Ba preset
1. BASELINE V2.4
   - YOLOX ON
   - UFLD ON
   - Supercombo OFF
2. SUPERCOMBO
   - YOLOX ON
   - UFLD OFF
   - Supercombo ON
   - Supercombo Path/Lane ON
   - Supercombo Lead ON
   - Fusion Smart Lead ON
3. HYBRID
   - YOLOX + UFLD + Supercombo cùng hoạt động
   - Lane geometry được kết hợp khi cả hai nguồn hợp lệ
   - Smart Lead YOLOX được Supercombo lead-distance hint hỗ trợ

## Công tắc độc lập
- YOLOX-Tiny
- UFLD Lane
- Supercombo Engine
- Supercombo Path / Lane
- Supercombo Lead
- Fusion Smart Lead
- FCW + HMW
- LDW + TLC
- Thông tin kỹ thuật

## Supercombo
Model test: public Supercombo v0.8.10-compatible ONNX, 57,554,657 bytes.
I/O:
- input_imgs: 1x12x128x256
- desire: 1x8
- traffic_convention: 1x2
- initial_state: 1x512
- output: 6472 floats

V3 giữ recurrent state và dùng 2 temporal frames.
Supercombo chạy trên executor riêng, không chặn trực tiếp YOLOX/CameraX.

## Giới hạn quan trọng
Camera điện thoại không có đúng intrinsics/warp của comma device. V3 dùng preprocessing YUV temporal xấp xỉ để đánh giá thực tế trên điện thoại.
Vì vậy phải dùng kết quả test ngoài đường để quyết định có giữ Supercombo ở bản sau hay không.

## Cách đánh giá
Chạy cùng một cung đường ba lượt:
- lượt 1 BASELINE
- lượt 2 SUPERCOMBO
- lượt 3 HYBRID

Quan sát:
- độ ổn định lane trên đường cong
- lane khi bị xe che vạch
- road edge
- Smart Lead/handoff khi đổi làn
- số ms Supercombo
- nhiệt máy
- camera có giật/stall không

## License / cập nhật
- package giữ nguyên: com.trungkien.adas
- version 3.0.0 / code 3000
- dùng signing key ổn định V2.4
- giữ SharedPreferences license DG12 và device-code salt V22
- V3 cập nhật đè lên app TrungKien ADAS chính, không tạo device code mới nếu signing key V2.4 được giữ nguyên.

## An toàn
Đây là ADAS camera điện thoại thử nghiệm, không thay thế hệ thống ADAS/chức năng an toàn được chứng nhận trên xe.
