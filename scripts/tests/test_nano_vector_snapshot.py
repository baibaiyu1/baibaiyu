"""The memory-bounded loader must retain upstream snapshot/query compatibility."""

from pathlib import Path
import tempfile
import unittest

import numpy as np
from nano_vectordb import NanoVectorDB
from lightrag.kg.nano_vector_client import SnapshotNanoVectorDB


class NanoVectorSnapshotTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.path = Path(self.temporary.name) / "vectors.json"

    def test_existing_unicode_snapshot_has_identical_queries_and_metadata(self):
        original = NanoVectorDB(3, storage_file=str(self.path))
        original.upsert([
            {"__id__": "a", "__vector__": np.array([1, 2, 0]), "content": "灯具与图谱😀"},
            {"__id__": "b", "__vector__": np.array([0, 1, 3]), "content": "另一文档"},
        ])
        original.store_additional_data(lightrag_embedding_model="existing-model", custom="保留")
        original.save()
        before = self.path.read_bytes()
        loaded = SnapshotNanoVectorDB(3, storage_file=str(self.path))
        query = np.array([1, 2, 1], dtype=np.float32)
        expected, actual = original.query(query, 2), loaded.query(query, 2)
        self.assertEqual([row["__id__"] for row in expected], [row["__id__"] for row in actual])
        np.testing.assert_allclose([row["__metrics__"] for row in expected], [row["__metrics__"] for row in actual])
        self.assertEqual(original.get_additional_data(), loaded.get_additional_data())
        self.assertEqual(original.get(["a", "b"]), loaded.get(["a", "b"]))
        self.assertEqual(before, self.path.read_bytes(), "Loading must never rewrite the original vectors")

        loaded.upsert([{"__id__": "c", "__vector__": np.array([2, 0, 1]), "content": "新增"}])
        loaded.delete(["b"])
        loaded.save()
        reopened = NanoVectorDB(3, storage_file=str(self.path))
        self.assertEqual(2, len(reopened))
        self.assertEqual("新增", reopened.get(["c"])[0]["content"])
        self.assertEqual("保留", reopened.get_additional_data()["custom"])

    def test_empty_and_missing_snapshots_allow_writes(self):
        for existing in (False, True):
            if existing:
                NanoVectorDB(3, storage_file=str(self.path)).save()
            loaded = SnapshotNanoVectorDB(3, storage_file=str(self.path))
            self.assertEqual(0, len(loaded))
            loaded.upsert([{"__id__": "a", "__vector__": np.array([1, 0, 0])}])
            self.assertEqual(1, len(loaded))

    def test_dimension_mismatch_and_corruption_refuse_without_writes(self):
        NanoVectorDB(3, storage_file=str(self.path)).save()
        before = self.path.read_bytes()
        with self.assertRaises(AssertionError):
            SnapshotNanoVectorDB(4, storage_file=str(self.path))
        self.assertEqual(before, self.path.read_bytes())
        self.path.write_text('{"embedding_dim":3,"matrix":', encoding="utf-8")
        before = self.path.read_bytes()
        with self.assertRaises(ValueError):
            SnapshotNanoVectorDB(3, storage_file=str(self.path))
        self.assertEqual(before, self.path.read_bytes())


if __name__ == "__main__":
    unittest.main()
