"""Resolve images from retrieved chunks in this workspace, without another query.

Only documents reached through the workspace's text-chunk store are inspected.
No filesystem-wide drawing index, source URL fetch or VLM call is needed.
"""

from __future__ import annotations

import asyncio
import base64
import json
import re
from pathlib import Path

from lightrag.image_quality import image_skip_reason
from lightrag.utils import validate_workspace
from lightrag.utils_pipeline import input_dir_path, resolve_sidecar_uri

DEFAULT_MAX_IMAGES = 3
MAX_IMAGES = 20
MAX_IMAGE_BYTES = 5 * 1024 * 1024
MAX_TOTAL_BYTES = 8 * 1024 * 1024
MAX_SOURCE_CHUNKS = 1000
_IMAGE_ID = re.compile(r'\b(?:id|refid)\s*=\s*["\'](im-[^"\']+)["\']')


def _mime(data: bytes) -> str | None:
    if data.startswith(b"\x89PNG\r\n\x1a\n"):
        return "image/png"
    if data.startswith(b"\xff\xd8\xff"):
        return "image/jpeg"
    if data.startswith((b"GIF87a", b"GIF89a")):
        return "image/gif"
    if data.startswith(b"RIFF") and data[8:12] == b"WEBP":
        return "image/webp"
    if data.startswith(b"BM"):
        return "image/bmp"
    return None


def _read_images(
    documents: list[tuple[str, dict]], wanted: dict[str, str], root: Path,
    max_images: int = DEFAULT_MAX_IMAGES,
) -> list[dict]:
    images: list[dict] = []
    total = 0
    seen: set[tuple[str, str]] = set()
    for doc_id, document in documents:
        directory = resolve_sidecar_uri(document.get("sidecar_location"))
        if directory is None:
            continue
        directory = directory.resolve()
        if not directory.is_relative_to(root):
            continue
        for manifest in sorted(directory.glob("*.drawings.json")):
            try:
                if not manifest.resolve().is_relative_to(directory):
                    continue
                with manifest.open("rb") as stream:
                    raw = stream.read(4 * 1024 * 1024 + 1)
                if len(raw) > 4 * 1024 * 1024:
                    continue
                drawings = json.loads(raw).get("drawings", {})
            except (OSError, ValueError, AttributeError):
                continue
            if not isinstance(drawings, dict):
                continue
            for image_id, reference in wanted.items():
                item = drawings.get(image_id)
                if not isinstance(item, dict) or (doc_id, image_id) in seen:
                    continue
                analysis = item.get("llm_analyze_result") or {}
                if not isinstance(analysis, dict):
                    analysis = {}
                if analysis.get("status") in ("skipped", "failure") or image_skip_reason(
                    analysis
                ):
                    continue
                path = item.get("path")
                if not isinstance(path, str) or not path.strip():
                    continue
                asset = (directory / path).resolve()
                if not asset.is_relative_to(directory) or not asset.is_file():
                    continue
                try:
                    with asset.open("rb") as stream:
                        content = stream.read(
                            min(MAX_IMAGE_BYTES, MAX_TOTAL_BYTES - total) + 1
                        )
                except OSError:
                    continue
                if (
                    not content
                    or len(content) > MAX_IMAGE_BYTES
                    or total + len(content) > MAX_TOTAL_BYTES
                ):
                    continue
                mime = _mime(content)
                if mime is None:
                    continue
                images.append(
                    {
                        "id": image_id,
                        "doc_id": doc_id,
                        "reference_id": reference,
                        "file_path": document.get("file_path", ""),
                        "caption": str(
                            item.get("caption") or analysis.get("name") or image_id
                        ),
                        "mime_type": mime,
                        "data": base64.b64encode(content).decode("ascii"),
                    }
                )
                total += len(content)
                seen.add((doc_id, image_id))
                if len(images) >= max_images:
                    return images
    return images


async def retrieved_images(rag, data: dict, max_images: int = DEFAULT_MAX_IMAGES) -> list[dict]:
    """Read a bounded set of assets belonging to the retrieved documents."""
    if not 1 <= max_images <= MAX_IMAGES:
        raise ValueError(f"max_images must be between 1 and {MAX_IMAGES}")
    wanted: dict[str, str] = {}
    chunk_ids: dict[str, None] = {}
    # Keep graph evidence ahead of broad vector matches when the image budget
    # is small. Product nodes and edges can reach drawings even when the
    # drawing node itself was omitted from the graph context token budget.
    for entity in data.get("entities", []):
        if not isinstance(entity, dict):
            continue
        for chunk_id in str(entity.get("source_id") or "").split("<SEP>"):
            if chunk_id:
                chunk_ids[chunk_id] = None
        if str(entity.get("entity_type", "")).lower() == "drawing":
            image_id = str(entity.get("entity_name") or "")
            if image_id:
                wanted.setdefault(image_id, "")
    for relation in data.get("relationships", []):
        if not isinstance(relation, dict):
            continue
        for chunk_id in str(relation.get("source_id") or "").split("<SEP>"):
            if chunk_id:
                chunk_ids[chunk_id] = None
        for endpoint in ("src_id", "tgt_id"):
            image_id = str(relation.get(endpoint) or "")
            if image_id.startswith("im-"):
                wanted.setdefault(image_id, "")
    for chunk in data.get("chunks", []):
        if not isinstance(chunk, dict):
            continue
        if chunk.get("chunk_id"):
            chunk_ids[str(chunk["chunk_id"])] = None
        for image_id in _IMAGE_ID.findall(str(chunk.get("content") or "")):
            if not wanted.get(image_id):
                wanted[image_id] = str(chunk.get("reference_id") or "")
    chunks = (
        await rag.text_chunks.get_by_ids(list(chunk_ids)[:MAX_SOURCE_CHUNKS])
        if chunk_ids else []
    )
    doc_ids: dict[str, None] = {}
    for chunk in chunks:
        if not isinstance(chunk, dict):
            continue
        doc_id = chunk.get("full_doc_id")
        if doc_id:
            doc_ids[str(doc_id)] = None
        sidecar = chunk.get("sidecar") or {}
        if isinstance(sidecar, dict):
            refs = sidecar.get("refs") or [sidecar]
            for ref in refs if isinstance(refs, list) else []:
                if (
                    isinstance(ref, dict)
                    and ref.get("type") == "drawing"
                    and ref.get("id")
                ):
                    wanted.setdefault(str(ref["id"]), "")
    if not wanted or not doc_ids:
        return []
    workspace = str(rag.workspace or "")
    validate_workspace(workspace)
    root = (input_dir_path() / workspace).resolve()
    ids = list(doc_ids)
    documents = await rag.full_docs.get_by_ids(ids)
    images = await asyncio.to_thread(
        _read_images,
        [(doc_id, doc) for doc_id, doc in zip(ids, documents) if isinstance(doc, dict)],
        wanted,
        root,
        max_images,
    )
    references = {
        str(ref.get("file_path")): str(ref.get("reference_id") or "")
        for ref in data.get("references", [])
        if isinstance(ref, dict)
    }
    for image in images:
        if not image["reference_id"]:
            image["reference_id"] = references.get(image["file_path"], "")
    return images
