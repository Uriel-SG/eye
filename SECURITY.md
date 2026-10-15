# Sicurezza

## Segnalazione responsabile
Scrivi a **urielsec@salvatoregarasto.com** con descrizione e passi per riprodurre. Non aprire issue pubbliche per vulnerabilità e non allegare dati reali. Ti chiediamo un tempo ragionevole per la correzione prima della divulgazione.

## Chiavi di firma
Le chiavi di firma di produzione dei pacchetti regole e del catalogo **non sono nel repository**. Le sole chiavi private presenti (`*.testkey.pem`) servono ai test e non hanno alcun valore.

## Fork
Chi fa un fork deve generare le proprie chiavi (`tools/rulepack/README.md`), sostituire le chiavi pubbliche nel motore e cambiare `FEED_BASE_URL`, altrimenti l'app non accetterà i suoi feed. Non riutilizzare nome e logo «Eye».

Modello delle minacce: `docs/security/THREAT_MODEL.md`.
