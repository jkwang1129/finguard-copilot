import argparse
import json
from pathlib import Path

from .runtime import EmbeddingError, Engine
from .server import create_server


def main():
    parser = argparse.ArgumentParser(description="Explicit local CPU E5 runtime; no inference downloads")
    commands = parser.add_subparsers(dest="command", required=True)
    download = commands.add_parser("prepare", help="Download and verify pinned public model once")
    download.add_argument("--model-dir", type=Path, required=True)
    serve = commands.add_parser("serve", help="Verify frozen artifacts then bind127.0.0.1")
    serve.add_argument("--model-dir", type=Path, required=True)
    serve.add_argument("--manifest-sha256", required=True)
    serve.add_argument("--host", default="127.0.0.1")
    serve.add_argument("--port", type=int, default=8766)
    args = parser.parse_args()
    try:
        if args.command == "prepare":
            from .download import prepare
            print(json.dumps(prepare(args.model_dir), sort_keys=True))
            return
        if args.host != "127.0.0.1":
            raise EmbeddingError("loopback_only")
        engine = Engine(args.model_dir, args.manifest_sha256)
        server = create_server(engine, args.host, args.port)
        print(json.dumps({**engine.health(), "host": args.host, "port": server.server_port}), flush=True)
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            pass
        finally:
            server.server_close()
    except EmbeddingError as error:
        parser.exit(2, f"semantic runtime: {error}\n")
    except Exception:
        parser.exit(2, "semantic runtime: preparation_or_startup_failed\n")


if __name__ == "__main__":
    main()
