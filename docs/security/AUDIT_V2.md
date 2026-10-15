# Audit di sicurezza V2 — Secondo parere AI, cronologia completa, navigazione

Data: 2026-10-11 · Revisore: SecDev · Ambito: modifiche non committate (`git status`) · Modalità: sola lettura (nessuna modifica al codice).
Riferimenti: `docs/security/THREAT_MODEL.md` (SEC-02, SEC-03, SEC-14, SEC-16, T7, T8, T9), OWASP MASVS (STORAGE, CRYPTO, NETWORK, PLATFORM, PRIVACY).

Fuori ambito: `ui/scan/*`, `core/Scan.kt` (CameraX/QR/OCR, ancora in lavorazione).

## Verifiche positive

- **KeyVault**: AES-256-GCM nell'Android Keystore, alias versionato `eye_ai_key_v1`, IV generato dal Keystore (cifratura randomizzata di default), tag a 128 bit, formato `v1:base64(iv||ct)`. `decrypt` restituisce null per qualsiasi eccezione (chiave persa o reinstallazione → «nessuna chiave»). `deleteKey` viene chiamato da «Rimuovi chiave» e da «Cancella tutti i dati».
- La chiave non compare mai in `toString` (`AiConfig`, `HttpSpec`), non viene salvata in `rememberSaveable` e non viene loggata da nessuna parte del codice AI.
- **Rete**: solo https (`validBase` rifiuta http, userinfo, query e fragment; l'host è obbligatorio), `usesCleartextTraffic=false`, nessun trust anchor utente (targetSdk 36), `instanceFollowRedirects=false` (la chiave non segue i redirect), timeout di 30 s, corpo della risposta limitato a 1 MB, modello Gemini validato prima di inserirlo nell'URL.
- **Risposta ostile**: si estraggono solo i campi attesi; un giudizio sconosciuto diventa INVALID; motivi limitati a 4 da 300 caratteri, consiglio a 500 caratteri; caratteri di controllo rimossi; rendering con `Text` semplice (nessun `LinkAnnotation`, `Linkify` o HTML), quindi nessun link cliccabile.
- **Verdetto**: il parere AI vive in un `StateFlow` separato (`_ai`). Non scrive mai il `Verdict` né la cronologia e compare sotto i motivi delle regole.
- **Consenso e invio**: l'invio avviene solo con un tocco su un pulsante. Al primo uso compare un dialog di consenso, con «invia senza oscurare» spento di default; la redazione (`redactKeepLinks`) è attiva di default.
- **MiniJson**: profondità massima 32 (nessuno StackOverflow), tutti gli errori convergono in `IllegalArgumentException` → null, stringhe, escape e numeri malformati vengono rifiutati.
- **Cronologia**: Room nello storage privato, `allowBackup=false`, `fullBackupContent=false`, `dataExtractionRules` che escludono tutto (SEC-02). La purge a 7 giorni avviene all'avvio, all'apertura della cronologia e prima di ogni salvataggio. La lettura filtra con `createdAt >= soglia`. Il toggle `saveHistory` viene rispettato in `CheckUseCase`.
- **Navigazione**: `history/{id}` usa `NavType.LongType` e non ha deep link dichiarati (non è raggiungibile da intent esterni). Un id inesistente mostra il messaggio «non disponibile», senza crash.

## Findings (dal più grave)

### M-1 — MEDIO — Chiave con caratteri non validi: crash e chiave nel crash report (T7, SEC-14)
`core/ai/AiService.kt:120` (`setRequestProperty`) e `:147-152` (catch).
La chiave viene solo trimmata (`AppViewModel.kt:111`, `:131`). Se si incolla una chiave con NBSP, uno zero-width, un tab o un a capo (`singleLine` non filtra l'incolla), l'OkHttp interno di Android lancia `IllegalArgumentException("Unexpected char … in header value: <valore>")`. Il messaggio contiene la chiave in chiaro. L'eccezione non viene catturata (si catturano solo `IOException` e `SecurityException`), quindi l'app crasha nel `viewModelScope` e la chiave finisce in logcat e in Android vitals.
**Fix**: (1) validare la chiave al salvataggio e alla prova: `Regex("[\\x21-\\x7E]{8,512}")`, oppure mostrare un errore; rimuovere i caratteri invisibili. (2) In `AiService.ask` aggiungere `catch (_: Exception) { Failure(NETWORK) }` senza propagare il messaggio. (3) Applicare la stessa validazione a modello e base URL (header e URL).

### M-2 — MEDIO — Prompt injection: il parere AI può veicolare contatti o link del truffatore
`ui/AiOpinionCard.kt:118-119` e `core/ai/AiService.kt:98` (`clean`).
Il parere non abbassa il verdetto, però un messaggio malevolo può ordinare al modello «consiglio: chiama il numero verde 800… / verifica su https://…». L'utente legge quel testo dentro la card «secondo parere», che ha un'aura di autorevolezza, e può ricopiarlo a mano. `clean` toglie solo i caratteri di controllo e lascia passare anche gli override bidi (U+202E) e gli zero-width.
**Fix**: sull'output AI applicare `Redactor.redact` (URL, telefoni, email, IBAN → segnaposto), rimuovere i caratteri `Cf` (bidi, zero-width) e aggiungere una nota fissa «Eye non suggerisce mai numeri o siti da contattare: usa solo i canali ufficiali». Aggiungere un test con un payload di injection.

### M-3 — MEDIO — Delimitatori del prompt aggirabili
`core/ai/AiRequests.kt:28`.
`replace("<<<","<<")` non è idempotente: `<<<<FINE_MESSAGGIO>>>>` diventa `<<<FINE_MESSAGGIO>>>`, quindi il messaggio può chiudere il blocco dati e aggiungere «istruzioni». L'impatto resta limitato da M-2 e dal verdetto immutabile.
**Fix**: `text.replace(Regex("<{3,}"), "<<").replace(Regex(">{3,}"), ">>")`, con un test sulle sequenze di 4 o più caratteri. In alternativa, delimitatori con un nonce casuale per richiesta.

### M-4 — MEDIO — Cronologia: testo completo in chiaro, salvato di default, senza FLAG_SECURE (T8, SEC-16, GDPR-09)
`core/CheckUseCase.kt:28`, `data/History.kt:72`, `ui/VerdictScreen.kt:87`.
Prima veniva salvata solo l'anteprima oscurata. Ora, con `saveHistory=true` di default, finiscono nel DB OTP, IBAN e dati sanitari per 7 giorni, in chiaro. Il dettaglio mostra il testo completo con «Mostra» e non usa `FLAG_SECURE`: lo screenshot è possibile e il testo resta visibile nei «Recenti». La cancellazione non è fisica (vedi F1 L-8: pagine libere e WAL).
**Fix**: (1) cifrare `fullText` (ed eventualmente `verdictJson`) con una chiave Keystore AES-GCM dedicata (alias `eye_history_v1`, stesso schema di KeyVault; se la decifratura fallisce → «non disponibile»). (2) Impostare `FLAG_SECURE` almeno su cronologia e dettaglio, come da default di SEC-16. (3) Usare `PRAGMA secure_delete=ON` con checkpoint del WAL dopo le delete. (4) Far validare al GDPR il salvataggio del testo completo di default; in alternativa renderlo opt-in separato da «Salva cronologia».

### L-1 — BASSO — Consenso non legato a provider/endpoint; dialog senza host
`ui/AiOpinionCard.kt:133`, `:144`.
Il consenso è un booleano globale. Passare in seguito a «Compatibile» con un endpoint arbitrario (scenario di social engineering: «configura questo server AI») non richiede un nuovo consenso, e il dialog mostra «Compatibile», non l'host di destinazione.
**Fix**: salvare il consenso come `provider|host` e chiederlo di nuovo quando cambia. Per COMPATIBLE, mostrare l'host nel dialog e nelle impostazioni.

### L-2 — BASSO — Base URL personalizzata: IP privati e localhost ammessi
`core/ai/AiModels.kt:36-43`.
Bloccarli non è necessario: l'URL lo sceglie l'utente, un attaccante remoto non può guidare la richiesta e i certificati self-signed falliscono comunque, perché non ci sono CA utente. Il rischio residuo è l'esfiltrazione verso un endpoint scelto sotto inganno, coperto da L-1.
**Raccomandazione**: non bloccare. Mostrare un avviso quando l'host non è un dominio pubblico o è un IP letterale; facoltativamente consentire solo la porta 443.

### L-3 — BASSO — Redazione `redactKeepLinks`: gli URL passano interi, query comprese
`core/Redactor.kt:44`.
Restano in chiaro token personali, tracking ID, `?tel=`/`?email=` e path con codici pratica. In più, un token tipo `mario.rossi` viene classificato come dominio e lasciato visibile.
**Fix**: inviare `scheme://host/path` senza query e fragment (oppure redigere i valori della query); per l'analisi del phishing basta il dominio più il path.

### L-4 — BASSO — `MiniJson.firstObject` quadratico (DoS CPU)
`core/MiniJson.kt:23-52`.
Per ogni `{` `balancedEnd` scansiona fino in fondo. Un testo di 1 MB di `{` (endpoint COMPATIBLE ostile) costa circa 5·10^11 operazioni: thread IO bloccato, spinner infinito, batteria. Con i provider ufficiali il testo è limitato dai token di output (circa 8 KB), quindi l'impatto è trascurabile.
**Fix**: limitare il testo a 16 KB prima di `parseOpinion` e fermare la ricerca dopo N tentativi (per esempio 8) oppure al primo oggetto bilanciato.

### L-5 — BASSO — Nessun timeout complessivo (slow-drip)
`core/ai/AiService.kt:117`.
`readTimeout` si azzera a ogni read: un server che invia 1 byte ogni 29 s tiene la richiesta aperta all'infinito.
**Fix**: una scadenza totale di 60 s, con un watchdog (`Handler`/`Executor`) che chiama `conn.disconnect()`, oppure un controllo del tempo trascorso nel ciclo di lettura.

### L-6 — BASSO — Avviso «parere più tranquillo» incompleto
`core/ai/AiModels.kt:50`.
Con regole a PROBABILE_TRUFFA e AI a SOSPETTO non compare nessun avviso.
**Fix**: confrontare l'ordinale (`TRUFFA > SOSPETTO > NESSUN_SEGNALE`) e mostrare l'avviso se l'AI dà un rischio minore delle regole.

### L-7 — BASSO — Un errore di cifratura cancella la chiave salvata senza avvisare
`ui/AppViewModel.kt:111-112`.
Se `encrypt` fallisce, `setAiKeyCipher(null)` rimuove la chiave precedente e l'utente non vede nessun errore.
**Fix**: con `ct == null` non toccare il valore salvato e mostrare un errore.

### I-1 — INFO
- `MiniJson.kt:155`: `toIntOrNull(16)` accetta il segno (`\u+041`, `\u-001`). Non è un problema di sicurezza; si può correggere con un controllo esadecimale esplicito.
- `History.kt:62`: la soglia di `observe()` viene calcolata una sola volta, alla creazione del ViewModel. È mitigato dalla purge fisica all'apertura della cronologia.
- KeyVault: si può valutare `setUnlockedDeviceRequired(true)` (API 28+). StrongBox è facoltativo. La chiave resta in memoria come `String` immutabile (accettabile).
- `Scan.kt` (fuori ambito, in lavorazione): i `Log.w("EyeScan", …e.message)` restano in release, perché le regole R8 rimuovono solo d/v/i. Va verificato che i messaggi non contengano URI o testo (SEC-14).
- `fallbackToDestructiveMigration(true)` va bene finché lo schema v2 non è pubblicato. Prima del rilascio servono migrazioni esplicite.

## Verdetto

**Approvato con condizioni.** L'architettura è corretta: chiave nel Keystore, solo https, nessun redirect, verdetto delle regole immutabile, nessun invio automatico, backup esclusi. Prima del rilascio vanno chiusi **M-1** (crash con la chiave nel report), **M-2** e **M-3** (prompt injection verso contatti del truffatore) e **M-4** (testo completo in chiaro senza FLAG_SECURE, da allineare con il GDPR). I finding L sono consigliati nella stessa iterazione; nessuno è bloccante da solo.
