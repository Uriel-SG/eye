# tools/rulepack

Firma dei pacchetti regole (formato: `docs/security/THREAT_MODEL.md` §5-6). Dominio di firma: `EYE-RULES-v1\n`.

## Procedura chiavi di produzione (U5, a carico dell'utente)
1. `pip install cryptography`
2. Su macchina fidata, **fuori dal repo** (lo script rifiuta path dentro il repo):
   `python tools/rulepack/rulepack.py keygen --out-dir D:/segreti/eye --key-id eye-2026-a` e poi `--key-id eye-2026-b`.
3. Cifrare le chiavi private (age/password manager), 2 backup offline, eliminare i file in chiaro.
4. Incollare le due chiavi pubbliche (base64 raw, stampate dal comando) in `PinnedKeys.production`
   (`engine/.../RulePackVerifier.kt`): `a` attiva, `b` successiva.
5. Firmare: `python tools/rulepack/rulepack.py sign --key D:/segreti/eye/eye-2026-a.key.pem --key-id eye-2026-a --version 42 --rules engine/src/main/resources/rules/rules.json --out rules.eye.json`
   (la `version` deve essere maggiore di quella installata; viene scritta anche nel payload insieme a `keyId`).

Chiave in `engine/src/test/resources/rulepack/`: **solo TEST**, mai in produzione.
Il pacchetto nell'APK resta `rules.json` in chiaro (fidato perche' dentro l'APK firmato).

## Feed del sito (regole + campagne)
- `sign-campaigns`: firma `campaigns.json` con envelope `format 2` e dominio `EYE-CAMPAIGNS-v1\n` (non intercambiabile con le regole).
- `build-site-data --key <cartella-segreti>/eye-2026-a.key.pem --key-id eye-2026-a --site-data <sito>/data`
  firma `engine/.../rules.json` e `tools/campaigns/campaigns.json` e scrive `version.json` (indice con sha256), `rules.eye.json`, `campaigns.eye.json`, `_headers`.
  Le versioni salgono da sole (ultima pubblicata + 1; regole sempre > quella inclusa nell'app); si possono forzare con `--rules-version` / `--campaigns-version`.
- Verifica come la fara' l'app: `EYE_SITE_DATA=<Eye-WebSite/data> ./gradlew.bat :engine:test` (`ProductionFeedCheckTest`).
- `_headers` (Content-Type JSON, `Cache-Control: public, max-age=300`) e' in `data/`; Netlify legge `_headers` solo dalla radice di publish
  (qui `publish = "."`), quindi per applicarlo serve un blocco `[[headers]] for = "/data/*"` in `netlify.toml` (da fare a parte) oppure copiarlo alla radice.
- Fonte campagne: `tools/campaigns/campaigns.json` (solo fonti ufficiali verificate, esempi con domini `.example`).
