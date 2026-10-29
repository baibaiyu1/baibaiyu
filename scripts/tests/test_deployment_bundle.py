"""Focused checks for destructive-restore guards and migration invariants."""

import importlib.util
import io
import json
import os
from pathlib import Path
import tarfile
import tempfile
import threading
import unittest
from unittest.mock import patch
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ROOT = Path(__file__).resolve().parents[2]


def module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


bundle = module('deployment_bundle', ROOT / 'scripts/deployment_bundle.py')
files = module('bundle_files', ROOT / 'docker/bundle/files.py')
network = module('bundle_network', ROOT / 'docker/bundle/network.py')


class ArchiveTests(unittest.TestCase):
    def test_outer_package_has_private_linux_permissions_on_windows_too(self):
        for name, kind, expected in (('package/', tarfile.DIRTYPE, 0o700),
                                     ('package/deploy.sh', tarfile.REGTYPE, 0o700),
                                     ('package/compose.json', tarfile.REGTYPE, 0o600)):
            member = tarfile.TarInfo(name)
            member.type, member.mode, member.uid, member.gid = kind, 0o777, 1234, 5678
            secured = bundle.private_archive_member(member)
            self.assertEqual((secured.mode, secured.uid, secured.gid), (expected, 0, 0))

    def test_round_trip_unicode_binary_and_empty_directory(self):
        with tempfile.TemporaryDirectory() as scratch:
            root = Path(scratch)
            source = root / 'source'
            (source / '空目录').mkdir(parents=True)
            (source / 'platform_models_default.json').write_bytes(b'{"credential":"redacted"}\n')
            (source / 'image.bin').write_bytes(bytes(range(256)) * 900)
            files.pack(source, root / 'backup.tar')
            files.unpack(root / 'backup.tar', root / 'restored')
            self.assertEqual((source / 'image.bin').read_bytes(), (root / 'restored/image.bin').read_bytes())
            self.assertTrue((root / 'restored/空目录').is_dir())
            with self.assertRaisesRegex(ValueError, 'nonempty'):
                files.unpack(root / 'backup.tar', root / 'restored')

    def test_traversal_devices_and_external_symlinks_are_rejected(self):
        with tempfile.TemporaryDirectory() as scratch:
            root = Path(scratch)
            for number, name in enumerate(('../escaped', '/absolute', 'a/../../escaped', 'a\\..\\escaped')):
                archive = root / f'{number}.tar'
                with tarfile.open(archive, 'w') as output:
                    output.addfile(tarfile.TarInfo(name), io.BytesIO())
                with self.assertRaises(ValueError):
                    files.unpack(archive, root / str(number))
            for link in ('/etc/passwd', '../../outside'):
                member = tarfile.TarInfo('folder/link')
                member.type, member.linkname = tarfile.SYMTYPE, link
                with self.assertRaises(ValueError):
                    files.validate_member(member)
            member = tarfile.TarInfo('device')
            member.type = tarfile.CHRTYPE
            with self.assertRaises(ValueError):
                files.validate_member(member)

    def test_binary_archive_stream_does_not_use_text_redirection(self):
        with tempfile.TemporaryDirectory() as scratch:
            root = Path(scratch)
            (root / 'payload').write_bytes(bytes(range(256)))
            output = io.BytesIO()
            files.pack(root, output)
            with tarfile.open(fileobj=io.BytesIO(output.getvalue())) as archive:
                self.assertEqual(archive.extractfile('payload').read(), bytes(range(256)))

    @unittest.skipIf(os.name == 'nt', 'Linux symlink extraction is checked by the Docker smoke test')
    def test_archive_cannot_write_through_a_symlink(self):
        with tempfile.TemporaryDirectory() as scratch:
            root = Path(scratch)
            with tarfile.open(root / 'attack.tar', 'w') as archive:
                directory = tarfile.TarInfo('real')
                directory.type = tarfile.DIRTYPE
                archive.addfile(directory)
                link = tarfile.TarInfo('alias')
                link.type, link.linkname = tarfile.SYMTYPE, 'real'
                archive.addfile(link)
                archive.addfile(tarfile.TarInfo('alias/injected'), io.BytesIO())
            with self.assertRaisesRegex(ValueError, 'symlink'):
                files.unpack(root / 'attack.tar', root / 'target')


class MigrationTests(unittest.TestCase):
    def test_restore_creates_mount_directories_before_containers_start(self):
        with tempfile.TemporaryDirectory() as scratch:
            root = Path(scratch)
            (root / 'backup').mkdir()
            (root / 'backup/redis.rdb').write_bytes(b'fixture-rdb')
            (root / 'manifest.json').write_text(json.dumps({
                'archives': [], 'redis_uid_gid': [999, 999], 'bundle_id': 'test'}))
            with patch.object(os, 'chown', create=True):
                files.restore(root)
            for path in ('logs/console', 'logs/runtime', 'logs/gateway', 'logs/proxy',
                         'logs/frontend', 'logs/lightrag', 'data/mysql_data', 'data/pgvector_data',
                         'data/lightrag/rag_storage', 'data/lightrag/inputs', 'data/lightrag/prompts',
                         'data/mineru/output', 'data/mineru/cache'):
                self.assertTrue((root / path).is_dir(), path)
            with self.assertRaisesRegex(ValueError, 'never overwritten'):
                files.restore(root)

    def test_scoped_knowledge_update_preserves_other_values_and_uses_tableconst(self):
        row = {'id': '9223372036854775806', 'tenant_id': '200', 'connection_config': {
            'baseUrl': 'http://host.docker.internal:9621', 'apiKey': 'old',
            'embeddingModelId': '123', 'platformModelsManaged': True}}
        original = json.dumps(row)
        sql, matched = bundle.connection_updates(original, {'KNOWLEDGE': 'knowledge_table'}, (), "new'$secret")
        self.assertIn('`knowledge_table`', sql)
        self.assertIn("AND tenant_id = 200 AND kb_type = 'LIGHTRAG'", sql)
        self.assertNotIn('embeddingModelId', sql)
        self.assertNotIn("new'$secret", sql)
        self.assertEqual(matched, [{'id': row['id'], 'tenant_id': row['tenant_id']}])
        self.assertEqual(original, json.dumps(row))
        row['connection_config']['baseUrl'] = 'https://another-server:9621'
        with self.assertRaisesRegex(ValueError, 'another LightRAG'):
            bundle.connection_updates(json.dumps(row), {'KNOWLEDGE': 'knowledge_table'}, (), 'key')

    def test_dollar_escaping_survives_nested_compose_content(self):
        self.assertEqual(bundle.literal({'environment': {'PASSWORD': 'a$b${HOME}'}, 'command': ['echo $PASSWORD']}),
                         {'environment': {'PASSWORD': 'a$$b$${HOME}'}, 'command': ['echo $$PASSWORD']})

    def test_mount_overlap_detects_parent_directory(self):
        self.assertTrue(bundle.mounts_overlap({'Type': 'bind', 'Source': '/data'}, {'Type': 'bind', 'Source': '/data/rag'}))
        self.assertFalse(bundle.mounts_overlap({'Type': 'bind', 'Source': '/data'}, {'Type': 'bind', 'Source': '/data-other'}))
        self.assertFalse(bundle.mounts_overlap({'Type': 'volume', 'Name': 'rag'}, {'Type': 'bind', 'Source': '/rag'}))

    def test_source_state_is_restored_after_backup_exception(self):
        names = ('gmd-faq-mysql', 'gmd-faq-pgvector', 'gmd-faq-redis', 'gmd-faq-console', 'lightrag')
        running = {name: name in ('gmd-faq-console', 'lightrag') for name in names}
        sources = {name: {'Id': name, 'State': {'Running': value}} for name, value in running.items()}
        initial = running.copy()

        def inspect(name):
            return {'State': {'Running': running[name], 'ExitCode': 0}}

        def docker(action, *args, **kwargs):
            name = args[-1]
            if action in ('stop', 'start'):
                running[name] = action == 'start'
            return type('Result', (), {'returncode': 0})()

        with tempfile.TemporaryDirectory() as scratch, patch.object(bundle, 'docker', docker), patch.object(bundle, 'inspect', inspect):
            state_file = Path(scratch) / 'state.json'
            with self.assertRaisesRegex(RuntimeError, 'dump failure'):
                with bundle.quiesce(sources, state_file):
                    self.assertFalse(running['lightrag'])
                    running['gmd-faq-mysql'] = True
                    raise RuntimeError('dump failure')
            self.assertEqual(running, initial)
            self.assertTrue(json.loads(state_file.read_text())['restored'])

    def test_count_mismatch_blocks_application_start(self):
        with tempfile.TemporaryDirectory() as scratch:
            root = Path(scratch)
            (root / 'manifest.json').write_text(json.dumps({'mysql_row_counts': {'knowledge': 3}}))
            (root / '.mysql-counts.jsonl').write_text(json.dumps({'table': 'knowledge', 'rows': 2}) + '\n')
            with self.assertRaisesRegex(ValueError, 'startup is blocked'):
                files.check_counts(root)


class NetworkTests(unittest.TestCase):
    def test_gateway_uses_published_db_port_and_its_actual_hosts_name(self):
        config = {'services': {'gmd-faq-gateway': {'environment': {
            'MYSQL_HOST': '127.0.0.1', 'MYSQL_PORT': '13306', 'REDIS_HOST': '127.0.0.1',
            'REDIS_PORT': '16379', 'CONSOLE_HOST': '127.0.0.1', 'PROXY_HOST': '127.0.0.1'}}}}
        targets = network.endpoints(config, 'gmd-faq-gateway')
        self.assertIn(('MySQL', '127.0.0.1', 13306), targets)
        self.assertIn(('Redis', '127.0.0.1', 16379), targets)
        self.assertIn(('LightRAG', 'lightrag', 9621), targets)
        script = network.bash_probe(config, 'gmd-faq-gateway')
        self.assertIn('timeout 5 bash', script)
        self.assertIn('probe MySQL 127.0.0.1 13306', script)

    def test_http_health_rejects_bad_key_without_printing_secret(self):
        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def do_GET(self):
                good = self.headers.get('X-API-Key') == 'expected-test-key'
                self.send_response(200 if good else 401)
                self.send_header('Content-Type', 'application/json')
                self.end_headers()
                self.wfile.write(b'{"status":"healthy"}' if good else b'{"detail":"credential-invalid"}')

        server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            probes = network.Probes('runtime')
            url = f'http://127.0.0.1:{server.server_port}/health'
            probes.health(url, {'X-API-Key': 'expected-test-key'})
            output = io.StringIO()
            with patch('sys.stdout', output):
                probes.check('LightRAG', lambda: probes.health(url, {'X-API-Key': 'wrong-test-key'}))
            self.assertEqual(probes.failures, 1)
            self.assertIn('FAIL: runtime -> LightRAG', output.getvalue())
            self.assertNotIn('wrong-test-key', output.getvalue())
            self.assertNotIn('credential-invalid', output.getvalue())
        finally:
            server.shutdown()
            server.server_close()
            thread.join()

    def test_proxy_probe_rejects_static_fallback_and_upstream_failure(self):
        probes = network.Probes('runtime')
        for response in ((200, 'text/html', b'<html>fallback</html>'),
                         (502, 'text/html', b'bad gateway'),
                         (404, 'application/json', b'{"code":404}'),
                         (200, 'application/json', b'{"code":200}')):
            with patch.object(probes, 'response', return_value=response):
                with self.assertRaises(ValueError):
                    probes.protected_route('/api/auth/perms')
        with patch.object(probes, 'response', return_value=(401, 'application/json', b'{"code":401}')):
            probes.protected_route('/api/auth/perms')


if __name__ == '__main__':
    unittest.main()
