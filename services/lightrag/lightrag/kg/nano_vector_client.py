"""Load existing NanoVectorDB snapshots without a full Unicode file copy.

The upstream loader reads the complete JSON as Python text. Chinese metadata
can widen that entire string, including the large ASCII base64 matrix, to four
bytes per character. Parse bytes with the existing Pydantic dependency instead;
the database format and all query/write operations remain upstream's.
"""

import base64
from pathlib import Path

import numpy as np
from nano_vectordb import NanoVectorDB
from nano_vectordb.dbs import logger
from pydantic_core import from_json


class SnapshotNanoVectorDB(NanoVectorDB):
    def __post_init__(self):
        path = Path(self.storage_file)
        if path.exists():
            storage = from_json(path.read_bytes(), cache_strings=False)
            assert storage["embedding_dim"] == self.embedding_dim, (
                f"Embedding dim mismatch, expected: {self.embedding_dim}, "
                f"but loaded: {storage['embedding_dim']}"
            )
            encoded = storage.pop("matrix")
            storage["matrix"] = np.frombuffer(
                base64.b64decode(encoded), dtype=np.float32
            ).reshape(-1, self.embedding_dim)
            del encoded
        else:
            storage = {
                "embedding_dim": self.embedding_dim,
                "data": [],
                "matrix": np.empty((0, self.embedding_dim), dtype=np.float32),
            }
        # NanoVectorDB's public operations use this private snapshot. Keep the
        # same layout, metadata, metric validation and normalization as upstream.
        self._NanoVectorDB__storage = storage
        self.usable_metrics = {"cosine": self._cosine_query}
        assert self.metric in self.usable_metrics, f"Metric {self.metric} not supported"
        self.pre_process()
        logger.info("Init %s with %s records", self.storage_file, len(self))
