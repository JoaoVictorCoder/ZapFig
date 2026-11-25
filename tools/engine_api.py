#!/usr/bin/env python3
"""Impressão digital da API do motor: SHA-256 das classes Java geradas pelo gomobile.

App e motor só funcionam juntos se essas classes forem idênticas, então o app só
aceita motores com a mesma impressão. O Gradle calcula o mesmo valor (app/build.gradle.kts).
Uso: engine_api.py engine-sources.jar
"""
import hashlib, re, sys, zipfile

def _code_only(src: bytes) -> bytes:
    """Remove comentários e espaços: mudar documentação não muda a API."""
    t = src.decode("utf-8")
    t = re.sub(r"/\*.*?\*/", "", t, flags=re.S)
    t = re.sub(r"//[^\n]*", "", t)
    return " ".join(t.split()).encode()

def api_hash(sources_jar: str) -> str:
    h = hashlib.sha256()
    with zipfile.ZipFile(sources_jar) as z:
        for name in sorted(n for n in z.namelist() if n.endswith(".java")):
            h.update(name.encode() + b"\n")
            h.update(_code_only(z.read(name)))
    return h.hexdigest()[:16]

if __name__ == "__main__":
    print(api_hash(sys.argv[1]))
