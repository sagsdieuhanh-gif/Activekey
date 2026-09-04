# TrungKien ADAS V4.2.1

- YOLO vẫn bị loại; Supercombo là lõi.
- Không thermal-skip SPC nữa vì làm giãn temporal pair x2.
- Khi nóng chỉ giảm UFLD helper; dùng màn hình đen để giảm nhiệt.
- Lead lock: acquire >=28%, keep >=12%, 4 misses, hold 2.2s.
- Lane/path projection neo 3 m xuống y=0.94 để bám mặt đường.
- Marker lead: reticle 4 góc màu cam + SPC distance/confidence.
- SPC lane <0.42 thì LDW fallback UFLD.
- Virtual warp giảm cường độ để giữ hành vi lead gần bản SPC cũ.
- versionCode 4210 / versionName 4.2.1.
