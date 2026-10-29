#!/usr/bin/env python3
"""Read-only network probes run inside the actual source service containers."""

import json
from pathlib import Path
import shlex
import socket
import sys
import urllib.error
import urllib.request

SOURCES = ('gmd-faq-console', 'gmd-faq-runtime', 'gmd-faq-proxy',
           'gmd-faq-websocket', 'gmd-faq-gateway', 'lightrag')


def endpoints(config, source):
    env = config['services'][source].get('environment', {})
    targets = []
    if source in ('gmd-faq-console', 'gmd-faq-runtime', 'gmd-faq-gateway'):
        targets += [('MySQL', env.get('MYSQL_HOST', 'gmd-faq-mysql'), int(env.get('MYSQL_PORT', 3306))),
                    ('Redis', env.get('REDIS_HOST', 'gmd-faq-redis'), int(env.get('REDIS_PORT', 6379)))]
    if source == 'gmd-faq-websocket':
        targets += [('Redis', env.get('REDIS_HOST', 'gmd-faq-redis'), int(env.get('REDIS_PORT', 6379)))]
    if source == 'gmd-faq-runtime':
        targets += [('pgvector', env.get('PG_HOST', 'gmd-faq-pgvector'), int(env.get('PG_PORT', 5432)))]
    if source in ('gmd-faq-runtime', 'gmd-faq-proxy', 'gmd-faq-websocket', 'gmd-faq-gateway'):
        targets += [('Console', env.get('CONSOLE_HOST', 'gmd-faq-console'), 3060)]
    if source in ('gmd-faq-runtime', 'gmd-faq-gateway'):
        targets += [('Proxy', env.get('PROXY_HOST', 'gmd-faq-proxy'), int(env.get('PROXY_PORT', 3062)))]
    if source in ('gmd-faq-console', 'gmd-faq-runtime', 'gmd-faq-gateway') and env.get('WEBSOCKET_ENABLED', 'false').lower() == 'true':
        targets += [('WebSocket', env.get('WEBSOCKET_HOST', 'gmd-faq-websocket'), int(env.get('WEBSOCKET_PORT', 3064)))]
    if source in ('gmd-faq-console', 'gmd-faq-gateway'):
        targets += [('LightRAG', 'lightrag', 9621)]
    for label, host, port in targets:
        if not host or any(c.isspace() for c in host) or not 1 <= port <= 65535:
            raise ValueError('Invalid connection host/port configuration')
    return targets


def bash_probe(config, source):
    lines = ['#!/usr/bin/env bash', 'result=0',
             'probe() {',
             '  if timeout 5 bash -c \'exec 3<>"/dev/tcp/$1/$2"\' bash "$2" "$3" 2>/dev/null; then',
             '    printf "PASS: %s -> %s (%s:%s)\\n" "$source" "$1" "$2" "$3"',
             '  else',
             '    printf "FAIL: %s -> %s (%s:%s): DNS, listener or network unreachable\\n" "$source" "$1" "$2" "$3"',
             '    result=1', '  fi', '}', 'source=' + shlex.quote(source)]
    for label, host, port in endpoints(config, source):
        lines.append('probe ' + ' '.join(shlex.quote(str(v)) for v in (label, host, port)))
    return '\n'.join(lines + ['exit "$result"']) + '\n'


class Probes:
    def __init__(self, source):
        self.source = source
        self.failures = 0
        # Internal service traffic must bypass host HTTP_PROXY settings.
        self.http = urllib.request.build_opener(urllib.request.ProxyHandler({}))

    def check(self, label, callback):
        try:
            callback()
        except Exception as error:
            self.failures += 1
            # Exception messages/response bodies may contain credentials or data.
            print(f'FAIL: {self.source} -> {label} ({type(error).__name__})', flush=True)
        else:
            print(f'PASS: {self.source} -> {label}', flush=True)

    def tcp(self, host, port):
        with socket.create_connection((host, port), timeout=5):
            pass

    def response(self, url, headers=None):
        request = urllib.request.Request(url, headers=headers or {})
        try:
            response = self.http.open(request, timeout=10)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            return response.status, response.headers.get('Content-Type', ''), response.read(2 * 1024 * 1024)

    def health(self, url, headers=None):
        status, content_type, body = self.response(url, headers)
        if status != 200 or 'json' not in content_type or not isinstance(json.loads(body), dict):
            raise ValueError('Expected a successful JSON API response')

    def frontend(self):
        status, content_type, body = self.response('http://gmd-faq-frontend:80/web/')
        if status != 200 or 'text/html' not in content_type or b'<html' not in body.lower():
            raise ValueError('Expected frontend HTML')

    def protected_route(self, path):
        # An unauthenticated probe must reach the application's JSON auth response.
        # A static fallback HTML page, 404, 502 or 504 is a failed proxy check.
        status, content_type, body = self.response('http://gmd-faq-frontend:80' + path)
        if status not in (200, 401, 403) or 'json' not in content_type:
            raise ValueError('Expected application authentication response')
        if json.loads(body).get('code') not in (401, 403):
            raise ValueError('Expected application authentication response')


def python_probe(config, source):
    probes = Probes(source)
    for label, host, port in endpoints(config, source):
        probes.check(f'{label} ({host}:{port})', lambda h=host, p=port: probes.tcp(h, p))
    if source == 'gmd-faq-runtime':
        key = config['services']['lightrag']['environment']['LIGHTRAG_API_KEY']
        probes.check('LightRAG authenticated /health',
                     lambda: probes.health('http://lightrag:9621/health', {'X-API-Key': key}))
        probes.check('LightRAG authenticated /auth/verify',
                     lambda: probes.health('http://lightrag:9621/auth/verify', {'X-API-Key': key}))
        probes.check('frontend /web/', probes.frontend)
        probes.check('Nginx -> Console authentication route', lambda: probes.protected_route('/api/auth/perms'))
        probes.check('Nginx -> Runtime authentication route',
                     lambda: probes.protected_route('/api/runtime/agui/active-runs'))
    elif source == 'lightrag':
        endpoint = config['services']['lightrag']['environment']['MINERU_LOCAL_ENDPOINT'].rstrip('/')
        probes.check('MinerU /health', lambda: probes.health(endpoint + '/health'))
    return int(probes.failures > 0)


def main():
    action = sys.argv[1]
    if action == 'python':
        return python_probe(json.load(sys.stdin), sys.argv[2])
    config = json.loads((Path(sys.argv[-1]) / '.effective-compose.json').read_text())
    if action == 'plan':
        for source in SOURCES:
            print(source + '\t' + ('python' if source in ('gmd-faq-runtime', 'lightrag') else 'bash'))
    elif action == 'bash':
        print(bash_probe(config, sys.argv[2]), end='')
    elif action == 'services':
        for source in config['services']:
            print(source)
    elif action == 'frontend-port':
        print(config['services']['gmd-faq-frontend']['ports'][0]['published'])
    return 0


if __name__ == '__main__':
    sys.exit(main())
