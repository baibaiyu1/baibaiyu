#!/usr/bin/env python3
"""Copy a MinerU pipeline snapshot into this project, or verify it in place."""

import argparse
import hashlib
from pathlib import Path
import shutil
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
REQUIRED = ('Layout', 'MFR', 'OCR', 'TabCls', 'TabRec')


def validate(root: Path) -> None:
    missing = [name for name in REQUIRED if not (root / 'models' / name).is_dir()
               or not any(p.is_file() and p.stat().st_size for p in (root / 'models' / name).rglob('*'))]
    if missing:
        raise ValueError('Incomplete MinerU snapshot: missing model files in ' + ', '.join(missing))


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(4 * 1024 * 1024), b''):
            digest.update(block)
    return digest.hexdigest()


def prepare(source: Path, target: Path) -> tuple[int, int]:
    source, target = source.resolve(), target.resolve()
    validate(source)
    if source == target or source in target.parents or target in source.parents:
        raise ValueError('Source and target must be separate directories without nesting')
    source_files = sorted(p for p in (source / 'models').rglob('*') if p.is_file())
    # Check conflicts before copying; never replace an existing model with different content.
    for path in source_files:
        dest = target / path.relative_to(source)
        if dest.exists() and (not dest.is_file() or sha256(path) != sha256(dest)):
            raise ValueError('Target already contains a different file: ' + str(dest))
    copied = 0
    for path in source_files:
        dest = target / path.relative_to(source)
        if dest.exists():
            continue
        dest.parent.mkdir(parents=True, exist_ok=True)
        # A failed copy never leaves a partial weight at the final filename.
        with tempfile.NamedTemporaryFile(dir=dest.parent, prefix='.copy-', delete=False) as stream:
            temporary = Path(stream.name)
        try:
            shutil.copyfile(path, temporary)
            if sha256(path) != sha256(temporary):
                raise ValueError('Model checksum mismatch: ' + str(path))
            temporary.replace(dest)
            copied += 1
        finally:
            temporary.unlink(missing_ok=True)
    validate(target)
    return copied, len(source_files)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, help='Snapshot directory containing models/Layout, models/OCR, etc.')
    parser.add_argument('--target', type=Path, default=ROOT / 'models/mineru')
    parser.add_argument('--check', action='store_true', help='Verify model files without copying')
    args = parser.parse_args()
    try:
        if args.check:
            validate(args.target)
            print('MinerU pipeline model files are present: ' + str(args.target.resolve()))
        else:
            if args.source is None:
                parser.error('--source is required unless --check is used')
            copied, total = prepare(args.source, args.target)
            print(f'MinerU models ready: {total} files, {copied} copied into {args.target.resolve()}')
    except (ValueError, OSError) as error:
        print(str(error), file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
