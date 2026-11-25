#!/usr/bin/env python3
"""Empacota o motor para publicação como release engine-v*.

Gera em OUT_DIR:
  engine-<versao>-<abi>.so.gz   biblioteca do motor (comprimida) por processador
  engine.json                    manifesto: versão, API e SHA-256 de cada arquivo
  engine.json.sig                assinatura RSA-SHA256 do manifesto (chave do APK)

Uso: package_engine.py <engine.aar> <engine-sources.jar> <versao> <out_dir> <chave_privada.pem>
"""
import gzip, hashlib, json, os, subprocess, sys, zipfile
from engine_api import api_hash

def main(aar, sources, version, out, key):
    os.makedirs(out, exist_ok=True)
    files = {}
    with zipfile.ZipFile(aar) as z:
        for name in z.namelist():
            if not (name.startswith("jni/") and name.endswith("/libgojni.so")):
                continue
            abi = name.split("/")[1]
            data = z.read(name)
            asset = f"engine-{version}-{abi}.so.gz"
            with gzip.open(os.path.join(out, asset), "wb", compresslevel=9) as g:
                g.write(data)
            files[abi] = {"asset": asset, "sha256": hashlib.sha256(data).hexdigest(), "size": len(data)}
    if not files:
        sys.exit("nenhuma libgojni.so dentro do AAR")
    manifest = {"version": version, "api": api_hash(sources), "files": files}
    path = os.path.join(out, "engine.json")
    with open(path, "w") as f:
        json.dump(manifest, f, indent=1, sort_keys=True)
    subprocess.run(["openssl", "dgst", "-sha256", "-sign", key, "-out", path + ".sig", path], check=True)
    print(json.dumps(manifest, indent=1))

if __name__ == "__main__":
    main(*sys.argv[1:6])
