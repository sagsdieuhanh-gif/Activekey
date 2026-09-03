# TRUNGKIEN V15.7C — PicoDet official preprocessing fix

Sửa lỗi V15.7B không nhận diện:

- Dùng đúng normalization trong PaddleDetection `demo_onnxruntime/infer_demo.py`.
- RGB mean: 103.53 / 116.28 / 123.675.
- RGB std: 57.375 / 57.12 / 58.395.
- `im_shape = [416,416]`.
- `scale_factor = [416/originalHeight, 416/originalWidth]`.
- Output boxes được đọc theo tọa độ crop gốc thay vì chia cố định cho 416.
- Vẫn giữ XNNPACK, không NNAPI.
