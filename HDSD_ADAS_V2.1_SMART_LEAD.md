# TRUNGKIEN ADAS V2.1 — SMART LEAD TRACKING

## Mục tiêu
V2.1 tập trung làm chính xác `XE PHÍA TRƯỚC` và chuyển mục tiêu mượt khi xe mình hoặc xe trước chuyển làn.

## 1. Tracking ID mạnh hơn
Association không còn chỉ dựa IoU:
- IoU box;
- tâm box dự đoán theo vận tốc X/Y;
- tỷ lệ diện tích box;
- family class;
- bonus cho current lead.

CAR / BUS / TRUCK vẫn được phép giữ cùng track khi AI đổi class ngắn hạn.

Track có thể sống tối đa 5 missed-frame để tái bắt ID, nhưng track stale không được dùng để kích hoạt FCW.

## 2. Lead acquire / lead hold
Mỗi xe có vị trí tương đối so với ego lane:
- `ACQUIRE <= 0.92` lane-half;
- current lead được `HOLD <= 1.18` lane-half.

HOLD rộng hơn ACQUIRE để lane rung 1 frame không làm đổi lead liên tục.

## 3. Lead handoff khi xe trước chuyển làn
Nếu current lead:
- ra khỏi ego lane liên tiếp;
- trong khi có xe khác ổn định nằm trong ego lane;

thì hệ thống chuyển sang xe mới.

Lead cũ không bị xóa track. Nó tiếp tục là phương tiện bình thường, chỉ bỏ cờ LEAD.

## 4. Xe mình chuyển làn
Khi UFLD ego-lane dịch sang làn mới:
- lead cũ dần ra vùng HOLD;
- xe trước của làn mới vào vùng ACQUIRE;
- sau persistence, `LANE_HANDOFF` chuyển lead.

Do đó hệ thống không cố bám xe của làn cũ.

## 5. Xe cắt vào
Một vehicle mới trong ego lane được chuyển thành lead khi:
- gần hơn current lead rõ rệt;
- tồn tại đủ 2 frame.

Điều kiện gần hơn:
- distance < 88% distance current lead;
hoặc
- gần hơn ít nhất khoảng 2 m.

## 6. Chống nhảy lead
- current lead tốt -> giữ nguyên ID;
- candidate mới phải persistence 2 frame;
- minimum hold khoảng 650 ms;
- current lead ra hẳn lane -> handoff nhanh hơn;
- lane mất -> fallback corridor trung tâm.

## 7. Risk reset khi đổi lead
FCW/HMW của lead cũ không được mang sang lead mới.
Khi switch target:
- reset FCW evidence;
- reset HMW evidence;
- reset baseline đèn đỏ.

Lead mới phải tự xây dựng TTC/closing speed của chính nó.

## 8. DEBUG
Debug text có:
- `lead=#ID`
- `cand=#ID/frames`
- `out=frames`
- `switch=ACQUIRE / CUT_IN / LANE_HANDOFF / LANE_EXIT / ...`

Dùng để kiểm tra thực tế lúc đổi làn.

## Các chức năng V2.0 vẫn giữ
- YOLOX-Tiny vehicle detection;
- UFLD lane;
- auto calibration;
- hood mask;
- distance;
- relative closing speed;
- TTC / FCW;
- HMW;
- Lead Start + Google TTS;
- LDW / TLC;
- DRIVE / DEBUG.

## Phiên bản
- Version: 2.1.0
- Application ID: `com.trungkien.adas.v21smartlead`
- Cài song song các V1.x / V2.0.x.
- Icon: `V2.1 LEAD`.
