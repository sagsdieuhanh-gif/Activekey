# V12 ADMIN LICENSE SETUP

APK/source build không cần private signing key. Ứng dụng chỉ chứa public verification key.

Private signing key được giao trong gói riêng `TRUNGKIEN_V12_ADMIN_LICENSE_TOOL_PRIVATE.zip` để admin giữ tách khỏi app/source phát hành.

Quy trình:
1. Người dùng mở `CÀI ĐẶT -> BẢN QUYỀN / KEY` và gửi MÃ THIẾT BỊ.
2. Admin giải nén gói private tool trên máy riêng.
3. Chạy `python generate_license.py <MÃ-THIẾT-BỊ>`.
4. Gửi lại duy nhất chuỗi key.

Không commit/upload private key lên GitHub và không copy nó vào `app/src/main`/APK.
