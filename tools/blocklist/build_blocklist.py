#!/usr/bin/env python3
"""Costruisce l'elenco locale di host malevoli noti (phishing/malware) per Eye. Solo stdlib.

  build_blocklist.py [--cache-dir DIR] [--out-dir DIR] [--tranco-top 50000] [--offline] [--with-urlhaus]

URLhaus (abuse.ch) e' ESCLUSO di default: i termini attuali di abuse.ch non lo danno come CC0 e ne subordinano
l'uso commerciale a un abbonamento (vedi SOURCES.md). --with-urlhaus resta solo per prove locali.

Scarica (o legge da cache) le fonti elencate in SOURCES.md, normalizza in host ASCII minuscoli (IDN -> punycode),
applica i filtri anti-falso-positivo e scrive in out-dir:
  hashes.bin    SHA-256 troncato a 6 byte per host, ordinato, senza duplicati (formato: vedi README.md)
  stats.json    conteggi per fonte e dopo ogni filtro
La firma e il confezionamento in envelope sono di tools/rulepack/rulepack.py (sign-blocklist / build-site-data).
"""
import argparse, hashlib, io, ipaddress, json, os, re, sys, urllib.request, zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", ".."))
HASH_LEN = 6

SOURCES = {
    "urlhaus": "https://urlhaus.abuse.ch/downloads/text_online/",
    "phishdb_domains": "https://raw.githubusercontent.com/Phishing-Database/Phishing.Database/master/phishing-domains-ACTIVE.txt",
    "phishdb_links": "https://raw.githubusercontent.com/Phishing-Database/Phishing.Database/master/phishing-links-ACTIVE.txt",
    "tranco": "https://tranco-list.eu/download/daily/top-1m.csv.zip",
}

# ---------------------------------------------------------------- suffissi pubblici (stessi dati del motore)


def _kotlin_strings(src, name):
    """Parole tra apici di `val NAME = ( "..." + "..." ).split(' ')` o `const val NAME = "..."` in PublicSuffix.kt."""
    m = re.search(r"val\s+" + name + r"\s*=\s*(.*?)(?:\)\.split|\n\n|\n\s*/\*\*)", src, re.S)
    if not m:
        raise RuntimeError("PublicSuffix.kt: " + name + " non trovato")
    words = []
    for lit in re.findall(r'"([^"]*)"', m.group(1)):
        words += lit.split()
    return words


def load_suffixes(path=None):
    path = path or os.path.join(REPO, "engine", "src", "main", "kotlin", "app", "eye", "engine", "PublicSuffix.kt")
    with open(path, encoding="utf-8") as f:
        src = f.read()
    gtlds = set(_kotlin_strings(src, "gtlds"))
    multi = set(_kotlin_strings(src, "multi")) | set(_kotlin_strings(src, "IT_TERRITORIAL"))
    hosting = set(_kotlin_strings(src, "hosting"))
    return gtlds, multi, hosting


GTLDS, MULTI, HOSTING_ENGINE = load_suffixes()
# Hosting condiviso in piu' rispetto al motore: una voce tenant (evil.github.io) si tiene intera, la radice si scarta.
HOSTING_EXTRA = set("""
    web.app firebaseapp.com pages.dev vercel.app netlify.app github.io gitlab.io blogspot.com sites.google.com
    weebly.com wixsite.com wix.com webflow.io 000webhostapp.com herokuapp.com onrender.com fly.dev glitch.me
    workers.dev r2.dev trycloudflare.com azurewebsites.net azureedge.net cloudfront.net amazonaws.com s3.amazonaws.com
    storage.googleapis.com googleusercontent.com myshopify.com carrd.co godaddysites.com square.site
    ddns.net no-ip.org hopto.org zapto.org duckdns.org serveo.net ngrok.io ngrok-free.app lovable.app repl.co replit.app
    railway.app framer.app framer.website notion.site typedream.app strikingly.com mystrikingly.com yolasite.com
    jimdofree.com jimdosite.com webself.net site123.me wordpress.com wpcomstaging.com
    pythonanywhere.com surge.sh bubbleapps.io softr.app codesandbox.io stackblitz.io
""".split())
HOSTING = HOSTING_ENGINE | HOSTING_EXTRA


def is_suffix(s):
    return s in MULTI or s in HOSTING or ("." not in s and (s in GTLDS or (len(s) == 2 and s.isalpha()) or s.startswith("xn--")))


def suffix_of(host):
    labels = host.split(".")
    for i in range(len(labels)):
        cand = ".".join(labels[i:])
        if is_suffix(cand):
            return cand
    return labels[-1]


def registrable(host):
    suf = suffix_of(host)
    if host == suf:
        return host
    rest = host[: -(len(suf) + 1)]
    return rest.rsplit(".", 1)[-1] + "." + suf


# ---------------------------------------------------------------- normalizzazione

HOST_RE = re.compile(r"^[a-z0-9_]([a-z0-9_-]{0,61}[a-z0-9_])?(\.[a-z0-9_]([a-z0-9_-]{0,61}[a-z0-9_])?)+$")
IPV4_RE = re.compile(r"^\d{1,3}(\.\d{1,3}){3}$")
SCHEME_RE = re.compile(r"^[a-z][a-z0-9+.-]*://", re.I)


def to_ascii_host(h):
    """Host minuscolo senza punto finale, IDN in punycode. None se non valido."""
    h = h.strip().lower()
    if not h or len(h) > 253:
        return None
    h = h.rstrip(".")
    if h.startswith("["):  # IPv6: fuori scopo
        return None
    try:
        h = h.encode("idna").decode("ascii")  # gia' ASCII: invariato; unicode: punycode
    except UnicodeError:
        return None
    if IPV4_RE.match(h):
        return h if all(int(x) <= 255 for x in h.split(".")) else None
    return h if HOST_RE.match(h) and len(h.rsplit(".", 1)[-1]) >= 2 else None


def host_of(line):
    """Host da un URL (con o senza schema) o da un host nudo."""
    s = line.strip()
    if not s or s.startswith("#"):
        return None
    s = SCHEME_RE.sub("", s, count=1)
    s = re.split(r"[/?#\\]", s, maxsplit=1)[0]
    if "@" in s:
        s = s.rsplit("@", 1)[1]
    if s.count(":") == 1:
        s = s.split(":", 1)[0]
    return to_ascii_host(s)


def is_public_ip(h):
    """True/False se e' un IPv4 (pubblico o no); None se non e' un IP."""
    if not IPV4_RE.match(h):
        return None
    return ipaddress.ip_address(h).is_global


# ---------------------------------------------------------------- filtri

INSTITUTIONAL_PREFIXES = ("comune.", "regione.", "provincia.", "citta-metropolitana.")


def is_institutional(reg):
    return reg == "gov.it" or reg.endswith(".gov.it") or (
        reg.endswith(".it") and reg.count(".") >= 2 and reg.startswith(INSTITUTIONAL_PREFIXES))


def load_protected(rules_path, tranco_domains):
    """Domini registrabili mai ammessi: popolari e fiduciari del motore, domini ufficiali dei marchi, top list pubblica."""
    with open(rules_path, encoding="utf-8") as f:
        lists = json.load(f)["lists"]
    prot = set(lists.get("popularDomains", [])) | set(lists.get("trustedDomains", []))
    for b in lists.get("brands", []):
        prot.update(b.get("official", []))
    return prot | set(tranco_domains)


def filter_hosts(hosts, protected):
    """hosts: iterable di host normalizzati. Ritorna (tenuti, contatori degli scartati per motivo)."""
    drop = {"ip_privato": 0, "suffisso_nudo": 0, "popolare_o_ufficiale": 0, "istituzionale": 0}
    keep = set()
    for h in hosts:
        pub = is_public_ip(h)
        if pub is False:
            drop["ip_privato"] += 1
            continue
        if pub is True:
            keep.add(h)
            continue
        if h == suffix_of(h):  # es. github.io, co.uk: mai la radice di un hosting condiviso
            drop["suffisso_nudo"] += 1
            continue
        reg = registrable(h)  # su hosting condiviso = tenant.hosting (evil.github.io): e' quello ad essere confrontato
        if is_institutional(reg):
            drop["istituzionale"] += 1
            continue
        if reg in protected:
            drop["popolare_o_ufficiale"] += 1
            continue
        keep.add(h)
    return keep, drop


def collapse_children(hosts):
    """Toglie gli host il cui genitore (fino al registrabile) e' gia' in lista: il lookup controlla i genitori."""
    out = set()
    for h in hosts:
        if is_public_ip(h) is not None:
            out.add(h)
            continue
        reg = registrable(h)
        p, covered = h, False
        while p != reg:
            p = p.split(".", 1)[1]
            if p in hosts:
                covered = True
                break
        if not covered:
            out.add(h)
    return out


def host_hash(h):
    return hashlib.sha256(h.encode("ascii")).digest()[:HASH_LEN]


def pack(hosts):
    hs = sorted({host_hash(h) for h in hosts})
    return hs, b"".join(hs)


# ---------------------------------------------------------------- rete e I/O

def fetch(name, cache_dir, offline):
    path = os.path.join(cache_dir, name + (".zip" if name == "tranco" else ".txt"))
    if not os.path.exists(path):
        if offline:
            sys.exit("manca in cache (--offline): " + path)
        os.makedirs(cache_dir, exist_ok=True)
        req = urllib.request.Request(SOURCES[name], headers={"User-Agent": "eye-blocklist-builder/1"})
        with urllib.request.urlopen(req, timeout=120) as r, open(path + ".part", "wb") as f:
            while True:
                b = r.read(1 << 16)
                if not b:
                    break
                f.write(b)
        os.replace(path + ".part", path)
    return path


def read_lines(path):
    with open(path, encoding="utf-8", errors="replace") as f:
        yield from f


def read_tranco(path, top):
    out = []
    with zipfile.ZipFile(path) as z:
        with z.open(z.namelist()[0]) as f:
            for i, line in enumerate(io.TextIOWrapper(f, encoding="utf-8")):
                if i >= top:
                    break
                d = to_ascii_host(line.split(",", 1)[1]) if "," in line else None
                if d:
                    out.append(d)
                    out.append(registrable(d))
    return out


def build(cache_dir, out_dir, tranco_top, offline, rules_path, with_urlhaus=False):
    tranco = read_tranco(fetch("tranco", cache_dir, offline), tranco_top)
    protected = load_protected(rules_path, tranco)
    per_source, union = {}, set()
    for name in (("urlhaus",) if with_urlhaus else ()) + ("phishdb_domains", "phishdb_links"):
        hosts, n = set(), 0
        for line in read_lines(fetch(name, cache_dir, offline)):
            n += 1
            h = host_of(line)
            if h:
                hosts.add(h)
        kept, _ = filter_hosts(hosts, protected)
        per_source[name] = {"righe": n, "host_validi": len(hosts), "dopo_filtri": len(kept)}
        union |= hosts
    kept, dropped = filter_hosts(union, protected)
    collapsed = collapse_children(kept)
    hs, blob = pack(collapsed)
    os.makedirs(out_dir, exist_ok=True)
    with open(os.path.join(out_dir, "hashes.bin"), "wb") as f:
        f.write(blob)
    hset = set(hs)
    stats = {
        "per_fonte": per_source, "unione_host": len(union), "scartati": dropped, "dopo_filtri": len(kept),
        "dopo_collasso_figli": len(collapsed), "hash_unici": len(hs), "hash_bytes": HASH_LEN, "byte_hashes": len(blob),
        "protetti": len(protected), "tranco_top": tranco_top,
        # controllo di sicurezza: nessun hash in lista deve collidere con host/registrabili della top list
        "collisioni_con_tranco": sum(1 for d in set(tranco) if host_hash(d) in hset),
    }
    with open(os.path.join(out_dir, "stats.json"), "w", encoding="utf-8") as f:
        json.dump(stats, f, indent=1, ensure_ascii=False)
    return stats


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--cache-dir", default=os.path.join(HERE, "cache"))
    p.add_argument("--out-dir", default=os.path.join(HERE, "out"))
    p.add_argument("--tranco-top", type=int, default=50000)
    p.add_argument("--offline", action="store_true")
    p.add_argument("--with-urlhaus", action="store_true", help="SOLO prove locali: vedi SOURCES.md")
    p.add_argument("--rules", default=os.path.join(REPO, "engine", "src", "main", "resources", "rules", "rules.json"))
    a = p.parse_args()
    print(json.dumps(build(a.cache_dir, a.out_dir, a.tranco_top, a.offline, a.rules, a.with_urlhaus), indent=1, ensure_ascii=False))


if __name__ == "__main__":
    main()
