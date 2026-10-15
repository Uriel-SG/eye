# Eye — Audit di sicurezza F1 (F1-T20)

Data: 2026-10-11 · Revisore: SecDev · Ambito: `app/src/main/**`, `engine/src/main/**`, `app/proguard-rules.pro`, `gradle/libs.versions.toml`, APK release.
Riferimenti: `docs/security/THREAT_MODEL.md` (SEC-01..21), `docs/adr/ADR-001-decisioni-fase0.md`.
Nota: audit sullo stato del working tree alle 13:09 (in quel momento `Detectors.kt`, `PublicSuffix.kt` e `rules.json` risultavano modificati da DEV-A). Nessun file sorgente è stato modificato durante l'audit.

## Verifiche eseguite
- `./gradlew.bat :app:assembleRelease` → BUILD SUCCESSFUL; `app-release-unsigned.apk` da 3,6 MB.
- `aapt2 dump permissions` (build-tools 36.0.0) sull'APK release → `INTERNET`, `ACCESS_NETWORK_STATE`, `app.eye.android.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` (signature). Nessun permesso SMS, call log, accessibilità o `CAMERA`.
- `aapt2 dump xmltree AndroidManifest.xml` → esportati solo `MainActivity` (launcher + SEND) e `androidx.profileinstaller.ProfileInstallReceiver` (protetto da `android.permission.DUMP`). Tutti i provider risultano `exported=false`. `debuggable` assente (quindi false), `allowBackup=false`, `usesCleartextTraffic=false`.
- PoC su JVM (motore reale `engine.jar`, `RuleEngine.default()`), vedi H-1 e M-1.

## Tabella requisiti MVP

| Req | Esito | Evidenza |
|---|---|---|
| SEC-01 | PASS | `AndroidManifest.xml:15-44`: solo `MainActivity` esportata, `FileProvider` con `exported=false`. Nel manifest unito risulta esportato anche `ProfileInstallReceiver` (androidx, protetto da DUMP), che è accettabile. |
| SEC-02 | PASS | `AndroidManifest.xml:6-8` (`allowBackup=false`, `fullBackupContent=false`, `dataExtractionRules`); `res/xml/data_extraction_rules.xml` esclude root/file/database/sharedpref/external, sia per il backup cloud sia per il trasferimento tra dispositivi. |
| SEC-03 | PASS (nota) | `AndroidManifest.xml:9` `usesCleartextTraffic=false`. Il file `networkSecurityConfig` non esiste, ma con targetSdk 36 i trust anchor sono già solo quelli di sistema. Si consiglia un file esplicito (L-9). |
| SEC-04 | PASS (nota) | Nessuna WebView (grep). Appunti letti solo al tap (`HomeScreens.kt:191-197`). `CAMERA` non serve: si usa Google Code Scanner (`Scan.kt:53-66`). `ACCESS_NETWORK_STATE` e `INTERNET` arrivano dal manifest di ML Kit/datatransport (L-7). |
| SEC-05 | PASS | `CheckUseCase.kt:23` rifiuta oltre 10.000 caratteri; `RuleEngine.kt:32` tronca; `MainActivity.kt:35-45` controlla azione e MIME e prende solo `MAX_CHARS+1`; QR `AppViewModel.kt:61`; campo testo `HomeScreens.kt:186,190`. |
| SEC-06 | PASS con riserve | `Rules.kt:257-347` (RegexLint) e `RuleEngine.kt:9,49,55` (budget di 800 ms). Lacune del linter e budget silenzioso: L-4. La regex di `Redactor` (app) non passa dal linter: M-1. |
| SEC-07 | FAIL | `Extractors.kt:89` non considera `\` come delimitatore dell'authority, quindi il dominio estratto è diverso da quello che apre il browser (H-1). La schermata QR usa `android.net.Uri` e una PSL ridotta propria (`Scan.kt:23-42`), non quella del motore (L-5). |
| SEC-08 | PARZIALE | `Scan.kt:72-84`: `inSampleSize` limita il lato lungo solo a meno di 4096 px (non 2048). Mancano il limite di 20 MB e il timeout OCR, e `OutOfMemoryError` non viene catturato (`AppViewModel.kt:74`). Vedi L-2. |
| SEC-09 | PASS | QR mai aperto (`QrScreen.kt:20-60`, `Scan.kt:34-42`); `ExternalIntents.kt:12-25` accetta solo http/https, con dialogo di conferma (`VerdictScreen.kt:240-247`, `CatalogScreens.kt:130`). I link aperti sono solo canali ufficiali scritti nel codice. |
| SEC-10 | PASS | `RulePackVerifier.kt:55-96`: ordine §5 rispettato, firma verificata prima di gunzip e parse, envelope letto con schema rigido. |
| SEC-11 | PASS (aperto U5) | `RulePackVerifier.kt:13-19,65`: keyId sconosciuto viene rifiutato. `PinnedKeys.production` è vuota: oggi nessun pacchetto scaricato è accettato. |
| SEC-12 | PARZIALE | Il verificatore rifiuta `version <= installed` (`RulePackVerifier.kt:67`). Il pacchetto incluso nell'APK (`RuleEngine.kt:130-134`) non è firmato e resta fuori dalla catena anti-rollback. Un file versione corrotto riporta la versione a 0 (`RulePackStore.kt:158-160`). Vedi L-3. |
| SEC-13 | N.A. (download non implementato) | I limiti di 2 MB e 10 MB e lo swap atomico ci sono già (`RulePackVerifier.kt:57,106,117-118`; `RulePackStore.kt:164-178`), ma non esiste ancora un client HTTP. |
| SEC-14 | PASS | Nessun `Log.`/`println`/`printStackTrace` in `app/src/main` e `engine/src/main` (grep). |
| SEC-15 | PARZIALE | `app/build.gradle.kts:21-23` (R8 e shrinkResources attivi); APK senza `debuggable`. `app/proguard-rules.pro` contiene solo un commento: manca `-assumenosideeffects` per `Log` (L-1). |
| SEC-16 | N.A. | ADR-001 §1: FLAG_SECURE non usato (decisione documentata). |
| SEC-17 | FAIL | Manca `gradle/verification-metadata.xml`. Versioni bloccate nel catalog, ma `play-services-*` scritte inline (`libs.versions.toml:41-42`). ML Kit include `com.google.android.datatransport` (backend CCT, telemetria verso Google): M-3. |
| SEC-18 | FAIL | Nessuna CI e nessun gitleaks (`.github` assente). `.gitignore` non contiene `*.key`, `*.key.pem`, `*.age`, `*.pem`, richiesti da §6. Nel repo non ci sono segreti reali: `.env` è ignorato; le chiavi `TEST-*.testkey.pem` sono solo di test. Vedi M-4. |
| SEC-19 | PASS (nota) | `History.kt:57-61` (purge a 7 giorni al salvataggio) ed `EyeApp.kt:17` (all'avvio); `AppViewModel.kt:102,106` (cancellazione totale). Manca `secure_delete` (L-8). |
| SEC-20 | PASS | `res/xml/file_paths.xml` (solo `cache/share/`); `ShareCard.kt:151-168` (FileProvider non esportato, solo `FLAG_GRANT_READ_URI_PERMISSION`, chooser su azione dell'utente, pulizia dopo un'ora); nessun invio automatico. |
| SEC-21 | PASS | Nessun `PendingIntent`. Gli intent impliciti sono solo `ACTION_SEND` con chooser e `ACTION_VIEW` http/https (`ExternalIntents.kt`). |

## Findings (dal più grave)

### H-1 — ALTO — Parsing URL: un `\` nell'authority fa estrarre il dominio ufficiale al posto di quello reale
- **Dove**: `engine/src/main/kotlin/app/eye/engine/Extractors.kt:89` (`authEnd` si ferma solo su `/ ? #`), poi `:92-94` (`lastIndexOf('@')`). Il problema analogo è in `app/.../core/Scan.kt:36-38` (`android.net.Uri.getHost`).
- **Problema**: per gli schemi http/https i browser (WHATWG URL) trattano `\` come `/`. Il motore invece lo considera parte dell'host.
- **PoC** (motore reale, testo «Poste Italiane: il tuo conto è sospeso, accedi qui …»):
  - `https://poste-verifica.top/login` → PROBABILE_TRUFFA, score 100.
  - `https://evil.top\.poste.it/login` → **SOSPETTO 28**, dominio mostrato `poste.it`, scatta **OFF-01 (-25, «dominio ufficiale»)**. Il browser apre `evil.top`.
  - `https://evil.top\@poste.it/login` → SOSPETTO 53, dominio mostrato `poste.it`.
- **Impatto**: A4. Il truffatore abbassa il verdetto con un solo carattere e la UI mostra in grassetto «Dominio: poste.it». Linkify nelle app SMS rende cliccabile `https://evil.top`.
- **Fix**:
  1. In `Extractors.parse`, per gli schemi http/https usare `authEnd = rest.indexOfFirst { it == '/' || it == '?' || it == '#' || it == '\\' }`.
  2. Dopo `Text.toASCII` rifiutare gli host con caratteri fuori da `[a-z0-9.-]`, usando `IDN.toASCII(host, IDN.ALLOW_UNASSIGNED or IDN.USE_STD3_ASCII_RULES)`. Un URL con schema ma host non valido deve dare un segnale positivo (nuovo rilevatore `malformed_url`), mai OFF-01.
  3. `officialDomains` deve richiedere che `raw` non contenga `\`.
  4. Aggiungere i test: `https://evil.top\.poste.it/x`, `https://evil.top\@poste.it`, `https://evil.top%5C.poste.it`, `https:\\evil.top`. Esito atteso: dominio `evil.top`, OFF-01 assente.
  5. Nella schermata QR non usare `android.net.Uri` per host e registrabile: esporre dal motore una `fun describeUrl(raw): UrlInfo?` pubblica (vedi L-5).
- File di DEV-A (`engine/**`): da coordinare.

### M-1 — MEDIO — `Redactor`: StackOverflowError (crash) con input lungo a segmenti puntati
- **Dove**: `app/.../core/Redactor.kt:5` (`(?:\.[a-z0-9-]+)*` è ricorsivo in `java.util.regex`), chiamato da `CheckUseCase.kt:27`, `ShareCard.kt:82` e `VerdictText.kt:69`. Le chiamate catturano solo `Exception` (`AppViewModel.kt:94`, `VerdictScreen.kt:102`).
- **PoC (JVM)**: 2.000 caratteri `a.a.a…` passano in 5 ms; da 4.000 caratteri in su si ha `StackOverflowError`. Su Android la regex è ICU, con stack su heap e un limite proprio: va verificato con un test strumentato. In ogni caso un `Error` non catturato chiude l'app.
- **Impatto**: un messaggio costruito ad arte (sotto i 10.000 caratteri) fa crashare il controllo, quindi l'utente non riceve il verdetto (DoS locale) e i test JVM di `:app` restano esposti.
- **Fix**: sostituire con `\b[a-z0-9][a-z0-9.-]*\.(?:tld)\b/?\S*` (la classe di caratteri è iterativa, senza gruppo ripetuto). Applicare il Redactor token per token (`\S+`, al massimo 2.048 caratteri per token). In caso di `Throwable` restituire `"[contenuto nascosto]"`. Aggiungere un test con 10.000 caratteri `a.` e `1 `.

### M-2 — MEDIO — Oscuramento incompleto: OTP, IBAN minuscoli, email, codice fiscale e domini con altri TLD finiscono in cronologia e nel report
- **Dove**: `Redactor.kt:5-7`. I dati finiscono in cronologia (`CheckUseCase.kt:27`, conservata 7 giorni), nel «Copia report» (`VerdictText.kt:69`, sempre, anche se `showMessage=false`) e nella scheda condivisa con `showMessage` attivo (`ShareCard.kt:82`).
- **PoC**: `codice OTP 482913`, `PIN 1234`, `mario.rossi@azienda.eu`, `RSSMRA80A01H501U` e `iban it60x0542811101000000123456` restano in chiaro. `poste-verifica.app` e `bancaintesa-sicura.eu` non vengono oscurati.
- **Fix**:
  - oscurare le sequenze di 4 o più cifre (`(?<![\w])\d(?:[\s.-]?\d){3,}(?!\w)`);
  - IBAN case-insensitive per qualunque paese (`(?i)\b[a-z]{2}\d{2}(?: ?[a-z0-9]){11,30}\b`);
  - email (`\S+@\S+`);
  - codice fiscale (`(?i)\b[a-z]{6}\d{2}[a-z]\d{2}[a-z]\d{3}[a-z]\b`);
  - domini: riusare l'estrattore del motore (tutti i TLD noti della PSL) invece della lista fissa di 18 TLD;
  - nel report copiato includere l'anteprima solo se `showMessage` è attivo.

### M-3 — MEDIO — SEC-17: dipendenze non verificate e telemetria di ML Kit
- **Dove**: manca `gradle/verification-metadata.xml`; `libs.versions.toml:41-42`; nel manifest unito ci sono `TransportBackendDiscovery`/`CctBackendFactory`, `JobInfoSchedulerService` e `AlarmManagerSchedulerBroadcastReceiver` di `com.google.android.datatransport`.
- **Impatto**: (a) non c'è protezione da artefatti sostituiti nella supply chain (T15); (b) ML Kit e Code Scanner inviano a Google metriche d'uso e diagnostica. Questo è in tensione con «nessun SDK di terze parti che raccoglie dati» e con la Data Safety «nessun dato raccolto».
- **Fix**:
  - `./gradlew.bat --write-verification-metadata sha256 :app:assembleRelease`, poi committare il file;
  - spostare le due versioni `play-services-*` in `[versions]`;
  - con GDPR: documentare il flusso ML Kit in Data Safety e nell'informativa (dati gestiti da Google Play services) oppure valutarne la rimozione.

### M-4 — MEDIO — SEC-18: niente secret scanning e `.gitignore` incompleto prima della generazione delle chiavi (U5)
- **Dove**: `.gitignore` (c'è solo `keys/private*`); nessuna CI. Le chiavi private di test `engine/src/test/resources/rulepack/TEST-eye-{a,b}.testkey.pem` sono tracciate.
- **Fix**:
  - aggiungere a `.gitignore` le voci `*.key`, `*.key.pem`, `*.age`, `*.pem` e `!engine/src/test/resources/rulepack/TEST-*.testkey.pem`;
  - hook pre-commit gitleaks e workflow CI con gitleaks, più `:engine:test` e `lintRelease`;
  - test che fallisce se una chiave pubblica in `PinnedKeys.production` coincide con una chiave di test.

### L-1 — BASSO — SEC-15: `proguard-rules.pro` vuoto
Aggiungere:
```
-assumenosideeffects class android.util.Log { public static int v(...); public static int d(...); public static int i(...); }
```
Oggi nessuna chiamata `Log` è presente: è difesa in profondità.

### L-2 — BASSO — SEC-08: decodifica immagini e OCR senza limiti completi
- **Dove**: `Scan.kt:81` e `AppViewModel.kt:69-77`.
- **Fix**:
  - `while (max / sample > 2048) sample *= 2`;
  - rifiutare i file oltre 20 MB (`openAssetFileDescriptor(uri,"r")?.length`);
  - `withTimeout(10_000)` su `OcrReader.read`;
  - catturare anche `OutOfMemoryError` in `ocr()`.

### L-3 — BASSO — SEC-12/13: anti-rollback incompleto
- **Dove**: `RulePackStore.kt:158-160` (versione corrotta = 0) e `RuleEngine.kt:130-134` (pacchetto incluso non firmato).
- **Fix**:
  - persistere la versione massima anche nel DataStore, e in caso di errore di lettura trattarla come `Long.MAX_VALUE` (fail-closed) invece di 0;
  - alla scelta delle regole usare `max(bundled.version, downloaded.version)`;
  - quando si implementa il download: client solo HTTPS, lettura limitata a 2 MB in streaming;
  - dichiarare `INTERNET` esplicitamente nel manifest dell'app.
- Allineare il threat model §5 al dominio di firma reale, che è `EYE-RULES-v1\n` (il testo dice ancora `OCCHIO-RULES-v1`).

### L-4 — BASSO — SEC-06: lacune del linter e budget silenzioso
- **Dove**: `Rules.kt:314,333-336`. `?` viene ignorato e un gruppo che contiene quantificatori limitati non conta come «unbounded». Per questo sono accettati `(a{1,10})+x`, `(ab?)+x`, `(\w\w?)+x` e `\s+\s+x` (verificato). Inoltre `RuleEngine.kt:55,60-61,67-68`: se il budget si esaurisce, le regole rimanenti vengono saltate senza segnalarlo e il verdetto può risultare NESSUN_SEGNALE.
- **Fix**:
  - nel linter, marcare il frame come «variabile» per qualunque quantificatore (`?`, `{m,n}`, `*`, `+`) e rifiutare un quantificatore illimitato su un gruppo variabile;
  - rifiutare due quantificatori illimitati adiacenti su classi sovrapposte;
  - quando scatta `BudgetExceeded` o la deadline, il risultato deve essere almeno SOSPETTO, con il motivo «Analisi incompleta: messaggio troppo complesso».

### L-5 — BASSO — SEC-07 nella schermata QR
`Scan.kt:23-42` usa una PSL ridotta (8 suffissi) e mostra l'host in Unicode: un omografo cirillico come `pоste.it` appare identico all'originale. Fix: usare l'estrattore del motore (PSL completa, host ASCII/punycode affiancato all'Unicode, flag omografo) già prima del tap su «Controlla».

### L-6 — BASSO — Condivisione immagine ignorata su Android 8-12
`MainActivity.kt:43`: `getParcelableExtra(String, Class)` esiste solo da API 33 (minSdk 26). La chiamata fallisce con `NoSuchMethodError`, l'errore viene assorbito da `runCatching` e l'immagine viene ignorata senza avviso. Fix: `IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)`.

### L-7 — INFO — Permessi aggiunti dalle librerie
`INTERNET` e `ACCESS_NETWORK_STATE` arrivano dalle librerie GMS (l'app non li dichiara, `AndroidManifest.xml:3`). Sono permessi «normal» e accettabili, ma il criterio di accettazione FASI («solo CAMERA, INTERNET») va aggiornato: `CAMERA` non serve e `ACCESS_NETWORK_STATE` va documentato. In alternativa si può rimuovere con `tools:node="remove"` se non è necessario a Play services.

### L-8 — BASSO — Cancellazione non fisica in SQLite
`History.kt`: le righe cancellate restano nelle pagine libere e nel WAL. Fix: `RoomDatabase.Callback.onOpen { db.execSQL("PRAGMA secure_delete=ON") }` (`AppContainer.kt:15`).

### L-9 — INFO — `networkSecurityConfig` esplicito
Aggiungere `res/xml/network_security_config.xml` con `<base-config cleartextTrafficPermitted="false"><trust-anchors><certificates src="system"/></trust-anchors></base-config>`, per rendere SEC-03 verificabile e resistente a regressioni.

## Verdetto gate F1-T20

**RESPINTO.** Il criterio di FASI F1 è «nessun finding alto aperto», e H-1 è aperto (riproducibile, fa scendere un phishing a SOSPETTO e mostra il dominio ufficiale).

Per lo sblocco:
- **Obbligatorio**: fix di H-1 con i test di regressione indicati, poi re-review mirata su `Extractors.kt` e `Scan.kt`.
- **Fortemente raccomandato prima della release**: M-1 e M-2.
- **Da chiudere entro la Fase Pro / prima della pubblicazione**: M-3 e M-4.

Il resto (manifest, export, backup, FileProvider, link, firma Ed25519) è conforme.

---

## Ri-audit mirato (2026-10-11, commit `ceab287`)

Verifiche:
- `:engine:jar` e `:app:assembleRelease` vanno a buon fine; APK release da 2,4 MB.
- PoC sul motore reale (`RuleEngine.default()` e `UrlInfo`), sempre con il testo «Poste Italiane: il tuo conto è sospeso, accedi qui <URL>».
- `aapt2 dump permissions/xmltree` sull'APK release.

### Esito dei finding precedenti
| ID | Esito | Evidenza |
|---|---|---|
| H-1 | **CHIUSO** | `Extractors.kt` (`schemeSep` accetta `[/\]{2,}`, `authEnd` include `\`, decodifica percentuale, `validHost`, IPv6/porta). Varianti con `\` singolo e multiplo, `\@`, schema maiuscolo o misto (`hTtPs:\`), `https:/\`, `%2e`, `%40`, `%5c`, userinfo `poste.it@` e `poste.it:443@`, `。` U+3002, cirillico `pоste.it` e punycode danno tutte `evil.top` (o URL scartato), con PROBABILE_TRUFFA oppure senza OFF-01. `Scan.kt` ora usa `UrlInfo` (stesso parser del motore): chiude anche L-5. |
| M-1 | **CHIUSO** | `Redactor.kt` scansiona token per token in modo lineare; regex solo con ripetizioni limitate (max 30); token oltre 2.048 caratteri sostituiti con `HIDDEN`; `catch Throwable` → `HIDDEN`. |
| M-2 | **CHIUSO** | Oscurate le cifre ≥4, gli IBAN case-insensitive di ogni paese, codice fiscale, email e qualunque dominio con TLD alfabetico. Nel report l'anteprima c'è solo se `includeMessage` è attivo; i domini sono «defanged». |
| M-3 | **CHIUSO (residuo)** | `gradle/verification-metadata.xml`: 812 hash sha256, `verify-metadata=true`; `verify-signatures=false` è accettabile per ora. I componenti datatransport (servizi, receiver, metadati CCT) risultano assenti dal manifest dell'APK (`grep -c datatransport` = 0). **Residuo**: fare uno smoke test su dispositivo di QR e OCR (nessun crash, logcat pulito), perché ML Kit potrebbe tentare lo scheduling senza `JobInfoSchedulerService`. |
| M-4 | **CHIUSO (CI rinviata a F2)** | `.gitignore` ora copre `*.key`, `*.pem` (con eccezione per le `*.testkey.pem` di test), `*.age`, `*.p12` e `secrets/`. `.gitleaks.toml` usa le regole di default e mette in allowlist solo le chiavi di test. Da fare in F2: CI con gitleaks e hook pre-commit. |
| L-6 | **CHIUSO** | `MainActivity.kt` usa `IntentCompat.getParcelableExtra`. |
| L-7 | **CHIUSO** | `INTERNET` è ora dichiarato in `AndroidManifest.xml`. Permessi nell'APK: `INTERNET`, `ACCESS_NETWORK_STATE` (GMS, normal) e `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` (signature). |
| L-1, L-2, L-3, L-4, L-8, L-9 | Aperti (bassi) | Invariati. |

### `slimBouncyCastle` (`engine/build.gradle.kts:6-23`)
Rischio supply-chain **basso**:
- la configurazione `bcSource` risolve `bcprov-jdk18on:1.84`, che passa dalla dependency verification (hash in `verification-metadata.xml`, riga 2419), e il jar ricostruito contiene solo le sue `.class`;
- tolto `META-INF` si perdono le firme JAR di BC, ma Android non le verifica.

Osservazioni:
- (a) bcprov non compare più nel `runtimeClasspath` come modulo, quindi gli strumenti SCA (OWASP Dependency-Check, Dependabot) non lo vedono. Va aggiunto a mano al perimetro SCA, oppure si scansiona la configurazione `bcSource`.
- (b) La licenza BC (MIT-like) richiede la nota di copyright: va inclusa nelle licenze open source dell'app.
- (c) Gli aggiornamenti di versione restano centralizzati nel catalog: bene.

### Nuovi finding
**H-2 — ALTO — URL attaccato a punteggiatura: il motore non lo vede.** `Extractors.urls`/`parse` (`noScheme` ancorato con `^`; `LEAD` contiene solo `([{<"'«“‘`; `schemeSep` richiede `//`).

PoC (motore reale):

| Input | Esito |
|---|---|
| `accedi:evil.top/login www.poste.it` | SOSPETTO 28, dominio `poste.it`, **OFF-01 (-25)** |
| `*evil.top/login* www.poste.it` | SOSPETTO 28, OFF-01 |
| `->evil.top/login www.poste.it` | SOSPETTO 28, OFF-01 |
| `=evil.top/login www.poste.it` | SOSPETTO 28, OFF-01 |
| `accedi:poste-verifica.top/login` | 53, nessun dominio (con uno spazio dopo `:` sarebbe 100) |
| `https:poste-verifica.top/login` (WHATWG lo apre come `https://poste-verifica.top/login`) | 53 |

Impatto: identico a H-1 (A4). Un solo carattere attaccato al link azzera i segnali sul link; se nel testo compare anche un link ufficiale, il verdetto coincide con quello di un messaggio legittimo e mostra «Dominio: poste.it». Le app SMS (Linkify/`Patterns.WEB_URL`) rendono comunque cliccabile `evil.top/login`.

Fix:
1. In `urls()`, cercare candidati *dentro* il token invece di fare il parse del token intero: scansione lineare che spezza il token sui caratteri esclusi da host e URL, cioè tutto tranne `[\p{L}\p{N}.\-_~:/?#\[\]@!$&'()*+,;=%\]`, tenendo uniti `scheme://`. In alternativa, cercare la prima posizione da cui `parse()` riesce, partendo da ogni confine tra un carattere non-host e un carattere host.
2. Accettare `http:`/`https:` seguiti da zero o una barra (WHATWG).
3. OFF-01 (`officialDomains`) e `shortener_official_context` scattano solo se nel testo non resta alcun frammento «simile a un dominio» non estratto (`[\p{L}\p{N}-]+\.[\p{L}]{2,}`) e nessun URL scartato come non valido (`%2e`, `%5c`, `／`, `‥`). Altrimenti va emesso un nuovo segnale positivo «link in formato anomalo».
4. Test di regressione con tutte le righe della tabella PoC.

**M-5 — MEDIO — Tab o a capo dentro l'URL (QR).** Il payload QR `https://poste.it<TAB>@evil.top/x` (anche con LF) dà SOSPETTO 28, dominio `poste.it`, OFF-01. Il browser elimina TAB/LF/CR (WHATWG) e apre `evil.top`. In `Scan.kt` il contenuto con spazi bianchi viene trattato come testo e poi analizzato dal motore, quindi l'utente vede «poste.it». Fix: in `QrContent.parse` e per `Source.QR`, rimuovere `\t\r\n` prima di `UrlInfo` e dell'analisi; nel motore, un token che finisce con `://…` seguito da whitespace e poi da un token che inizia con `@` deve essere riunito (o segnalato).

**L-10 — BASSO — `isInstitutional` accetta `comune.it`, `regione.it`, `provincia.it`.** `Detectors.kt:30-31`: questi sono normali domini `.it` registrabili da privati e ottengono OFF-01 (verificato: `https://comune.it/x` → OFF-01). Fix: richiedere almeno 3 etichette (`d.count { it == '.' } >= 2`).

### Verdetto ri-audit
**RESPINTO.** H-1 e i quattro MEDI sono chiusi, ma il nuovo H-2 (stessa classe di H-1, già presente prima e non rilevato nel primo giro) è un finding alto aperto.

Per lo sblocco:
- fix di H-2 con i test della tabella PoC;
- fortemente consigliato insieme M-5 (riguarda la stessa funzione) e L-10 (una riga);
- re-review limitata a `Extractors.urls`, `Detectors.officialDomains`, `Scan.kt`.

---

## Verifica finale (2026-10-11, commit `5b1fac8`)

Ambito: H-2, M-5, L-10 e la nuova regola URL-10 (`Extractors.scan`/`parseLoose`/`unresolvedFragments`, `Detectors.unresolved_link`, `RuleEngine.kt`, `rules.json` URL-10 con peso 30, `UrlEvasionTest.kt`). PoC sul motore reale (`engine.jar` ricompilato).

| ID | Esito | Evidenza |
|---|---|---|
| H-2 | **CHIUSO** | `accedi:`, `*…*`, `->`, `=`, `https:` senza barre, `«…»`, `[.]`, `(.)` e testo tutto maiuscolo estraggono `evil.top`: PROBABILE_TRUFFA 73, senza OFF-01. `//evil.top`, `evil.top⁄login` (U+2044), `evil.top%2flogin` e `poste.it∕evil.top` danno URL-10 e restano PROBABILE_TRUFFA. |
| M-5 | **CHIUSO** | `https://poste.it<TAB>@evil.top` e `<LF>@evil.top` estraggono anche `evil.top`: PROBABILE_TRUFFA 73. |
| L-10 | **CHIUSO** | `isInstitutional` richiede almeno 3 etichette. |
| URL-10 (abuso) | **OK** | 13 messaggi legittimi o banali (punti senza spazio come `Ciao.Ci`, `Arrivato.Io`, `confermata.Info`, `Bonus.Tv`; `S.p.A.`; email `assistenza@poste.it`; `report.pdf`; `www.poste.it/faq.Il`; link ufficiale completo) danno sempre NESSUN_SEGNALE, mai URL-10. I falsi «domini» del tipo `ciao.ci` vengono estratti come URL, non contati come anomalie, e non producono punteggio. Un terzo non può aggiungere URL-10 a un messaggio che non scrive lui. Nota: URL-10 da solo (30) supera la soglia di SOSPETTO (25); per questo va tenuto monitorato nei falsi positivi del corpus. |

### Nuovo finding
**H-3 — ALTO — Caratteri invisibili dentro il dominio.**

PoC:

| Input | Esito |
|---|---|
| `evil\u00AD.top/login www.poste.it` (soft hyphen) | SOSPETTO 28, dominio `poste.it`, **OFF-01** |
| `evil\u200B.top/login www.poste.it` (ZWSP) | SOSPETTO 28, dominio `poste.it`, **OFF-01** |

Il motore non vede il link (non c'è nemmeno un'anomalia). Linkify invece lo rende cliccabile: `Patterns.WEB_URL` accetta i caratteri da U+00A0 a U+D7FF nelle etichette. Il browser poi, con l'IDNA UTS #46, elimina U+00AD e U+200B e apre `evil.top`. È la stessa classe di H-1 e H-2.

Fix (una riga): in `RuleEngine.analyze` passare a `Extractors.scan` il testo senza caratteri invisibili, usando lo stesso `Text.invisible` (U+00AD, U+200B-U+200F, U+202A-U+202E, U+2060-U+2064, U+FEFF). Meglio ancora: se un token contiene un carattere invisibile e diventa un URL solo dopo la rimozione, contare un'anomalia (URL-10).

Test da aggiungere a `UrlEvasionTest`: le due righe della tabella PoC, più `\u2060` e `\uFEFF`. Esito atteso: dominio `evil.top`, nessun OFF-01.

Residui bassi, non bloccanti: `e v i l . t o p` e `evil .top` (non sono cliccabili). L-1, L-2, L-3, L-4, L-8 e L-9 restano aperti.

### Verdetto finale gate F1-T20
**RESPINTO.** Il criterio è «nessun alto aperto» e H-3 lo è.

Il fix richiesto è minimo (normalizzare gli invisibili prima di `Extractors.scan`, più i test). Diventa **APPROVATO** quando `UrlEvasionTest` contiene i casi H-3 e risulta verde: basta questa verifica, senza un nuovo audit completo. Tutti gli altri finding ALTI e MEDI (H-1, H-2, M-1…M-5) sono chiusi.
