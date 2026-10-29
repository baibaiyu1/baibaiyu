ARG MINERU_BASE_IMAGE=pytorch/pytorch:2.8.0-cuda12.8-cudnn9-runtime
FROM ${MINERU_BASE_IMAGE}

WORKDIR /app
ENV PYTHONUNBUFFERED=1 PIP_DISABLE_PIP_VERSION_CHECK=1

RUN if ! dpkg-query -W ca-certificates libgl1 libglib2.0-0 libgomp1 >/dev/null 2>&1; then \
      apt-get update \
      && apt-get install -y --no-install-recommends ca-certificates libgl1 libglib2.0-0 libgomp1; \
    fi \
    && rm -rf /var/lib/apt/lists/*

# Resolve dependencies separately so source edits do not redownload the OCR
# stack. Keep the CUDA-enabled torch/torchvision from the selected base image.
COPY services/mineru/pyproject.toml /tmp/mineru-pyproject.toml
RUN python -c "import tomllib; from pathlib import Path; p=tomllib.loads(Path('/tmp/mineru-pyproject.toml').read_text()); Path('/tmp/mineru-requirements.txt').write_text('\n'.join(p['project']['dependencies'] + p['project']['optional-dependencies']['pipeline']))" \
    && python -m pip install --no-cache-dir -r /tmp/mineru-requirements.txt \
    && python -m pip check

# Install this repository's package without fetching another MinerU distribution.
COPY services/mineru/ /app/mineru-src/
RUN python -m pip install --no-cache-dir --no-deps --no-build-isolation /app/mineru-src \
    && python -m pip check \
    && python -c "import torch, torchvision; from mineru.cli.fast_api import app; assert torch.version.cuda; assert any(r.path == '/tasks' for r in app.routes)"

COPY docker/mineru/mineru.json docker/mineru/entrypoint.py /app/
RUN mkdir -p /app/output /app/cache

EXPOSE 8000
ENV MINERU_API_ENABLE_FASTAPI_DOCS=0
ENTRYPOINT ["python", "/app/entrypoint.py"]
CMD ["mineru-api", "--host", "0.0.0.0", "--port", "8000"]
