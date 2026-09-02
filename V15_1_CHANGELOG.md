# TRUNGKIEN V15.1.0 — FOCUSED LANE + FRONT DISTANCE

- Chỉ giữ 2 chức năng nghiệp vụ chính: lane và xe/khoảng cách phía trước.
- Bỏ hoàn toàn đọc biển báo/OCR khỏi runtime và giao diện.
- Bỏ pedestrian/cut-in/side warning khỏi runtime và overlay.
- LaneStabilityGate: nhiều-frame confirm, chống nhảy lane, GPS road-context gate, không chắc thì không vẽ.
- TTS chỉ còn: mốc khoảng cách xe phía trước và câu “Xe phía trước di chuyển.”
- Move-off chỉ kích hoạt khi GPS xác nhận xe mình dừng, cùng lead ổn định >=3.5 s, sau đó khoảng cách tăng có xác nhận nhiều frame.
