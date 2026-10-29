#!/usr/bin/env python3
"""Export the installed single-host platform and its actual mounts for Linux GPU."""

import argparse
from contextlib import contextmanager
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import secrets
import shutil
import signal
import socket
import subprocess
import sys
import tarfile
import tempfile
import time
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parents[1]
PLATFORM = tuple('gmd-faq-' + name for name in (
    'mysql', 'redis', 'pgvector', 'console', 'runtime', 'proxy', 'websocket', 'gateway', 'frontend'))
APPS = PLATFORM[3:]
BUILD_SERVICES = (*APPS, 'lightrag', 'mineru')
LOCAL_HOSTS = {'localhost', '127.0.0.1', 'host.docker.internal', 'lightrag', 'lightrag-lightrag-1'}


def say(message):
    print(message, flush=True)


def run(args, *, output=None, input_data=None, env=None, check=True):
    """Never print argv or stderr: Docker configuration can contain credentials."""
    result = subprocess.run([str(x) for x in args], input=input_data, stdout=output or subprocess.PIPE,
                            stderr=subprocess.PIPE, env=env)
    if check and result.returncode:
        raise RuntimeError(f'{args[0]} {args[1]} failed (exit {result.returncode}); '
                           'inspect the relevant container/build log without sharing credentials')
    return result


def docker(*args, **kwargs):
    return run(['docker', *args], **kwargs)


def inspect(name):
    return json.loads(docker('inspect', name).stdout)[0]


def environment(container):
    return dict(item.split('=', 1) for item in container['Config'].get('Env', []) if '=' in item)


def write_json(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8', newline='\n')
    path.chmod(0o600)


def sha256(path):
    result = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(4 * 1024 * 1024), b''):
            result.update(block)
    return result.hexdigest()


def private_archive_member(member):
    """Windows chmod cannot express POSIX ownership/mode; normalize the outer tar."""
    member.uid = member.gid = 0
    member.uname = member.gname = 'root'
    member.mode = 0o700 if member.isdir() or member.name.endswith('.sh') else 0o600
    return member


def sql_identifier(value):
    if not re.fullmatch(r'[A-Za-z_][A-Za-z0-9_]*', value):
        raise ValueError('Invalid SQL identifier')
    return '`' + value + '`'


def sql_string(value):
    # Hex literals avoid both SQL mode and command-line escaping problems.
    return "CONVERT(X'" + value.encode('utf-8').hex() + "' USING utf8mb4)"


def table_constants():
    path = ROOT / 'GMD-FAQ-COMMON-BASE/src/main/java/com/faq/server/main/common/consts/TableConst.java'
    values = dict(re.findall(r'public static final String (\w+)\s*=\s*"([^"]+)";', path.read_text('utf-8')))
    for value in values.values():
        sql_identifier(value)
    return values


def matching_mount(container, destinations, required=True):
    matches = [m for m in container['Mounts'] if m['Destination'] in destinations]
    if len(matches) != 1:
        if not matches and not required:
            return None
        raise ValueError(f"{container['Name']}: expected one data mount at {destinations}")
    return matches[0]


def mount_identity(mount):
    return mount.get('Name') if mount['Type'] == 'volume' else mount['Source'].replace('\\', '/').rstrip('/').lower()


def mounts_overlap(left, right):
    if left['Type'] != right['Type']:
        return False
    a, b = mount_identity(left), mount_identity(right)
    return a == b or (left['Type'] == 'bind' and (a.startswith(b + '/') or b.startswith(a + '/')))


def require_no_other_writers(sources, protected):
    projects = {v['Config'].get('Labels', {}).get('com.docker.compose.project') for v in sources.values()}
    projects.discard(None)
    names = docker('ps', '-q').stdout.decode().split()
    for name in names:
        item = inspect(name)
        if item['Id'] in {v['Id'] for v in sources.values()}:
            continue
        if item['Config'].get('Labels', {}).get('com.docker.compose.project') in projects:
            raise ValueError(f"Unmanaged running service belongs to a source stack: {item['Name']}; stop it first")
        if any(m.get('RW', False) and mounts_overlap(m, p) for m in item['Mounts'] for p in protected):
            raise ValueError(f"Unmanaged running writer shares source storage: {item['Name']}; stop it first")


@contextmanager
def quiesce(sources, state_file):
    """Restore the initial running/stopped state even when dumping fails."""
    # Building can take time; capture state at the actual backup boundary.
    for name, item in sources.items():
        current = inspect(item['Id'])['State']
        if current.get('Paused') or current.get('Restarting'):
            raise ValueError(f'{name} is paused/restarting at the backup boundary')
        item['State'] = current
    initial = {name: bool(item['State']['Running']) for name, item in sources.items()}
    write_json(state_file, {'initial_running': initial, 'restored': False})
    try:
        for name in ('gmd-faq-frontend', 'gmd-faq-gateway', 'gmd-faq-console', 'gmd-faq-runtime',
                     'gmd-faq-websocket', 'gmd-faq-proxy', 'lightrag', 'mineru'):
            if initial.get(name):
                docker('stop', '-t', '90', sources[name]['Id'])
                if inspect(sources[name]['Id'])['State']['ExitCode'] == 137:
                    raise ValueError(f'{name} did not stop gracefully; backup was cancelled')
        yield
    finally:
        failures = []
        # Stop temporarily started middleware before restarting applications.
        for name in PLATFORM[:3]:
            if name in sources and not initial[name]:
                if inspect(sources[name]['Id'])['State']['Running']:
                    if docker('stop', '-t', '60', sources[name]['Id'], check=False).returncode:
                        failures.append(name)
        for name in [*PLATFORM[:3], 'mineru', 'lightrag', *APPS]:
            if initial.get(name) and not inspect(sources[name]['Id'])['State']['Running']:
                if docker('start', sources[name]['Id'], check=False).returncode:
                    failures.append(name)
        write_json(state_file, {'initial_running': initial, 'restored': not failures,
                                'restore_failures': failures})
        if failures:
            raise RuntimeError('Could not restore initial source state: ' + ', '.join(failures))


def wait_exec(container, command, timeout=180):
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        if docker('exec', container['Id'], 'sh', '-c', command, check=False).returncode == 0:
            return
        time.sleep(2)
    raise TimeoutError(f"Database readiness timed out: {container['Name']}")


def mysql_query(container, query):
    command = 'export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"; exec mysql -uroot --default-character-set=utf8mb4 --batch --raw --skip-column-names "$MYSQL_DATABASE"'
    return docker('exec', '-i', container['Id'], 'sh', '-c', command,
                  input_data=query.encode('utf-8')).stdout.decode('utf-8')


def source_lightrag_env(container):
    # Use the installed dotenv parser, matching LightRAG's override=False behavior.
    code = ('import json,os; from dotenv import dotenv_values; '
            'print(json.dumps(dict(dotenv_values("/app/.env"))))')
    result = docker('run', '--rm', '--network', 'none', '--volumes-from', container['Id'] + ':ro',
                    '--entrypoint', 'python', container['Image'], '-c', code)
    values = {k: v for k, v in json.loads(result.stdout).items() if v is not None}
    values.update(environment(container))
    expected = {'LIGHTRAG_KV_STORAGE': 'JsonKVStorage', 'LIGHTRAG_DOC_STATUS_STORAGE': 'JsonDocStatusStorage',
                'LIGHTRAG_GRAPH_STORAGE': 'NetworkXStorage', 'LIGHTRAG_VECTOR_STORAGE': 'NanoVectorDBStorage'}
    for key, default in expected.items():
        if values.get(key, default) != default:
            raise ValueError(f'{key}: this single-host bundle supports file-backed LightRAG only; '
                             'external storage must be migrated separately')
    return values


def connection_updates(rows, constants, source_names, api_key):
    statements, matched = [], []
    allowed = LOCAL_HOSTS | set(source_names)
    for line in rows.splitlines():
        row = json.loads(line)
        cfg = row['connection_config']
        if isinstance(cfg, str):
            cfg = json.loads(cfg)
        cfg = cfg or {}
        address = urlsplit(cfg.get('baseUrl', ''))
        if address.hostname not in allowed or address.port not in (9621, None):
            # Do not silently leave a second/local or remote instance out of a full migration.
            raise ValueError(f"Knowledge base {row['id']} points to another LightRAG instance; "
                             'migrate that instance separately before creating a single-instance bundle')
        if address.path.rstrip('/') not in ('', '/api'):
            raise ValueError('Custom LightRAG reverse-proxy path needs explicit migration')
        for key in ('id', 'tenant_id'):
            if not re.fullmatch(r'\d+', str(row[key])):
                raise ValueError('Invalid knowledge base identity')
        table = sql_identifier(constants['KNOWLEDGE'])
        statements.append(f'UPDATE {table} SET connection_config = JSON_SET(connection_config, '
                          f"'$.baseUrl', {sql_string('http://lightrag:9621')}, '$.apiKey', {sql_string(api_key)}) "
                          f"WHERE id = {row['id']} AND tenant_id = {row['tenant_id']} AND kb_type = 'LIGHTRAG';")
        matched.append({'id': str(row['id']), 'tenant_id': str(row['tenant_id'])})
    return '\n'.join(statements) + '\n', matched


def template_config(args, sources, rag_env, temporary):
    overlay = (ROOT / 'docker/docker-compose-linux-gpu.yml').read_text('utf-8')
    model_file = temporary / 'lightrag.env'
    model_file.write_text('', encoding='utf-8')
    overlay = overlay.replace('./lightrag.env.linux-gpu', model_file.as_posix())
    overlay_file = temporary / 'overlay.yml'
    overlay_file.write_text(overlay, encoding='utf-8')
    env = os.environ.copy()
    # Do not inherit unrelated shell overrides into a release.
    for key in re.findall(r'\$\{([A-Z0-9_]+)', (ROOT / 'docker/docker-compose-simple.yml').read_text('utf-8') + overlay):
        env.pop(key, None)
    db, redis, pg = (environment(sources[name]) for name in PLATFORM[:3])
    console = environment(sources['gmd-faq-console'])
    runtime = environment(sources['gmd-faq-runtime'])
    db_name = console.get('MYSQL_DATABASE', db.get('MYSQL_DATABASE', ''))
    if db_name != db.get('MYSQL_DATABASE') or runtime.get('MYSQL_DATABASE') != db_name:
        raise ValueError('The selected MySQL container is not the platform database')
    if console.get('MYSQL_USER', 'root') != 'root':
        raise ValueError('This bundle currently requires the source single-host root MySQL account')
    for name in ('gmd-faq-console', 'gmd-faq-runtime', 'gmd-faq-gateway'):
        values = environment(sources[name])
        if values.get('MYSQL_PASSWORD') != db['MYSQL_ROOT_PASSWORD']:
            raise ValueError('Platform and selected MySQL credentials do not match')
        if values.get('REDIS_PASSWORD') != redis['REDIS_PASSWORD']:
            raise ValueError('Platform and selected Redis credentials do not match')
        if values.get('JWT_SECRET') != console.get('JWT_SECRET') or not values.get('JWT_SECRET'):
            raise ValueError('JWT secret must be nonempty and identical across platform services')
    sql_identifier(db_name)
    if runtime.get('PG_DATABASE') != pg['POSTGRES_DB'] or runtime.get('PG_PASSWORD') != pg['POSTGRES_PASSWORD']:
        raise ValueError('The selected pgvector container is not the platform vector database')
    key = rag_env.get('LIGHTRAG_API_KEY') or secrets.token_hex(32)
    env.update(MYSQL_ROOT_PASSWORD=db['MYSQL_ROOT_PASSWORD'], MYSQL_PASSWORD=db['MYSQL_ROOT_PASSWORD'],
               MYSQL_DATABASE=db_name, MYSQL_USER='root', REDIS_PASSWORD=redis['REDIS_PASSWORD'],
               PG_DATABASE=pg['POSTGRES_DB'], PG_USER=pg['POSTGRES_USER'], PG_PASSWORD=pg['POSTGRES_PASSWORD'],
               JWT_SECRET=console['JWT_SECRET'], LIGHTRAG_API_KEY=key,
               LIGHTRAG_WORKSPACE=rag_env.get('WORKSPACE', ''),
               MINERU_MODELS_DIR=args.mineru_models_dir.as_posix(), LIGHTRAG_SOURCE_DIR=args.lightrag_dir.as_posix())
    result = docker('compose', '--project-directory', ROOT / 'docker', '--env-file',
                    ROOT / 'docker/.env.linux-gpu.example', '-f', ROOT / 'docker/docker-compose-simple.yml',
                    '-f', overlay_file, 'config', '--format', 'json', env=env)
    return json.loads(result.stdout), key


def literal(value):
    """Compose re-interpolates generated JSON; protect all literal dollar signs."""
    if isinstance(value, str):
        return value.replace('$', '$$')
    if isinstance(value, list):
        return [literal(v) for v in value]
    if isinstance(value, dict):
        return {k: literal(v) for k, v in value.items()}
    return value


def target_config(template, sources, rag_env, images, old_workspace):
    config = literal(template)
    config.pop('name', None)
    config['networks']['gmd_faq_simple']['name'] = '${COMPOSE_PROJECT_NAME:-gmd-migrated}_net'
    for name, service in config['services'].items():
        # Compose config emits escaped dollars in command/entrypoint/healthcheck.
        # Preserve that representation, rather than escaping it a second time.
        for field in ('command', 'entrypoint', 'healthcheck'):
            if field in template['services'][name]:
                service[field] = template['services'][name][field]
        service.pop('build', None)
        service.pop('container_name', None)
        service['image'] = images[name]['tag']
        service['pull_policy'] = 'never'
        values = service.setdefault('environment', {})
        if name in sources and name in APPS:
            source_env = environment(sources[name])
            # Preserve runtime settings, including secrets; use current template for routing.
            routing = {k for k in values if k.endswith(('_HOST', '_PORT')) or k.startswith('GMD_FAQ_')}
            for key in values.keys() & source_env.keys() - routing:
                values[key] = literal(source_env[key])
        if name == 'lightrag':
            baked = {'PATH', 'HOME', 'HOSTNAME', 'LANG', 'GPG_KEY', 'PYTHON_VERSION', 'PYTHON_SHA256',
                     'PIP_CACHE_DIR', 'XDG_CACHE_HOME', 'TIKTOKEN_CACHE_DIR', 'PIP_TRUSTED_HOST',
                     'UI_TEMPLATES_DIR'}
            # Current template wins only for routing, parser and managed container paths.
            values = {**{k: literal(v) for k, v in rag_env.items() if k not in baked}, **values}
            service['environment'] = values
            service.pop('env_file', None)
        if name == 'gmd-faq-redis':
            service['command'] = ['sh', '-c', 'exec redis-server --requirepass "$$REDIS_PASSWORD"']
        for volume in service.get('volumes', [])[:]:
            dest = volume['target']
            if dest.startswith('/docker-entrypoint-initdb.d'):
                service['volumes'].remove(volume)  # Never run the destructive V1 baseline over the dump.
                continue
            mapping = {'/var/lib/mysql': './data/mysql_data', '/var/lib/postgresql/data': './data/pgvector_data',
                       '/data': './data/redis_data', '/app/.baibaiyu': './data/workspace',
                       '/opt/tools': './data/proxy-tools' if name == 'gmd-faq-proxy' else './data/runtime-tools',
                       '/models': './data/mineru/models', '/app/output': './data/mineru/output',
                       '/app/cache': './data/mineru/cache', '/var/log/nginx': './logs/frontend'}
            if dest in mapping:
                volume['source'] = mapping[dest]
            elif dest.startswith('/app/data/') and name == 'lightrag':
                volume['source'] = './logs/lightrag' if dest.endswith('/logs') else './data/lightrag/' + dest.rsplit('/', 1)[-1]
            elif dest == '/app/logs':
                volume['source'] = './logs/' + name.removeprefix('gmd-faq-')
            else:
                raise ValueError(f'Unmapped deployment mount: {name}: {dest}')
        if old_workspace != '/app/.baibaiyu' and any(v['target'] == '/app/.baibaiyu' for v in service.get('volumes', [])):
            service['volumes'].append({'type': 'bind', 'source': './data/workspace', 'target': old_workspace})
        if 'GMD_FAQ_HOST_IP' in values:
            values['GMD_FAQ_HOST_IP'] = '${BUNDLE_HOST_IP:?Set BUNDLE_HOST_IP to the server address}'
            values['GMD_FAQ_HOST_NAME'] = '${BUNDLE_HOST_NAME:-linux-gpu}'
        if 'GMD_FAQ_NODE_ID' in values:
            values['GMD_FAQ_NODE_ID'] = 'linux-gpu-' + name.removeprefix('gmd-faq-')
        if name == 'gmd-faq-frontend':
            service['ports'][0]['published'] = '${FRONTEND_PORT:-80}'
        # Local storage/API ports also serve the host-network gateway.
        port_keys = {'gmd-faq-mysql': 'MYSQL_PORT', 'gmd-faq-redis': 'REDIS_PORT', 'gmd-faq-pgvector': 'PG_PORT'}
        if name in port_keys:
            service['ports'][0]['published'] = '${' + port_keys[name] + ':-' + str(service['ports'][0]['target']) + '}'
        if name == 'gmd-faq-gateway':
            for key in ('MYSQL_PORT', 'REDIS_PORT'):
                values[key] = '${' + key + ':-' + ('3306' if key == 'MYSQL_PORT' else '6379') + '}'
        if name == 'mineru':
            service['deploy']['resources']['reservations']['devices'][0]['device_ids'] = ['${MINERU_GPU_ID:-0}']
        for volume in service.get('volumes', []):
            volume['bind'] = {'create_host_path': False}
    return config


def build_images(args, template, sources, bundle_id, output):
    mapping = json.loads(args.image_map.read_text('utf-8')) if args.image_map else None
    if mapping is not None and set(mapping) != set(BUILD_SERVICES):
        raise ValueError('--image-map must explicitly map all eight application services (six platform, LightRAG, MinerU)')
    if mapping:
        say('Using explicitly supplied images; their source freshness is the caller\'s responsibility.')
    records = {}
    for name in template['services']:
        tag = f'gmd-bundle/{name}:{bundle_id}'
        if name in BUILD_SERVICES and mapping is None:
            say('Building latest source: ' + name)
            build = template['services'][name]['build']
            command = ['build', '--platform', 'linux/amd64', '-t', tag, '-f',
                       str(Path(build['context']) / build['dockerfile'])]
            for key, value in build.get('args', {}).items():
                command.extend(['--build-arg', key + '=' + str(value)])
            command.append(build['context'])
            with (output / 'build.log').open('ab') as log:
                result = subprocess.run(['docker', *command], stdout=log, stderr=subprocess.STDOUT)
            if result.returncode:
                raise RuntimeError(f'Build failed: {name}; see build.log')
        else:
            original = mapping[name] if name in BUILD_SERVICES else sources[name]['Image']
            docker('tag', original, tag)
        image = json.loads(docker('image', 'inspect', tag).stdout)[0]
        if image['Os'] != 'linux' or image['Architecture'] != 'amd64':
            raise ValueError(f'Image must be linux/amd64: {name}')
        records[name] = {'tag': tag, 'id': image['Id'], 'size': image['Size']}
    say('Saving all 11 offline images (shared layers are stored once)...')
    docker('image', 'save', '-o', output / 'images.tar', *(v['tag'] for v in records.values()))
    return records


def archive_mount(helper, source, mount, output, *, host_path=None):
    command = ['run', '--rm', '--network', 'none', '--user', '0', '--entrypoint', 'python',
               '--mount', f'type=bind,source={ROOT / "docker/bundle/files.py"},target=/files.py,readonly']
    if host_path is not None:
        command += ['--mount', f'type=bind,source={host_path},target=/source,readonly']
        root = '/source'
    else:
        command += ['--volumes-from', source['Id'] + ':ro']
        root = mount['Destination']
    command += [helper, '/files.py', 'pack', root, '-']
    with output.open('xb') as stream:
        result = docker(*command, output=stream)
    return json.loads(result.stderr)


def export(args):
    info = json.loads(docker('info', '--format', '{{json .}}').stdout)
    if info['OSType'] != 'linux' or info['Architecture'] not in ('x86_64', 'amd64'):
        raise ValueError('Export requires an amd64 Linux Docker Engine (Docker Desktop Linux containers are supported)')
    sources = {name: inspect(name) for name in PLATFORM}
    rag_name = args.lightrag_container
    if not rag_name:
        candidates = docker('ps', '-aq', '--filter', 'label=com.docker.compose.service=lightrag').stdout.decode().split()
        if len(candidates) != 1:
            raise ValueError('Specify --lightrag-container when LightRAG discovery is ambiguous')
        rag_name = candidates[0]
    sources['lightrag'] = inspect(rag_name)
    for item in sources.values():
        if item['State'].get('Paused') or item['State'].get('Restarting'):
            raise ValueError('Paused/restarting source containers must be stabilized before export')
    rag_env = source_lightrag_env(sources['lightrag'])
    workspace = matching_mount(sources['gmd-faq-runtime'], ('/app/.baibaiyu', '/app/.apboa'))
    mounts = [('workspace', sources['gmd-faq-runtime'], workspace, 'data/workspace')]
    for name in ('gmd-faq-console', 'gmd-faq-proxy'):
        other = matching_mount(sources[name], ('/app/.baibaiyu', '/app/.apboa'))
        if not mounts_overlap(workspace, other):
            raise ValueError('Platform workspace mounts differ; consolidate them before exporting')
    for name in ('runtime', 'proxy'):
        source = sources['gmd-faq-' + name]
        mounts.append((name + '-tools', source, matching_mount(source, ('/opt/tools',)), 'data/' + name + '-tools'))
    for name, env_key in (('rag_storage', 'WORKING_DIR'), ('inputs', 'INPUT_DIR'), ('prompts', 'PROMPT_DIR')):
        dest = rag_env.get(env_key, '/app/data/' + name)
        mounts.append(('lightrag-' + name, sources['lightrag'],
                       matching_mount(sources['lightrag'], (dest,)), 'data/lightrag/' + name))
        if dest != '/app/data/' + name:
            raise ValueError('Custom LightRAG data paths require a path-preserving migration')
    for name in ('Layout', 'MFR', 'OCR', 'TabCls', 'TabRec'):
        if not (args.mineru_models_dir / 'models' / name).is_dir():
            raise ValueError('MinerU model snapshot is incomplete: ' + name)
    protected = [m for _, _, m, _ in mounts] + [m for n in PLATFORM[:3] for m in sources[n]['Mounts']]
    require_no_other_writers(sources, protected)
    output = args.output
    if output.exists():
        raise ValueError('Output already exists; choose a new bundle directory')
    archive_suffix = '.tar.gz' if args.gzip else '.tar'
    if not args.no_archive and Path(str(output) + archive_suffix).exists():
        raise ValueError('Final archive already exists; choose a new bundle directory')
    if shutil.disk_usage(output.parent if output.parent.exists() else ROOT).free < 35 * 1024**3:
        raise ValueError('Allow at least 35 GiB of free disk for images, data and the final archive')
    output.mkdir(parents=True, mode=0o700)
    (output / 'backup').mkdir()
    bundle_id = datetime.now(timezone.utc).strftime('%Y%m%d-%H%M%S') + '-' + secrets.token_hex(3)
    say('Actual LightRAG volume: ' + mount_identity(mounts[3][2]))
    say('Platform workspace container path: ' + workspace['Destination'])
    with tempfile.TemporaryDirectory(prefix='gmd-bundle-') as scratch:
        template, key = template_config(args, sources, rag_env, Path(scratch))
        images = build_images(args, template, sources, bundle_id, output)
    helper = images['lightrag']['tag']
    archives = []
    say('Saving MinerU model snapshot before pausing writers...')
    record = archive_mount(helper, None, None, output / 'backup/mineru-models.tar', host_path=args.mineru_models_dir)
    archives.append({'file': 'backup/mineru-models.tar', 'destination': 'data/mineru/models', **record})
    constants = table_constants()
    say('Entering consistent backup window; original service state will be restored on exit.')
    with quiesce(sources, output / 'source-state.json'):
        require_no_other_writers(sources, protected)
        mysql, redis, pg = (sources[name] for name in PLATFORM[:3])
        if not inspect(mysql['Id'])['State']['Running']:
            docker('start', mysql['Id'])
        wait_exec(mysql, 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -N -e "SELECT 1" "$MYSQL_DATABASE"')
        say('Exporting MySQL (all platform IDs and Flyway history are preserved)...')
        query = ('SELECT JSON_OBJECT(\'id\', CAST(id AS CHAR), \'tenant_id\', CAST(tenant_id AS CHAR), '
                 "'connection_config', connection_config) FROM " + sql_identifier(constants['KNOWLEDGE']) +
                 " WHERE kb_type = 'LIGHTRAG';")
        rows = mysql_query(mysql, query)
        source_hosts = {sources['lightrag']['Name'].lstrip('/'), sources['lightrag']['Config']['Hostname']}
        source_hosts.update(n['IPAddress'] for n in sources['lightrag']['NetworkSettings']['Networks'].values()
                            if n.get('IPAddress'))
        try:
            source_hosts.update(socket.gethostbyname_ex(socket.gethostname())[2])
        except OSError:
            pass
        updates, knowledge_ids = connection_updates(rows, constants,
                            source_hosts, key)
        (output / 'backup/relocate.sql').write_text(updates, encoding='utf-8', newline='\n')
        mysql_tables = mysql_query(mysql, 'SHOW TABLES;').splitlines()
        counts = {}
        for table in mysql_tables:
            counts[table] = int(mysql_query(mysql, 'SELECT COUNT(*) FROM ' + sql_identifier(table) + ';').strip())
        (output / 'backup/verify-mysql.sql').write_text('\n'.join(
            "SELECT JSON_OBJECT('table', " + sql_string(table) + ", 'rows', COUNT(*)) FROM " + sql_identifier(table) + ';'
            for table in mysql_tables) + '\n', encoding='utf-8', newline='\n')
        command = ('export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"; exec mysqldump -uroot '
                   '--single-transaction --routines --events --triggers --hex-blob '
                   '--set-gtid-purged=OFF --no-tablespaces --default-character-set=utf8mb4 "$MYSQL_DATABASE"')
        with (output / 'backup/mysql.sql').open('xb') as stream:
            docker('exec', mysql['Id'], 'sh', '-c', command, output=stream)
        if not sources['gmd-faq-mysql']['State']['Running']:
            docker('stop', '-t', '60', mysql['Id'])
        if not inspect(pg['Id'])['State']['Running']:
            docker('start', pg['Id'])
        wait_exec(pg, 'pg_isready -U "$POSTGRES_USER" -d "$POSTGRES_DB"')
        say('Exporting pgvector...')
        with (output / 'backup/pgvector.dump').open('xb') as stream:
            docker('exec', pg['Id'], 'sh', '-c', 'exec pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc', output=stream)
        if not sources['gmd-faq-pgvector']['State']['Running']:
            docker('stop', '-t', '60', pg['Id'])
        if not inspect(redis['Id'])['State']['Running']:
            docker('start', redis['Id'])
        wait_exec(redis, 'REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli ping')
        say('Exporting Redis RDB (all logical databases)...')
        rdb_name = '/tmp/gmd-bundle-' + bundle_id + '.rdb'
        docker('exec', redis['Id'], 'sh', '-c',
               'export REDISCLI_AUTH="$REDIS_PASSWORD"; exec redis-cli --rdb "$1"', 'sh', rdb_name)
        docker('cp', redis['Id'] + ':' + rdb_name, output / 'backup/redis.rdb')
        docker('exec', redis['Id'], 'rm', rdb_name)
        redis_ids = docker('exec', redis['Id'], 'sh', '-c', 'id -u redis; id -g redis').stdout.decode().split()
        if not sources['gmd-faq-redis']['State']['Running']:
            docker('stop', '-t', '60', redis['Id'])
        for name, source, mount, dest in mounts:
            say('Archiving actual mounted files: ' + name)
            record = archive_mount(helper, source, mount, output / 'backup' / (name + '.tar'))
            archives.append({'file': 'backup/' + name + '.tar', 'destination': dest,
                             'source_type': mount['Type'], 'source': mount_identity(mount), **record})
    say('Source running/stopped states restored. Finalizing portable configuration and checksums...')
    config = target_config(template, sources, rag_env, images, workspace['Destination'])
    write_json(output / 'compose.json', config)
    for name in ('files.py', 'deploy.sh', 'network.py', 'network-check.sh'):
        content = (ROOT / 'docker/bundle' / name).read_text('utf-8')
        (output / name).write_text(content, encoding='utf-8', newline='\n')
        (output / name).chmod(0o700 if name.endswith('.sh') else 0o600)
    (output / 'helper.image').write_text(helper + '\n', encoding='ascii', newline='\n')
    (output / 'settings.env').write_text(
        'COMPOSE_PROJECT_NAME=gmd-migrated\nFRONTEND_PORT=80\nMYSQL_PORT=3306\nREDIS_PORT=6379\n'
        'PG_PORT=5432\nMINERU_GPU_ID=0\n', encoding='utf-8', newline='\n')
    (output / 'README.txt').write_text(
        'Linux x86_64 + NVIDIA GPU offline migration\n'
        'Prerequisites: Docker Engine, Compose >= 2.24.4, NVIDIA driver and NVIDIA Container Toolkit.\n'
        'Extract with tar -xf <bundle.tar>, then run: BUNDLE_HOST_IP=<server-ip> bash deploy.sh\n'
        'Existing data/ is never overwritten. See deploy.sh help and deployment-bundle.md.\n'
        'This bundle contains database records and secrets. Restrict access and transfer over SSH/SFTP.\n', encoding='utf-8')
    shutil.copyfile(ROOT / 'docker/deployment-bundle.md', output / 'deployment-bundle.md')
    notices = (ROOT / 'THIRD_PARTY_NOTICES.md').read_text('utf-8')
    for old, new in (('(LICENSE)', '(licenses/platform-LICENSE.txt)'),
                     ('(services/lightrag/LICENSE)', '(licenses/lightrag-LICENSE.txt)'),
                     ('(services/mineru/LICENSE.md)', '(licenses/mineru-LICENSE.md)')):
        notices = notices.replace(old, new)
    (output / 'THIRD_PARTY_NOTICES.md').write_text(notices, encoding='utf-8', newline='\n')
    license_dir = output / 'licenses'
    license_dir.mkdir()
    for source, name in ((ROOT / 'LICENSE', 'platform-LICENSE.txt'),
                         (ROOT / 'services/lightrag/LICENSE', 'lightrag-LICENSE.txt'),
                         (ROOT / 'services/mineru/LICENSE.md', 'mineru-LICENSE.md')):
        shutil.copyfile(source, license_dir / name)
    guide = (ROOT / 'docker/linux-gpu.md').read_text('utf-8')
    preparation = guide.split('## 服务器准备\n', 1)[1].split('## 上传内容\n', 1)[0]
    (output / 'linux-gpu.md').write_text('# Linux GPU 服务器准备\n\n' + preparation, encoding='utf-8', newline='\n')
    manifest = {'format': 1, 'bundle_id': bundle_id, 'created_utc': datetime.now(timezone.utc).isoformat(),
                'platform': 'linux/amd64', 'images': images, 'archives': archives,
                'redis_uid_gid': [int(x) for x in redis_ids], 'mysql_row_counts': counts,
                'relocated_knowledge_bases': knowledge_ids, 'lightrag_workspace': rag_env.get('WORKSPACE', ''),
                'images_built_from_source': not bool(args.image_map)}
    write_json(output / 'manifest.json', manifest)
    for path in output.rglob('*'):
        if path.is_file():
            path.chmod(0o600 if path.suffix != '.sh' else 0o700)
    # settings.env is deliberately editable for destination ports/project/GPU.
    checked = [p for p in sorted(output.rglob('*')) if p.is_file() and p.name != 'settings.env']
    checksums = '\n'.join(sha256(p) + '  ' + p.relative_to(output).as_posix() for p in checked) + '\n'
    (output / 'SHA256SUMS').write_text(checksums, encoding='ascii', newline='\n')
    docker('compose', '--env-file', output / 'settings.env', '-f', output / 'compose.json', 'config', '--quiet',
           env={**os.environ, 'BUNDLE_HOST_IP': '192.0.2.1'})
    if not args.no_archive:
        destination = Path(str(output) + archive_suffix)
        if destination.exists():
            raise ValueError('Final archive already exists; exported directory is preserved')
        say('Creating the complete deployment package (large images may take several minutes)...')
        options = {'compresslevel': 1} if args.gzip else {}
        with tarfile.open(destination, 'x:gz' if args.gzip else 'x', **options) as archive:
            archive.add(output, arcname=output.name, filter=private_archive_member)
        destination.chmod(0o600)
        say(f'Complete: {destination} ({destination.stat().st_size / 1024**3:.2f} GiB)')
    say('Exported directory: ' + str(output))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, default=ROOT / 'output' / ('gmd-linux-gpu-' + datetime.now().strftime('%Y%m%d-%H%M%S')))
    parser.add_argument('--lightrag-dir', type=Path, default=ROOT / 'services/lightrag')
    parser.add_argument('--mineru-models-dir', type=Path, default=ROOT / 'models/mineru')
    parser.add_argument('--lightrag-container')
    parser.add_argument('--image-map', type=Path, help='Explicitly reuse all eight application images; does not build source')
    parser.add_argument('--no-archive', action='store_true', help='Keep the complete folder without an outer archive')
    parser.add_argument('--gzip', action='store_true', help='Compress the outer tar archive (slower; images are already compressed)')
    args = parser.parse_args()
    for key in ('output', 'lightrag_dir', 'mineru_models_dir'):
        setattr(args, key, getattr(args, key).resolve())
    # Ctrl+C must pass through the backup context's finally block. SIGTERM from
    # an orchestrator can be handled too; an OS kill/power loss cannot.
    if hasattr(signal, 'SIGTERM'):
        def terminate(signum, frame):
            raise KeyboardInterrupt
        signal.signal(signal.SIGTERM, terminate)
    try:
        export(args)
    except (RuntimeError, ValueError, OSError, TimeoutError) as error:
        say('ERROR: ' + str(error))
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
