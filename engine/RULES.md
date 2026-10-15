# Regole del motore Eye v1

Specifica F0-T3, implementata in `engine/src/main/resources/rules/rules.json` (generata/mantenuta come dati). Le regole sono dati: `regex` valutate sul testo normalizzato (NFKC, minuscolo, confusables cirillico/greco piegati, input troncato a 10.000 caratteri); `detector` richiamano un rilevatore in codice (URL, marchi, mittente, numeri, IBAN). Un peso **negativo** riduce il punteggio.

## Verdetto e soglie

Punteggio = somma dei pesi delle regole attivate (una volta per regola), limitato a 0-100.

| Punteggio | Livello | Etichetta in UI |
|---|---|---|
| >= 55 | `PROBABILE_TRUFFA` | Probabile truffa |
| >= 25 | `SOSPETTO` | Sospetto |
| < 25 | `NESSUN_SEGNALE` | Nessun segnale evidente |

Non esiste il livello «Sicuro»: l'assenza di segnali non è una garanzia e le azioni lo ribadiscono. Un singolo segnale debole (peso < 25) non basta a far scattare un avviso; servono più indizi concordi o un indizio forte (OTP richiesto, dominio che imita un marchio, truffa del finto figlio). I domini ufficiali (whitelist per marchio, più `*.gov.it`) riducono il punteggio ma non lo azzerano.

## Sicurezza regex (SEC-06)

Linter `RegexLint` in caricamento: niente backreference, niente `.` con `*`/`+`, niente quantificatori o alternanze sotto un gruppo ripetuto in modo illimitato, al massimo 2 quantificatori illimitati per pattern, lunghezza <= 600; spazi tra parole con `.{0,N}` limitati. Budget di tempo per analisi 800 ms.

## Elenco regole (57)

| ID | Categoria | Peso | Tipo | Spiegazione (IT) | Esempio |
|---|---|---:|---|---|---|
| URG-01 | urgenza | +10 | regex | Il messaggio mette fretta: i truffatori usano scadenze strette per non farti riflettere. | Agisci entro 24 ore |
| URG-02 | urgenza | +20 | regex | Minaccia di blocco o sospensione di conto, carta o servizio: è un pretesto tipico per farti cliccare. | Il tuo conto verrà sospeso |
| URG-03 | urgenza | +15 | regex | Toni da «ultimo avviso» o diffida: tipici dei messaggi che cercano di spaventarti. | Ultimo avviso prima della diffida |
| URG-04 | urgenza | +15 | regex | Minaccia di conseguenze legali o economiche per spingerti ad agire subito. | Altrimenti pignoramento e azioni legali |
| PAC-01 | pacco | +25 | regex | Falso avviso di pacco in giacenza, consegna fallita o indirizzo da aggiornare: truffa molto diffusa. | Il tuo pacco è in giacenza |
| PAC-02 | pacco | +30 | regex | Ti chiedono di pagare piccole spese di spedizione, dogana o riconsegna: serve a rubarti i dati della carta. | Paga 1,99 euro di spese di riconsegna |
| PED-01 | pedaggio | +30 | regex | Falso avviso di pedaggio autostradale non pagato. | Pedaggio autostradale non pagato |
| BAN-01 | banca | +25 | regex | Segnala un accesso o un'operazione anomala o si presenta come «ufficio antifrode»: è un pretesto per spaventarti. | Rilevato accesso anomalo al tuo conto |
| BAN-02 | banca | +25 | regex | Ti chiedono di verificare, aggiornare o confermare i tuoi dati, o dicono che app e token sono scaduti: le banche non lo fanno per messaggio. | Verifica i tuoi dati per evitare il blocco |
| BAN-03 | banca | +40 | regex | Ti chiedono di comunicare un codice (OTP, PIN, password): nessuna banca lo chiede mai. | Comunicaci il codice OTP ricevuto |
| BAN-04 | banca | +35 | regex | Ti chiedono dati della carta o credenziali di accesso: non vanno mai inviati per messaggio. | Inserisci numero carta e CVV |
| BAN-07 | banca | +20 | regex | Ti invita a chiamare subito un numero indicato nel messaggio: potrebbe essere un falso operatore. | Chiama subito il numero 02 1234567 |
| BAN-10 | banca | +35 | regex | Ti chiede di spostare i soldi su un «conto sicuro»: è una truffa, i conti sicuri non esistono. | Sposta i fondi su un conto sicuro |
| SPI-01 | identita | +30 | regex | Falso avviso di scadenza o sospensione di SPID, CIE o tessera sanitaria. | Il tuo SPID è in scadenza |
| FIS-01 | fisco | +25 | regex | Promette rimborsi, crediti o bonus a nome di Agenzia delle Entrate, INPS o simili: è un'esca tipica. | Hai diritto a un rimborso dall'Agenzia delle Entrate |
| FIS-03 | fisco | +25 | regex | Parla di cartelle, accertamenti o intimazioni: verifica sempre dal sito ufficiale dell'ente. | Cartella esattoriale da pagare |
| MUL-01 | multa | +25 | regex | Falso avviso di multa o sanzione da pagare con un link. | Multa non pagata: paga entro 24 ore |
| MUL-02 | multa | +25 | regex | Si finge una forza dell'ordine o un'autorità e minaccia denunce o sequestri. | La Polizia Postale ha rilevato un reato a tuo carico |
| PRE-01 | premio | +30 | regex | Annuncia una vincita o un premio che non hai mai richiesto. | Hai vinto un buono da 500 euro |
| PRE-02 | premio | +25 | regex | Regali gratuiti o sondaggi con premio: esche per rubare dati o farti pagare. | iPhone gratis rispondendo al sondaggio |
| FAM-01 | familiare | +40 | regex | Messaggio «Ciao mamma/papà» da un numero nuovo: è la truffa del finto figlio. | Ciao mamma, ho perso il telefono, questo è il mio nuovo numero |
| FAM-02 | familiare | +30 | regex | Dice di avere un numero nuovo e ti chiede di salvarlo o di scrivere su WhatsApp, o chiede denaro con urgenza. | Salva il mio nuovo numero e scrivimi su WhatsApp |
| INV-01 | investimenti | +35 | regex | Promette guadagni garantiti, enormi o senza rischi: nessun investimento serio lo fa. | Guadagni garantiti senza rischi |
| INV-02 | investimenti | +20 | regex | Parla di cripto, trading o wallet con piattaforme, depositi e consulenti: terreno tipico di truffe. | Investi in bitcoin sulla nostra piattaforma |
| INV-03 | investimenti | +25 | regex | Ti invita in gruppi Telegram/WhatsApp, o racconta guadagni facili, per spingerti a investire. | Entra nel gruppo Telegram con segnali di trading |
| LAV-01 | lavoro | +30 | regex | Falsa offerta di lavoro con guadagni facili o con richieste di soldi e documenti: spesso serve a rubare dati o denaro. | Lavoro da casa, guadagni 200 euro al giorno |
| ANT-01 | anticipo | +35 | regex | Ti chiedono di pagare commissioni o tasse in anticipo per ricevere una somma: è la classica truffa dell'anticipo. | Per trasferire l'eredità versi 150 euro di commissione |
| ENE-01 | bollette | +25 | regex | Falso avviso o falsa offerta su bolletta, distacco o fornitura luce/gas. | Bolletta luce non pagata, distacco della fornitura |
| ACC-01 | account | +25 | regex | Dice che un tuo account (Amazon, PayPal, Netflix, Apple...) è bloccato o da verificare, o che un pagamento è stato rifiutato. | Il tuo account Netflix è sospeso |
| ACC-03 | account | +35 | regex | Falsa fattura o rinnovo di un servizio con un numero da chiamare per il «rimborso»: serve a farti installare programmi o a rubarti dati. | Hai rinnovato Prime per 49,99 EUR, chiama il +39... per il rimborso |
| WAP-01 | account | +40 | regex | Ti chiede di inoltrare un codice che «ti è arrivato per errore»: serve a rubarti l'account WhatsApp. | Ti è arrivato per errore un codice a 6 cifre, puoi inoltrarmelo? |
| TEL-01 | telefonia | +25 | regex | Falso avviso del tuo operatore telefonico su SIM o linea da disattivare o verificare. | La tua SIM TIM verrà disattivata |
| SEX-01 | ricatto | +45 | regex | Ricatto con presunti video o foto compromettenti e richiesta di pagamento in bitcoin o ricariche: è sextortion, un bluff. | Ho hackerato il tuo computer e ti ho filmato con la webcam |
| SUP-01 | assistenza_remota | +35 | regex | Finto avviso di virus o supporto tecnico con un numero da chiamare: è la truffa dell'assistenza informatica. | Il tuo PC è infetto, chiama il supporto tecnico |
| DOC-01 | documenti | +30 | regex | Ti chiedono foto di documenti, bollette o un selfie: servono a rubare l'identità. | Inviaci la foto della carta d'identità e un selfie |
| RDP-01 | assistenza_remota | +40 | regex | Ti chiedono di installare un programma di controllo remoto: darebbe il controllo del telefono ai truffatori. | Installa AnyDesk per l'assistenza |
| PAY-01 | banca | +30 | regex | Chiede pagamenti con gift card, ricariche o criptovalute: modalità non tracciabili, tipiche delle truffe. | Comprami delle gift card Amazon e mandami i codici |
| RIS-01 | generico | +20 | regex | Ti chiede di non parlarne con nessuno: i truffatori isolano la vittima. | Non dirlo a nessuno, resta tra noi |
| GEN-01 | generico | +8 | regex | Il messaggio ti invita a cliccare su un link o a scansionare un codice. | Clicca sul seguente link |
| GEN-04 | generico | +15 | regex | Chiede di pagare una piccola somma: spesso è solo un modo per ottenere i dati della carta. | Paga 1,99 euro per sbloccare |
| VEN-01 | vendite | +35 | regex | Dice che per «ricevere» un pagamento devi inserire dati della carta: per incassare non serve mai. | Per ricevere il pagamento inserisci i dati della carta |
| VEN-02 | vendite | +20 | regex | Chiede caparre o pagamenti anticipati per bloccare un acquisto. | Versa una caparra per bloccare l'articolo |
| OFF-02 | generico | -10 | regex | Il messaggio contiene l'avviso classico di non condividere i codici o di non cliccare link sospetti, tipico delle comunicazioni vere. | Non condividere questo codice con nessuno |
| URL-01 | link | +45 | detector | Il dominio {domain} imita il nome di {brand} ma non è un sito ufficiale. | poste-verifica.com / paypa1.com |
| URL-02 | link | +40 | detector | L'indirizzo mette il nome di {brand} in un punto ingannevole (sottodominio o percorso): il vero sito è {domain}. | poste.it.verifica-conto.com |
| URL-03 | link | +20 | detector | Il link usa un'estensione di dominio molto usata nelle truffe ({domain}). | sito.xyz, pacco.top |
| URL-04 | link | +25 | detector | Il link è accorciato ({domain}): non si vede dove porta davvero. | bit.ly/3xYz |
| URL-05 | link | +35 | detector | L'indirizzo contiene caratteri speciali (punycode o lettere non latine) che possono imitare un sito noto: {domain}. | pоste.it con la «о» cirillica |
| URL-06 | link | +30 | detector | Il link punta a un indirizzo numerico (IP) invece che a un nome di sito: {domain}. | http://185.23.4.5/login |
| URL-07 | link | +25 | detector | Il nome del dominio {domain} ha molti trattini o parole come «verifica», «pagamento», «sicurezza»: tipico dei siti-esca. | verifica-sicurezza-conto.com |
| URL-09 | link | +40 | detector | Il link scarica un file .apk: installare app fuori dallo store è pericoloso. | http://sito.com/app.apk |
| MIS-01 | link | +35 | detector | Il mittente dichiarato è {brand} ma il link va su {domain}, che non è un suo sito. | Mittente «Poste» + link a poste-sicura.com |
| MIS-02 | link | +25 | detector | Il messaggio cita {brand} ma il link va su {domain}, che non è un suo sito ufficiale. | «Intesa Sanpaolo» + link a sito-qualsiasi.com |
| PHN-01 | banca | +25 | detector | Contiene un numero a pagamento o a tariffazione speciale (es. 89x, 144, 166). | Chiama lo 899 123 456 |
| PHN-02 | banca | +15 | detector | Contiene un numero estero in un messaggio che dice di arrivare da un'azienda o un ente italiano. | Chiama il +44 7000 000000 |
| IBN-01 | banca | +15 | detector | Contiene un IBAN a cui ti chiedono di pagare: verifica l'intestatario prima di inviare denaro. | Bonifico su IT60 X054... |
| OFF-01 | generico | -25 | detector | Tutti i link puntano a siti ufficiali noti ({domain}): non basta a escludere una truffa, ma è un segnale rassicurante. | https://www.poste.it/... |
| INC-01 | generico | 0 | detector | Il messaggio è molto lungo o insolito e non sono riuscito ad analizzarlo tutto: trattalo con prudenza. | (budget di analisi scaduto: verdetto minimo SOSPETTO, nessuno sconto OFF-*) |

Categorie di verdetto (`Verdict.category`): pacco, pedaggio, banca, identita, fisco, multa, premio, familiare, investimenti, lavoro, bollette, account, telefonia, vendite, assistenza_remota, documenti. Le categorie `generico`, `link`, `forma` non vengono mai restituite come categoria prevalente; i rilevatori di marchio usano la categoria del marchio.
