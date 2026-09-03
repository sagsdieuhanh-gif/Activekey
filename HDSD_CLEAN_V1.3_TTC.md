# TRUNGKIEN CLEAN V1.3 — TTC BEEP

- Application ID riêng: `com.trungkien.cleanvehicle.v13ttc`
- Icon riêng: `V1.3 / TTC`
- Cài song song với V1.0, V1.1, V1.2.

## TTC
TTC dùng tốc độ đóng khoảng cách:
`closing = (distance_previous - distance_current) / dt`

Sau EMA smoothing:
`TTC = distance / closing_speed`

Không dùng tốc độ GPS thay cho closing speed.

## GPS
GPS dùng để:
- hiển thị tốc độ xe;
- tắt cảnh báo khi xe gần như đứng yên (<5 km/h).

## Beep
- TTC > 6 s: im lặng
- 4–6 s: khoảng 1.1 s/tít, tone level 45
- 2.8–4 s: khoảng 0.7 s/tít, tone level 62
- 1.8–2.8 s: khoảng 0.39 s/tít, tone level 82
- <=1.8 s: khoảng 0.21 s/tít, tone level 100

Media Volume của điện thoại vẫn quyết định âm lượng cuối cùng.

## Lưu ý
Khoảng cách và TTC đều là ước lượng camera đơn mắt, chưa hiệu chuẩn.
Không dùng thay thế ADAS/FCW chính thức của xe.
