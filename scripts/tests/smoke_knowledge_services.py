#!/usr/bin/env python3
"""Exercise repository images: real GPU PDF parsing, indexing, graphs and retrieval.

Uses fresh containers and a deterministic local OpenAI protocol fixture instead
of paid model calls. Does not connect to platform databases or existing volumes.
"""

import argparse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import math
from pathlib import Path
import secrets
import subprocess
import sys
import time
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen
import uuid

ROOT = Path(__file__).resolve().parents[2]
DIMENSION = 64


class Provider(BaseHTTPRequestHandler):
    """External model fixture; the parsing, storage and retrieval code is real."""

    def log_message(self, *args):
        pass

    def do_GET(self):
        if self.path != '/health':
            self.send_error(404)
            return
        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.end_headers()
        self.wfile.write(b'{"status":"ok"}')

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
        if self.path == '/v1/embeddings':
            values = body['input']
            values = values if isinstance(values, list) else [values]
            result = {'object': 'list', 'model': body['model'], 'data': [
                {'object': 'embedding', 'index': i, 'embedding': [1 / math.sqrt(DIMENSION)] * DIMENSION}
                for i in range(len(values))], 'usage': {'prompt_tokens': 10, 'total_tokens': 10}}
        elif self.path == '/v1/chat/completions':
            content = ('entity<|#|>GMD6800<|#|>Artifact<|#|>GMD6800 is a mobile emergency lighting vehicle.\n'
                       'entity<|#|>Emergency Lighting<|#|>Concept<|#|>Emergency Lighting illuminates rescue sites.\n'
                       'relation<|#|>GMD6800<|#|>Emergency Lighting<|#|>provides<|#|>GMD6800 provides emergency lighting.\n'
                       '<|COMPLETE|>')
            result = {'id': 'smoke-completion', 'object': 'chat.completion', 'created': 1, 'model': body['model'],
                      'choices': [{'index': 0, 'message': {'role': 'assistant', 'content': content}, 'finish_reason': 'stop'}],
                      'usage': {'prompt_tokens': 100, 'completion_tokens': 80, 'total_tokens': 180}}
        else:
            self.send_error(404)
            return
        payload = json.dumps(result).encode()
        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)


def docker(*args, check=True):
    result = subprocess.run(['docker', *(str(a) for a in args)], capture_output=True)
    if check and result.returncode:
        # Arguments can include the test's random API key; report only diagnostics.
        raise RuntimeError('Docker operation failed: ' + result.stderr.decode(errors='replace')[-3000:])
    return result


def request(base, path, key=None, data=None, method=None, content_type='application/json'):
    headers = {'X-API-Key': key} if key else {}
    if data is not None:
        headers['Content-Type'] = content_type
        data = data if isinstance(data, bytes) else json.dumps(data).encode()
    req = Request(base + path, data=data, headers=headers, method=method)
    try:
        with urlopen(req, timeout=30) as response:
            raw = response.read()
            return response.status, json.loads(raw) if raw else None
    except HTTPError as error:
        return error.code, error.read().decode(errors='replace')


def await_health(base, key=None, timeout=180):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            status, body = request(base, '/health', key)
            if status == 200:
                return body
        except (URLError, TimeoutError, ConnectionError):
            pass
        time.sleep(2)
    raise TimeoutError('Knowledge service did not become healthy')


def endpoint(container, port):
    mapping = json.loads(docker('inspect', container).stdout)[0]['NetworkSettings']['Ports'][f'{port}/tcp'][0]
    return 'http://127.0.0.1:' + mapping['HostPort']


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--provider', action='store_true', help=argparse.SUPPRESS)
    parser.add_argument('--lightrag-image', default='gmd/lightrag-api:local')
    parser.add_argument('--mineru-image', default='gmd/mineru-api:3.4.5-cuda12.8')
    parser.add_argument('--models', type=Path, default=ROOT / 'models/mineru')
    args = parser.parse_args()
    if args.provider:
        ThreadingHTTPServer(('0.0.0.0', 8081), Provider).serve_forever()
        return
    if not (args.models / 'models/Layout').is_dir():
        parser.error('--models must contain a complete MinerU pipeline snapshot')

    name = 'gmd-knowledge-smoke-' + uuid.uuid4().hex[:8]
    output = ROOT / 'output' / name
    output.mkdir(parents=True)
    network = name + '-net'
    containers = []
    key = secrets.token_hex(24)
    checks = []

    def passed(message):
        checks.append(message)
        print('PASS: ' + message, flush=True)

    def start(suffix, image, options, command=()):
        container = name + '-' + suffix
        docker('run', '-d', '--name', container, '--network', network, '--network-alias', suffix,
               *options, image, *command)
        containers.append(container)
        return container

    docker('network', 'create', network)
    try:
        provider = start('provider', args.lightrag_image,
              ['--entrypoint', 'python', '--publish', '127.0.0.1::8081', '--mount',
               f'type=bind,source={Path(__file__).resolve()},target=/app/scripts/tests/smoke.py,readonly'],
              ['/app/scripts/tests/smoke.py', '--provider'])
        await_health(endpoint(provider, 8081), timeout=30)
        miner = start('mineru', args.mineru_image,
                      ['--gpus', 'all', '--shm-size', '2g', '--publish', '127.0.0.1::8000',
                       '--mount', f'type=bind,source={args.models.resolve()},target=/models,readonly',
                       '-e', 'MINERU_MODEL_SOURCE=local', '-e', 'MINERU_DEVICE_MODE=cuda',
                       '-e', 'MINERU_TOOLS_CONFIG_JSON=/app/mineru.json', '-e', 'MINERU_API_OUTPUT_ROOT=/app/output',
                       '-e', 'MINERU_API_ENABLE_FASTAPI_DOCS=0', '-e', 'NO_PROXY=*'])
        miner_base = endpoint(miner, 8000)
        health = await_health(miner_base)
        assert health['version'] == '3.4.5', health
        passed('MinerU 3.4.5 starts with the project model snapshot and an NVIDIA GPU')
        assert request(miner_base, '/docs')[0] == 404

        env = {'LLM_BINDING': 'openai', 'LLM_MODEL': 'fixture-text', 'LLM_BINDING_HOST': 'http://provider:8081/v1',
               'LLM_BINDING_API_KEY': 'fixture', 'EMBEDDING_BINDING': 'openai', 'EMBEDDING_MODEL': 'fixture-embedding',
               'EMBEDDING_BINDING_HOST': 'http://provider:8081/v1', 'EMBEDDING_BINDING_API_KEY': 'fixture',
               'EMBEDDING_DIM': str(DIMENSION), 'RERANK_BINDING': 'null', 'LIGHTRAG_API_KEY': key,
               'ENABLE_API_DOCS': 'false', 'WORKERS': '1', 'SUMMARY_LANGUAGE': 'English',
               'MINERU_API_MODE': 'local', 'MINERU_LOCAL_ENDPOINT': 'http://mineru:8000',
               'MINERU_LOCAL_BACKEND': 'pipeline', 'MINERU_LOCAL_PARSE_METHOD': 'auto',
               'MINERU_LOCAL_IMAGE_ANALYSIS': 'false', 'MINERU_POLL_INTERVAL_SECONDS': '1',
               'LIGHTRAG_PARSER': 'pdf:mineru-iteP,md:native-iteP,*:legacy-R', 'NO_PROXY': '*'}
        options = ['--publish', '127.0.0.1::9621']
        for field, value in env.items():
            options += ['-e', field + '=' + value]
        rag = start('lightrag', args.lightrag_image, options)
        base = endpoint(rag, 9621)
        health = await_health(base, key)
        assert health['webui_available'] is False
        assert request(base, '/docs')[0] == 404
        assert request(base, '/models/config', data={'owner': '1:1'}, method='PUT')[0] == 401
        assert request(base, '/query/data', data={'query': 'GMD6800 lighting'})[0] == 403
        passed('API authentication works; independent WebUI and API documentation are absent')

        config = {'owner': '1:1',
                  'llm': {'binding': 'openai', 'model': 'fixture-text', 'host': 'http://provider:8081/v1', 'api_key': 'fixture'},
                  'embedding': {'binding': 'openai', 'model': 'fixture-embedding', 'host': 'http://provider:8081/v1',
                                'api_key': 'fixture', 'dimension': DIMENSION}}
        status, result = request(base, '/models/config', key, config, 'PUT')
        assert status == 200 and result['restart_required'] is True, result
        assert request(base, '/models/config', key, {**config, 'owner': '2:2'}, 'PUT')[0] == 409
        docker('restart', rag)
        base = endpoint(rag, 9621)
        await_health(base, key)
        status, result = request(base, '/models/config', key, config, 'PUT')
        assert status == 200 and result['restart_required'] is False, result
        passed('Platform models persist and apply after restart; another knowledge base owner is refused')

        docker('exec', miner, 'python', '-c',
               'from reportlab.pdfgen import canvas; c=canvas.Canvas("/tmp/knowledge.pdf"); '
               'c.setFont("Helvetica",18); c.drawString(50,780,"GMD6800 Emergency Lighting"); '
               'c.setFont("Helvetica",12); '
               'c.drawString(50,740,"GMD6800 is a mobile emergency lighting vehicle."); '
               'c.drawString(50,715,"It provides emergency lighting for rescue sites."); c.save()')
        pdf = output / 'knowledge.pdf'
        docker('cp', miner + ':/tmp/knowledge.pdf', pdf)
        boundary = 'knowledge-smoke-' + uuid.uuid4().hex
        payload = (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="knowledge.pdf"\r\n'
                   'Content-Type: application/pdf\r\n\r\n').encode() + pdf.read_bytes() + f'\r\n--{boundary}--\r\n'.encode()
        status, result = request(base, '/documents/upload', key, payload, content_type='multipart/form-data; boundary=' + boundary)
        assert status == 200, result
        track = result['track_id']
        deadline = time.monotonic() + 600
        previous = None
        while time.monotonic() < deadline:
            status, result = request(base, '/documents/track_status/' + track, key)
            assert status == 200, result
            documents = result.get('documents', [])
            states = [d['status'] for d in documents]
            if states != previous:
                print('PDF pipeline: ' + ', '.join(states or ['queued']), flush=True)
                previous = states
            if 'failed' in states:
                raise RuntimeError('PDF processing failed; inspect the retained LightRAG and MinerU logs')
            if states and all(state == 'processed' for state in states):
                break
            time.sleep(2)
        else:
            raise TimeoutError('PDF processing did not complete within 10 minutes')
        passed('A real PDF passes through LightRAG -> MinerU GPU -> parsing -> entity extraction -> persistent indexes')

        status, graph = request(base, '/graphs?label=*&max_depth=3&max_nodes=100', key)
        assert status == 200 and graph.get('nodes') and graph.get('edges'), graph
        status, result = request(base, '/query/data', key,
                                 {'query': 'GMD6800 emergency lighting', 'mode': 'mix', 'enable_rerank': False,
                                  'hl_keywords': ['Emergency Lighting'], 'll_keywords': ['GMD6800'], 'top_k': 5})
        assert status == 200 and result['status'] == 'success', result
        assert result['data'].get('chunks') and result['data'].get('entities'), result
        assert 'GMD6800' in json.dumps(result['data']), result
        assert result['data'].get('references'), result
        passed('Graph browsing and /query/data return entities, relationships, text chunks and source references')

        docker('restart', rag)
        base = endpoint(rag, 9621)
        await_health(base, key)
        status, result = request(base, '/documents/paginated', key, {'page': 1, 'page_size': 10})
        assert status == 200 and result['pagination']['total_count'] == 1, result
        passed('Document state, model binding and indexes survive a LightRAG process restart')
        (output / 'verification.json').write_text(json.dumps({'checks': checks, 'external_models': 'deterministic fixture',
            'pdf_parsing': 'real MinerU pipeline on NVIDIA GPU'}, indent=2) + '\n', encoding='utf-8')
    finally:
        for container in reversed(containers):
            logs = docker('logs', container, check=False)
            (output / (container.rsplit('-', 1)[-1] + '.log')).write_bytes(logs.stdout + logs.stderr)
            docker('rm', '-f', container, check=False)
        docker('network', 'rm', network, check=False)
        print('Verification files: ' + str(output), flush=True)


if __name__ == '__main__':
    main()
