"""Require the configured local models and a working CUDA device at startup."""

import json
import os
from pathlib import Path
import sys

import torch


def main() -> None:
    if not torch.cuda.is_available():
        raise SystemExit(
            "MinerU requires a visible NVIDIA GPU. Check the host driver, "
            "NVIDIA Container Toolkit and the Compose GPU reservation."
        )

    config_path = Path(os.environ.get("MINERU_TOOLS_CONFIG_JSON", "/app/mineru.json"))
    config = json.loads(config_path.read_text(encoding="utf-8"))
    model_root = Path(config["models-dir"]["pipeline"])
    required_dirs = ("Layout", "MFR", "OCR", "TabCls", "TabRec")
    missing = [name for name in required_dirs if not (model_root / "models" / name).is_dir()]
    if missing:
        raise SystemExit(
            "Incomplete MinerU model mount: missing "
            + ", ".join(missing)
            + ". MINERU_MODELS_DIR must contain the pipeline snapshot's models/ directory."
        )

    print(f"MinerU CUDA device: {torch.cuda.get_device_name(0)}", flush=True)
    if len(sys.argv) < 2:
        raise SystemExit("A MinerU API command is required.")
    os.execvp(sys.argv[1], sys.argv[1:])


if __name__ == "__main__":
    main()
