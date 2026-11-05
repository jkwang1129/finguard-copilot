"""Run-owned AMQP outage fixture. Fixed loopback ports, no payload inspection or logging."""
import select
import socket
import socketserver

class Forward(socketserver.BaseRequestHandler):
    def handle(self):
        try:
            with socket.create_connection(('127.0.0.1',5672),timeout=5) as upstream:
                upstream.settimeout(None)
                pair=(self.request,upstream)
                while True:
                    readable,_,_=select.select(pair,[],[],10)
                    for source in readable:
                        data=source.recv(65536)
                        if not data:return
                        (upstream if source is self.request else self.request).sendall(data)
        except OSError:return

class Proxy(socketserver.ThreadingTCPServer):
    allow_reuse_address=True
    daemon_threads=True

if __name__=='__main__':
    with Proxy(('127.0.0.1',15679),Forward) as proxy:proxy.serve_forever()
