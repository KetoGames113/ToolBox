#!/usr/bin/env python3
"""Builds update.json + bsdiff patches for the in-app updater.
usage: mkupdate.py <run_number> <new_dir> <prev_dir>
new_dir must contain ToolBox-arm64-v8a.apk and ToolBox-armeabi-v7a.apk
prev_dir contains the previous release's APKs and update.json (may be empty)."""
import hashlib, json, os, subprocess, sys

run, new_dir, prev_dir = int(sys.argv[1]), sys.argv[2], sys.argv[3]
ABIS = {"arm64-v8a": "arm64", "armeabi-v7a": "armv7"}


def sha(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


try:
    prev = json.load(open(os.path.join(prev_dir, "update.json")))
except Exception:
    prev = {}

out = {"versionCode": run, "versionName": "1.0.%d" % run}
keep = []
for abi, key in ABIS.items():
    new_apk = os.path.join(new_dir, "ToolBox-%s.apk" % abi)
    new_sha = sha(new_apk)
    out[key] = os.path.getsize(new_apk)
    out["sha_" + key] = new_sha
    chain = list(prev.get("chain_" + key, []))
    old_apk = os.path.join(prev_dir, "ToolBox-%s.apk" % abi)
    if os.path.exists(old_apk) and prev.get("sha_" + key):
        old_sha = sha(old_apk)
        if old_sha != new_sha:
            name = "patch-%s-%d.bin" % (key, run)
            subprocess.check_call(["bsdiff", old_apk, new_apk, os.path.join(new_dir, name)])
            chain.append({"from": old_sha, "to": new_sha, "asset": name,
                          "size": os.path.getsize(os.path.join(new_dir, name)), "code": run})
            print(key, "patch", os.path.getsize(os.path.join(new_dir, name)), "bytes of", out[key])
    chain = chain[-6:]
    out["chain_" + key] = chain
    keep += [e["asset"] for e in chain]

json.dump(out, open(os.path.join(new_dir, "update.json"), "w"))
open(os.path.join(new_dir, "keep.txt"), "w").write("\n".join(keep))
