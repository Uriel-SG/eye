# tools/blocklist

Elenco locale di siti di phishing/malware noti, controllato sul telefono (anche offline e dalle notifiche in background).
Fonti e licenze: `SOURCES.md`.

## Costruire e pubblicare
1. `python tools/blocklist/build_blocklist.py` (scarica in `tools/blocklist/cache/`, scrive `tools/blocklist/out/hashes.bin` e `stats.json`; ~1 minuto).
   Cartelle `cache/` e `out/` sono ignorate da git.
2. `python tools/blocklist/measure_corpus.py` : truffe dei corpus con host in elenco (attese poche) e legit (deve essere 0).
3. `python tools/rulepack/rulepack.py build-site-data --key <cartella-segreti>/eye-2026-a.key.pem --key-id eye-2026-a --site-data <Eye-WebSite/data>`
   firma anche l'elenco (`blocklist.eye.json`, envelope `format 3`, dominio `EYE-BLOCKLIST-v1\n`) e aggiunge la voce `blocklist` a `version.json`
   (le app vecchie ignorano la chiave sconosciuta). Se `hashes.bin` non c'e', la voce pubblicata resta quella precedente.
4. Test: `python -I -m unittest discover -s tools/blocklist`.

## Filtri (anti falso positivo)
Mai nell'elenco: domini registrabili di `popularDomains`/`trustedDomains`/domini ufficiali dei marchi in `rules.json`, primi 50.000 di Tranco,
`gov.it` e `comune.*`/`regione.*`/..., radici di hosting condiviso (`github.io`, `web.app`, `pages.dev`, ...: si tiene solo l'host completo `evil.github.io`),
IP non pubblici. Gli host figli di un host gia' in elenco sono omessi (il motore controlla anche i padri fino al registrabile).
Il calcolo del registrabile usa gli stessi suffissi di `engine/.../PublicSuffix.kt` (letti dal file) piu' un elenco extra di hosting condiviso.

## Formato (envelope `format 3`)
Stesso envelope Ed25519 di regole e campagne (`engine/FEEDS.md`); payload = gzip di un contenitore binario `EBL1`:
`"EBL1"` | u32 BE lunghezza intestazione | intestazione JSON firmata (`version`, `keyId`, `issuedAt`, `expiresAt`, `count`, `hashBytes`=6) | corpo.
Voce = primi 6 byte di SHA-256 dell'host ASCII minuscolo (punycode) = intero a 48 bit; falso positivo ~N/2^48 per ricerca.
Corpo: primo hash a 6 byte, poi le differenze (u32 BE) tra hash consecutivi ordinati; differenza >= 2^32-1 -> `FFFFFFFF` + hash assoluto.
Nell'app: un `LongArray` ordinato con ricerca binaria (nessun oggetto per voce). Scadenza `expiresAt` = 30 giorni: oltre, l'elenco si usa lo stesso e Impostazioni lo segnala.
