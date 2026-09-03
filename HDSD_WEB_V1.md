# TRUNGKIEN WEB V1 — HDSD

## Mục tiêu
Bản thử lane chạy trực tiếp trên Safari iPhone, không cần Mac/Xcode/IPA/Apple Developer.

## Mô hình
- UFLD CULane FP32
- Input: `1x3x288x800`
- Output: `1x201x18x4`
- Inference: ONNX Runtime Web 1.29.0, WASM
- Model được GitHub Actions tải lúc deploy; không commit model 178 MB vào repo.

## Cách dùng trên iPhone
1. Mở URL GitHub Pages bằng Safari.
2. Bấm **BẮT ĐẦU**.
3. Cho phép Camera.
4. Chờ model tải lần đầu.
5. Đặt xe ở giữa làn và bấm **CÂN GIỮA**.
6. Nếu lane bắt quá cao/thấp, bấm **CHỈNH** và kéo **Vùng đường**.
7. Có thể Safari → Share → **Add to Home Screen**.

## Lưu ý
Đây là bản thử nghiệm, không dùng thay hệ thống ADAS an toàn của xe. Không thao tác điện thoại khi đang lái xe.
