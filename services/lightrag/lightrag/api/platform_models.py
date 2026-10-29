"""Persist platform model selections and apply them before server/index initialization.

Model changes use a full restart, including LLM-only changes: every worker must
use the same configuration. Embedding identity changes additionally require an
offline vector rebuild. See docs/PlatformModels.md.
"""

from __future__ import annotations

import hashlib
import hmac
import json
import os
import tempfile
from contextlib import contextmanager
from pathlib import Path
from typing import Literal
from urllib.parse import urlsplit

from fastapi import APIRouter, HTTPException, Request
from pydantic import BaseModel, ConfigDict, Field, SecretStr, field_validator

from lightrag.llm_roles import ROLE_NAMES


class ModelSpec(BaseModel):
    model_config = ConfigDict(extra="forbid")
    binding: Literal["openai", "ollama", "gemini", "cohere", "aliyun"]
    model: str = Field(min_length=1, max_length=256)
    host: str = Field(min_length=1, max_length=2048)
    api_key: SecretStr = SecretStr("")
    dimension: int | None = Field(default=None, ge=1, le=65536)
    send_dimensions: bool = False
    provider_options: dict = Field(default_factory=dict)

    @field_validator("host")
    @classmethod
    def validate_host(cls, value: str) -> str:
        uri = urlsplit(value)
        if (
            uri.scheme not in {"http", "https"}
            or not uri.hostname
            or uri.username
            or uri.password
            or uri.query
            or uri.fragment
        ):
            raise ValueError("Invalid model endpoint")
        return value.rstrip("/")


class PlatformModels(BaseModel):
    model_config = ConfigDict(extra="forbid")
    owner: str = Field(pattern=r"^[0-9]+:[0-9]+$", max_length=100)
    llm: ModelSpec | None = None
    roles: dict[str, ModelSpec] = Field(default_factory=dict)
    embedding: ModelSpec | None = None
    rerank: ModelSpec | None = None

    @field_validator("roles")
    @classmethod
    def validate_roles(cls, roles: dict[str, ModelSpec]) -> dict[str, ModelSpec]:
        if set(roles) - ROLE_NAMES:
            raise ValueError("Unknown model role")
        return roles


def config_path(args) -> Path:
    workspace = getattr(args, "workspace", "") or "default"
    if any(
        char not in "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_"
        for char in workspace
    ):
        raise ValueError("Invalid workspace")
    return Path(args.working_dir) / f"platform_models_{workspace}.json"


def _plain(config: PlatformModels) -> dict:
    result = config.model_dump(mode="json")
    for key in ("llm", "embedding", "rerank"):
        if result[key] is not None:
            result[key]["api_key"] = getattr(config, key).api_key.get_secret_value()
    for role, spec in config.roles.items():
        result["roles"][role]["api_key"] = spec.api_key.get_secret_value()
    return result


def _validated(data: dict) -> dict:
    config = PlatformModels.model_validate(data)
    for spec in [config.llm, *config.roles.values()]:
        if spec and spec.binding not in {"openai", "ollama", "gemini"}:
            raise ValueError("Invalid LLM binding")
    if config.embedding and (
        config.embedding.binding not in {"openai", "ollama"}
        or config.embedding.dimension is None
    ):
        raise ValueError("Embedding requires a compatible binding and dimension")
    if config.rerank and config.rerank.binding not in {"cohere", "aliyun"}:
        raise ValueError("Invalid reranker binding")
    result = _plain(config)
    for spec in [
        result["llm"],
        *result["roles"].values(),
        result["embedding"],
        result["rerank"],
    ]:
        if spec:
            if not spec["model"].strip():
                raise ValueError("Empty model name")
            # Provider bindings may fall back to process-global keys on an empty
            # value. An explicitly keyless platform model must not inherit them.
            if spec["binding"] != "ollama" and not spec["api_key"]:
                spec["api_key"] = "platform-keyless-endpoint"
    return result


def read_config(args) -> dict | None:
    path = config_path(args)
    if not path.exists():
        return None
    try:
        return _validated(json.loads(path.read_text(encoding="utf-8")))
    except (ValueError, TypeError, OSError):
        raise ValueError("Invalid persisted platform model configuration") from None


def _revision(config: dict | None) -> str:
    return hashlib.sha256(json.dumps(config, sort_keys=True).encode()).hexdigest()


def _embedding_identity(spec: dict) -> dict:
    return {
        key: spec.get(key)
        for key in ("binding", "model", "host", "dimension", "send_dimensions")
    }


def embedding_storage_model_name(args, model_name: str | None) -> str | None:
    """Keep endpoint changes out of the old vector space, including offline rebuilds."""
    config = getattr(args, "platform_models_active", None)
    spec = config.get("embedding") if config else None
    if spec is None:
        return model_name
    identity = _revision(_embedding_identity(spec))[:16]
    return f"{spec['model']}@platform-{identity}"


def _embedding_state(args, config: dict | None) -> dict:
    spec = config.get("embedding") if config else None
    if spec is not None:
        return {"managed": True, **_embedding_identity(spec)}
    defaults = getattr(args, "_platform_embedding_defaults", None)
    if defaults is None:
        defaults = {
            "binding": getattr(args, "embedding_binding", None),
            "model": getattr(args, "embedding_model", None),
            "host": getattr(args, "embedding_binding_host", None),
            "dimension": getattr(args, "embedding_dim", None),
            "send_dimensions": getattr(args, "embedding_send_dim", False),
        }
    return {"managed": False, **defaults}


@contextmanager
def _configuration_lock(path: Path):
    """Keep the lock inode stable; OS locks are released even after a crash."""
    fd = os.open(path.with_suffix(".lock"), os.O_CREAT | os.O_RDWR, 0o600)
    locked = False
    try:
        if os.name == "nt":
            import msvcrt

            if os.fstat(fd).st_size == 0:
                os.write(fd, b"\0")
            os.lseek(fd, 0, os.SEEK_SET)
            try:
                msvcrt.locking(fd, msvcrt.LK_NBLCK, 1)
            except OSError:
                raise HTTPException(
                    409, "Configuration update already in progress"
                ) from None
        else:
            import fcntl

            try:
                fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError:
                raise HTTPException(
                    409, "Configuration update already in progress"
                ) from None
        locked = True
        yield
    finally:
        if locked:
            if os.name == "nt":
                os.lseek(fd, 0, os.SEEK_SET)
                msvcrt.locking(fd, msvcrt.LK_UNLCK, 1)
            else:
                fcntl.flock(fd, fcntl.LOCK_UN)
        os.close(fd)


def apply_platform_models(args) -> None:
    """Apply the same persisted selection to the server and offline rebuild tool."""
    if not hasattr(args, "working_dir"):
        return
    if getattr(args, "_platform_models_applied", False):
        return
    args._platform_embedding_defaults = _embedding_identity(
        _embedding_state(args, None)
    )
    config = read_config(args)
    args.platform_models_active = config
    if config is None:
        return
    base = config["llm"]
    if base:
        # A text default cannot silently become the VLM fallback. Preserve the
        # deployment's effective VLM endpoint before replacing the base LLM.
        if "vlm" not in config["roles"]:
            for attr in ("binding", "model", "binding_host", "binding_api_key"):
                current = getattr(args, f"vlm_llm_{attr}", None)
                if current is None:
                    setattr(args, f"vlm_llm_{attr}", getattr(args, f"llm_{attr}", None))
            if getattr(args, "vlm_llm_model", None):
                args._platform_vlm_fallback = {
                    "binding": args.vlm_llm_binding,
                    "model": args.vlm_llm_model,
                    "host": args.vlm_llm_binding_host,
                    "api_key": args.vlm_llm_binding_api_key,
                }
        args.llm_binding = base["binding"]
        args.llm_model = base["model"]
        args.llm_binding_host = base["host"]
        args.llm_binding_api_key = base["api_key"]
    effective_roles = dict(config["roles"])
    if base:
        for role in ROLE_NAMES - {"vlm"}:
            effective_roles.setdefault(role, base)
    for role, spec in effective_roles.items():
        for attr, key in [
            ("binding", "binding"),
            ("model", "model"),
            ("binding_host", "host"),
            ("binding_api_key", "api_key"),
        ]:
            setattr(args, f"{role}_llm_{attr}", spec[key])
    if "vlm" in config["roles"]:
        args.vlm_process_enable = True
    embedding = config["embedding"]
    if embedding:
        args.embedding_binding = embedding["binding"]
        args.embedding_model = embedding["model"]
        args.embedding_binding_host = embedding["host"]
        args.embedding_binding_api_key = embedding["api_key"]
        args.embedding_dim = embedding["dimension"]
        args.embedding_send_dim = embedding["send_dimensions"]
    rerank = config["rerank"]
    if rerank:
        args.rerank_binding = rerank["binding"]
        args.rerank_model = rerank["model"]
        args.rerank_binding_host = rerank["host"]
        args.rerank_binding_api_key = rerank["api_key"]
    args._platform_models_applied = True


def platform_role_metadata(args, role: str) -> dict:
    config = getattr(args, "platform_models_active", None)
    if not config:
        return {}
    if role == "vlm" and role not in config["roles"]:
        return getattr(args, "_platform_vlm_fallback", {})
    spec = config["roles"].get(role) or (config["llm"] if role != "vlm" else None)
    return spec or {}


def create_platform_model_routes(args, api_key: str | None) -> APIRouter:
    """Only the configured server API key can write credentials; no JWT/whitelist bypass."""
    router = APIRouter(tags=["Platform model configuration"])
    active = getattr(args, "platform_models_active", None)

    @router.put("/models/config")
    async def configure(request: Request):
        provided = request.headers.get("X-API-Key", "")
        if not api_key or not hmac.compare_digest(provided, api_key):
            raise HTTPException(401, "A configured server API key is required")
        try:
            raw = await request.body()
            if len(raw) > 131072:
                raise ValueError("Body too large")
            config = _validated(json.loads(raw))
        except (ValueError, TypeError):
            # Validation errors can contain submitted API keys. Never return them.
            raise HTTPException(400, "Invalid platform model configuration") from None
        path = config_path(args)
        path.parent.mkdir(parents=True, exist_ok=True)
        with _configuration_lock(path):
            previous = read_config(args)
            if previous and previous["owner"] != config["owner"]:
                raise HTTPException(
                    409, "This workspace belongs to another knowledge base"
                )
            if previous != config:
                fd, name = tempfile.mkstemp(dir=path.parent, prefix=path.name + ".")
                try:
                    with os.fdopen(fd, "w", encoding="utf-8") as stream:
                        json.dump(config, stream, ensure_ascii=False)
                        stream.flush()
                        os.fsync(stream.fileno())
                    os.replace(name, path)
                finally:
                    if os.path.exists(name):
                        os.unlink(name)
        rebuild = _embedding_state(args, active) != _embedding_state(args, config)
        return {
            "status": "success",
            "restart_required": _revision(active) != _revision(config),
            "rebuild_required": rebuild,
        }

    return router
