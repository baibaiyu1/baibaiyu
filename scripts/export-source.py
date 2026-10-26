#!/usr/bin/env python3
"""Export the current source tree without local configuration or Git metadata."""

import argparse
import datetime
import pathlib
import subprocess
import zipfile


EXCLUDED_DIRS = {
    '.git', '.idea', '.vscode', '.codex', '.codex_tmp', '.agents', '.claude',
    '.cursor', '.qoder', '.trae', '.omc', '.omx', '.gitnexus', '.baibaiyu',
    '.m2', '.pnpm-store', 'node_modules', 'target', 'dist', 'dist-main',
    'dist-doc', 'dist-zip', 'output', 'scratch', 'logs', '__pycache__',
}
ENV_TEMPLATES = {
    'docker/.env.console', 'docker/.env.execute', 'docker/.env.middleware',
    'docker/.env.simple', 'ui/.env.main', 'ui/.env.doc',
}


def excluded(name):
    path = pathlib.PurePosixPath(name)
    if any(part in EXCLUDED_DIRS for part in path.parts):
        return True
    if path.name.startswith('.env') and name not in ENV_TEMPLATES and not path.name.endswith(('.sample', '.example')):
        return True
    if path.name == 'application-dev.yml':
        return True
    if path.suffix in {'.log', '.class', '.pyc', '.iml', '.iws', '.ipr'}:
        return True
    if name.startswith(('docker/data/', 'docker/logs/')):
        return True
    return False


def export(root, output):
    if output.exists():
        raise SystemExit(f'Output already exists: {output}')
    names = subprocess.check_output(
        ['git', 'ls-files', '-z', '--cached', '--others', '--exclude-standard'], cwd=root
    ).decode('utf-8').split('\0')
    selected = []
    for name in sorted(set(names)):
        if not name or excluded(name):
            continue
        source = root / name
        if source.is_symlink():
            raise SystemExit(f'Symlinks are not supported: {name}')
        if source.is_file():
            if not source.resolve().is_relative_to(root):
                raise SystemExit(f'File outside repository: {name}')
            selected.append(name)
    required = {'LICENSE', 'README.md', 'pom.xml', 'ui/package.json',
                'ui/pnpm-lock.yaml', 'ui/lightrag-management/LICENSE'}
    missing = required.difference(selected)
    if missing:
        raise SystemExit(f'Required source files missing: {sorted(missing)}')
    output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(output, 'x', compression=zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
        for name in selected:
            archive.write(root / name, f'baibaiyu/{name}')
    with zipfile.ZipFile(output) as archive:
        corrupt = archive.testzip()
        if corrupt:
            raise SystemExit(f'Archive verification failed: {corrupt}')
        for name in required:
            if archive.read(f'baibaiyu/{name}') != (root / name).read_bytes():
                raise SystemExit(f'Archive content mismatch: {name}')
    print(f'Exported {len(selected)} files to {output}')
    print('Git metadata, local configuration, dependencies and build output are excluded.')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=pathlib.Path, help='Path of a new ZIP file')
    args = parser.parse_args()
    repository = pathlib.Path(__file__).resolve().parents[1]
    stamp = datetime.datetime.now().strftime('%Y%m%d-%H%M%S')
    destination = args.output or repository / 'output' / f'baibaiyu-source-{stamp}.zip'
    if not destination.is_absolute():
        destination = repository / destination
    export(repository, destination.resolve())
