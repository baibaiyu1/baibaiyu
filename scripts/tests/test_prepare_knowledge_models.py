"""Model imports must be repeatable and preserve existing/source weights."""

import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('prepare_models', Path(__file__).resolve().parents[1] / 'prepare_knowledge_models.py')
models = importlib.util.module_from_spec(spec)
spec.loader.exec_module(models)


class PrepareModelsTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.source, self.target = self.root / 'source', self.root / 'target'
        for name in models.REQUIRED:
            path = self.source / 'models' / name / 'weights.bin'
            path.parent.mkdir(parents=True)
            path.write_bytes(name.encode())

    def test_copy_and_repeat_preserve_source_and_skip_identical_weights(self):
        self.assertEqual(models.prepare(self.source, self.target), (5, 5))
        self.assertEqual(models.prepare(self.source, self.target), (0, 5))
        for name in models.REQUIRED:
            relative = Path('models') / name / 'weights.bin'
            self.assertEqual((self.source / relative).read_bytes(), name.encode())
            self.assertEqual((self.target / relative).read_bytes(), name.encode())
        self.assertEqual(list(self.target.rglob('.copy-*')), [])

    def test_conflict_fails_before_copy_and_never_overwrites(self):
        existing = self.target / 'models/TabRec/weights.bin'
        existing.parent.mkdir(parents=True)
        existing.write_bytes(b'existing-model')
        with self.assertRaisesRegex(ValueError, 'different file'):
            models.prepare(self.source, self.target)
        self.assertEqual(existing.read_bytes(), b'existing-model')
        self.assertFalse((self.target / 'models/Layout').exists())

    def test_empty_model_directory_is_rejected(self):
        (self.source / 'models/OCR/weights.bin').unlink()
        with self.assertRaisesRegex(ValueError, 'OCR'):
            models.prepare(self.source, self.target)
        self.assertFalse(self.target.exists())

    def test_source_and_target_cannot_overlap(self):
        for target in (self.source, self.source / 'nested', self.root):
            with self.subTest(target=target), self.assertRaisesRegex(ValueError, 'nesting'):
                models.prepare(self.source, target)


if __name__ == '__main__':
    unittest.main()
