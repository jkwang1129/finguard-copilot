"""Loopback-only synthetic model for the isolated business acceptance script."""
import json
import os
import re
from http.server import BaseHTTPRequestHandler, HTTPServer


class Handler(BaseHTTPRequestHandler):
    calls = 0

    def do_GET(self):
        if self.path != '/_calls':
            self.send_error(404)
            return
        body = json.dumps({'calls': type(self).calls}).encode()
        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args):
        pass

    def do_POST(self):
        type(self).calls += 1
        try:
            if self.headers.get('Transfer-Encoding', '').lower() == 'chunked':
                parts = []
                while True:
                    size = int(self.rfile.readline().split(b';', 1)[0].strip(), 16)
                    if size == 0:
                        while self.rfile.readline().strip():
                            pass
                        break
                    parts.append(self.rfile.read(size))
                    self.rfile.read(2)
                payload = b''.join(parts)
            else:
                payload = self.rfile.read(int(self.headers['Content-Length']))
            request = json.loads(payload)
            context = request['messages'][1]['content']
            def fact(name):
                return re.search(r'^' + re.escape(name) + r'=(.*)$', context, re.M).group(1)
            knowledge = json.loads(context.split('KNOWLEDGE_EVIDENCE\n', 1)[1].split('\nREVIEWER_INPUT', 1)[0])
            result = {'schemaVersion': 1, 'analysisId': fact('analysisId'),
                'reviewTaskSummary': 'Synthetic review', 'riskRuleExplanation': 'Synthetic acceptance result',
                'businessEvidence': [{'transactionId': int(fact('transaction.id')),
                    'accountId': int(fact('transaction.accountId')), 'amount': float(fact('transaction.amount')),
                    'currency': fact('transaction.currency'), 'ruleCode': fact('reviewTask.ruleCode'),
                    'reasonCode': fact('reviewTask.reasonCode'), 'sourcePaths': ['transaction.amount']}],
                'policyCitations': [knowledge[0]['citation']], 'recommendation': os.environ.get('P4_STUB_RECOMMENDATION', 'CONFIRMED'),
                'reasoningSummary': 'Synthetic acceptance only', 'missingInformation': [], 'limitations': ['Test model']}
            body = json.dumps({'id': 'fixture-response-' + str(type(self).calls), 'model': 'local-test-stub',
                'choices': [{'message': {'content': json.dumps(result)}}],
                'usage': {'prompt_tokens': 10, 'completion_tokens': 5}}).encode()
            self.send_response(200)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Content-Length', str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        except (KeyError, ValueError, IndexError, AttributeError, TypeError):
            self.send_error(400, 'Invalid acceptance request')


if __name__ == '__main__':
    HTTPServer(('127.0.0.1', int(os.environ.get('P4_STUB_PORT', '18765'))), Handler).serve_forever()
