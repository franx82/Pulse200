# Pulse 200

App Android nativa Kotlin/Jetpack Compose ispirata al mockup fornito e alle funzioni del TAMA RW200. Il nome pubblico è Pulse 200, con icona dedicata a scanalature, pad TAP TEMPO e targhetta PULSE 200. L'interfaccia è composta da controlli reali; il metronomo è generato da un callback audio nativo Oboe, non da timer UI. Il pacchetto Android e le chiavi delle preferenze sono conservati per aggiornare l'installazione esistente senza perdere programmi o impostazioni.

## Funzioni implementate

- Mixer con sei fader indipendenti: quarter, eighth (solo levare), sixteenth (secondo e quarto sedicesimo), triplet (tutti e tre i colpi) e beat (accento al primo beat, senza guadagno aggiuntivo). SOUND 1: C#7 per quarter/eighth/sixteenth, C#6 per triplet e F#7 per beat. SOUND 2: B5 per quarter/eighth/sixteenth, B4 per triplet e F#7 per beat. L'area touch di ciascun fader copre tutta la larghezza della scala e il relativo canale.
- Tempo 30–250 BPM regolato trascinando verticalmente la manopola; tap tempo calcolato da almeno cinque tocchi, con media mobile degli ultimi quattro intervalli, lampeggio rapido del BPM per un secondo alla fine della misurazione e indicazione `Lo`/`Hi` fuori intervallo.
- Beat 0–9; a zero non viene prodotto il colpo di campanella/accento BEAT.
- Due timbri sintetici SOUND 1/2, rispettivamente più brillante e più morbido. Non sono campioni del RW200.
- 30 slot di programma persistenti. `−`/`+` seleziona gli slot; STORE salva tempo, beat e suono nello slot, senza salvare MODE o i livelli del mixer.
- PRACTICE suona finché non si preme STOP. STAGE termina dopo otto battute, avanza al programma seguente e resta in standby. Con Beat 0, che non definisce una lunghezza di battuta, l'app conta un click per battuta per conservare le otto unità Stage.
- Impostazioni e livelli correnti persistono alla riapertura. Il display mostra la batteria Android, si oscura dopo 10 secondi e STORE premuto a lungo abilita/disabilita la retroilluminazione simulata. PROGRAM +/- riusa gli stessi pulsanti circolari e la stessa dimensione degli altri comandi; i centri dei due tasti occupano due celle equidistanti e i simboli sono sotto come sul mockup. SOUND è selezionabile dalla UI. La schermata riempie l'area sicura disponibile senza scroll, con sfondo nero edge-to-edge; i fader si adattano allo spazio verticale e lasciano il cursore completamente visibile agli estremi.
- Foreground service per continuare la riproduzione mentre l'app è in background.
- Zona inferiore compatta: START/STOP e corpo della manopola condividono l'altezza, adattata all'altezza disponibile entro 72–92 dp. I fader occupano lo spazio verticale restante: sui display più alti si allungano e la fila dei pulsanti si sposta verso il basso. Rimosse le didascalie sotto la manopola e la dicitura tecnica di stato per recuperare spazio.

## Differenze hardware

La riproduzione usa il dispositivo Android o l'uscita audio collegata. Pulsanti fisici, LED tempo, altoparlante e jack LINE dedicati del RW200 non sono presenti. I due timbri sono sintesi provvisorie: per una riproduzione timbrica fedele servono campioni audio originali utilizzabili. Il feedback STORE è visualizzato sul display perché l'interfaccia app non usa i LED fisici del mockup.

## Verifica e build

Build debug ARM64 verificata con Gradle. L'APK condivisibile si trova in `outputs/Pulse200-debug.apk`. L'icona originale è salvata in `outputs/Pulse200-icon.png` e usata nel progetto come icona adattiva con margine per le maschere del launcher. La compilazione dimostra che Kotlin, JNI, C++ e packaging APK sono integrati; non certifica latenza, jitter, qualità acustica o comportamento sul Pixel. ADB non rilevava un telefono connesso al momento della build, quindi installazione e prova reale restano da fare.

Per rigenerare: usare il Gradle Wrapper incluso con JDK 21 e SDK/NDK definiti nel progetto, poi eseguire `./gradlew assembleDebug`.
