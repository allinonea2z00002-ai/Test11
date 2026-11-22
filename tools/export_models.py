# Exports the three models to INT8 ONNX and writes model_info.json (label index + normalisation).
# Needs internet. Run from the repo root:  python tools/export_models.py
import json, os, shutil, subprocess, sys

# CI-only repair, BEFORE importing torch: the workflow installs torch from the
# CPU wheel index, but timm pulls torchvision from PyPI, and the two can
# mismatch (RuntimeError: operator torchvision::nms does not exist). Installing
# both from the CPU index lets pip pick a matched pair. A no-op (seconds) when
# the pair already matches. Skipped for local runs so a local CUDA torch is
# never replaced.
if os.environ.get("CI"):
    subprocess.run([sys.executable, "-m", "pip", "install", "torch", "torchvision",
                    "--index-url", "https://download.pytorch.org/whl/cpu"], check=True)

import torch, timm, nudenet
from onnxruntime.quantization import quantize_dynamic, QuantType

OUT = "assets_out"
os.makedirs(OUT, exist_ok=True)
info = {}

# 1) Falconsai (ViT-base, 224px)
subprocess.run(["optimum-cli", "export", "onnx", "--model", "Falconsai/nsfw_image_detection",
                "--task", "image-classification", "falconsai_tmp/"], check=True)
quantize_dynamic("falconsai_tmp/model.onnx", f"{OUT}/falconsai_int8.onnx", weight_type=QuantType.QInt8)
cfg = json.load(open("falconsai_tmp/config.json"))
idx = [int(k) for k, v in cfg["id2label"].items() if v.lower() == "nsfw"][0]
try:
    pp = json.load(open("falconsai_tmp/preprocessor_config.json"))
    mean, std = pp["image_mean"], pp["image_std"]
except Exception:
    mean, std = [0.5] * 3, [0.5] * 3
info["falconsai"] = {"nsfw_index": idx, "mean": mean, "std": std}

# 2) Marqo (timm ViT-tiny, 384px)
m = timm.create_model("hf_hub:Marqo/nsfw-image-detection-384", pretrained=True).eval()
names = m.pretrained_cfg["label_names"]
midx = [i for i, n in enumerate(names) if n.upper() == "NSFW"][0]
info["marqo"] = {"nsfw_index": midx, "mean": list(m.pretrained_cfg["mean"]), "std": list(m.pretrained_cfg["std"])}
torch.onnx.export(m, torch.randn(1, 3, 384, 384), "marqo_tmp.onnx",
                  input_names=["pixel_values"], output_names=["logits"], opset_version=17)
quantize_dynamic("marqo_tmp.onnx", f"{OUT}/marqo_int8.onnx", weight_type=QuantType.QInt8)

# 3) NudeNet v3 (320n), bundled inside the pip package
shutil.copy(os.path.join(os.path.dirname(nudenet.__file__), "320n.onnx"), f"{OUT}/nudenet.onnx")

json.dump(info, open(f"{OUT}/model_info.json", "w"), indent=2)
print(json.dumps(info, indent=2))
print("Done. Copy assets_out/* into app/src/main/assets/")
