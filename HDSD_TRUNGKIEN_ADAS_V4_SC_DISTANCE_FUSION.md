# TrungKien ADAS V4.0 — Supercombo Distance Fusion

V4: YOLOX dùng để nhận diện/Track ID; Supercombo ưu tiên khoảng cách lead; YOLO geometry làm fallback/đối chiếu.

- V4 AUTO mặc định: SC khoảng 80–95% khi confidence >=55%, dữ liệu 2–180 m và còn mới.
- SC PRIMARY: dùng trực tiếp SC khi hợp lệ, YOLO fallback.
- YOLO CHECK: dùng YOLO geometry để đối chiếu.
- TTC/HMW/FCW dùng khoảng cách OUT của V4.
- Closing speed tính từ chuỗi khoảng cách OUT, chưa dùng trực tiếp velocity output của SC.
- Technical info hiện YOLO DIST / SC DIST + confidence / OUT / SOURCE / TTC / inference.

Package com.trungkien.adas, signing stable, DG12 và DEVICE_CODE_SALT V22 giữ nguyên.
VersionCode 4000, versionName 4.0.0.
