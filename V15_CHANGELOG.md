# TRUNGKIEN V15.0.0

- Replaces legacy Lane Core with dedicated Ultra-Fast-Lane-Detection (CULane) ONNX lane model.
- UFLD receives the full camera road view at 800x288; no synthetic horizon is fed to the model.
- CV near-road lane paint remains as cross-check/fallback via existing hybrid fusion.
- Road Core lead detection stays independent from lane lock; CENTER fallback remains active.
- Voice/TTS is now FRONT DISTANCE ONLY. Pedestrian, lane, cut-in, traffic-sign, speed-limit, thermal and safe-gap voice prompts are disabled.
- Visual warnings remain available on screen.
- Version 15.0.0 / code 1500 / API 36.
