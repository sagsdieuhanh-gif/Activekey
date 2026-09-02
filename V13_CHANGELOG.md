# TRUNGKIEN V13.2.0 FULL ADAS

V13.2 gom toàn bộ các hạng mục V13 đã chốt vào một build line thay vì tiếp tục chia P2/P3.

## ADAS / lane
- Hành lang ego-lane xanh ngọc theo đường cong, center chevrons bám theo tâm làn.
- Lane Core + CV fusion, temporal smoothing và hold ngắn qua vạch đứt/khung hình yếu.
- Lane yếu/ước lượng hiển thị nét đứt và giảm opacity; không giả vờ LOCKED khi confidence thấp.
- Không hiển thị đường chân trời; horizon chỉ còn là hình học nội bộ nếu cần.
- User mode bỏ source/confidence kỹ thuật; long-press chip trạng thái mới bật DEBUG ADAS.

## Lead / tracking
- Lead-first selection theo lane overlap + center alignment + class priority + hysteresis.
- Xe máy/xe đạp lane bên không được cướp lead chỉ vì gần/confidence cao.
- Two-wheel chỉ handover sớm khi thực sự cut-in sâu và gần hơn đáng kể.
- New lead phải tồn tại nhiều detector pass; long-range >=60 m cần confirmation mạnh hơn.
- Tracker V13.2 giữ Track ID qua glare/occlusion ngắn bằng prediction + adaptive association gate.
- Mature tracks được reacquire mềm hơn; weak/new false positives vẫn cần nhiều hits.

## Khoảng cách / 100 m
- Near / mid / far / long-range confidence bands nội bộ.
- Central long-range ROI 60–100 m chạy xen kẽ full-frame khi tốc độ đủ cao.
- Range fusion pinhole + metric Lane Core cross-check, robust temporal median và DistanceTracker.
- >=60 m luôn dùng sai số bảo thủ; >=85 m không quảng bá HIGH precision.
- UI >=60 m làm tròn theo 5 m và dùng dấu ~ khi thích hợp.
- Following-distance advisor dùng lower confidence bound, không xác nhận SAFE chỉ từ point estimate.

## Cut-in / side risk
- Theo dõi trái/phải theo Track ID và lane-relative motion.
- TLC dựa trên mép xe gần vạch lane thay vì đợi tâm xe cắt vạch.
- NORMAL -> WATCH -> CUT_IN_PREDICTED -> CUT_IN_IMMINENT.
- UI chỉ nổi bật tối đa threat mạnh nhất mỗi bên.

## Risk / TTS
- RiskStabilizer chống spike TTC/range một frame; COLLISION vẫn immediate.
- Startup TTS warm-up 2.8 s để tránh app vừa mở đã đọc sai mốc.
- TTS có global queue guard: thông tin thấp không xếp chồng; nguy cơ cao được quyền QUEUE_FLUSH.
- Khoảng cách milestone: 20 / 10 / 5 / 4 / 3 / 2 / 1 m.
- Giữ ưu tiên: collision > pedestrian > cut-in > following gap > lane > traffic sign.

## Biển báo
- Nút BIỂN BÁO AI vẫn tắt hoàn toàn pipeline khi OFF.
- P.127 tốc độ tối đa + R.420/R.421 khu đông dân cư theo consensus nhiều frame.
- Rule đã xác nhận được giữ sau khi biển ra khỏi camera nhưng có TTL session để tránh rule cũ tồn tại vô hạn.

## Thermal / performance
- 4 mode: NORMAL / BALANCED / HOT / VERY_HOT.
- Kết hợp Android thermal status + nhiệt độ pin.
- Khi đứng/chạy rất chậm và không có nguy cơ: tự hạ inference rate.
- Khi nguy cơ xuất hiện: lead/lane được ưu tiên tăng nhịp, sign reader vẫn bị throttle.
- Long-range ROI vẫn cho phép ở BALANCED nhưng giảm cadence.
- Road Core preprocessor tái sử dụng tensor ~4.9 MB thay vì copy mỗi inference, giảm allocation/GC/nhiệt.
- HOT/VERY_HOT tự hạ độ sáng; user vẫn có chế độ màn hình tiết kiệm riêng.

## Debug / privacy
- Long-press chip trạng thái để bật/tắt DEBUG ADAS.
- Debug overlay: FPS, lane lock/confidence, lead ID, range band/quality, TTC, risk, thermal.
- Debug CSV 1 Hz chỉ lưu app-private; không lưu video/frame; rotate ~1 MiB.
- Camera/AI vẫn on-device, không upload frame.

## Hood exclusion / calibration
- Vùng bỏ đầu xe chỉnh tay trực tiếp; không cố định 20%.
- False positive nằm hoàn toàn trong vùng hood không được sống sót chỉ vì từng bị lock.
- Auto góc / auto sai số / reset giữ nguyên.

## License / compatibility
- 5 phút trial foreground + key admin theo device code.
- V13 tiếp tục dùng cặp public/private key đã chốt của V12 để app Admin cấp key dùng chung.
- APK chỉ chứa public key; private key không có trong source/GitHub/APK.
- API build: Android 36 / Java 17 / Gradle 9.5.0.
- versionCode 1320 / versionName 13.2.0.
