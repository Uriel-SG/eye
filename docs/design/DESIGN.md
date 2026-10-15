# Occhio — Design (v0.1, 2026-10-11)
Principi: nessuna dark pattern · WCAG 2.2 AA · TalkBack · testo scalabile fino al 200% · target >= 48dp · verdetto mai solo a colore · mai la parola «Sicuro». Material 3, Compose.

## 1. Design token
### Colori (contrasto WCAG calcolato; testo >= 4.5, elementi grafici >= 3)
| Token | Light | Dark | Contrasto L / D |
|---|---|---|---|
| background | #FBFBFD | #121316 | — |
| onBackground | #1B1B1F | #E4E2E6 | 16.6 / 14.4 |
| surface (card) | #FFFFFF | #1E1F23 | 17+ / 12.8 |
| onSurfaceVariant | #49454F | #C9C5CF | 9.0 / 10.9 |
| outline (bordi) | #79747E | #938F99 | 4.4 / 5.9 (>= 3) |
| primary | #1D4ED8 | #AEC6FF | 6.5 / 10.9 |
| onPrimary | #FFFFFF | #002A78 | 6.7 / 7.7 |
| error (azioni distruttive) | #B3261E | #FFB4AB | 6.5 / 8+ |

Verdetto (container, testo su container, colore pieno per icona/barra):
| Stato | Container L / D | Testo L / D | Pieno L / D | Contrasto testo L / D |
|---|---|---|---|---|
| Probabile truffa | #FDECEA / #5C1410 | #8C1D18 / #FFDAD6 | #B3261E / #FFB4AB | 8.0 / 10.4 |
| Sospetto | #FFF1D6 / #4A3500 | #6B4500 / #FFE08A | #F5B700 / #FFE08A | 7.6 / 9.0 |
| Nessun segnale evidente | #DDF1F4 / #0E3A43 | #0B4F5C / #BFEAF1 | #0B6B7A / #7FD0DE | 7.9 / 9.5 |

Pieno con testo sopra: Truffa #FFF su #B3261E = 6.5; Sospetto #000 su #F5B700 = 11.7; Nessun segnale #FFF su #0B6B7A = 6.2. Teal (non verde) per evitare il significato «via libera». (Il pieno #7FD0DE dark va ricontrollato in implementazione: usato solo per icona, >= 3 su #0E3A43.)

### Tipografia (Roboto/sistema, in sp, scalabile)
displaySmall 36/44 (titolo verdetto) · headlineSmall 24/32 (titoli schermata) · titleMedium 16/24 medium (card) · bodyLarge 16/24 (base; Modalità Famiglia 20/30) · bodyMedium 14/20 (secondario) · labelLarge 14/20 medium (pulsanti). Minimo 14sp. Nessuna altezza fissa: a 200% il testo va a capo e i pulsanti crescono.

### Spaziature (dp)
4 · 8 · 12 · 16 (margine schermata) · 24 (tra sezioni) · 32. Target tocco >= 48x48, distanza tra target >= 8. Pulsante primario alto 56.

### Forme
Card 16 · pulsanti pillola · chip 8 · bottom sheet 28 (angoli alti) · scheda condivisibile 24. Elevazione tonale (niente ombre forti). Bordo 1dp outline sulle card nel dark.

### Icone (Material Symbols Rounded, 24dp, peso 500)
Truffa `gpp_bad` · Sospetto `warning` · Nessun segnale `visibility` · Controlla `search_check` · QR `qr_code_scanner` · Screenshot `image_search` · Incolla `content_paste` · Cronologia `history` · Catalogo `menu_book` · Impostazioni `settings` · Famiglia `family_restroom` · Condividi `share` · Copia `content_copy` · Segnala `flag` · Privacy `lock`.

## 2. Stati verdetto
| Stato | Icona | Titolo | Sottotitolo |
|---|---|---|---|
| Probabile truffa | gpp_bad | Probabile truffa | Non cliccare, non rispondere, non inviare dati. |
| Sospetto | warning | Sospetto | Ci sono segnali strani. Verifica prima di agire. |
| Nessun segnale evidente | visibility | Nessun segnale evidente | Non abbiamo trovato segnali, ma non è una garanzia. |

Regole: sempre icona + titolo + colore + barra laterale 8dp. contentDescription «Verdetto: {titolo}. {sottotitolo}»; annuncio live region al risultato; animazioni <= 300ms e disattivate con «rimuovi animazioni». Il disclaimer breve è sempre mostrato (vedi 4).

## 3. Schermate (layout a elenco, dall'alto)
Navigazione: bottom bar a 4 voci (Home, Catalogo, Cronologia, Impostazioni), 80dp, etichetta sempre visibile. Back di sistema ovunque. Nessun account.

**Onboarding (3 pagine; pager, puntini, «Salta»)**
- Illustrazione semplice 160dp (decorativa) · titolo headlineSmall · testo bodyLarge · pulsante «Avanti» in basso · «Indietro» (da pagina 2).
- 1 Cosa fa (icona `visibility`) · 2 Cosa non fa (icona `lock`; elenco con icona spunta/croce + testo) · 3 Come condividere (3 passi numerati con mini-schema; pulsante finale «Inizia»).

**Home**
- Top bar: logo + «Occhio»; icona Impostazioni.
- Card hero: «Hai ricevuto qualcosa di strano?» + pulsante grande «Controlla» (56dp, larghezza piena).
- «Controlla» apre un bottom sheet con 3 righe da 72dp (icona + titolo + sottotitolo): Incolla o condividi · Scansiona un QR · Scegli uno screenshot.
- Card «Truffe del momento» (max 3 voci, «Vedi tutte» verso Catalogo).
- Riga di fiducia: icona lock + «Controllo sul tuo telefono. Nessun account.»
- Nessun banner nella v1.

**Verdetto**
- Card verdetto (container colorato, icona 48dp, titolo displaySmall, sottotitolo).
- Anteprima contenuto (max 3 righe, «Mostra tutto»); per QR: URL decodificato con dominio registrato in grassetto, mai aperto in automatico.
- «Perché»: elenco breve (max 5), ogni voce icona + frase semplice.
- «Cosa fare ora»: passi numerati (max 4).
- Disclaimer sempre presente.
- Azioni: primaria «Condividi scheda»; secondarie in riga «Manda a un familiare», «Copia report»; testuali «Segnala», «Il verdetto è sbagliato?».
- «Analisi approfondita online» come riga neutra con consenso esplicito; mai bloccante. Nessun banner in questa schermata.

**Catalogo**
- Top bar con ricerca; chip filtro (Tutte, SMS, Banca, Pacchi, Pedaggi, Enti pubblici, QR).
- «Truffe del momento» in cima, etichetta «In corso».
- Lista card: icona categoria + nome + una riga + chevron (min 72dp). Funziona offline.

**Dettaglio scheda**
- Titolo + categoria · «Come riconoscerla» (elenco).
- «Esempio di messaggio falso» in card (testo anonimizzato).
- «Cosa fare» (passi) · «Dove segnalare» (link ai canali ufficiali).
- «Condividi» in top bar.

**Cronologia**
- Elenco: icona stato + titolo verdetto + anteprima 1 riga + data/ora. Eliminazione da menu e da swipe (alternativa a pulsante).
- Free: nota «Ultimi 7 giorni». Pro: ricerca e storico illimitato (riga discreta, non bloccante).
- Stato vuoto con testo; «Elimina tutto» in fondo con conferma.

**Impostazioni (righe da 56dp)**
- Modalità Famiglia (switch) · Dimensione testo (rimanda al sistema) · Lingua (Italiano).
- Occhio Pro: stato e gestione abbonamento (prezzo e rinnovo chiari, annullo da Google Play).
- Aggiornamento regole: versione + «Aggiorna ora».
- Privacy: informativa, cosa non facciamo, «Cancella dati».
- Informazioni: metodo di rilevamento, disclaimer esteso, versione, contatti, licenze.

**Modalità Famiglia**
- Stessa app, interfaccia semplificata: testo >= 20sp, contrasto aumentato (colori pieni, bordi 2dp), una azione principale per schermata.
- Home: solo 3 pulsanti da 96dp (Incolla o condividi, Scansiona un QR, Scegli uno screenshot). Catalogo e Cronologia sotto «Altro».
- Verdetto: icona grande + una frase + un solo pulsante primario «Manda a {nome}» (contatto di fiducia scelto via foglio di condivisione Android, nessun server) + «Chiudi».
- Attivazione con conferma; di solito la imposta il familiare.

## 4. Testi UI (italiano)
**Generali**: Controlla · Annulla · Chiudi · Avanti · Indietro · Salta · Inizia · Condividi · Copia · Elimina · Aggiorna ora.

**Onboarding**
1. «Occhio ti aiuta a capire se un messaggio è una truffa» / «Controlla SMS, link, QR code e screenshot in pochi secondi. Ti spieghiamo il perché in parole semplici e ti diciamo cosa fare.»
2. «Cosa fa e cosa non fa»
   - Cosa fa: «Controlla solo quello che scegli tu.» · «Analizza sul tuo telefono.» · «Ti spiega i motivi e cosa fare dopo.»
   - Cosa non fa: «Non legge i tuoi messaggi di nascosto.» · «Non chiede account o registrazione.» · «Non apre i link al posto tuo.» · «Non può garantire che un messaggio sia sicuro.»
3. «Come controllare un messaggio» / «1. Apri il messaggio e tocca Condividi. 2. Scegli Occhio dall'elenco. 3. Leggi il verdetto e segui i consigli.» / «Puoi anche incollare il testo, scansionare un QR o scegliere uno screenshot.»

**Home**: «Hai ricevuto qualcosa di strano?» · «Controlla» · «Incolla o condividi» («Testo o link ricevuto») · «Scansiona un QR» («Non apriamo il link: lo mostriamo prima») · «Scegli uno screenshot» («Leggiamo il testo sul tuo telefono») · «Truffe del momento» · «Vedi tutte» · «Controllo sul tuo telefono. Nessun account.»

**Stati di sistema**: «Sto controllando…» · «Non sono riuscito a leggere il contenuto. Riprova o incolla il testo.» · «Non ho trovato testo nell'immagine.» · «Il testo è troppo lungo. Controlla una parte alla volta.»

**Verdetto**
- Titoli e sottotitoli: sezione 2. Sezioni: «Perché» · «Cosa fare ora» · «Anteprima» · «Mostra tutto».
- Probabile truffa: «Non cliccare sul link e non rispondere.» · «Non dare codici, PIN, password o dati della carta.» · «Se hai già inserito dati, chiama subito la tua banca al numero ufficiale.» · «Segnala il messaggio ai canali ufficiali.»
- Sospetto: «Non cliccare finché non sei sicuro.» · «Cerca il sito o il numero ufficiale per conto tuo, senza usare il link.» · «Chiedi a un familiare o a una persona di fiducia.»
- Nessun segnale evidente: «Resta prudente: se ti chiedono soldi, codici o dati, fermati e verifica.» · «Se hai dubbi, contatta l'ente al numero ufficiale.»
- Disclaimer breve: «Occhio è un aiuto alla decisione, non una garanzia. Nessun controllo può trovare ogni truffa. Nessun segnale non significa sicuro.»
- Disclaimer esteso: «Occhio analizza il contenuto con regole spiegabili e può sbagliare: può non riconoscere una truffa o segnalare per errore un messaggio legittimo. Il verdetto non sostituisce il tuo giudizio né il parere di banche, enti o forze dell'ordine. Nel dubbio, contatta direttamente l'ente al numero o al sito ufficiale.»
- Azioni: «Condividi scheda» · «Manda a un familiare» · «Copia report» · «Segnala» · «Il verdetto è sbagliato?» → «Prepariamo un messaggio. Lo invii tu, se vuoi. Non inviamo nulla in automatico.»
- Analisi online: «Analisi approfondita» / «Invieremo solo il dominio del link, non il testo del messaggio, per controllare l'età del sito e i reindirizzamenti. Vuoi procedere?» [Procedi] [Non ora] · quota: «Hai usato i 3 controlli di oggi. Torna domani o scopri Occhio Pro.»
- QR: «Questo QR porta a:» · «Il link non è stato aperto.» · «Dominio:»

**Catalogo**: «Catalogo truffe» · «Cerca una truffa» · «In corso» · «Aggiornato al {data}» · «Come riconoscerla» · «Esempio di messaggio falso» · «Cosa fare» · «Dove segnalare»

**Cronologia**: «Cronologia» · «Ultimi 7 giorni, solo su questo telefono.» · «Nessun controllo recente.» · «Elimina tutto» / «Vuoi eliminare tutta la cronologia? Non si può annullare.»

**Impostazioni**: «Impostazioni» · «Modalità Famiglia» («Schermate più semplici, testo grande e invio del verdetto a una persona di fiducia.») · «Lingua» · «Occhio Pro» · «Gestisci abbonamento» («1,99 € al mese. Rinnovo automatico. Puoi annullare in qualsiasi momento da Google Play.») · «Aggiornamento regole» · «Privacy» · «Cosa non facciamo: nessun accesso a SMS, rubrica o chiamate; nessuna lettura in background; nessun account.» · «Informazioni»

**Modalità Famiglia**: «Attivare la Modalità Famiglia?» / «L'app diventa più semplice, con testo grande. Puoi disattivarla in Impostazioni.» · «Attiva» · «Non ora» · «Scegli una persona di fiducia» · «Manda a {nome}»

**Testo di condivisione**: «Ho controllato questo messaggio con Occhio: {titolo}. Motivi: {motivi}. Non cliccare. Verifica sempre dai canali ufficiali.»

**Accessibilità (contentDescription)**: «Controlla, pulsante» · «Verdetto: Probabile truffa. …» · «Passo {n} di {tot}» · «Pagina {n} di 3»

## 5. Scheda verdetto condivisibile (PNG 1080x1350, 4:5)
Margini 72px; scala in px fissi (è un'immagine). Sfondo = container verdetto (light), testo >= 7:1.
```
y 72-168    Intestazione: logo occhio 64px + «Occhio» 40px medium | data a destra 28px
y 192-480   Icona verdetto 160px (colore pieno) + titolo 88px bold, max 2 righe
            «Questo messaggio è una probabile truffa: ecco perché»   (Truffa)
            «Questo messaggio è sospetto: ecco perché»               (Sospetto)
            «Nessun segnale evidente in questo messaggio»            (Nessun segnale)
y 520-640   Estratto messaggio: card bianca raggio 24, testo 36px, max 3 righe;
            dati personali sostituiti da «[link nascosto]», «[numero nascosto]»
y 680-1040  «Perché»: max 3 motivi, icona 48px + testo 40/52px
y 1060-1200 «Cosa fare»: 1-2 passi, testo 40px
y 1220-1278 Disclaimer 26px: «Aiuto alla decisione, non una garanzia. Verifica sempre dai canali ufficiali.»
y 1278-1350 Piè: «Controllato con Occhio · gratuita · nessun account» + link/QR store (Free; Pro: senza marchio)
```
Regole: barra colore 24px sul bordo sinistro oltre a icona e testo; carattere minimo 26px; nessun logo di terzi (nomi solo descrittivi); contenuto sensibile oscurato di default (opzione «Mostra messaggio» spenta); testo alternativo: «Scheda: {titolo}. {motivi}»; condivisione via foglio di sistema, nessun server.
