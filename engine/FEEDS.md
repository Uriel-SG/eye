# Feed remoti: regole e campagne (API per l'app)

Tutto in `app.eye.engine`, modulo JVM puro: **nessuna rete nel motore**. L'app scarica i byte (solo HTTPS, `connect` + `read` timeout brevi, lettura con tetto di byte) e li passa al motore.

## File pubblicati (cartella `data/` del sito)
| File | Contenuto | Limiti |
|---|---|---|
| `version.json` | indice non firmato: `{"rules":{"version":N,"sha256":"..","url":"rules.eye.json"},"campaigns":{"version":M,"sha256":"..","url":"campaigns.eye.json"}}` | max `FeedIndex.MAX_BYTES` = 4 KB |
| `rules.eye.json` | envelope Ed25519 `format 1`, dominio `EYE-RULES-v1\n` | max 2 MB (decompresso 10 MB) |
| `campaigns.eye.json` | envelope Ed25519 `format 2`, dominio `EYE-CAMPAIGNS-v1\n` | max 256 KB (decompresso 1 MB) |

`version.json` e' solo un indice: la sicurezza sta nella firma, nell'anti-rollback e nei limiti. `sha256` e' dell'envelope (byte esatti) e serve a integrita'/cache; non sostituisce la firma. Gli URL sono **nomi file relativi** alla stessa cartella di `version.json` (il parser rifiuta URL assoluti, `/`, `..`): l'app compone `base + entry.url` con base fissa nel codice, mai presa dal server.

Base consigliata (da confermare con l'URL reale del sito): `https://<dominio-sito>/data/`.

## Flusso (identico per i due feed)
1. `GET version.json` con `If-None-Match` (ETag salvato). `304` = niente da fare. Corpo > 4 KB: scartare.
2. `FeedIndex.parse(bytes)` -> `Result<FeedIndex>` (mai eccezioni). Errore = ignorare il ciclo.
3. Se `index.rules.version > rulesStore.installedVersion()` (e analogamente campagne con `campaignStore`): `GET` del file. Tetto di lettura = limite di tabella; oltre -> abortire.
4. (opzionale) `entry.matches(bytes)`: se falso scartare (file troncato/cache stantia), non e' un errore di sicurezza.
5. Installare, che fa verify + swap atomico:
   - regole: `rulesStore.install(RulePackVerifier(), bytes)` -> `Result<RuleSet>`
   - campagne: `campaignStore.installCampaigns(CampaignFeedVerifier(), bytes)` -> `Result<CampaignFeed>`
6. Errore (`RulePackException.reason`) = stato precedente invariato; non ritentare in loop, aspettare il prossimo intervallo.

Store (versioni anti-rollback **separate**):
```kotlin
val rulesStore = FileRulePackStore(dir)            // rules.eye.json + rules.version
val campaignStore = campaignStore(dir)             // campaigns.eye.json + campaigns.version
```
Caricamento all'avvio (riverifica la firma): `rulesStore.loadVerified(RulePackVerifier()): RuleSet?`, `campaignStore.loadCampaigns(CampaignFeedVerifier()): CampaignFeed?`.
Le regole scaricate valgono solo se `version` > quella del pacchetto incluso nell'APK (la piu' alta vince; spetta all'app confrontare `RuleSet.version`).

## Modello campagne (`CampaignFeed`)
`Campaign(id, title, category, summary, signals[], actions[], example, firstSeen, updatedAt, source{name,url}, status)`.
- `id` stabile (`[a-z0-9-]{3,64}`): chiave per «gia' letta»/notifiche.
- `category` in `smishing|phishing|whatsapp|malware|investimenti|acquisti|altro`; `status` in `in_corso|conclusa`; date `yyyy-MM-dd` (ordinabili come stringhe).
- `source.url`: validato https verso domini ufficiali (`.gov.it`, `poliziadistato.it`, `commissariatodips.it`, `inps.it`, `inail.it`, `aci.it`, `carabinieri.it`, `bancaditalia.it`, `consob.it`, `ivass.it`, `agcom.it`, `garanteprivacy.it`). L'app deve aprirlo solo con tocco esplicito dell'utente.
- Testi mostrati come testo semplice (non HTML). Max 100 campagne; limiti per campo in `CampaignFeedVerifier.validate`.
- Un feed valido puo' avere 0 campagne.

## Costanti e intervalli consigliati
| Cosa | Valore |
|---|---|
| Controllo regole | ogni 24 h, tutti gli utenti (Free e Pro), con vincolo di rete/batteria del sistema |
| Campagne Free | solo su richiesta (apertura/pull-to-refresh del catalogo «Aggiorna»), non prima di 1 h dall'ultimo controllo |
| Campagne Pro | ogni 12 h in background |
| Timeout rete | connect 10 s, read 15 s |
| Cache `version.json` | ETag; il server dice `max-age=300` |
| Tetto download | `FeedIndex.MAX_BYTES`, `RulePackVerifier.MAX_ENVELOPE`, `CampaignFeedVerifier.MAX_ENVELOPE` |
| Richiesta | nessun cookie, nessun identificativo, niente corpo; solo `GET` HTTPS |

## Pubblicazione (lato nostro)
`python tools/rulepack/rulepack.py build-site-data --key <eye-2026-a.key.pem> --key-id eye-2026-a --site-data <Eye-WebSite/data>`
firma regole (`version` = max(versione in `rules.json` incluso nell'app, ultima pubblicata) + 1) e campagne (`tools/campaigns/campaigns.json`, ultima + 1), poi scrive `version.json`, i due envelope e `_headers`. Dettagli in `tools/rulepack/README.md`.

## Freschezza e scadenza (`issuedAt` / `expiresAt`)
Ogni pacchetto firmato porta nel **payload firmato** `issuedAt` e `expiresAt` (ISO-8601 UTC, +45 giorni dalla firma). Non usare mai `createdAt` dell'envelope: non e' firmato.
- `RuleSet.isStale(now: Instant)`, `CampaignFeed.isStale(now)`: `true` oltre `expiresAt`. **Non e' un rifiuto**: il pacchetto resta valido e usato; l'app mostra solo un avviso.
- `RuleSet.staleDays(now)`, `CampaignFeed.staleDays(now)`: giorni dall'emissione (`Long?`, `null` per le regole incluse nell'APK, che non hanno date e non sono mai «scadute»).
- Testo suggerito: «Regole non aggiornate da N giorni» / «Campagne non aggiornate da N giorni» quando `isStale` e' vero; usare l'ora di sistema ma mai per rifiutare il pacchetto.
- Sono campi nuovi di `RuleSet` (default `null`) e `CampaignFeed`: le funzioni esistenti (`install`, `installCampaigns`, `loadVerified`, `loadCampaigns`) sono invariate. Ripubblicare almeno ogni ~30 giorni.
- Il tool rifiuta di firmare contenuti non validi (schema, URL non ammessi, dimensioni) prima di produrre l'envelope.
