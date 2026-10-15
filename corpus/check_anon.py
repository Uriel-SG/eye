# -*- coding: utf-8 -*-
"""Checker anonimizzazione corpus (F0-T5): exit != 0 se fallisce."""
import json, os, re, sys

HERE = os.path.dirname(os.path.abspath(__file__))
MIN = 130
IBAN_RE = re.compile(r"\bIT\d{2}[A-Z0-9]{23}\b")
PHONE_RE = re.compile(r"(?<![\w/.=-])(?:\+39[ ]?)?3\d{2}[ ]?\d{6,7}(?!\d)")
FAKE_PHONE = re.compile(r"^(?:\+39[ ]?)?3\d{2}[ ]?000\d{4}$")
KEYS = {"id", "text", "label", "category", "channel", "source"}


def iban_valid(s):
    return int("".join(str(int(c, 36)) for c in s[4:] + s[:4])) % 97 == 1


errors = []
ids = set()
for name in ("scam", "legit"):
    path = os.path.join(HERE, name + ".jsonl")
    n = 0
    with open(path, encoding="utf-8") as f:
        for ln, line in enumerate(f, 1):
            if not line.strip():
                continue
            r = json.loads(line)
            n += 1
            if set(r) != KEYS:
                errors.append("%s:%d chiavi errate" % (name, ln))
            if r["label"] != name:
                errors.append("%s:%d label errata" % (name, ln))
            if r["channel"] not in ("sms", "whatsapp", "email", "qr"):
                errors.append("%s:%d canale" % (name, ln))
            if r["source"] != "sintetico":
                errors.append("%s:%d source" % (name, ln))
            if r["id"] in ids:
                errors.append("id duplicato " + r["id"])
            ids.add(r["id"])
            for m in IBAN_RE.findall(r["text"].replace(" ", "")):
                if iban_valid(m):
                    errors.append("%s:%d IBAN valido %s" % (name, ln, m))
            for m in PHONE_RE.findall(r["text"]):
                if not FAKE_PHONE.match(m.strip()):
                    errors.append("%s:%d telefono reale? %s" % (name, ln, m))
    print(name, n)
    if n < MIN:
        errors.append("%s: %d righe < %d" % (name, n, MIN))

if errors:
    print("FAIL")
    print("\n".join(errors))
    sys.exit(1)
print("OK")
