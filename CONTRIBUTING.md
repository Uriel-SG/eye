# Contribuire a Eye

Grazie! Regole del progetto:

1. **Test obbligatori.** Ogni modifica a regole o motore ha test in `engine/src/test` (`./gradlew :engine:test` deve passare). Nuove regole: almeno un caso positivo e uno negativo (falso positivo).
2. **Corpus anonimizzato.** Gli esempi in `corpus/` sono sintetici o anonimizzati: niente nomi, numeri di telefono, IBAN, indirizzi, link o email reali. Esegui `python corpus/check_anon.py`.
3. **Niente dati reali.** Nessun messaggio reale di persone, nessuna chiave, token o credenziale, nessun dato personale in codice, test, issue o screenshot.
4. **Privacy.** Niente nuovi permessi sensibili (SMS, accessibilità), niente analitica, niente invio dei contenuti dell'utente.
5. **Piccolo e idiomatico.** Kotlin idiomatico, modifiche mirate; il motore resta Kotlin puro.
6. **Licenza.** Contribuendo accetti che il tuo contributo sia rilasciato con Apache-2.0.

Apri una issue prima di modifiche grandi. Le PR devono passare la CI. Vedi `CODE_OF_CONDUCT.md`.
