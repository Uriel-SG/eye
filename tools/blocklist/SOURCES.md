# Fonti dell'elenco siti malevoli

Verificate il 2026-10-21. Regola: solo fonti senza chiave API e con termini che permettono di ridistribuire dati derivati
(qui: hash troncati dei nomi di host, mai gli URL). Rivedere i termini a ogni nuova versione dell'elenco.

| Fonte | Uso | Licenza / termini | Esito |
|---|---|---|---|
| Phishing.Database, `phishing-domains-ACTIVE.txt` e `phishing-links-ACTIVE.txt` (github.com/Phishing-Database/Phishing.Database, mitchellkrogza / funilrys) | domini e URL di phishing attivi, di ogni tipo (anche malware/ransomware) | MIT (copyright Mitchell Krog, Nissar Chababy, contributors): uso e ridistribuzione liberi con avviso di copyright | USATA. Attribuzione in `NOTICE` e nella schermata Licenze |
| Tranco top 1M (tranco-list.eu) | solo come FILTRO in fase di build (primi 50.000 domini mai nell'elenco); non e' ridistribuita | gratuita per uso di ricerca/tecnico, nessun dato ne' file di Tranco finisce nell'app o nel feed | USATA come filtro |
| URLhaus (abuse.ch) | host di URL malware attivi | I termini attuali di abuse.ch (abuse.ch/terms-of-use, sez. 3.4 e 4.1) danno come CC0 solo Feodo Tracker, SSL Blacklist e YARAify; per URLhaus l'uso «commerciale o a fini di lucro» puo' richiedere un abbonamento (Spamhaus) e l'export e' pensato per usi non commerciali | ESCLUSA di default (`--with-urlhaus` solo per prove locali). Contributo comunque piccolo (~1.800 host). Da riconsiderare solo con un accordo scritto con abuse.ch |
| OpenPhish community feed | URL di phishing | Feed community per uso non commerciale, senza diritto di ridistribuzione; Premium a pagamento | NON USATA |

Note:
- Nessuna fonte richiede chiave. Il download avviene solo sul PC di chi costruisce l'elenco (mai dal telefono).
- Il telefono scarica un solo file firmato dal sito di Eye; non invia nulla dell'utente.
- Falsi positivi: la fonte ACTIVE puo' contenere siti legittimi compromessi. Mitigazioni: filtri di `build_blocklist.py`
  (domini popolari/ufficiali, top 50.000 Tranco, istituzionali `.gov.it`/`comune.*`, hosting condiviso solo per host completo)
  e, nel motore, BLK-01 non scatta mai su domini ufficiali o molto diffusi.
