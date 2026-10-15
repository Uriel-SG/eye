# Audit di sicurezza V3: Protezione automatica (notifiche), gate Pro e feed firmati

Data: 2026-10-15 · Revisore: SecDev · Modalità: sola lettura, codice non modificato.
Ambito: commit `59c2746` (v0.4 WIP), commit `18cf85c` (motore feed: `SignedEnvelope.kt`, `Campaigns.kt`, `RulePackStore.kt`, `tools/rulepack/rulepack.py`, `engine/FEEDS.md`), cartella `Eye-WebSite/data/` e `Eye-WebSite/netlify.toml`.
Riferimenti: `THREAT_MODEL.md` §5-6 (SEC-05, SEC-12, T15), ADR-002, OWASP MASVS (PLATFORM, STORAGE, CRYPTO, RESILIENCE, PRIVACY).
Fuori ambito: l'integrazione di rete dei feed in `app/**`, che è ancora in corso. I requisiti per quella parte sono nella sezione R.

## Verifiche positive

- **Listener**: `exported="true"` è obbligatorio per i NotificationListener ed è protetto da `BIND_NOTIFICATION_LISTENER_SERVICE` (`AndroidManifest.xml:78-85`). Il `packageName` di `StatusBarNotification` viene attestato dal sistema, quindi un'app maligna non può spacciarsi per WhatsApp o SMS. Le notifiche dell'app stessa sono escluse (`NotificationRules.kt:79`), così come quelle ongoing e i group summary.
- **Nessun contenuto persistito o loggato**: nel codice non c'è nessun `Log`/`println`. Il testo resta solo in memoria (`PendingAlertTexts`, al massimo 10 voci, TTL 30 min, consumato con `take`). Anche lo stato del gate (dedupe e rate) vive solo in memoria.
- **Intent dell'avviso**: è esplicito (`MainActivity`) e il `PendingIntent` è `FLAG_IMMUTABLE` (`AlertNotifier.kt:50`). Negli extras viaggia solo un UUID casuale e mai il testo. Il corpo della notifica è una stringa fissa, mentre l'etichetta dell'app viene dalla lista preset e non dalla notifica. Un'app terza che invia `ACTION_OPEN_ALERT` a `MainActivity` (esportata) non può indovinare la chiave e ottiene solo il toast «scaduto».
- **Limiti**: il testo è troncato a 10.000 caratteri (`NotificationRules.kt:58`). Ci sono un backlog massimo di 16, un worker seriale, un budget di 3 s, una dedupe di 60 s e al massimo 1 avviso ogni 10 s per app. Il motore normalizza NFKC e rimuove i caratteri invisibili principali (`Text.kt:12`).
- **Permessi**: niente `QUERY_ALL_PACKAGES`, solo `<queries>` con i 9 pacchetti preset. `POST_NOTIFICATIONS` viene richiesto all'attivazione e `canPost` lo controlla. `com.android.shell` compare solo in `src/debug/AndroidManifest.xml` e in `MonitoredApps.presets(BuildConfig.DEBUG)`.
- **Gate Pro e debug**: `LocalEntitlements` parte sempre Free. L'override funziona solo se `debugBuild` è true (`Entitlements.kt:14-19`): l'unico chiamante è dentro `if (BuildConfig.DEBUG)` (`EyeApp.kt:17`) e anche lo switch UI è dietro `BuildConfig.DEBUG` (`OtherScreens.kt:249`). In release, R8 elimina il ramo. Una chiave `dev_pro` scritta a mano nel DataStore (con root) in release viene ignorata. Chi fa bypass con un APK modificato ottiene solo funzioni Pro locali (parere AI con la *propria* chiave, nessun costo nostro): **accettabile** finché non arriva il Billing, poi serve la verifica Play (vedi I-3).
- **`.debug` e rete**: `applicationIdSuffix=".debug"` separa sandbox, DB e Keystore dalla release. Non esiste un `network_security_config` di debug. `usesCleartextTraffic=false` vale per entrambe le build e non ci sono trust anchor utente (targetSdk 36). Backup ed estrazione dati restano esclusi.
- **Feed**:
  - La separazione dei domini è corretta: `EYE-RULES-v1\n` con format 1 e `EYE-CAMPAIGNS-v1\n` con format 2. Il messaggio firmato è `domain||keyId||version||payload`.
  - Il parse dell'envelope è stretto (campi sconosciuti rifiutati) e la firma è lunga 64 byte. Il gunzip ha un tetto (zip bomb) e i limiti di dimensione sono controllati prima del parse.
  - Il payload deve ripetere `version` e `keyId`. L'anti-rollback è separato per regole e campagne e lo swap è atomico (pack, poi versione: un crash nel mezzo resta coerente).
  - `version.json` accetta solo un nome file relativo (`FILE` regex, niente `/` né `..`) con base fissa nel codice, al massimo 4 KB.
  - `source.url` è limitato a https verso domini istituzionali, senza userinfo, porte diverse da 443 o caratteri non ASCII.
  - Il sito ha CSP `default-src 'none'`, HSTS e nosniff. La chiave privata non è nel repo e `keygen` rifiuta di crearla dentro il repo.

## Findings (dal più grave)

### M-1 (MEDIO): l'analisi esce dal worker seriale e il timeout non la ferma (DoS CPU e batteria)
`core/notif/EyeNotificationListener.kt:49-50`, `:84`.
`withContext(Dispatchers.Default)` sposta `analyze` fuori da `worker` (`limitedParallelism(1)`), mentre `withTimeoutOrNull` cancella solo la coroutine: il calcolo CPU-bound (regex) prosegue. Dopo 3 s il `finally` decrementa `backlog` e parte la notifica successiva. Una raffica di messaggi distinti e «pesanti», da 10.000 caratteri e costruiti per le regex lente, accumula così analisi parallele fino a saturare `Dispatchers.Default`, che è condiviso con l'UI. Il risultato è CPU al 100% e batteria consumata.
**Fix**:
1. Eseguire `analyze` direttamente su `worker`, senza `withContext`, così resta seriale anche oltre il timeout.
2. Aggiungere un `AtomicBoolean running`: se un'analisi è ancora in corso, scartare le nuove.
3. Aggiungere un budget aggregato, per esempio al massimo 30 analisi al minuto in totale, oltre il quale si salta fino al minuto successivo.
4. Nel motore, verificare `coroutineContext.isActive` o un deadline tra una regola e l'altra (cancellazione cooperativa).
5. Test con 100 notifiche distinte in un secondo.

### L-1 (BASSO): dedupe con `String.hashCode` aggirabile (evasione dell'avviso)
`core/notif/NotificationRules.kt:83`.
È banale costruire collisioni di `hashCode`. Il truffatore manda un messaggio innocuo con lo stesso hash della truffa e, entro 60 s, la truffa: questa viene scartata come duplicato e l'avviso non parte. Anche collisioni casuali danno lo stesso effetto.
**Fix**: usare come chiave `pkg + "\u0000" + text` (la mappa è piccola, al massimo 64 voci), oppure lo SHA-256 del testo.

### L-2 (BASSO): spam di avvisi heads-up
`core/notif/AlertNotifier.kt:22,46,62`, `NotificationRules.kt:70`.
Ogni avviso ha un id nuovo, quindi `setOnlyAlertOnce` non ha effetto: con il canale HIGH escono suono e heads-up fino a 6 al minuto per app, per 9 app. Un truffatore, o un gruppo WhatsApp molto attivo, può rendere il telefono inutilizzabile e spingere l'utente a disattivare la protezione.
**Fix**:
- Usare un id fisso per pacchetto (`pkg.hashCode()`) o un gruppo con summary, così l'aggiornamento resta silenzioso.
- Aggiungere un tetto globale, per esempio al massimo 1 avviso sonoro ogni 2 minuti; oltre il tetto pubblicare sul canale DEFAULT o aggregare («3 messaggi sospetti da WhatsApp»).

### L-3 (BASSO): extras letti da tutte le app, anche con la protezione spenta (minimizzazione e CPU)
`core/notif/EyeNotificationListener.kt:30`, `:57-72`; `NotificationRules.kt:50`.
`read()` gira sul main thread per *ogni* notifica di *ogni* app, prima dei filtri per pacchetto e `autoProtect`, e copia per intero i `CharSequence` (`toString` prima di `take`). Un'app ostile può pubblicare `BIG_TEXT` di centinaia di KB e far allocare memoria in continuazione. La privacy-by-design (GDPR art. 25) chiede di non toccare i contenuti delle app non monitorate.
**Fix**:
- In `onNotificationPosted` controllare prima `sbn.packageName` contro un set monitorato in cache (`@Volatile`, aggiornato dal flow delle impostazioni), insieme ad `autoProtect`.
- Troncare con `subSequence(0, min(len, MAX_CHARS))` prima di `toString`.
- Opzionale: con la protezione spenta, `requestUnbind()`.

### L-4 (BASSO): `createdAt` dell'envelope non è firmato
`engine/SignedEnvelope.kt:18`, `:31-32`.
`createdAt` non fa parte del messaggio firmato. Se l'app lo mostrasse («regole aggiornate al …») o lo usasse per controllare la freschezza, chi controlla l'hosting potrebbe alterarlo.
**Fix**: non usare mai `env.createdAt`. Portare la data dentro il payload firmato (per esempio `issuedAt` e, per il punto L-5, `expiresAt`), oppure includerla nel messaggio con un nuovo dominio `-v2`.

### L-5 (BASSO): freeze del feed (`version.json` non firmato, nessuna scadenza)
`engine/Campaigns.kt:133-168` (FeedIndex), `FEEDS.md:16`.
Rollback, sostituzione e indicizzazione malevola sono mitigati da firma, anti-rollback, URL relativo e limiti. Resta il **freeze**: chi compromette l'hosting o la CDN, o un proxy aziendale con una CA dell'utente (non vale per noi, che non abbiamo trust anchor utente), può servire per sempre un `version.json` vecchio o l'envelope legittimo corrente. L'app resta ferma su regole o campagne superate senza accorgersene. L'impatto è limitato perché il motore incluso nell'APK funziona comunque.
**Fix**:
- Aggiungere `expiresAt` nel payload firmato (per esempio 30 giorni per le regole e 14 per le campagne) e ripubblicare periodicamente.
- Se il feed installato è scaduto, l'app mostra «aggiornamenti non disponibili da X giorni» senza degradare le regole.
- In alternativa, firmare anche `version.json` con un dominio `EYE-INDEX-v1\n`.

### L-6 (BASSO): `installedVersion()` corrotto vale 0 e riapre il rollback
`engine/RulePackStore.kt:49-51`.
Una lettura o un parse fallito restituisce `0L`, e da lì qualunque envelope firmato più vecchio viene accettato. Per sfruttarlo serve scrivere nella sandbox dell'app, quindi l'impatto è minimo.
**Fix**:
- Se il file della versione esiste ma non si legge, usare `max(versione del pack salvato verificato, versione inclusa nell'APK)`.
- Nell'app passare sempre `max(installedVersion(), RuleSet incluso.version)`, come già richiede `FEEDS.md:32`.

### L-7 (BASSO): `build-site-data` non valida il contenuto prima di firmare
`tools/rulepack/rulepack.py:81-106`.
Campagne o regole non valide, per esempio un URL fonte fuori whitelist, una regex lenta o un campo troppo lungo, vengono firmate e pubblicate, e l'app le rifiuta *tutte*: è un auto-DoS del feed. Se `version.json` va perso, le versioni ripartono da `bundled+1` e da 1, e gli utenti già aggiornati rifiutano per sempre (freeze autoinflitto). Inoltre il file `_headers` viene generato ma non compare in `Eye-WebSite/data/`: oggi copre `netlify.toml`, quindi va bene.
**Fix**:
- Dopo la firma eseguire sempre `ProductionFeedCheckTest` (`EYE_SITE_DATA=...`) come gate obbligatorio prima del deploy, riportato nel README.
- In Python, validare lo schema delle campagne (stessi limiti di `validate`) e rifiutare una versione minore o uguale a quella pubblicata online (leggerla via HTTPS, non solo dal file locale).
- La chiave privata viene letta con `password=None`: tenerla cifrata a riposo come da THREAT_MODEL §6 (`age` o una PEM con passphrase e `--key-pass` da prompt).

### L-8 (BASSO): caratteri invisibili non coperti
`engine/Text.kt:12`.
Mancano gli isolate bidi U+2066-2069, U+180E, U+034F, U+115F/U+1160/U+3164 (filler Hangul) e i tag U+E0000-E007F. Servono a spezzare le keyword («po⁦ste») ed eludere le regole, anche nelle notifiche.
**Fix**: rimuovere tutta la categoria Unicode `Cf` (`\\p{Cf}`) più i filler indicati, con un test per ciascuno.

### I-1 (INFO): testi delle campagne
`engine/Campaigns.kt:107-127`. Il contenuto è firmato da noi, ma come difesa in profondità conviene rifiutare i caratteri `Cc`/`Cf` in titolo, testi e nome della fonte, e mostrarli sempre come `Text` semplice (nessun Linkify).

### I-2 (INFO): revoca delle chiavi
`RulePackVerifier.kt:12-15`. `eye-2026-a` e `-b` sono entrambe sempre valide. La revoca di `a`, se compromessa, è possibile solo con un aggiornamento dell'app. È accettabile ed è documentata, ma va annotata nella procedura di incidente.

### I-3 (INFO): Billing futuro
Quando le funzioni Pro avranno un costo lato nostro, il gate locale non basterà più: servirà la verifica del `purchaseToken` (Play Integrity / server). Fino ad allora il bypass è innocuo.

### I-4 (INFO): `publish = "."` nel sito
`Eye-WebSite/netlify.toml:2`. Con un deploy da cartella (CLI o drag and drop) verrebbero pubblicati anche `README.md` e `_preview/`, che è nel `.gitignore`. Il deploy da git li esclude. Conviene usare una cartella `public/` o un deploy solo da git.

## R: requisiti per l'integrazione dei feed in corso in `app/**`

1. Scaricare solo con `GET` HTTPS su base costante nel codice, con `instanceFollowRedirects=false` (o redirect solo verso lo stesso host https), timeout di 10/15 s e lettura con tetto pari a `MAX_ENVELOPE` / `FeedIndex.MAX_BYTES`, interrotta oltre il limite (non fidarsi di `Content-Length`).
2. Nessun cookie, header identificativo o `User-Agent` personalizzato con id. In caso di errore nessun retry in loop: va usato WorkManager con backoff e vincoli di rete e batteria.
3. Anti-rollback: `max(store.installedVersion(), bundled.version)`. Le regole scaricate valgono solo se la loro versione è maggiore di quella inclusa.
4. `source.url`: riapplicare `CampaignFeedVerifier.isOfficialHttpsUrl` al momento del tocco, mostrare il dialog di conferma con l'host (come `ExternalIntents.openUrl`) e non aprirlo mai in automatico. Nessuna WebView.
5. Non mostrare `env.createdAt` (L-4).

## Verdetto

**Approvato con riserva** per la v0.4 interna e il closed test. Prima della produzione va corretto **M-1**. L-1, L-2 e L-3 vanno chiusi nella stessa iterazione: sono piccoli e riguardano l'affidabilità della Protezione automatica. Le notifiche finte da app maligne non sono un vettore: il pacchetto viene attestato dal sistema. L'unico «falso allarme» possibile viene da messaggi reali, che è proprio il caso d'uso. Il motore dei feed è solido: L-4, L-5, L-6 e L-7 sono miglioramenti di robustezza operativa da pianificare prima del primo aggiornamento remoto delle regole. Gate Pro e build debug sono conformi.
