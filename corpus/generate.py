# -*- coding: utf-8 -*-
"""Generatore deterministico del corpus sintetico (F0-T4). Nessun dato reale."""
import json, os, random

random.seed(20260101)
HERE = os.path.dirname(os.path.abspath(__file__))


def fake_iban(n):
    """IBAN italiano di struttura plausibile con checksum mod-97 volutamente NON valido."""
    rnd = random.Random(n)
    bban = "X" + "".join(str(rnd.randint(0, 9)) for _ in range(10)) + "".join(str(rnd.randint(0, 9)) for _ in range(12))
    for cd in range(2, 98):
        s = "IT%02d%s" % (cd, bban)
        if int("".join(str(int(c, 36)) for c in s[4:] + s[:4])) % 97 != 1:
            return s


def ph(i):  # telefono fittizio +39 3XX 000xxxx
    return "+39 3%02d 000%04d" % (30 + i % 60, 1000 + i * 37 % 9000)


I1, I2 = fake_iban(1), fake_iban(2)
P1, P2, P3 = ph(3), ph(11), ph(19)

# (categoria, canale, testo)
SCAM = [
# pacco
("pacco","sms","Poste Italiane: il tuo pacco e' in giacenza. Costi di spedizione 1,99 EUR non pagati. Riprogramma la consegna: https://poste-consegna-it.com/t/8841"),
("pacco","sms","BRT: Impossibile consegnare il pacco, indirizzo incompleto. Aggiorna i dati entro 24h o verra' restituito: bit.ly/3xKp9Qa"),
("pacco","whatsapp","Buongiorno, sono il corriere GLS. Il suo pacco non e' stato consegnato x assenza. Confermi indirizzo qui 👉 gls-ritiro-pacchi.top/conferma"),
("pacco","sms","SDA Express: pacco 4TY77281 in attesa. Paga 2,50€ di dogana per sbloccare la spedizione http://sda-dogana.info/pay"),
("pacco","email","Oggetto: Ultimo avviso - spedizione DHL sospesa\n\nGentile cliente, la sua spedizione e' trattenuta in magazzino. Per evitare la restituzione al mittente completi il pagamento delle spese di sdoganamento (3,00 EUR): https://dhl-sdoganamento-it.com/track"),
("pacco","sms","Il tuo pacco Amazon non e' stato consegnato. Rischedula qui: tinyurl.com/ritiro-pacco-24 Grazie"),
("pacco","sms","GIACENZA: pacco in scadenza oggi!! ritira subito altrimenti verra' distrutto -> corriere-giacenza.xyz/id=77120"),
("pacco","whatsapp","ciao sono Marco del corriere, ho un pacco per te ma civico sbagliato, clicca e correggi https://consegne-express24.cc/c"),
("pacco","sms","URGENTE!!! il tuo pacco e fermo in magazzino, paga 1€ per la riconsegna 📦 https://poste-pacco-1euro.top/x"),
("pacco","whatsapp","Salve, sono dal deposito corrieri. non riusciamo a consegnare, mi conferma l indirizzo e paga la contrassegno di 2€ su https://ritiro-colli.cc/p ?"),
# pedaggio/multa
("pedaggio","sms","Autostrade: risulta un pedaggio non pagato di 4,80 EUR. Salda entro 48h per evitare sanzione di 50 EUR: https://autostrade-pedaggi.top/pay"),
("pedaggio","sms","Telepass: mancato pagamento transito. Regolarizza ora per evitare il blocco del dispositivo: telepass-saldo.info/x9"),
("pedaggio","sms","AVVISO MULTA: violazione codice della strada n. 884412. Importo 35,00 EUR ridotto se paghi oggi. Paga: bit.ly/MultaPagaOra"),
("pedaggio","email","Oggetto: Notifica contravvenzione\n\nLa Polizia Municipale la informa che e' stata rilevata un'infrazione ZTL. Visualizzi il verbale e paghi online: https://polizia-municipale-verbali.com/zt"),
("pedaggio","sms","Ultimo sollecito pedaggio A1 - se non paghi subito scatta la maggiorazione e la segnalazione. https://pedaggio-it-saldo.com"),
("pedaggio","sms","Gentile automobilista, ha un verbale non pagato. Eviti il pignoramento del veicolo: multe-pagamento-it.cc/n"),
("pedaggio","sms","Gentile utente il suo transito sulla A14 non risulta saldato. Pagare entro oggi: pedaggi-a14.info/saldo - Società Autostrade"),
("pedaggio","sms","Multa non pagata. Importo aggiornato 89,00 euro. Ultima notifica prima del recupero forzoso: https://verbali-notifica.top/n"),
# poste
("poste","sms","BancoPosta: rilevato accesso anomalo al tuo conto. Verifica la tua identita' subito: https://poste-verifica-it.com/login"),
("poste","sms","PosteID: la tua utenza scade oggi. Per non perdere l'accesso aggiorna i dati: postepay-sicurezza.top/aggiorna"),
("poste","email","Oggetto: Postepay - carta sospesa\n\nLa tua carta Postepay e' stata sospesa per motivi di sicurezza. Per riattivarla inserisci numero carta, scadenza e CVV su https://postepay-riattiva.com/carta"),
("poste","whatsapp","Poste Italiane Info: il suo conto sara' bloccato entro 12 ore. Contatti l'assistenza al numero " + P1 + " o acceda a bit.ly/poste-sblocco"),
("poste","sms","Poste: Hai un nuovo messaggio di sicurezza. Se non hai autorizzato tu l'operazione di 480 EUR annullala qui: poste-annulla-pagamento.com"),
("poste","sms","POSTE ITALIANE - App Postepay disattivata. Reinstalla la nuova versione da https://poste-app-update.xyz/apk"),
("poste","sms","Poste Italiane: il tuo conto presenta irregolarita. Accedi ora ->> posteitaliane-clienti.top <<-"),
("poste","email","Oggetto: Rimborso Poste Italiane\n\nPoste Italiane le deve un rimborso di 56,80 EUR a seguito di una doppia commissione. Inserisca i dati carta per ricevere l'importo: https://poste-rimborso-clienti.com/r"),
# banca
("banca","sms","Intesa Sanpaolo: il suo conto e' stato temporaneamente bloccato. Sblocchi da qui entro 2 ore: https://intesa-sblocco-conto.com/sec"),
("banca","sms","UniCredit ALERT: tentativo di bonifico da 1.250,00 EUR verso " + I1 + ". Se non sei stato tu chiama subito " + P2),
("banca","whatsapp","Buonasera sono Luca Ferraro dell'ufficio antifrode della sua banca. Abbiamo rilevato movimenti sospetti, deve spostare i fondi su un conto sicuro. IBAN: " + I2),
("banca","sms","BNL: la tua carta e' stata bloccata. Per sbloccarla accedi: bnl-sicurezza-clienti.top/accedi"),
("banca","email","Oggetto: Aggiornamento obbligatorio PSD2\n\nGentile cliente, per normativa europea deve riconfermare le credenziali di accesso entro 24 ore, altrimenti il conto verra' sospeso.\n\nAccedi: https://banca-psd2-verifica.com/login\n\nServizio Clienti"),
("banca","sms","Nexi: addebito sospetto 899,00€ su carta *4417. Non riconosci? Blocca subito: nexi-blocca-carta.info/b"),
("banca","sms","Il suo home banking sara' disattivato. Scarichi l'app di sicurezza https://app-sicurezza-banca.cc/download.apk e segua le istruzioni"),
("banca","whatsapp","Sono dell'assistenza fraudi di Fineco. Per annullare il bonifico non autorizzato mi serve che installi AnyDesk e mi comunichi il codice."),
("banca","sms","Mediolanum Info: rilevata operazione anomala. Confermi o neghi: https://mediolanum-conferma.top/op"),
("banca","sms","Gentile cliente, per motivi di sicurezza la sua carta CartaSi e' stata sospesa. Riattivala: cartasi-riattiva.xyz/s"),
("banca","email","Oggetto: Messaggio importante dalla tua banca\n\nCaro cliente, il tuo accesso scade. Clicca qui per mantenere attivo il tuo conto: https://banca-accesso-attivo.com/ok"),
("banca","sms","ING: il conto Arancio e' in fase di chiusura. Per evitarlo conferma qui i tuoi dati: ing-conferma-dati.cc"),
# inps
("inps","sms","INPS: hai diritto a un rimborso di 312,40 EUR. Richiedilo entro 48 ore: https://inps-rimborso.top/richiesta"),
("inps","email","Oggetto: INPS - Bonus erogato\n\nGentile utente, le e' stato accreditato un bonus di 600 EUR. Inserisca IBAN e dati della carta per ricevere il pagamento: https://inps-bonus-servizi.com/ibn"),
("inps","sms","INPS Informa: la tua pensione verra' sospesa. Aggiorna il certificato di esistenza in vita: inps-aggiorna-dati.cc/ev"),
("inps","sms","INPS: Assegno unico non erogato per dati mancanti. Completa subito https://assegno-unico-inps.xyz/dati"),
("inps","whatsapp","Gentile cittadino, dal sito INPS risulta un credito non riscosso. Per riscuoterlo rispondi con codice fiscale e IBAN."),
("inps","sms","INPS: Bonus 2026 approvato per te! Controlla l'importo e inserisci i dati bancari https://inps-bonus-2026.xyz"),
# entrate
("entrate","email","Oggetto: Agenzia delle Entrate - Rimborso fiscale\n\nDopo gli ultimi calcoli annuali risulta un rimborso di 489,63 EUR. Per riceverlo compili il modulo: https://agenziaentrate-rimborsi.com/form"),
("entrate","sms","AGENZIA ENTRATE: avviso di pagamento non evaso. Sanzione 120 EUR. Saldo immediato: agenzia-entrate-pay.top/avviso"),
("entrate","sms","Agenzia Entrate: hai un credito d'imposta di 215€. Accedi e inserisci i dati della carta per l'accredito http://entrate-credito.info/c"),
("entrate","email","Oggetto: Cartella esattoriale n. 0042918\n\nEquitalia comunica che e' in scadenza la cartella a suo nome. Se non versa entro 3 giorni verra' avviato il pignoramento. Pagamento: https://riscossione-cartelle-it.com/p"),
("entrate","sms","Rimborso IRPEF disponibile, clicca per accettare: bit.ly/IrpefRimb22"),
("entrate","whatsapp","Agenzia delle Entrate comunica: e' in corso un accertamento a suo carico. Chiami subito il " + P3 + " per evitare il sequestro."),
("entrate","sms","Agenzia Entrate-Riscossione: fermo amministrativo sul veicolo. Evita il blocco pagando 78€ -> fermo-auto-ade.top"),
# spid
("spid","sms","SPID: la tua identita' digitale scade tra 24 ore. Rinnova ora per non perderla: https://spid-rinnovo-it.com/id"),
("spid","email","Oggetto: CIE - Carta d'identita' elettronica in scadenza\n\nLa informiamo che la sua CIE non risulta piu' attiva. Aggiorni i dati per continuare ad accedere ai servizi pubblici: https://cie-aggiornamento.top/login"),
("spid","sms","Il tuo SPID sara' sospeso. Verifica i dati personali e carica un documento: spid-verifica-account.cc"),
("spid","whatsapp","Servizio SPID: richiesta di riconferma identita'. Invii foto del documento e codice fiscale su questa chat per evitare la disattivazione."),
("spid","sms","SPID Poste: il suo livello di sicurezza e' scaduto. Riattivi qui: poste-spid-livello.com/rn"),
("spid","whatsapp","Ministero dell'Interno - CIE: l'identita' digitale risulta non valida. Aggiorni i dati qui https://cie-ministero-verifica.top"),
# energia
("energia","sms","ENEL Energia: fattura scaduta di 187,30 EUR. Paga oggi per evitare il distacco della fornitura: https://enel-fatture-pay.com/f"),
("energia","email","Oggetto: Sollecito pagamento bolletta luce\n\nGentile cliente, la sua bolletta risulta insoluta. Il distacco del contatore sara' effettuato entro 48 ore. Paghi qui: https://bollette-sollecito-luce.top/pay"),
("energia","sms","Offerta ENERGIA: risparmia il 60% sulla bolletta, solo oggi! Rispondi SI e un operatore ti richiama. Attiva: bit.ly/risparmiaLuce"),
("energia","whatsapp","Buongiorno sono del servizio gas, devo fare un controllo urgente all'impianto. Mi mandi foto dell'ultima bolletta con codice POD e nome."),
("energia","sms","Eni Plenitude: rimborso bolletta 94,20€ in sospeso. Inserisci l'IBAN: plenitude-rimborso.xyz"),
("energia","sms","A2A: fattura n.449812 non pagata. Ultimo avviso prima del distacco. Paga: a2a-bolletta-online.top/pay"),
# ciao mamma
("ciao_mamma","whatsapp","Ciao mamma, ho perso il telefono e questo e' il mio nuovo numero. Salvalo! Mi servirebbe un favore urgente 🙏"),
("ciao_mamma","whatsapp","Ciao papà sono io, mi si e' rotto il cellulare, scrivimi su questo numero nuovo. Non riesco ad accedere alla banca, puoi pagare una fattura per me? Poi ti rimborso"),
("ciao_mamma","sms","mamma sono io il mio telefono e' caduto in acqua uso quello di un amico. Puoi fare un bonifico urgente di 890 euro? ti mando l'IBAN " + I1),
("ciao_mamma","whatsapp","Ciao nonno! Sono Giulia, ho cambiato numero. Ho un problema con la carta, puoi ricaricarmi una Postepay? Ti spiego dopo ❤️"),
("ciao_mamma","whatsapp","hey ma, nuovo numero. non dire niente a papà ma ho bisogno di 450€ subito, un fornitore mi aspetta. rispondi appena puoi"),
("ciao_mamma","whatsapp","Ciao zia sono Marco, ho il cellulare nuovo. Mi servono dei soldi per un'emergenza, ti mando i dati: " + I2 + " intestato a un mio amico"),
("ciao_mamma","whatsapp","CIAO MAMMA SONO IO!! nuovo numero, il vecchio e' rotto. mi serve un aiuto urgente col bonifico, scrivimi subito"),
# investimenti
("investimenti","whatsapp","Ciao! Sono Anna, consulente finanziaria. Con soli 250€ i nostri clienti guadagnano fino al 3.000% in un mese col trading di Bitcoin. Scrivimi per entrare nel gruppo VIP 📈"),
("investimenti","sms","Guadagna 500€ al giorno con l'AI trading, zero rischi! Registrati gratis: https://crypto-profit-ai.top/reg"),
("investimenti","email","Oggetto: Opportunita' esclusiva - rendimento garantito 15% settimanale\n\nUn nostro analista ha selezionato la tua posizione. Capitale protetto al 100%. Investi ora su https://invest-garantito-it.com e ricevi un bonus di benvenuto."),
("investimenti","whatsapp","Benvenuto nel gruppo 'Trading Elite Italia 🔥'. Il mio mentore ti guidera' passo passo. Per iniziare deposita 300 USDT sul wallet 0x7a3f9c21d0b8e45aa6c1; i profitti sono immediati"),
("investimenti","sms","Hai visto l'intervista a un noto imprenditore italiano? Sta rendendo ricchi migliaia di risparmiatori con questa piattaforma: bit.ly/BitcoinItalia"),
("investimenti","whatsapp","Il tuo investimento ha raggiunto 4.200€! Per prelevare devi pagare una tassa del 10% di sblocco. Versa sul conto " + I1),
("investimenti","email","Oggetto: Hai ricevuto un'eredita' in criptovalute\n\nGentile signore, un wallet a lei intestato contiene 2,4 BTC. Per trasferirli versi 150 EUR di commissione su " + I2),
("investimenti","whatsapp","ciao, ti ho visto nel gruppo calcio. sto facendo soldi con questa app di forex, io ho gia' ritirato 1800 euro. ti spiego come fare? 😉"),
# lavoro
("lavoro","whatsapp","Buongiorno! Cerchiamo persone per lavoro da casa, 30 minuti al giorno, guadagni da 150 a 400€ al giorno. Basta mettere like a video YouTube. Scrivici su Telegram @lavoro_facile_it"),
("lavoro","sms","Offerta di lavoro: recensisci prodotti Amazon dal telefono e guadagna 80€/ora. Info: wa.me/393300001234"),
("lavoro","whatsapp","Ciao, ti contatto da parte di TikTok Italia 🎉 Sei stato selezionato per un lavoro part-time. Prima attività gratuita, poi ti chiederemo un deposito iniziale di 50€ per sbloccare i compiti."),
("lavoro","email","Oggetto: Lavora con noi - assunzione immediata\n\nGentile candidato, ha superato la selezione! Per procedere invii copia fronte/retro della carta d'identita', codice fiscale e IBAN per il contratto: lavoro-assunzioni-it@mail.com"),
("lavoro","whatsapp","Task di oggi completato ✅ Hai guadagnato 38€. Per prelevare devi completare altri 3 pacchetti ordini da 120€ (rimborsati con commissione)."),
("lavoro","sms","Lavoro online senza esperienza: 200€ al giorno. Contattaci su WhatsApp +39 333 0004567 (solo persone serie)"),
("lavoro","whatsapp","Buongiorno, sono Chiara delle Risorse Umane. Posizione di addetto ordini da casa, 2.400€/mese. Per l'attivazione serve un piccolo versamento di 29€ per il kit."),
# vincita
("vincita","sms","COMPLIMENTI! Sei stato scelto per vincere un iPhone 15 Pro. Rispondi al sondaggio entro 10 min: https://premio-iphone-it.xyz/win"),
("vincita","email","Oggetto: Hai vinto 1.000 EUR - ritira il premio\n\nAbbiamo estratto il tuo indirizzo nella lotteria internazionale. Per riscuotere invia nome, indirizzo e paga 4,99 EUR di spese: https://lotteria-vincite-int.com/claim"),
("vincita","sms","Gentile cliente Esselunga, hai vinto una GIFT CARD da 500€! Ritirala qui https://esselunga-premio.top/gift 🎁"),
("vincita","whatsapp","Congratulazioni!!! Il tuo numero e' stato sorteggiato al Mega Concorso WhatsApp 2026 ed hai vinto 85.000 €. Contatta l'agente " + P3),
("vincita","sms","Ti e' stato assegnato un buono Lidl da 250 EUR. Compila il modulo, ultimi posti: bit.ly/buonoLidl250"),
("vincita","whatsapp","🎉🎁 HAI VINTO!!! Un buono Zalando da 300 euro, clicca e ricevilo prima che scada: https://zalando-buono-gratis.cc"),
("vincita","sms","Sei il visitatore 1.000.000! Ritira il tuo regalo subito, solo oggi. Clicca qui: tinyurl.com/regalo-1000000"),
("vincita","email","Oggetto: Il suo numero ha vinto - Euro Millions Promo\n\nNumero di riferimento 5521-IT. Invii copia del documento e IBAN per ricevere 250.000 EUR. Contatti: ufficio-vincite@mail.com"),
# abbonamento
("abbonamento","email","Oggetto: Il tuo abbonamento Netflix e' stato sospeso\n\nNon siamo riusciti a elaborare il pagamento. Aggiorna le informazioni di fatturazione: https://netflix-fatturazione-it.com/billing"),
("abbonamento","sms","Amazon: il tuo account Prime e' stato limitato. Verifica il metodo di pagamento: https://amazon-verifica-prime.top/it"),
("abbonamento","email","Oggetto: Attivita' sospetta sul tuo account Amazon\n\nAbbiamo bloccato un ordine da 749,00 EUR. Se non sei stato tu, annulla subito: https://amazon-annulla-ordine.cc/a"),
("abbonamento","sms","Spotify Premium: pagamento rifiutato, il piano verra' disattivato. Aggiorna carta qui spotify-pagamento-it.xyz"),
("abbonamento","email","Oggetto: Apple ID bloccato\n\nIl tuo ID Apple e' stato disabilitato per motivi di sicurezza. Verifica l'account entro 24 ore: https://appleid-sblocca-it.com/verify"),
("abbonamento","sms","Disney+: il tuo abbonamento e' scaduto. Rinnova subito con sconto 70%: disneyplus-rinnova.info/offerta"),
("abbonamento","sms","Netflix: impossibile rinnovare l'abbonamento. Verifica ora o l'account verra' chiuso: netflix-account-verifica.top"),
("abbonamento","email","Oggetto: Fattura Amazon Prime n. 8812-3345\n\nHai rinnovato Prime per 49,99 EUR. Se non riconosci l'addebito chiama il " + P1 + " per il rimborso immediato."),
# qr quishing
("qr","qr","https://parcheggio-pay-citta.top/qr?stallo=118&importo=2.50"),
("qr","qr","https://pagamento-sosta-it.com/p/9921"),
("qr","qr","https://bit.ly/3ParkPay8"),
("qr","qr","https://colonnina-ricarica-pay.cc/start?id=44"),
("qr","qr","https://menu-ristorante-digitale.xyz/ordina?cod=551&login=1"),
("qr","qr","https://poste-qr-verifica.com/bollettino?c=7721"),
("qr","qr","https://multa-pagopa-qr.top/v/883"),
("qr","qr","https://tinyurl.com/qr-premio-ritira"),
("qr","qr","https://parcheggio-municipale-pay.xyz/stallo/22?pay=1"),
("qr","qr","https://wifi-gratis-aeroporto.cc/login?qr=1"),
# sextortion
("sextortion","email","Oggetto: Ho accesso al tuo dispositivo\n\nCiao, ho hackerato il tuo computer e registrato video dalla webcam mentre guardavi siti per adulti. Se non invii 1.200 USD in Bitcoin a bc1qxy2kgdygjrsqtzq2n0yrf2493p entro 48 ore, inviero' il video a tutti i tuoi contatti."),
("sextortion","email","Oggetto: La tua password\n\nSo che la tua password e' Tr0ub4dor. Ti ho filmato con la webcam. Paga 800€ in bitcoin o il video sara' pubblicato. Hai 2 giorni."),
("sextortion","whatsapp","Ho le tue foto intime e la lista dei tuoi contatti Facebook. Se non paghi 500€ con ricarica PayPay le mando a tua moglie e ai colleghi."),
("sextortion","email","Oggetto: Ultimo avvertimento\n\nIl tuo account e' stato compromesso da un hacker. Ho tutti i tuoi dati. Pagamento di 650 euro in cripto entro domani, altrimenti pubblico tutto."),
("sextortion","email","Oggetto: Pagamento richiesto\n\nHo installato un trojan nel tuo telefono. Ho registrato tutto. Invia 700 euro in BTC a bc1q9h8x7w6v5u4t3s2r1 entro 24 ore."),
# supporto tecnico
("supporto","sms","MICROSOFT: il tuo PC e' infetto da 3 virus. Chiama subito il supporto tecnico al " + P1 + " per evitare la perdita dei dati."),
("supporto","email","Oggetto: Il tuo computer e' a rischio\n\nAbbiamo rilevato un'intrusione sul tuo router. Scarica lo strumento di rimozione: https://supporto-microsoft-it.top/fix.exe"),
("supporto","whatsapp","Assistenza Google: il tuo account Gmail sara' chiuso. Scarichi TeamViewer e ci comunichi ID e password per il ripristino."),
("supporto","sms","Windows Defender ALERT!! Virus rilevato. Chiama il numero verde " + P2 + " NON spegnere il computer"),
("supporto","sms","TIM: il tuo router e' compromesso, installa l'app di sicurezza da http://tim-sicurezza-wifi.cc/app"),
("supporto","whatsapp","Salve, le scrivo dal servizio clienti Fastweb: abbiamo rilevato un'anomalia sulla linea. Mi indichi nome utente e password dell'area clienti per risolvere."),
("supporto","email","Oggetto: Abbonamento antivirus rinnovato\n\nHa acquistato McAfee per 349,99 EUR. Per annullare e ottenere il rimborso chiami subito " + P2 + "."),
# otp/pin
("otp","sms","Sono dell'assistenza della tua banca. Le e' arrivato un SMS con un codice a 6 cifre? Me lo legga per annullare l'operazione, e' urgente."),
("otp","whatsapp","Ciao, ti ho mandato per errore un codice di verifica WhatsApp. Me lo giri per favore? E' importante 🙏"),
("otp","sms","Per ricevere il rimborso invii il PIN della sua carta e il codice OTP ricevuto tramite SMS a questo numero."),
("otp","sms","Ho provato ad accedere all'account Vinted, mi dai il codice che ti e' arrivato? Sennò perdo l'acquisto"),
("otp","sms","Verifica richiesta: rispondi a questo messaggio con il codice di sicurezza a 6 cifre appena ricevuto per confermare la tua identita'."),
("otp","whatsapp","Buongiorno, per completare il pagamento del suo annuncio su Subito mi serve il codice che riceve via SMS dalla banca, cosi' effettuo l'accredito."),
("otp","sms","Buongiorno sono il corriere, per consegnare il pacco mi serve il codice di 6 cifre che ha ricevuto per SMS."),
]

LEGIT = [
# otp veri senza link
("otp","sms","Il tuo codice di verifica Intesa Sanpaolo e' 482913. Valido per 5 minuti. Non comunicarlo a nessuno, nemmeno al personale della banca."),
("otp","sms","UniCredit: codice per confermare il pagamento di 24,90 EUR da CARTA *4417 a ZALANDO: 709214. Non lo condividere con nessuno."),
("otp","sms","123456 e' il tuo codice WhatsApp. Non condividerlo con nessuno."),
("otp","sms","Poste Italiane: il codice di autorizzazione per l'operazione e' 305581. Se non l'hai richiesto, ignora questo messaggio."),
("otp","sms","Il codice di sicurezza Amazon e' 918273. Non condividerlo con nessuno. Amazon non ti chiedera' mai di comunicarlo."),
("otp","sms","Codice Google G-640218. Non condividerlo con nessuno."),
("otp","sms","BNL: usa il codice 551902 per autorizzare l'accesso all'home banking. Valido 3 minuti."),
("otp","sms","Satispay: il tuo codice di accesso e' 7741. Non comunicarlo."),
("otp","sms","PayPal: il tuo codice di sicurezza e' 330485. Scade tra 10 minuti. Non lo condividere con nessuno."),
("otp","sms","Revolut: il codice di conferma per il tuo accesso e' 2087. Non lo dare a nessuno."),
("otp","sms","Il tuo codice Telepass per il login e' 190427."),
("otp","sms","Codice di verifica Vinted: 884120. Valido per 10 minuti."),
("otp","sms","Il tuo codice di accesso a MyINPS e' 623907. Non comunicarlo."),
("otp","sms","Fineco: codice SecurePhone 4081 per l'operazione richiesta. Non cliccare su link ricevuti da altri."),
("otp","sms","Mediolanum: il codice per autorizzare il pagamento e' 774520. Se non hai richiesto l'operazione chiama l'assistenza dal numero sul sito."),
# corriere legittimo
("corriere","sms","Poste Italiane: il tuo pacco 6A12345678 sara' consegnato oggi tra le 14 e le 18. Segui la spedizione su https://www.poste.it/cerca/index.html"),
("corriere","sms","BRT: il tuo collo verra' consegnato domani. Per modificare la consegna usa MyBRT su https://www.mybrt.it"),
("corriere","sms","DHL Express: la tua spedizione e' in consegna oggi. Traccia su https://www.dhl.com/it-it/home/tracking.html"),
("corriere","email","Oggetto: Il tuo ordine e' in viaggio\n\nCiao Elena, il tuo pacco con GLS e' stato spedito. Puoi seguirlo su https://www.gls-italy.com oppure dall'app GLS. Consegna prevista giovedi'."),
("corriere","sms","SDA: la spedizione 5678901234 e' stata consegnata al punto di ritiro. Ritira entro 7 giorni con un documento. Info su https://www.sda.it"),
("corriere","sms","Amazon: il tuo pacco e' stato consegnato nella cassetta della posta. Dettagli nell'app Amazon o su https://www.amazon.it/gp/css/order-history"),
("corriere","whatsapp","Ciao, sono il corriere Fedex, sto arrivando in via Garibaldi con il tuo pacco, tra 10 minuti sono da te. Ti trovo in casa?"),
("corriere","sms","UPS: il tuo pacco arrivera' domani. Per informazioni vai su https://www.ups.com/track"),
("corriere","sms","GLS: il corriere passera' domani. Se non sei in casa puoi scegliere un punto di ritiro su https://www.gls-italy.com"),
# appuntamenti
("appuntamento","sms","Promemoria: visita cardiologica il 14/11 alle 09:30 presso Poliambulatorio Centro, via Roma 12. Per disdire chiami lo 06 000 0000."),
("appuntamento","sms","Studio Dentistico Bianchi: ti ricordiamo l'appuntamento di domani alle 16:00. Rispondi OK per confermare."),
("appuntamento","whatsapp","Buongiorno, le confermo l'appuntamento col parrucchiere per sabato alle 11. A presto! 💇"),
("appuntamento","email","Oggetto: Promemoria prenotazione\n\nGentile Sig.ra Conti, la sua prenotazione per il tagliando auto e' confermata per martedi' 18 alle 8:30 presso Officina Rossi. Porti libretto e chiavi. Cordiali saluti."),
("appuntamento","sms","ASL Roma: ricordiamo prenotazione ecografia 20/10 ore 10:15. Per spostare chiami il CUP al numero indicato sul foglio di prenotazione."),
("appuntamento","sms","Hotel Belvedere: confermiamo la vostra prenotazione dal 3 al 5 dicembre. Check-in dalle 14. Vi aspettiamo!"),
("appuntamento","whatsapp","ciao, ti ricordo che domani c'e' la riunione di condominio alle 18:30 nel garage. porta la delega se non vieni"),
("appuntamento","sms","Veterinario Dott. Neri: vaccino di Birba previsto lunedi' 9:00. Grazie."),
("appuntamento","sms","Ottica Bruni: i suoi occhiali sono pronti, passi a ritirarli quando vuole. Orari 9-13 / 16-19."),
("appuntamento","sms","Agenzia delle Entrate: appuntamento confermato per il 12/11 alle 10:20 presso la sede di Napoli. Porti un documento d'identita'."),
# bollette vere
("bolletta","email","Oggetto: La tua bolletta luce e' disponibile\n\nCiao Giorgio, e' disponibile la bolletta n. 2026/884412 di 62,40 EUR con scadenza 30/11. La trovi nell'area clienti dopo l'accesso su https://www.enel.it oppure nell'app. Il pagamento avviene con addebito sul conto."),
("bolletta","sms","Eni Plenitude: la tua fattura gas di 41,15 EUR scade il 05/12. Pagamento automatico attivo, non devi fare nulla."),
("bolletta","email","Oggetto: Nuova fattura Fastweb\n\nGentile cliente, la fattura di ottobre e' disponibile in MyFastweb (https://www.fastweb.it). Importo 29,95 EUR, addebito su carta il 28/10."),
("bolletta","sms","A2A Energia: autolettura gas. Puoi comunicarla fino al 25 dal tuo Spazio Clienti su https://www.a2a.it"),
("bolletta","email","Oggetto: Acqua - fattura in scadenza\n\nIl documento relativo al periodo luglio-settembre e' consultabile nel portale clienti del gestore. Per informazioni chiami il numero verde riportato sul retro della bolletta."),
("bolletta","sms","Iren: la bolletta e' stata addebitata sul tuo conto corrente. Importo 58,20 EUR. Grazie."),
("bolletta","sms","TIM: la fattura n. 7X00123456 e' disponibile su MyTIM (https://www.tim.it). Addebito di 24,90 EUR previsto il 12."),
("bolletta","email","Oggetto: Bolletta disponibile\n\nLa tua fattura energia elettrica e' pronta. Puoi consultarla nell'area personale su https://www.eni.com. Importo 73,10 EUR, scadenza 15/12."),
# familiari
("familiare","whatsapp","Ciao mamma, sono io. Arrivo alle 20 per cena, porto il dolce 🍰 non cucinare troppo!"),
("familiare","whatsapp","amore ricordati di prendere il latte tornando a casa e di ritirare i vestiti in tintoria 😘"),
("familiare","whatsapp","Nonna oggi hanno fatto una recita a scuola, ti mando il video. E' stato bravissimo!!"),
("familiare","sms","Ciao Paola, sono Marco. Stasera pizza da Gino? Ci sono anche Sara e Luca. Fammi sapere entro le 5."),
("familiare","whatsapp","papà il cambio gomme lo faccio sabato, l'officina mi ha dato appuntamento alle 9. ti serve la macchina prima?"),
("familiare","whatsapp","Auguri zio Franco!! 🎂🎉 Cento di questi giorni. Ci vediamo domenica a pranzo."),
("familiare","whatsapp","ho cambiato lavoro! ti chiamo stasera e ti racconto tutto, sono felicissima"),
("familiare","sms","Sono nonna, ho il telefono nuovo ma il numero e' sempre lo stesso. Chiamami quando puoi che devo dirti una cosa sulla domenica."),
("familiare","whatsapp","Mi si e' scaricato il telefono, ti scrivo dal numero di Anna. Sono Davide, mi passi a prendere alle 19 in stazione?"),
("familiare","whatsapp","Ciao zia, scusa il disturbo. Domani devo fare il bonifico per l'affitto, ti va se ti passo a trovare e ne parliamo di persona?"),
("familiare","whatsapp","Ciao tesoro, ti ho mandato 50 euro per la spesa con Satispay. Fammi sapere se arrivano 😘"),
("familiare","whatsapp","Mamma ho ricevuto il tuo messaggio, ti chiamo appena esco dal lavoro. Bacio"),
("familiare","whatsapp","Ciao Luca, la mamma ha detto di portare il vino domani. Io compro il pane. A che ora arrivi?"),
("familiare","whatsapp","Sono arrivata! Parcheggio in via Verdi 🚗 sali pure."),
# newsletter
("newsletter","email","Oggetto: Le novita' di novembre da Libreria Mondadori\n\nScopri i libri piu' attesi del mese, le uscite e gli eventi in libreria. Per non ricevere piu' queste email puoi annullare l'iscrizione dalle impostazioni del tuo account su https://www.mondadoristore.it"),
("newsletter","email","Oggetto: Il tuo riepilogo settimanale da Il Post\n\nEcco gli articoli piu' letti della settimana. Buona lettura! Gestisci le preferenze della newsletter su https://www.ilpost.it/newsletter"),
("newsletter","email","Oggetto: Offerte del weekend - Decathlon\n\nFino al 30% di sconto su running e outdoor in negozio e su https://www.decathlon.it. Offerta valida fino a domenica."),
("newsletter","email","Oggetto: Black Friday in anticipo da MediaWorld\n\nIniziano le offerte: tv, smartphone ed elettrodomestici a prezzi speciali su https://www.mediaworld.it. Leggi le condizioni in negozio."),
("newsletter","email","Oggetto: Come risparmiare sulla bolletta in inverno\n\nI consigli del nostro blog per ridurre i consumi di luce e gas. Nessuna azione richiesta. Annulla iscrizione dal link in fondo alla pagina."),
("newsletter","email","Oggetto: Novita' dal tuo comune\n\nCalendario raccolta differenziata aggiornato e orari estivi della biblioteca. Consulta il sito ufficiale del Comune."),
("newsletter","email","Oggetto: Eventi del weekend a Bologna\n\nMostre, concerti e mercatini. Trovi il programma completo su https://www.bolognawelcome.com"),
# conferme ordine
("ordine","email","Oggetto: Il tuo ordine Amazon.it #171-0000000-0000000\n\nCiao Sara, grazie per il tuo ordine. Consegna prevista: venerdi'. Puoi controllare lo stato in 'I miei ordini' su https://www.amazon.it. Hai ordinato: Cuffie Bluetooth (39,99 EUR)."),
("ordine","email","Oggetto: Conferma ordine Zalando n. 1049-22\n\nGrazie per il tuo acquisto! Stiamo preparando il tuo pacco. Tutti i dettagli nel tuo account su https://www.zalando.it"),
("ordine","sms","IKEA: il suo ordine e' pronto per il ritiro in negozio. Mostri la conferma ricevuta via email. Orari su https://www.ikea.com/it"),
("ordine","email","Oggetto: Ricevuta di pagamento - Netflix\n\nAbbiamo ricevuto il pagamento di 12,99 EUR per il mese di novembre. Nessuna azione e' richiesta. Gestisci l'abbonamento da https://www.netflix.com/it/youraccount"),
("ordine","email","Oggetto: Rimborso emesso - Zalando\n\nAbbiamo elaborato il rimborso di 45,00 EUR per il reso. Lo vedrai sul metodo di pagamento originale entro 5-7 giorni lavorativi."),
("ordine","email","Oggetto: Grazie per il tuo acquisto su Vinted\n\nL'acquirente ha confermato la ricezione. Il pagamento sara' accreditato nel tuo portafoglio Vinted. Dettagli su https://www.vinted.it"),
("ordine","email","Oggetto: Spotify - Ricevuta\n\nPiano Premium Individual, 10,99 EUR. Il prossimo addebito e' il 14/11. Gestisci il piano su https://www.spotify.com/it/account"),
("ordine","sms","Subito.it: hai ricevuto un nuovo messaggio per il tuo annuncio 'Bici da corsa'. Leggilo nell'app Subito."),
("ordine","whatsapp","Ciao, sono quello che ha comprato la lavatrice. Passo domani alle 18, ti pago in contanti alla consegna. Va bene?"),
# inps/pagopa/istituzionali
("istituzionale","sms","INPS: e' disponibile una nuova comunicazione nel tuo Cassetto previdenziale. Accedi con SPID dall'app INPS mobile o dal portale https://www.inps.it"),
("istituzionale","email","Oggetto: pagoPA - Avviso di pagamento disponibile\n\nHai un avviso pagoPA per la TARI 2026 del Comune. Puoi pagare dall'app IO o dal sito del tuo Comune. Questo messaggio non contiene link di pagamento: non cliccare su link ricevuti da altre fonti."),
("istituzionale","sms","App IO: hai un nuovo messaggio dal Comune. Aprilo nell'app IO per leggerlo."),
("istituzionale","email","Oggetto: Agenzia delle Entrate - Dichiarazione precompilata\n\nLa dichiarazione precompilata e' disponibile dal sito https://www.agenziaentrate.gov.it con SPID, CIE o CNS. L'Agenzia non invia mai link per inserire credenziali o dati della carta."),
("istituzionale","sms","INPS: la tua domanda di Assegno Unico e' stata accolta. Verifica l'esito nell'area personale MyINPS, accedendo con SPID."),
("istituzionale","email","Oggetto: Scadenza carta d'identita' elettronica\n\nLa tua CIE scade il 14/12. Puoi prenotare il rinnovo presso il tuo Comune da https://www.prenotazionicie.interno.gov.it. Nessun pagamento online e' richiesto."),
("istituzionale","sms","Comune di Torino: avviso, domani sospensione della raccolta rifiuti per festivita'. Info su https://www.comune.torino.it"),
("istituzionale","sms","Agenzia Entrate: la tua dichiarazione e' stata trasmessa con successo. Ricevuta disponibile nel tuo cassetto fiscale sul sito ufficiale."),
("istituzionale","email","Oggetto: Il tuo SPID e' attivo\n\nPoste Italiane: l'identita' digitale PosteID e' attiva. Per assistenza usi l'app PosteID o il sito https://www.poste.it. Non chiederemo mai le sue credenziali per email."),
("istituzionale","sms","Agenzia delle Entrate-Riscossione: la rata di rottamazione scade il 30/11. Puoi pagare con pagoPA dall'app o da https://www.agenziaentrateriscossione.gov.it"),
# sconti
("sconto","sms","Esselunga: con il tuo codice SCONTO10 hai il 10% sulla spesa online fino al 31/10. Info su https://www.esselunga.it"),
("sconto","whatsapp","Ciao da Profumeria Rosa! Questa settimana -20% su tutti i profumi, mostra questo messaggio in cassa. 🌸"),
("sconto","sms","Conad: nuova raccolta punti! Passa in negozio con la carta Insieme per ritirare il catalogo premi."),
("sconto","email","Oggetto: 15 euro per te\n\nUsa il codice BENVENUTO15 sul tuo primo ordine su https://www.zalando.it. Valido 14 giorni, minimo d'acquisto 50 euro."),
("sconto","sms","Pizzeria Da Gino: martedi' 2x1 sulle pizze! Prenota allo 06 000 0000. Per non ricevere piu' sms rispondi STOP."),
("sconto","sms","Lidl Plus: hai un nuovo coupon -5 euro con spesa minima 40 euro. Attivalo nell'app Lidl Plus."),
("sconto","email","Oggetto: Il tuo coupon Douglas\n\nHai ricevuto un coupon da 10 euro valido in negozio fino al 15/11. Mostra il codice a barre in cassa. Info su https://www.douglas.it"),
# banca info
("banca_info","sms","Intesa Sanpaolo: pagamento di 32,50 EUR con carta *4417 presso ESSELUNGA MILANO. Non riconosci l'operazione? Contatta il numero verde sul retro della carta."),
("banca_info","sms","UniCredit: bonifico di 450,00 EUR ricevuto sul tuo conto. Dettagli nell'app Mobile Banking."),
("banca_info","email","Oggetto: Avviso di sicurezza\n\nPer proteggere il tuo conto non ti chiederemo mai password o codici via email, SMS o telefono. Se ricevi richieste sospette, non rispondere e segnalalo dall'app."),
("banca_info","email","Oggetto: Estratto conto disponibile\n\nL'estratto conto di ottobre e' disponibile nella tua area riservata. Accedi dall'app o dal sito della banca digitando l'indirizzo nel browser."),
("banca_info","sms","Postepay: acquisto di 15,00 EUR presso AMAZON IT. Se non l'hai fatto tu, blocca la carta dall'app PostePay."),
("banca_info","sms","Nexi: pagamento autorizzato 12,80 EUR a CAFFE CENTRALE. Grazie per aver scelto Nexi."),
("banca_info","sms","BancoPosta: nuova funzione disponibile nell'app BancoPosta. Aggiornala dallo store ufficiale del tuo telefono."),
# lavoro/amici/altro
("lavoro_vero","email","Oggetto: Colloquio confermato\n\nGentile Dott.ssa Greco, confermiamo il colloquio di giovedi' 15 alle 10:00 presso la nostra sede di Milano. Porti il CV aggiornato. Cordiali saluti, HR Bianchi S.p.A."),
("lavoro_vero","email","Oggetto: Cedolino ottobre disponibile\n\nIl tuo cedolino e' stato pubblicato nel portale HR aziendale. Accedi con le tue credenziali interne dalla intranet."),
("lavoro_vero","whatsapp","Buongiorno, sono Roberta dell'agenzia di selezione. Ho visto il suo profilo, avrebbe disponibilita' per una call domani? Nessun costo per il candidato."),
("amici","whatsapp","ragazzi stasera calcetto alle 21, chi c'e'? Siamo in 8, ne mancano 2"),
("amici","whatsapp","ti ho mandato il link del biglietto del concerto: https://www.ticketone.it/ordine/12345. Ci vediamo davanti all'ingresso alle 20 ✌️"),
("amici","whatsapp","Hai visto che Paolo si sposa?? Facciamo un regalo insieme, io metto 30€. Mi mandi la tua quota con Satispay?"),
("amici","sms","Ciao, sono Giulia della palestra. Il corso di yoga di giovedi' e' spostato alle 19. A presto!"),
("amici","whatsapp","Ti ricordi del consiglio che ci ha dato la commercialista? Lei dice di aspettare prima di investire in fondi. Ne parliamo a cena."),
("amici","whatsapp","Ti mando il mio IBAN per la cena di ieri, ognuno 18 euro: " + I1 + " grazie Chiara!"),
("amici","whatsapp","Ehi, ricordi la password del wifi di casa tua? Quella con il nome del cane? 😅"),
("amici","sms","Ci vediamo alle 20 al solito posto. Porto io le birre. Se tardi scrivimi."),
("scuola","sms","Istituto Comprensivo Leopardi: domani uscita anticipata alle 12 per assemblea sindacale. Comunicazione sul registro elettronico."),
("scuola","email","Oggetto: Pagamento gita scolastica\n\nEgregi genitori, la quota di 35 EUR per la gita del 20/11 va versata tramite pagoPA dalla sezione Pagamenti del registro elettronico. Non vengono richiesti pagamenti in contanti."),
("trasporti","sms","Trenitalia: il tuo treno 9512 per Bologna e' in ritardo di 15 minuti. Binario 7. Buon viaggio."),
("trasporti","email","Oggetto: Il tuo biglietto Italo\n\nIn allegato il biglietto per Roma Termini - Milano Centrale del 22/11. Buon viaggio! Gestisci la prenotazione su https://www.italotreno.it"),
("trasporti","sms","Telepass: la fattura di ottobre (46,70 EUR) e' disponibile nell'area clienti dell'app Telepass. Addebito automatico sul conto."),
("trasporti","sms","Autostrade per l'Italia: per pagare un transito senza Telepass hai 15 giorni su https://www.autostrade.it, sezione 'Paga il pedaggio'."),
("trasporti","sms","ATM Milano: sciopero domani dalle 8:45 alle 15 e dalle 18. Info su https://www.atm.it"),
("salute","sms","Farmacia San Marco: il medicinale che ha ordinato e' arrivato. Puo' ritirarlo in negozio fino a venerdi'."),
("salute","email","Oggetto: Referto disponibile\n\nIl referto dell'esame del 10/10 e' disponibile sul Fascicolo Sanitario Elettronico regionale. Accedi con SPID."),
("salute","sms","Laboratorio Analisi Rossi: i risultati sono disponibili. Ritiro in sede o scarico online dal sito del laboratorio con le credenziali stampate sul foglio di accettazione."),
("condominio","whatsapp","Avviso dall'amministratore: giovedi' manutenzione ascensore, dalle 9 alle 12 sara' fermo. Ci scusiamo per il disagio."),
("condominio","email","Oggetto: Rate condominiali\n\nGentile condomino, la 3a rata di 180 EUR scade il 30/11. Il versamento va fatto sull'IBAN indicato nel bilancio approvato in assemblea. Per variazioni di IBAN la contatteremo solo per raccomandata."),
("assicurazione","sms","UnipolSai: la polizza auto in scadenza il 03/12. Il tuo agente ti contattera' per il rinnovo. Nessuna azione e' richiesta ora."),
("assicurazione","email","Oggetto: Quietanza di pagamento\n\nAbbiamo ricevuto il pagamento del premio di 412,00 EUR. Il certificato sara' disponibile nella tua area riservata su https://www.generali.it."),
("telefonia","sms","Vodafone: hai consumato l'80% dei giga. Puoi controllare il traffico nell'app My Vodafone."),
("telefonia","sms","WindTre: la tua ricarica automatica di 10 EUR e' andata a buon fine. Grazie!"),
("telefonia","sms","Iliad: la tua offerta si rinnova il 03/11, 9,99 EUR. Per info vai su https://www.iliad.it"),
("telefonia","email","Oggetto: Il tuo contratto TIM\n\nConfermiamo la variazione dell'offerta richiesta in negozio. Dal 01/12 il costo sara' di 19,90 EUR al mese. Se non hai richiesto la modifica contatta il 187."),
# qr legittimi
("qr","qr","https://www.comune.milano.it/servizi/parcheggi"),
("qr","qr","https://www.trenitalia.com/it/informazioni/orari.html"),
("qr","qr","https://www.poste.it/cerca/index.html"),
("qr","qr","https://www.amazon.it/gp/css/order-history"),
("qr","qr","https://www.inps.it/it/it/dettaglio-scheda.html"),
("qr","qr","https://www.agenziaentrate.gov.it/portale/home"),
("qr","qr","https://www.museivaticani.va/content/museivaticani/it.html"),
("qr","qr","https://www.mondadoristore.it/libri/"),
]

# varianti negozi (deterministiche)
shops = ["Esselunga", "Coop", "Carrefour", "Decathlon", "Leroy Merlin", "Unieuro", "Feltrinelli", "Douglas"]
tpl_l = [
    "{s}: il tuo ordine #{n} e' pronto per il ritiro. Mostra la mail di conferma alla cassa.",
    "{s}: grazie per il tuo acquisto! Lo scontrino e' disponibile nell'app. Per il reso hai 30 giorni.",
    "{s} ti ricorda che il tuo buono sconto scade il {d}/12. Utilizzabile solo in negozio.",
]
for i in range(10):
    LEGIT.append((random.choice(["ordine", "sconto"]), random.choice(["sms", "email"]),
                  random.choice(tpl_l).format(s=shops[i % len(shops)], n=random.randint(10000, 99999), d=random.randint(1, 28))))


def build(rows, label, prefix):
    out, seen = [], set()
    for cat, ch, text in rows:
        if text in seen:
            continue
        seen.add(text)
        out.append({"id": "%s-%03d" % (prefix, len(out) + 1), "text": text, "label": label,
                    "category": cat, "channel": ch, "source": "sintetico"})
    return out


for name, rows, label, pre in (("scam", SCAM, "scam", "S"), ("legit", LEGIT, "legit", "L")):
    recs = build(rows, label, pre)
    with open(os.path.join(HERE, name + ".jsonl"), "w", encoding="utf-8", newline="\n") as f:
        for r in recs:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")
    print(name, len(recs))
