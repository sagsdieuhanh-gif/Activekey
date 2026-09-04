# TrungKien ADAS V4.2.2

## Lead sanity gate
- Giới hạn khoảng cách tracking theo tốc độ.
- Lead càng xa càng cần confidence cao.
- Lead phải nằm gần predicted path.
- HUD/reticle chỉ vẽ verified lead.
- Technical mode hiện DROP_FAR / DROP_SIDE / DROP_PROB.

## Lane sanity
- Kiểm tra inner lane so với path ở 8-55 m.
- Mỗi bên cách path 0.9-2.35 m.
- Tổng width 2.5-4.25 m.
- Nếu SPC lane không hợp lý thì LDW fallback UFLD.
- Overlay clamp lane về corridor quanh plan khoảng 3.2 m để tránh divider/road edge bị vẽ như lane quá rộng.

## Motorcycle
Supercombo lead không phải generic object detector và không đảm bảo bắt tất cả xe máy/người/xe đạp.
V4.2.2 sửa false/far SPC lead nhưng chưa bổ sung detector vật thể gần.

versionCode 4220
versionName 4.2.2
