# Models used by SafeVision

All inference runs on-device with ONNX Runtime. Models are downloaded and converted at build time by `tools/export_models.py` (see `.github/workflows/build.yml`). Check each model's current license before any public release.

| Role | Model | Format | Input | Notes |
|---|---|---|---|---|
| Main image / browser classifier | Falconsai/nsfw_image_detection (ViT-base) | INT8 ONNX | 224x224 | Two classes: normal / nsfw. License reported as Apache-2.0; verify on the model page. |
| Video frame classifier | Marqo/nsfw-image-detection-384 (timm ViT-tiny) | INT8 ONNX | 384x384 | Two classes: NSFW / SFW. License reported as Apache-2.0; verify. |
| Second-stage detector (borderline scores only) | NudeNet v3, 320n | ONNX | 320x320 | Detects exposed body-part classes. License may be AGPL-3.0: review before publishing the app. |

Website blocklist: StevenBlack/hosts, `alternates/porn-only` (MIT), downloaded at build time. The date of the download is the date of the build.

## Categories
The models cover adult/explicit visual content only. Graphic violence and disturbing imagery are NOT detected yet: no verified on-device model for them is bundled. Add one before claiming that coverage.

## Decision logic
- Images: Falconsai score. If between the suspicious and inappropriate thresholds, NudeNet runs too and the higher score is used.
- Video frames: Marqo score. If borderline, Falconsai runs too.
- Defaults: suspicious 0.30, inappropriate 0.70. The sensitivity slider moves both.

## Limitations
- No detector is 100% accurate. Expect false positives (art, medical, beach photos) and false negatives (cartoons, unusual images, heavy filters).
- Video is sampled every 500 ms; content between samples can be missed.
- Web: only images are inspected. Video streams, embedded players, CSS/canvas content and SVG are not.
- Android does not allow a normal app to filter other apps or the whole phone.
