# Platform-managed models

The GMD platform can select models from its tenant-scoped model catalogue for
LightRAG extraction, keywords, query answers, image understanding, embeddings and
reranking. `PUT /models/config` persists the selection. Restart every server
process to apply it. This endpoint does not hot-swap an indexing or query model.

## Connect and authenticate

Set `LIGHTRAG_API_KEY` and configure the same key in the platform knowledge graph
connection. The endpoint accepts that key only, via `X-API-Key`; guest tokens,
login tokens, and `WHITELIST_PATHS` cannot authorize configuration writes. A
server without a configured API key cannot accept platform selections.

The platform validates its model references, tenant, enabled status, intended
usage and protocol before sending any credentials. The server validates binding,
role names, endpoint URLs and embedding dimensions before writing anything. The
endpoint returns only status and the restart/rebuild flags, never credentials or
submitted validation values.

One LightRAG workspace belongs to one platform knowledge base, identified by
`<tenant-id>:<knowledge-id>`. A different owner is refused with HTTP 409. Platforms
must use separate service instances/workspaces for independent knowledge bases.
Ownership is an integration guard between trusted API-key holders, not a
replacement for per-user authorization in the platform.

## Configuration and persistence

The request contains `owner`, optional `llm`, `embedding`, `rerank`, and a `roles`
object with any of `extract`, `keyword`, `query`, `vlm`. Each model supplies
`binding`, `model`, `host`, `api_key`; LLM models may supply `provider_options`.
Embeddings additionally require `dimension` and may enable `send_dimensions`.
The default `llm` supplies unselected text roles. The platform explicitly avoids
assigning a text-only model to the VLM role.

Supported LLM bindings are `openai`, `ollama`, and `gemini`. Embeddings support
`openai` and `ollama`; rerankers use `cohere` (including compatible vLLM services)
or `aliyun`. Responses is not silently converted to Chat Completions: the
platform excludes it from compatible selections.

Configuration lives in `working_dir/platform_models_<workspace>.json` (`default`
for the empty workspace). Persist the working directory across deployments. This
file contains provider credentials: protect the storage volume and backups. New
files use owner-only POSIX permissions. Atomic replacement preserves the previous
complete configuration if validation or writing fails; an OS file lock prevents
concurrent owners from claiming the workspace. The lock is released after a
crash. Corrupt persisted configuration prevents startup rather than falling back
to unrelated environment credentials.

Environment and CLI settings remain the fallback for roles absent from the
persisted selection. The file takes precedence for selected models. Sending the
same owner with no selections resets to environment/CLI settings on restart.
To transfer the workspace to another platform knowledge base, stop the service,
verify the data ownership, and archive/remove the specific workspace config file
before the new owner synchronizes. Keep the lock file in place.

## Apply changes

1. Save/synchronize model selections in the platform. A successful response with
   `restart_required=true` means the file was saved but this process still uses
   its old configuration.
2. Stop every server process that shares this workspace. Finish or cancel any
   active ingestion before stopping.
3. If the embedding model, provider, endpoint or output dimension changed and
   vectors exist, rebuild them offline. A different model of the same dimension
   still requires a rebuild. Never delete document data to bypass identity checks.
   The first platform-managed embedding selection also requires a rebuild of
   existing vectors: its storage identity includes the model, endpoint, binding,
   dimension and dimension policy. Removing that selection requires rebuilding
   back into the deployment's vector space. Rotating an API key does not change
   the vector identity.
4. Start/recreate all server processes with the same working directory and
   workspace. Synchronize again to verify `restart_required=false`.

For a Compose service named `lightrag`, a typical restart/rebuild sequence is:

```bash
docker compose stop lightrag
docker compose run --rm --no-deps lightrag lightrag-rebuild-vdb --help
# Run the rebuild command with the storage arguments required by your backend.
docker compose up -d --build lightrag
```

Use the [rebuild guide](../lightrag/tools/README_REBUILD_VDB.md) for the actual
backend-specific command and verify its report before starting the service.
The server and `lightrag-rebuild-vdb` read the same saved selection through the
shared argument parser. All worker processes must restart together; a rolling
model change is unsupported.

The platform checks the current model configuration before ingestion/retrieval.
If the saved selection differs from the active process, the platform reports the
required restart instead of silently running against stale models. If the
endpoint is unavailable in an older LightRAG build, it reports an upgrade error.
Calls made directly to LightRAG keep their active configuration until restart;
operators must follow the stop/rebuild/restart sequence above.
