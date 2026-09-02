# TRUNGKIEN V14.1.0 — NIGHT / CENTER FIX

## Lý do phát hành
Ảnh test ban đêm cho thấy hai lỗi liên quan:
1. Lane có vạch rõ dưới đèn pha nhưng vẫn ở `ĐANG TÌM`.
2. Xe ô tô nằm chính giữa phía trước vẫn không được nhận/khóa lead khi lane chưa ổn định.

## Thay đổi
- NIGHT AUTO: threshold sáng tăng và bổ sung dark-pixel ratio.
- Road Core low-light gamma: 0.68.
- Front-focus crop: 21–79% chiều ngang, 10–84% chiều cao.
- Night/full Road Core threshold hạ có kiểm soát cho four-wheel.
- Center-focus/night four-wheel floor thấp hơn; side classes vẫn chặt hơn.
- Night temporal tracker: central four-wheel xác nhận 2–3 hits; association gate được nới nhẹ để chống bbox jitter.
- LaneDetector:
  - tự xác định thiếu sáng từ tensor;
  - scan 50–96.5% với stride 2;
  - local reflective-paint contrast;
  - adaptive white/yellow thresholds;
  - one-side lane acquisition thấp hơn nhưng chỉ cảnh báo lệch làn khi confidence đủ cao.
- LaneHybridFusion:
  - CV night usable ở confidence thấp hơn;
  - ưu tiên near-road reflective lane khi bất đồng mạnh với Lane Core;
  - giữ lane yếu ban đêm lâu hơn một chút nhưng hạ confidence.
- TargetSelector:
  - thêm `nightMode`;
  - center fallback cho four-wheel khi lane chưa reliable;
  - lead lock ban đêm nhanh hơn với target trung tâm ổn định.
- UI lane: thêm `NIGHT` / `NIGHT CENTER`.
- Traffic sign: thêm nhãn `ĐÃ NHỚ` khi rule còn hiệu lực nhưng observation trực tiếp đã stale.
- Version: 14.1.0 / code 1410.
- Artifact: `TRUNGKIEN-V14.1-debug-apk`.

## Không thay đổi
- License/key vẫn tương thích V12/V13/V14.
- Admin private key không nằm trong app/source user.
- Hood exclusion, TTS, Thermal Guard, long-range conservative uncertainty và Side Guard được giữ.
