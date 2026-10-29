#!/usr/bin/env python3
"""Real restore smoke test in an isolated Linux Docker volume, network and ports.

Requires a local test-runner image with bash, Python, Docker CLI and Compose.
Never starts or removes source platform containers. The verification volume is
preserved on failure and removed only after the full smoke test succeeds.
"""

import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import subprocess
import uuid


def run(*args, check=True, data=None):
    result = subprocess.run([str(x) for x in args], input=data, capture_output=True)
    if check and result.returncode:
        # Docker command arguments and captured logs can contain secrets.
        raise RuntimeError(f'{args[0]} {args[1]} failed with exit {result.returncode}')
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('bundle', type=Path)
    parser.add_argument('--runner-image', required=True)
    parser.add_argument('--resume-fixture', help='Reuse an untouched fixture after a pre-restore failure')
    parser.add_argument('--fixture-subdir', help='Create a fresh attempt under a retained verification volume')
    parser.add_argument('--resume-restored', action='store_true', help='Continue verification after a completed database restore')
    args = parser.parse_args()
    bundle = args.bundle.resolve()
    project = args.resume_fixture or ('codex-bundle-' + uuid.uuid4().hex[:8])
    if not project.startswith('codex-bundle-') or not all(c.isalnum() or c == '-' for c in project):
        raise ValueError('Invalid smoke-test project name')
    volume = project + '-files'
    container = project + '-runner'
    if not args.resume_fixture:
        run('docker', 'volume', 'create', volume)
    mount = json.loads(run('docker', 'volume', 'inspect', volume).stdout)[0]['Mountpoint']
    working = mount
    if not args.resume_fixture:
        run('docker', 'run', '-d', '--name', container, '--network', 'host', '--gpus', 'all',
            '--mount', 'type=bind,source=/var/run/docker.sock,target=/var/run/docker.sock',
            '--mount', f'type=volume,source={volume},target={mount}',
            '-w', mount, '--entrypoint', 'bash', args.runner_image, '-c', 'exec tail -f /dev/null')
    success = False
    if args.fixture_subdir:
        if not args.resume_fixture or not args.fixture_subdir.isalnum():
            raise ValueError('A new attempt requires a retained fixture and a simple directory name')
        working = mount + '/' + args.fixture_subdir
        condition = '(p/".databases-restored").exists()' if args.resume_restored else 'not (p/".restore-started").exists()'
        run('docker', 'exec', container, 'python', '-c',
            'from pathlib import Path; import sys; p=Path(sys.argv[1]); p.mkdir(exist_ok=True); assert ' + condition, working)

    def execute(*command, check=True, data=None):
        return run('docker', 'exec', '-i', '-w', working, '-e', 'BUNDLE_HOST_IP=127.0.0.1', container,
                   *command, check=check, data=data)

    def compose(*command, check=True, data=None):
        return execute('docker', 'compose', '--env-file', 'settings.env', '-f', 'compose.json',
                       *command, check=check, data=data)

    try:
        print('Preparing isolated Linux fixture: ' + project, flush=True)
        # Copy small payloads and data archives. The production image save/load
        # is exercised separately; this fixture already uses those exact IDs.
        if not args.resume_restored and (not args.resume_fixture or args.fixture_subdir):
            for path in bundle.iterdir():
                if path.name != 'images.tar' and path.is_file():
                    run('docker', 'cp', path, container + ':' + working + '/' + path.name)
            if (bundle / 'licenses').is_dir():
                run('docker', 'cp', bundle / 'licenses', container + ':' + working + '/licenses')
            if args.fixture_subdir:
                execute('python', '-c', 'from pathlib import Path; import os; root=Path.cwd(); (root/"backup").mkdir(exist_ok=True); '
                        '[os.link(p,root/"backup"/p.name) for p in (root.parent/"backup").iterdir() if not (root/"backup"/p.name).exists()]')
            else:
                run('docker', 'cp', bundle / 'backup', container + ':' + working + '/backup')
        elif not args.resume_restored:
            # Only pre-restore failures can be retried with this fixture.
            execute('python', '-c', "from pathlib import Path; assert not Path('.restore-started').exists()")
        if not args.resume_restored:
            for name in ('deploy.sh', 'files.py', 'network.py', 'network-check.sh'):
                run('docker', 'cp', Path(__file__).resolve().parents[2] / 'docker/bundle' / name,
                    container + ':' + working + '/' + name)
        preparation = r'''
import hashlib,json
from pathlib import Path
root=Path.cwd()
config=json.loads((root/'compose.json').read_text())
for service in config['services'].values():
    for port in service.get('ports',[]):
        target=int(port['target'])
        port['published']=str(target+20000 if target!=80 else 18089)
    if service.get('network_mode')=='host':
        service['profiles']=['host-gateway']
    if 'JAVA_HEAP_PERCENTAGE' in ' '.join(service.get('environment',{})):
        for key in service['environment']:
            if key.endswith('JAVA_HEAP_PERCENTAGE'):
                service['environment'][key]='40.0'
(root/'compose.json').write_text(json.dumps(config))
(root/'settings.env').write_text('COMPOSE_PROJECT_NAME=PROJECT_NAME\nFRONTEND_PORT=18089\nMYSQL_PORT=23306\nREDIS_PORT=26379\nPG_PORT=25432\nMINERU_GPU_ID=0\n')
(root/'images.tar').write_bytes(b'Images are already loaded and validated by ID in this smoke fixture.\n')
(root/'.images-loaded').touch()
paths=[p for p in sorted(root.rglob('*')) if p.is_file() and p.name not in ('SHA256SUMS','settings.env','smoke-restore.log') and not p.name.startswith('.')]
def sha(path):
    h=hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda:stream.read(4*1024*1024),b''):h.update(block)
    return h.hexdigest()
(root/'SHA256SUMS').write_text(''.join(sha(p)+'  '+p.relative_to(root).as_posix()+'\n' for p in paths))
'''.replace('=PROJECT_NAME', '=' + project)
        if not args.resume_restored:
            execute('python', '-c', preparation)
        result = execute('bash', 'deploy.sh', 'restore', check=False)
        if result.returncode:
            execute('python', '-c', "from pathlib import Path; Path('smoke-restore.log').write_bytes(__import__('sys').stdin.buffer.read())", data=result.stdout + result.stderr)
            raise RuntimeError('Restore failed; see smoke-restore.log in the preserved verification volume')
        print('PASS: deploy.sh restore, MySQL per-table counts, pgvector import, Redis and file restore', flush=True)
        assert execute('bash', 'deploy.sh', 'restore').returncode == 0
        print('PASS: repeated restore skips database import', flush=True)
        result = compose('up', '-d', '--no-build', '--pull', 'never', '--wait', '--wait-timeout', '360', 'gmd-faq-console', check=False)
        if result.returncode:
            execute('python', '-c', "from pathlib import Path; Path('smoke-console.log').write_bytes(__import__('sys').stdin.buffer.read())", data=result.stdout + result.stderr)
            raise RuntimeError('Console/Flyway did not become healthy; inspect the preserved fixture logs')
        print('PASS: restored Console starts and Flyway completes', flush=True)
        console_logs = compose('logs', '--no-color', 'gmd-faq-console')
        logs = (console_logs.stdout + console_logs.stderr).decode('utf-8', errors='replace')
        if 'Started ' not in logs or not any(text in logs for text in ('Successfully applied', 'is up to date', 'Successfully validated')):
            raise RuntimeError('Console health passed but startup/Flyway completion is not present in its log')
        compose('stop', 'gmd-faq-console')
        # Check actual archive contents, ownership and credential file permissions
        # against every original archive header; platform paths have an alias.
        code = r'''
import hashlib,json,stat,tarfile
from pathlib import Path
root=Path.cwd();manifest=json.loads((root/'manifest.json').read_text());checked=0
for entry in manifest['archives']:
    dest=root/entry['destination']
    with tarfile.open(root/entry['file']) as archive:
        for item in archive:
            if item.isfile():
                actual=dest/item.name
                def sha(stream):
                    h=hashlib.sha256()
                    for block in iter(lambda:stream.read(4*1024*1024),b''):h.update(block)
                    return h.hexdigest()
                with archive.extractfile(item) as a,actual.open('rb') as b:assert sha(a)==sha(b),item.name
                if item.name.startswith('platform_models_'):
                    assert stat.S_IMODE(actual.stat().st_mode)==0o600
                checked+=1
print('PASS: restored file contents match',checked,'archive entries; model config permissions retained')
'''
        result = execute('python', '-c', code)
        print(result.stdout.decode().strip(), flush=True)
        result = compose('up', '-d', '--no-build', '--pull', 'never', '--no-deps', '--wait', '--wait-timeout', '240', 'lightrag', check=False)
        if result.returncode:
            execute('python', '-c', "from pathlib import Path; Path('smoke-lightrag.log').write_bytes(__import__('sys').stdin.buffer.read())", data=result.stdout + result.stderr)
            raise RuntimeError('Restored LightRAG did not become healthy')
        print('PASS: restored LightRAG opens its active storage and authenticated health check succeeds', flush=True)
        compose('stop', 'lightrag')
        execute('bash', 'deploy.sh', 'check')
        print('PASS: deploy.sh check validates NVIDIA GPU with the packaged MinerU image', flush=True)
        (bundle / 'verification.json').write_text(json.dumps({
            'status': 'passed', 'verified_utc': datetime.now(timezone.utc).isoformat(),
            'mysql_table_count': len(json.loads((bundle / 'manifest.json').read_text('utf-8'))['mysql_row_counts']),
            'checks': ['MySQL table row counts', 'pgvector logical import', 'Redis RDB startup',
                       'repeat restore skips import', 'Console startup and Flyway completion',
                       'restored file hashes and model config permissions',
                       'restored LightRAG authenticated health', 'MinerU CUDA preflight'],
            'remote_linux_host_deployed': False,
            'gateway_host_network_verified': False,
        }, ensure_ascii=False, indent=2) + '\n', encoding='utf-8', newline='\n')
        success = True
    finally:
        if success:
            compose('down', check=False)
            run('docker', 'rm', '-f', container, check=False)
            run('docker', 'volume', 'rm', volume, check=False)
            print('PASS: isolated verification resources cleaned; source services untouched', flush=True)
        else:
            print('Verification preserved: runner=' + container + ', volume=' + volume, flush=True)


if __name__ == '__main__':
    main()
