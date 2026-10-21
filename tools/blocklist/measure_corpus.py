#!/usr/bin/env python3
"""Quante righe dei corpus hanno un host nell'elenco generato (hashes.bin)? Truffe: attese poche (corpus vecchi). Legit: deve essere 0.

  measure_corpus.py [--hashes tools/blocklist/out/hashes.bin] [--verbose]
"""
import argparse, json, os, re, sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import build_blocklist as b

URL_RE = re.compile(r"(?:https?://)?((?:[\w-]+\.)+[a-z]{2,24}|\d{1,3}(?:\.\d{1,3}){3})(?::\d+)?(?:[/?#]|\b)", re.I)
FILES = [
    ("scam", "corpus/notif_scam.jsonl"), ("scam", "corpus/blind2.jsonl"), ("scam", "corpus/blind3.jsonl"),
    ("scam", "corpus/blind.jsonl"), ("scam", "corpus/scam.jsonl"), ("scam", "ml/data/real_holdout.jsonl"),
    ("scam", "ml/data/real_scams.jsonl"),
    ("legit", "corpus/legit.jsonl"), ("legit", "corpus/notif_legit.jsonl"), ("legit", "corpus/notif_legit_holdout.jsonl"),
    ("legit", "corpus/regression.jsonl"), ("legit", "ml/data/real_legit.jsonl"),
]


def candidates(host):
    reg = b.registrable(host) if b.is_public_ip(host) is None else host
    h = host
    while True:
        yield h
        if h == reg or "." not in h:
            return
        h = h.split(".", 1)[1]
        if h != reg and not h.endswith("." + reg):
            return


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--hashes", default=os.path.join(b.HERE, "out", "hashes.bin"))
    p.add_argument("--verbose", action="store_true")
    a = p.parse_args()
    raw = open(a.hashes, "rb").read()
    n = b.HASH_LEN
    hs = {raw[i:i + n] for i in range(0, len(raw), n)}
    print("voci:", len(hs))
    for kind, rel in FILES:
        path = os.path.join(b.REPO, rel)
        if not os.path.exists(path):
            continue
        total = withurl = hit = 0
        shown = []
        with open(path, encoding="utf-8", errors="replace") as f:
            for line in f:
                if not line.strip():
                    continue
                o = json.loads(line)
                if o.get("label") not in (None, kind):
                    continue
                total += 1
                hosts = {b.to_ascii_host(m.group(1)) for m in URL_RE.finditer(o.get("text", ""))} - {None}
                if hosts:
                    withurl += 1
                hosts_hit = [h for h in hosts if any(b.host_hash(c) in hs for c in candidates(h))]
                if hosts_hit:
                    hit += 1
                    shown.append((o.get("id"), hosts_hit))
        print("%-5s %-34s righe %5d  con link %5d  host in elenco %3d" % (kind, rel, total, withurl, hit))
        if a.verbose or kind == "legit":
            for i, h in shown:
                print("      ", i, h)


if __name__ == "__main__":
    main()
