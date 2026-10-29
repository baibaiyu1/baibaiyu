#!/usr/bin/env python3
"""Stream archives through Docker mounts; no host Python or third-party modules."""

import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import shutil
import sys
import tarfile


def digest(path):
    result = hashlib.sha256()
    with open(path, 'rb') as stream:
        for block in iter(lambda: stream.read(4 * 1024 * 1024), b''):
            result.update(block)
    return result.hexdigest()


def relative_name(name):
    path = PurePosixPath(name)
    if not name or path.is_absolute() or '..' in path.parts or '\\' in name:
        raise ValueError(f'Unsafe archive path: {name}')
    return path


def validate_member(member):
    path = relative_name(member.name)
    if not (member.isfile() or member.isdir() or member.issym()):
        raise ValueError(f'Unsupported archive entry: {member.name}')
    if member.issym():
        link = PurePosixPath(member.linkname)
        if link.is_absolute() or '\\' in member.linkname:
            raise ValueError(f'External symlink: {member.name}')
        depth = len(path.parent.parts)
        for part in link.parts:
            depth += -1 if part == '..' else (0 if part == '.' else 1)
            if depth < 0:
                raise ValueError(f'External symlink: {member.name}')
    return path


def pack(root, output):
    root = Path(root)
    if not root.is_dir():
        raise ValueError(f'Missing source directory: {root}')
    count = size = 0
    # Do not follow symlinks or deduplicate hardlinks; validate every entry.
    handle = (tarfile.open(fileobj=output, mode='w|', dereference=False) if hasattr(output, 'write')
              else tarfile.open(output, 'w|', dereference=False))
    with handle as archive:
        def add(path):
            nonlocal count, size
            name = path.relative_to(root).as_posix()
            before = path.lstat()
            member = archive.gettarinfo(str(path), arcname=name)
            if member.islnk():
                member.type, member.linkname = tarfile.REGTYPE, ''
                member.size = before.st_size
            validate_member(member)
            if member.isfile():
                with path.open('rb') as content:
                    archive.addfile(member, content)
                after = path.stat()
                if (before.st_size, before.st_mtime_ns) != (after.st_size, after.st_mtime_ns):
                    raise ValueError(f'Source changed during backup: {name}')
                size += member.size
            else:
                archive.addfile(member)
            count += 1
            if member.isdir():
                for child in sorted(path.iterdir()):
                    add(child)
        for path in sorted(root.iterdir()):
            add(path)
    print(json.dumps({'entries': count, 'bytes': size}), file=sys.stderr)


def unpack(archive_path, destination):
    destination = Path(destination)
    if destination.is_symlink() or (destination.exists() and any(destination.iterdir())):
        raise ValueError(f'Refusing nonempty destination: {destination}')
    destination.mkdir(parents=True, exist_ok=True)
    directory_modes = []
    with tarfile.open(archive_path, 'r|') as archive:
        for member in archive:
            relative = validate_member(member)
            target = destination / str(relative)
            if target == destination:
                raise ValueError('Archive root entry is not allowed')
            parent = target.parent
            while parent != destination:
                if parent.is_symlink():
                    raise ValueError(f'Archive traverses a symlink: {member.name}')
                parent = parent.parent
            target.parent.mkdir(parents=True, exist_ok=True)
            if target.exists() or target.is_symlink():
                # tarfile may implicitly create a parent directory before its header.
                if not (member.isdir() and target.is_dir() and not target.is_symlink()):
                    raise ValueError(f'Duplicate archive entry: {member.name}')
            mode = member.mode & 0o777  # Do not restore setuid/setgid bits.
            if member.isdir():
                target.mkdir(exist_ok=True)
                directory_modes.append((target, mode, member.uid, member.gid, member.mtime))
            elif member.issym():
                target.symlink_to(member.linkname)
            else:
                with target.open('xb') as output, archive.extractfile(member) as content:
                    while block := content.read(4 * 1024 * 1024):
                        output.write(block)
                target.chmod(mode)
                if hasattr(os, 'chown'):
                    os.chown(target, member.uid, member.gid)
                os.utime(target, (member.mtime, member.mtime))
    for target, mode, uid, gid, mtime in reversed(directory_modes):
        target.chmod(mode)
        if hasattr(os, 'chown'):
            os.chown(target, uid, gid)
        os.utime(target, (mtime, mtime))


def restore(root):
    manifest = json.loads((root / 'manifest.json').read_text())
    data = root / 'data'
    if data.exists() and any(data.iterdir()):
        raise ValueError('data/ must be empty before the first restore; existing data is never overwritten')
    for item in manifest['archives']:
        unpack(root / item['file'], root / item['destination'])
    for name in ('mysql_data', 'pgvector_data', 'redis_data', 'mineru/output', 'mineru/cache'):
        (data / name).mkdir(parents=True, exist_ok=True)
    redis = data / 'redis_data/dump.rdb'
    shutil.copyfile(root / 'backup/redis.rdb', redis)
    # Redis runs as its image's redis UID, which is recorded during export.
    os.chown(redis, *manifest['redis_uid_gid'])
    os.chown(redis.parent, *manifest['redis_uid_gid'])
    for name in ('rag_storage', 'inputs', 'prompts'):
        path = data / 'lightrag' / name
        path.mkdir(parents=True, exist_ok=True)
        for current, dirs, files in os.walk(path):
            os.chown(current, 1000, 1000)
            for child in files:
                os.chown(Path(current) / child, 1000, 1000, follow_symlinks=False)
                if child.startswith('platform_models_') and child.endswith('.json'):
                    (Path(current) / child).chmod(0o600)
    for name in ('console', 'runtime', 'gateway', 'proxy', 'websocket', 'frontend', 'lightrag'):
        (root / 'logs' / name).mkdir(parents=True, exist_ok=True)
    os.chown(root / 'logs/lightrag', 1000, 1000)
    (root / '.files-restored').write_text(manifest['bundle_id'] + '\n')


def verify_images(root):
    # Emit a tab separated list, consumed as data by deploy.sh (never evaluated).
    manifest = json.loads((root / 'manifest.json').read_text())
    for name, record in manifest['images'].items():
        print(record['tag'] + '\t' + record['id'])


def check_counts(root):
    expected = json.loads((root / 'manifest.json').read_text())['mysql_row_counts']
    actual = {}
    for line in (root / '.mysql-counts.jsonl').read_text().splitlines():
        row = json.loads(line)
        actual[row['table']] = row['rows']
    if expected != actual:
        raise ValueError('Restored MySQL row counts do not match the source; application startup is blocked')
    print(f'PASS: restored MySQL row counts match all {len(expected)} source tables')


def effective(root, action):
    config = json.loads((root / '.effective-compose.json').read_text())
    if action == 'ports':
        for service in config['services'].values():
            for port in service.get('ports', []):
                print(int(port['published']))
    elif action == 'gpu':
        gpu = config['services']['mineru']['deploy']['resources']['reservations']['devices'][0]['device_ids'][0]
        if not gpu.isdecimal():
            raise ValueError('MINERU_GPU_ID must be a numeric GPU index')
        print(gpu)
    else:
        print(config['services']['mineru']['image'])


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('action', choices=('pack', 'restore', 'images', 'counts', 'ports', 'gpu', 'mineru-image'))
    parser.add_argument('root')
    parser.add_argument('output', nargs='?')
    args = parser.parse_args()
    if args.action == 'pack':
        pack(args.root, sys.stdout.buffer if args.output == '-' else args.output)
    elif args.action == 'restore':
        restore(Path(args.root))
    elif args.action == 'images':
        verify_images(Path(args.root))
    elif args.action == 'counts':
        check_counts(Path(args.root))
    else:
        effective(Path(args.root), args.action)


if __name__ == '__main__':
    main()
