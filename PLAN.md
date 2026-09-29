# PLAN — NoToDo (second brain Android)

## Stato di partenza (verificato)
- Repository vuoto (nessun commit): progetto inizializzato da zero.
- Ambiente di sviluppo: JDK 21, Gradle 9.8.0 (wrapper), Android SDK installato a mano (platform 36/37, build-tools 36/37).
- **Nessun emulatore né device**: niente KVM nel container. Tutto ciò che richiede hardware è elencato sotto e va verificato sul Nothing Phone (3).

## Scelte tecniche
| Area | Scelta | Motivo |
|---|---|---|
| Build | AGP 9.4.1 (Kotlin integrato), Kotlin 2.4.20, KSP 2.3.12 | versioni stabili correnti |
| SDK | compileSdk 37, targetSdk 36, minSdk 33 | 37 richiesto da AndroidX core 1.19; target = Android 16 del Phone (3); min 33 elimina rami legacy (POST_NOTIFICATIONS, exact alarm) |
| UI | Jetpack Compose + Material 3 con tema proprio | nessuna libreria di navigazione: 3 schermate a stato |
| Dati | Room (fonte di verità), DataStore Preferences | offline-first, nessun account |
| DI | manuale (`App`) | Hilt non giustificato per ~5 dipendenze |
| Backup | kotlinx.serialization (JSON versionato) + export Markdown | round-trip senza mapping manuale |
| Promemoria | AlarmManager, **un solo allarme** (il prossimo), exact se concesso | niente limite 500 allarmi, rescheduling idempotente per costruzione |
| Riepiloghi | allarme inexact dedicato | WorkManager non necessario |
| Widget | RemoteViews | Glance non necessario |
| Parser | regole deterministiche italiane dietro `CaptureParser` | 100% offline; nessun LLM (nessuno verificato su device) |
| Ricerca | colonna normalizzata (minuscole, senza accenti) + `LIKE` per termine | trova sottostringhe tecniche (`10.0.0.1`, `sw-core-01`) che i tokenizer FTS spezzano; volumi personali |

Scelte deliberate di *non* scrivere codice (YAGNI):
- **Sync**: nessuna interfaccia finché non esiste un backend. Già pronti: UUID stabili, `updatedAt`, export JSON versionato (è il contratto per un futuro sync CalDAV/Nextcloud).
- **STT**: nessun motore proprio; si usa la dettatura della tastiera.
- **Relazioni**: derivate (stessa cattura, stesse persone/tag); link manuali rinviati.

## Modello dati
- `capture`: testo originale intatto, sorgente (assistente/app/condivisione/widget/deep link), fuso, stato `processed`. Una cattura non elaborata **è** l'Inbox.
- `item`: tipo (Task, Appuntamento, Nota, Idea, Riferimento, Da verificare), titolo, dettagli, `at` + fuso + precisione (esatta/approssimata/giorno) + testo sorgente della data, date menzionate, persone, tag, confidenza, motivi, span nella cattura, stato, avvisi (`m60`, `d1`, `d0@09:00`), stato promemoria (`firedUpTo`, `snoozeUntil`, `nextAlertAt`).
- `audit`: cronologia (creazione con motivazioni, modifiche, completamento, snooze, spostamento).

## Fasi
1. **Slice A** — progetto compilabile, parser + test (date Europe/Rome, associazione data→elemento), DB, cattura con bozza autosalvata, anteprima (Correggi/Unisci/Separa/Scarta/Salva tutto), salvataggio transazionale idempotente.
2. **Slice B** — viste Inbox/Oggi/Prossimi/In sospeso/Conoscenza/Idee/Tutto, ricerca, filtri, dettaglio con testo originale/provenienza/cronologia; notifiche con azioni (Fatto, +15 min, Rimanda…), snooze ≠ sposta scadenza, receiver boot/ora/fuso; backup JSON/Markdown via SAF. Test Robolectric.
3. **Slice C** — VoiceInteractionService + SessionService + Session (apre la cattura con `startAssistantActivity`), deep link/intent per MacroDroid, scorciatoie, condivisione, widget, riepilogo giornaliero e riscoperta note, rifinitura grafica.

## Rischi
| Rischio | Mitigazione |
|---|---|
| Nothing OS potrebbe non invocare l'assistente come AOSP / non a schermo bloccato | spike + checklist su device; `supportsLaunchVoiceAssistFromKeyguard=false` nella MVP; fallback deep link |
| Un solo assistente predefinito (conflitto con Gemini o MacroDroid) | l'app non cambia nulla da sola; README spiega il trade-off e il percorso MacroDroid |
| `SCHEDULE_EXACT_ALARM` negato di default (Android 14+) | richiesta esplicita in Impostazioni; fallback inexact **segnalato** in app |
| `USE_EXACT_ALARM` | non usato: la policy Play lo limita a sveglie/calendari |
| Doze / ottimizzazione batteria OEM | istruzioni per "Senza restrizioni"; nessuna promessa "al minuto" senza permessi |
| Euristiche italiane incomplete | anteprima obbligatoria, confidenza + motivi, testo originale sempre salvato, Inbox come fallback |
| Dettatura senza punteggiatura | split euristico data+verbo, Unisci/Separa manuali |

## Da verificare su hardware (non verificabile qui)
1. NoToDo compare in *Impostazioni › App › App predefinite › App assistente digitale*.
2. Pressione lunga power (*Funzioni speciali › Gesti › Tieni premuto il tasto di accensione = Assistente digitale*) apre la cattura sopra l'app corrente con tastiera visibile.
3. Comportamento a schermo bloccato.
4. Allarmi esatti con telefono in Doze; notifiche dopo riavvio.
5. Deep link da MacroDroid (azione *Avvia attività* / *Invia intent*).
6. Widget su launcher Nothing, TalkBack e font grandi.

## Criteri di accettazione
Test automatici (JVM/Robolectric) per:
1. «Domani alle 09:00 chiama Luca; venerdì prossimo alle 16 verifica backup» → 2 task, date corrette in Europe/Rome.
2. «Il router usa VLAN 40» → riferimento, «VLAN 40» intatto, nessuna data/notifica.
3. «Venerdì ricordami di chiamare Rossi» → task con giorno, ora non inventata, avviso marcato da confermare.
4. «Incontra Marco il 3 ottobre alle 15; ricordami il giorno prima» → un solo elemento con avviso il giorno prima.
5. Input incomprensibile / parser in errore → cattura intera in Inbox.
6. Snooze da notifica → un solo allarme successivo, scadenza invariata.
7. Export → import (anche doppio) → stessi dati, nessun duplicato.
Più: DST, cambio anno, ora passata, «questo» vs «prossimo» venerdì, rescheduling dopo boot e cambio fuso.
