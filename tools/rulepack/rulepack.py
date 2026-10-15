#!/usr/bin/env python3
"""Eye rule pack tooling (THREAT_MODEL sezioni 5-6). Richiede: pip install cryptography

  rulepack.py keygen --out-dir <DIR FUORI DAL REPO> --key-id eye-2026-a
  rulepack.py sign --key <priv.pem> --key-id eye-2026-a --version 42 --rules rules.json --out rules.eye.json
  rulepack.py sign-campaigns --key <priv.pem> --key-id eye-2026-a --version 3 --campaigns campaigns.json --out campaigns.eye.json
  rulepack.py build-site-data --key <priv.pem> --key-id eye-2026-a --site-data <Eye-WebSite/data>
      [--rules engine/src/main/resources/rules/rules.json] [--campaigns tools/campaigns/campaigns.json]
      [--rules-version N] [--campaigns-version M]
"""
import argparse, base64, datetime, gzip, hashlib, json, os, re, sys, unicodedata, urllib.parse
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey

DOMAIN = b"EYE-RULES-v1\n"  # deve coincidere con RulePackVerifier.DOMAIN
CAMPAIGNS_DOMAIN = b"EYE-CAMPAIGNS-v1\n"  # deve coincidere con CampaignFeedVerifier.DOMAIN (format 2)
REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))


def inside_repo(p):
    p = os.path.abspath(p)
    return os.path.commonpath([p, REPO]) == REPO


def keygen(a):
    if inside_repo(a.out_dir) and not a.i_know_this_is_a_test_key:
        sys.exit("RIFIUTATO: la chiave privata non deve stare dentro il repository.")
    os.makedirs(a.out_dir, exist_ok=True)
    path = os.path.join(a.out_dir, a.key_id + ".key.pem")
    if os.path.exists(path):
        sys.exit("esiste già: " + path)
    k = Ed25519PrivateKey.generate()
    pem = k.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption())
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "wb") as f:
        f.write(pem)
    raw = k.public_key().public_bytes(serialization.Encoding.Raw, serialization.PublicFormat.Raw)
    print("privata:", path)
    print("keyId:", a.key_id)
    print("pubkey (raw 32 byte, base64):", base64.b64encode(raw).decode())


OFFICIAL_DOMAINS = ["gov.it", "poliziadistato.it", "commissariatodips.it", "inps.it", "inail.it", "aci.it",
                    "carabinieri.it", "bancaditalia.it", "consob.it", "ivass.it", "agcom.it", "garanteprivacy.it"]
CATEGORIES = {"smishing", "phishing", "whatsapp", "malware", "investimenti", "acquisti", "altro"}
ID_RE = re.compile(r"^[a-z0-9][a-z0-9-]{2,63}$")
DATE_RE = re.compile(r"^20\d\d-(0[1-9]|1[0-2])-(0[1-9]|[12]\d|3[01])$")
HOST_RE = re.compile(r"^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$")
MAX_PLAIN = {1: 10 * 1024 * 1024, 2: 1024 * 1024}  # decompresso, come i verifier
MAX_ENVELOPE = {1: 2 * 1024 * 1024, 2: 256 * 1024}


def official_https(url):
    if not isinstance(url, str) or len(url) > 300 or any(ord(c) < 0x21 or ord(c) > 0x7e for c in url):
        return False
    try:
        u = urllib.parse.urlsplit(url)
        host, port = u.hostname, u.port
    except ValueError:
        return False
    return (u.scheme == "https" and "@" not in u.netloc and port in (None, 443) and host is not None
            and HOST_RE.match(host) is not None and any(host == d or host.endswith("." + d) for d in OFFICIAL_DOMAINS))


def validate_campaigns(doc):
    """Stessi controlli di CampaignFeedVerifier.validate (il test Kotlin resta il gate finale)."""
    e = []
    cs = doc.get("campaigns")
    if not isinstance(cs, list) or len(cs) > 100:
        return ["campaigns: lista assente o oltre 100"]
    if len({c.get("id") for c in cs if isinstance(c, dict)}) != len(cs):
        e.append("id duplicati")
    def strs(v, lo, hi, mx):
        return isinstance(v, list) and lo <= len(v) <= hi and all(isinstance(x, str) and x.strip() and len(x) <= mx for x in v)
    def clean(s):
        return not any(unicodedata.category(ch) in ("Cc", "Cf") for ch in s)
    for c in cs:
        if not isinstance(c, dict):
            e.append("campagna non e' un oggetto"); continue
        w = str(c.get("id"))
        def s(k, mx, blank=False):
            v = c.get(k)
            return isinstance(v, str) and (blank or v.strip() != "") and len(v) <= mx and clean(v)
        if not (isinstance(c.get("id"), str) and ID_RE.match(c["id"])): e.append("id non valido: " + w)
        if not s("title", 120): e.append(w + ": titolo")
        if c.get("category") not in CATEGORIES: e.append(w + ": categoria")
        if not s("summary", 500): e.append(w + ": descrizione")
        if not s("example", 500, True): e.append(w + ": esempio")
        if not strs(c.get("signals"), 1, 8, 250): e.append(w + ": segnali")
        if not strs(c.get("actions"), 1, 8, 250): e.append(w + ": azioni")
        fs, ua = c.get("firstSeen"), c.get("updatedAt")
        if not (isinstance(fs, str) and isinstance(ua, str) and DATE_RE.match(fs) and DATE_RE.match(ua) and ua >= fs): e.append(w + ": date")
        if c.get("status") not in ("in_corso", "conclusa"): e.append(w + ": stato")
        src = c.get("source")
        if not (isinstance(src, dict) and isinstance(src.get("name"), str) and src["name"].strip() and len(src["name"]) <= 100):
            e.append(w + ": nome fonte")
        elif not official_https(src.get("url")):
            e.append(w + ": URL fonte non ammesso")
    return e


def validate_rules(doc):
    """Controlli strutturali sulle regole (schema, limiti, regex compilabili, niente backreference)."""
    e = []
    th, rs = doc.get("thresholds"), doc.get("rules")
    if not (isinstance(th, dict) and isinstance(th.get("probable"), int) and isinstance(th.get("suspect"), int)
            and 1 <= th["suspect"] < th["probable"]):
        e.append("soglie incoerenti")
    if not isinstance(rs, list) or not 1 <= len(rs) <= 500:
        return e + ["numero regole fuori limite"]
    if len({r.get("id") for r in rs if isinstance(r, dict)}) != len(rs):
        e.append("id regola duplicati")
    for r in rs:
        if not isinstance(r, dict):
            e.append("regola non e' un oggetto"); continue
        w = str(r.get("id"))
        if not isinstance(r.get("weight"), int) or not -100 <= r["weight"] <= 100: e.append(w + ": peso")
        if not (isinstance(r.get("explanation"), str) and r["explanation"].strip()): e.append(w + ": spiegazione")
        kind = r.get("kind", "regex")
        if kind == "regex":
            pats, kws = r.get("patterns", []), r.get("keywords", [])
            if not (isinstance(pats, list) and isinstance(kws, list) and 0 < len(pats) + len(kws) <= 200): e.append(w + ": pattern")
            else:
                for p in pats + ([r["unless"]] if r.get("unless") else []):
                    if not isinstance(p, str) or len(p) > 600 or re.search(r"\\[1-9k]", p): e.append(w + ": pattern non ammesso"); continue
                    try: re.compile(p)
                    except re.error: e.append(w + ": regex non compilabile")
        elif kind == "detector":
            if not r.get("detector"): e.append(w + ": rilevatore mancante")
        else:
            e.append(w + ": kind sconosciuto")
    return e


def make_envelope(keyfile, key_id, version, doc, domain, fmt, ttl_days=45):
    """Envelope firmato (THREAT_MODEL §5). Il payload ripete version e keyId e porta issuedAt/expiresAt (firmati)."""
    errors = (validate_rules if fmt == 1 else validate_campaigns)(doc)
    if errors:
        sys.exit("RIFIUTO di firmare, contenuto non valido:\n  " + "\n  ".join(errors[:30]))
    k = serialization.load_pem_private_key(open(keyfile, "rb").read(), password=None)
    doc = dict(doc)
    doc["version"] = version
    doc["keyId"] = key_id
    now = datetime.datetime.now(datetime.timezone.utc).replace(microsecond=0)
    doc["issuedAt"] = now.strftime("%Y-%m-%dT%H:%M:%SZ")
    doc["expiresAt"] = (now + datetime.timedelta(days=ttl_days)).strftime("%Y-%m-%dT%H:%M:%SZ")
    plain = json.dumps(doc, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    payload = gzip.compress(plain, mtime=0)
    msg = domain + key_id.encode() + b"\n" + str(version).encode() + b"\n" + payload
    env = {
        "format": fmt, "alg": "Ed25519", "keyId": key_id, "version": version,
        "createdAt": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "payload": base64.b64encode(payload).decode(),
        "sig": base64.b64encode(k.sign(msg)).decode(),
    }
    out = json.dumps(env, separators=(",", ":")).encode("utf-8")
    if len(plain) > MAX_PLAIN[fmt] or len(out) > MAX_ENVELOPE[fmt]:
        sys.exit("RIFIUTO di firmare: dimensioni oltre i limiti dell app")
    return out


def load_json(path):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def write_bytes(path, data):
    with open(path, "wb") as f:
        f.write(data)


def sign(a):
    write_bytes(a.out, make_envelope(a.key, a.key_id, a.version, load_json(a.rules), DOMAIN, 1))
    print("scritto", a.out, "versione", a.version)


def sign_campaigns(a):
    write_bytes(a.out, make_envelope(a.key, a.key_id, a.version, load_json(a.campaigns), CAMPAIGNS_DOMAIN, 2))
    print("scritto", a.out, "versione", a.version)


def build_site_data(a):
    """Firma regole e campagne e scrive version.json + i due envelope nella cartella dati del sito."""
    os.makedirs(a.site_data, exist_ok=True)
    old = {}
    vpath = os.path.join(a.site_data, "version.json")
    if os.path.exists(vpath):
        old = load_json(vpath)
    rules = load_json(a.rules)
    # regole: deve superare sia la versione inclusa nell'app (rules.json) sia l'ultima pubblicata
    rv = a.rules_version or max(int(rules["version"]), int(old.get("rules", {}).get("version", 0))) + 1
    cv = a.campaigns_version or int(old.get("campaigns", {}).get("version", 0)) + 1
    if rv <= int(rules["version"]):
        sys.exit("rules-version deve essere > versione inclusa nell'app (%s)" % rules["version"])
    files = {
        "rules.eye.json": (make_envelope(a.key, a.key_id, rv, rules, DOMAIN, 1), rv),
        "campaigns.eye.json": (make_envelope(a.key, a.key_id, cv, load_json(a.campaigns), CAMPAIGNS_DOMAIN, 2), cv),
    }
    index = {}
    for name, (data, ver) in files.items():
        write_bytes(os.path.join(a.site_data, name), data)
        index["rules" if name.startswith("rules") else "campaigns"] = {
            "version": ver, "sha256": hashlib.sha256(data).hexdigest(), "url": name}
    write_bytes(vpath, (json.dumps(index, separators=(",", ":")) + "\n").encode("utf-8"))
    headers = "/*\n  Content-Type: application/json; charset=utf-8\n  Cache-Control: public, max-age=300\n  X-Content-Type-Options: nosniff\n"
    write_bytes(os.path.join(a.site_data, "_headers"), headers.encode("utf-8"))
    print("scritto in", a.site_data, "- regole v%d, campagne v%d" % (rv, cv))


p = argparse.ArgumentParser()
s = p.add_subparsers(dest="cmd", required=True)
g = s.add_parser("keygen")
g.add_argument("--out-dir", required=True)
g.add_argument("--key-id", required=True)
g.add_argument("--i-know-this-is-a-test-key", action="store_true")
g.set_defaults(f=keygen)
t = s.add_parser("sign")
t.add_argument("--key", required=True)
t.add_argument("--key-id", required=True)
t.add_argument("--version", type=int, required=True)
t.add_argument("--rules", required=True)
t.add_argument("--out", required=True)
t.set_defaults(f=sign)
c = s.add_parser("sign-campaigns")
c.add_argument("--key", required=True)
c.add_argument("--key-id", required=True)
c.add_argument("--version", type=int, required=True)
c.add_argument("--campaigns", required=True)
c.add_argument("--out", required=True)
c.set_defaults(f=sign_campaigns)
b = s.add_parser("build-site-data")
b.add_argument("--key", required=True, help="chiave privata di produzione (fuori dal repo)")
b.add_argument("--key-id", required=True)
b.add_argument("--site-data", required=True)
b.add_argument("--rules", default=os.path.join(REPO, "engine", "src", "main", "resources", "rules", "rules.json"))
b.add_argument("--campaigns", default=os.path.join(REPO, "tools", "campaigns", "campaigns.json"))
b.add_argument("--rules-version", type=int)
b.add_argument("--campaigns-version", type=int)
b.set_defaults(f=build_site_data)
a = p.parse_args()
a.f(a)
