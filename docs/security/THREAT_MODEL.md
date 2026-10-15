# Eye — Threat model (v0.1, 2026-10-11)

Fonte: `docs/PROGETTO.md` §6, §8, §13, §15. Riferimenti: OWASP MASVS v2 (app), OWASP API Top 10 (serverless, Fase Pro).

## 1. Asset
| ID | Asset | Perché conta |
|----|-------|--------------|
| A1 | Contenuti analizzati (SMS, link, QR, screenshot/OCR) | Dati personali, possibili OTP/IBAN/nomi |
| A2 | Cronologia locale (Room/DataStore) | Storico privato dei messaggi ricevuti |
| A3 | Pacchetto regole + chiave privata Ed25519 di firma | Chi controlla le regole controlla i verdetti |
| A4 | Integrità del verdetto | Un falso «Nessun segnale» spinge l'utente verso la truffa |
| A5 | Stato Pro / quota (token d'acquisto, ID installazione) | Frode sugli abbonamenti, costi serverless |
| A6 | Infrastruttura serverless (Fase Pro) | Pivot SSRF verso rete interna/metadati cloud |

## 2. Attori
- **Truffatore**: crea messaggi/QR/pagine per eludere le regole o per colpire il parser (ReDoS, input enormi, omografi).
- **App malevola sullo stesso device**: invia intent alle componenti esportate, legge file/log condivisi.
- **Attaccante di rete** (Wi-Fi pubblico, CDN compromessa): manomette/riproduce il pacchetto regole.
- **Attaccante con accesso fisico/backup**: legge la cronologia da backup ADB/cloud o da screenshot/recenti.
- **Insider/supply chain**: chiave privata nel repo, dipendenza compromessa, CI compromessa.
- **Utente abusivo** (Pro): aggira quota, usa il serverless come proxy/scanner.

## 3. Superfici d'attacco
1. **Share Target / intent esportati** (`ACTION_SEND` text/plain, image/*): input non fidato da qualsiasi app.
2. **QR (CameraX + ML Kit)**: payload arbitrario (URL, `intent:`, `javascript:`, testo lungo).
3. **OCR su immagini**: immagine non fidata, testo estratto non fidato.
4. **Incolla manuale**: testo arbitrario.
5. **Pacchetto regole firmato + download periodico**: rete, CDN, parser JSON, regex fornite come dati.
6. **Storage locale**: DB cronologia, DataStore (versione regole, impostazioni), cache file.
7. **Output/condivisione**: scheda verdetto, «Manda a un familiare», «Segnala errore», PDF prova (Pro).
8. **Futuro serverless** (Pro): endpoint analisi URL, resolver redirect, RDAP, feed reputazione, verifica Play Integrity/Billing.

## 4. Minacce STRIDE e mitigazioni
| # | STRIDE | Minaccia | Mitigazione (→ requisito) |
|---|--------|----------|---------------------------|
| T1 | Spoofing | Pacchetto regole falso servito da CDN/MITM | Firma Ed25519 verificata prima del parse, chiavi pinnate nell'APK (SEC-10, SEC-11) |
| T2 | Tampering | Rollback a pacchetto vecchio firmato (regole meno efficaci) | Versione monotona persistita, rifiuto `version <= corrente` (SEC-12) |
| T3 | Tampering | App terza invia intent con extra malformati / Parcel enormi | Solo activity di share esportata, validazione tipo/lunghezza (SEC-01, SEC-05) |
| T4 | Tampering | Omografi/punycode per mascherare il dominio | Normalizzazione IDN + confusables, mostrare dominio registrato (SEC-07) |
| T5 | Repudiation | Utente contesta un verdetto / PDF prova manipolato | PDF con hash SHA-256 del contenuto e versione regole (SEC-31) |
| T6 | Info disclosure | Cronologia estratta via backup ADB/cloud | `allowBackup=false` + `dataExtractionRules` che escludono tutto (SEC-02) |
| T7 | Info disclosure | Contenuti nei log/crash report | Nessun log di contenuti, R8 rimuove `Log.d/v` (SEC-14, SEC-15) |
| T8 | Info disclosure | Contenuto visibile in screenshot/«Recenti» | Valutare `FLAG_SECURE` su schermata verdetto/cronologia (SEC-16) |
| T9 | Info disclosure | Invio di testo del messaggio al server (Pro) | Solo URL/dominio estratto, previo consenso esplicito (SEC-30) |
| T10 | DoS | ReDoS / input da MB / zip bomb nel pacchetto | Max 10.000 caratteri, regex lineari, limiti dimensione (SEC-05, SEC-06, SEC-13) |
| T11 | DoS | Immagine enorme a OCR (OOM) | Downsampling a max 2048 px lato lungo, timeout (SEC-08) |
| T12 | EoP | QR/link aperto automaticamente (`intent:`, `javascript:`, deep link) | Mai aprire link automaticamente; apertura solo esplicita, solo http/https (SEC-09) |
| T13 | EoP | SSRF tramite resolver redirect (Pro) | Allowlist schema/porte, blocco IP privati a ogni salto, 5 salti (SEC-32) |
| T14 | EoP/Fraud | Aggiramento quota/Pro | Verifica server-side Play Integrity + Play Developer API (SEC-33) |
| T15 | Tampering | Supply chain (dipendenze, chiave nel repo) | Lock dipendenze, secret scanning, chiave offline (SEC-17, SEC-18, §6) |
| T16 | Info disclosure | Traffico in chiaro | `networkSecurityConfig` cleartext off, solo HTTPS (SEC-03) |

## 5. Formato pacchetto regole firmato
File `rules.eye.json` (envelope), servito su HTTPS statico:
```json
{
  "format": 1,
  "alg": "Ed25519",
  "keyId": "eye-2026-a",
  "version": 42,
  "createdAt": "2026-10-11T10:00:00Z",
  "payload": "<base64 standard di gzip(rules.json)>",
  "sig": "<base64 della firma Ed25519, 64 byte>"
}
```
- **Messaggio firmato** = `"EYE-RULES-v1\n" || keyId || "\n" || version (decimale) || "\n" || payloadBytes` (payloadBytes = base64-decoded, ancora compresso). Il prefisso fa da separazione di dominio; `keyId` e `version` sono coperti dalla firma.
- **Ordine di verifica** (nessun passo successivo se uno fallisce):
  1. dimensione envelope ≤ 2 MB; parse JSON solo dell'envelope (schema fisso, campi sconosciuti rifiutati);
  2. `format == 1`, `alg == "Ed25519"`, `keyId` presente tra le chiavi pinnate;
  3. `version > versione corrente persistita` (anti-rollback);
  4. verifica firma sul messaggio sopra;
  5. decompressione con limite ≤ 10 MB (anti zip bomb);
  6. parse di `rules.json`, che deve ripetere `version` e `keyId` identici all'envelope;
  7. validazione semantica (schema regole, regex compilabili e conformi a SEC-06);
  8. swap atomico (scrittura su file temporaneo + rename), poi persistenza della nuova versione.
- Fallback: pacchetto incluso nell'APK (stesso formato, verificato all'avvio). Si usa la versione valida più alta tra inclusa e scaricata.

## 6. Generazione e custodia delle chiavi
- **La chiave privata non entra MAI nel repository**, né in CI log, né in file `.env` versionati.
- Generazione su macchina fidata, offline se possibile:
  `openssl genpkey -algorithm ed25519 -out eye-2026-a.key.pem` → `openssl pkey -in eye-2026-a.key.pem -pubout -out eye-2026-a.pub.pem`.
- Custodia: file privato cifrato (es. `age`/password manager con passphrase forte) + 2 backup offline cifrati in luoghi diversi. Eliminare la copia in chiaro dopo la cifratura.
- Generare subito **due** coppie: `a` (attiva) e `b` (successiva). Nell'APK si incorporano entrambe le **chiavi pubbliche** (raw 32 byte, base64) — rotazione senza aggiornamento d'urgenza.
- Firma: tool CLI locale (`tools/rulepack`, solo chiave pubblica nel repo per test) eseguito manualmente; se in CI, solo come secret di un environment protetto con approvazione manuale, mai su PR da fork.
- Compromissione: firmare con `b`, rilasciare app che rimuove `a` e aggiunge `c`, incrementare `version`, documentare l'incidente.
- `.gitignore`: `*.key`, `*.key.pem`, `*.age`; secret scanning (gitleaks) in pre-commit/CI.

## 7. Requisiti verificabili

### MVP
| ID | Requisito | Verifica |
|----|-----------|----------|
| SEC-01 | Esportata solo l'activity Share Target (+ launcher); ogni altro componente `exported="false"`; nessun ContentProvider esportato | Lettura manifest unito (`processDebugManifest`), test lint |
| SEC-02 | `android:allowBackup="false"`, `dataExtractionRules` (API 31+) e `fullBackupContent` (API ≤30) che escludono DB, DataStore e file | Manifest + `adb backup`/`bmgr` su emulatore |
| SEC-03 | `networkSecurityConfig` con `cleartextTrafficPermitted="false"`, nessun trust anchor utente | XML + test con URL http che fallisce |
| SEC-04 | Nessuna WebView; nessun permesso oltre `CAMERA` e `INTERNET` (no SMS, call log, accessibilità, lettura appunti in background) | Grep `WebView`, manifest unito |
| SEC-05 | Input testuale troncato/rifiutato oltre 10.000 caratteri prima di qualsiasi elaborazione; tipo MIME verificato nell'intent | Unit test `:engine` con 10.001 caratteri |
| SEC-06 | Regex senza quantificatori annidati/backreference/alternanze sovrapposte sotto `*`/`+`; regole da pacchetto validate da un linter; timeout/budget per analisi ≤ 1 s | Test con payload ReDoS noti, linter in CI |
| SEC-07 | Domini normalizzati (IDNA/punycode, confusables UTS #39); UI mostra il dominio registrato (eTLD+1 via Public Suffix List) evidenziato | Unit test omografi (`pоste.it` cirillico) |
| SEC-08 | Immagini decodificate con `inSampleSize` (max 2048 px), dimensione file ≤ 20 MB, errori gestiti senza crash | Test con immagine 10.000×10.000 |
| SEC-09 | QR/link mai aperti automaticamente; apertura solo su tap esplicito, solo `http/https`; `intent:`, `javascript:`, `file:`, `content:` mostrati come testo | Unit test + test UI |
| SEC-10 | Verifica Ed25519 (BouncyCastle) **prima** del parse del payload, secondo l'ordine §5 | Test: payload alterato di 1 byte → rifiuto senza parse |
| SEC-11 | Chiavi pubbliche pinnate nel codice (attiva + successiva); `keyId` sconosciuto → rifiuto | Unit test |
| SEC-12 | Anti-rollback: versione massima persistita, `version <= corrente` rifiutata; vale anche per il pacchetto incluso | Unit test replay |
| SEC-13 | Download regole: solo HTTPS, envelope ≤ 2 MB, decompresso ≤ 10 MB, swap atomico, errore → regole correnti invariate | Test con zip bomb e download troncato |
| SEC-14 | Nessun log di contenuti utente (testo, URL, OCR, IBAN, numeri) in nessun build type | Grep `Log.` + review; test su logcat |
| SEC-15 | R8 attivo in release (`minifyEnabled`, `shrinkResources`), regole `-assumenosideeffects` per `Log.d/v/i`; `debuggable=false` | Ispezione APK release |
| SEC-16 | Valutare e documentare `FLAG_SECURE` su verdetto e cronologia (default proposto: attivo in cronologia, opzionale nel verdetto per non bloccare la condivisione) | Decisione in ADR + test |
| SEC-17 | Nessun SDK analytics/ads/crash di terze parti nel Free; dipendenze con versioni bloccate (version catalog + verification metadata Gradle) | Review `libs.versions.toml`, `gradle/verification-metadata.xml` |
| SEC-18 | Nessun segreto nel repo; gitleaks in CI; chiave privata gestita come §6 | CI verde con gitleaks |
| SEC-19 | Cronologia solo locale, purge automatica a 7 giorni (Free); cancellazione totale dalle impostazioni | Test DAO |
| SEC-20 | Condivisione/segnalazione solo tramite intent di sistema con azione utente; nessun invio automatico; `FileProvider` non esportato con `grantUriPermissions` per le immagini | Review + test |
| SEC-21 | `PendingIntent` con `FLAG_IMMUTABLE`; intent impliciti verso esterno solo per share/browser | Lint |

### Pro / serverless
| ID | Requisito | Verifica |
|----|-----------|----------|
| SEC-30 | Al server solo URL/dominio estratto, dopo consenso esplicito revocabile; mai testo del messaggio | Test di rete (MockWebServer) sul body |
| SEC-31 | PDF prova: SHA-256 del contenuto, data/ora, versione regole; generato localmente | Unit test hash |
| SEC-32 | Resolver redirect anti-SSRF: solo http/https, porte 80/443, blocco IP privati/loopback/link-local/CGNAT/metadati (IPv4+IPv6) dopo DNS e a ogni salto (anti DNS rebinding: connessione all'IP verificato), max 5 salti, timeout ≤ 3 s, body ≤ 1 MB, nessun cookie | Test con 127.0.0.1, 169.254.169.254, `[::1]`, redirect verso IP privato |
| SEC-33 | Quota e stato Pro verificati server-side (Play Integrity + Play Developer API); ID installazione anonimo | Test con token falso/scaduto |
| SEC-34 | Rate limiting per ID installazione e per IP; validazione input (URL ≤ 2048 caratteri) | Test di carico |
| SEC-35 | Log senza IP completo né URL completi oltre la cache; retention ≤ 30 giorni | Review config logging |
| SEC-36 | Certificate pinning valutato per l'endpoint serverless (con pin di backup) | ADR |
| SEC-37 | Notifiche push (Pro) senza contenuti sensibili nel payload | Review |

## 8. Rischi residui
- Regole pubbliche nel pacchetto ⇒ un truffatore può studiarle (mitigato in parte da euristiche server-side, §13).
- Device rooted/compromesso: fuori scope.
- Falsi negativi: mitigati solo da UX prudente (mai «Sicuro»).
