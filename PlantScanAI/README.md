# PlantScan AI — Android-app voor tuininventarisatie (MVP 0.2.0)

Een Nederlandstalige **native Android-app** die met videobeelden planten helpt inventariseren en een PDF-rapport exporteert. De app is ontworpen voor APK-installatie via een gratis GitHub Actions-build, **zonder betaalde plantenherkennings-API**.

> ⚠️ **Proefversie / niet gevalideerd.** De app gebruikt een echte offline soortclassifier, maar **geen getraind detectie- of trackmodel voor afzonderlijke planten**. Automatische kaders ontstaan uit een eenvoudige groen-kleurheuristiek, en aantallen zijn voorlopige waarnemingen die je handmatig moet controleren. Een detectiekader kan een plant, groep, gazon of andere vegetatie voorstellen. De app is niet geschikt om zonder menselijke controle een complete tuininventarisatie te garanderen.

## Wat werkt in deze broncode

- Video filmen met de bestaande Android-camera-app, tot 15 minuten; of MP4/MOV selecteren via Android bestandskiezer.
- Videobeelden op intervallen selecteren, groen-vegetatiegebieden voorstellen, tussentijdse overlappende kaders voorlopig samenvoegen.
- **Echte gratis offline plantensoortherkenning** met [Iris 9](https://huggingface.co/brunopaiva15/iris): tot 1.569 soorten, top-5 suggesties met scores. Geen sleutel of cloudverzoek nodig.
- Waarnemingen als kaartjes met uitsneden, videomomenten, naam, suggesties, voorlopige tellingen.
- Correcties: ander soortvoorstel kiezen, vrije wetenschappelijke / Nederlandse naam invoeren, aantallen aanpassen, als groep markeren, bevestigen, opmerkingen, verwijderen en dubbele detecties handmatig samenvoegen.
- Video afspelen met voorlopige kaders, plant aanklikken, plant handmatig toevoegen via getekende selectie op een gepauzeerd beeld.
- Optioneel via een **publieke, gratis taxonomiezoekopdracht** Nederlandse plantnaam opzoeken bij iNaturalist. Dit verstuurt alleen de soortnaam, geen foto. Het is **geen tweede beeldherkenningsmodel**.
- PDF exporteren als echt `application/pdf` via de Android systeembestandskiezer, met voorblad, soortenoverzicht, aantallen, foto's, videotijdstippen, opmerkingen en onderscheid tussen bevestigd / AI-voorstel.
- Lokaal opslaan van de **laatste** inventarisatiesessie (geen account). De vorige sessie wordt vervangen wanneer je een nieuwe video kiest. Exporteer eerst de PDF.

## Niet gereed in deze eerste versie

- Geen bewezen individuele plantdetectie of objecttracking: de voorlopige aantallen zijn vaak niet correct.
- Geen onafhankelijke online beeldidentificatie: betrouwbare gratis, publiek toegestane endpoints zonder sleutel zijn niet aangetoond. Iris 9 blijft de primaire herkenner; de app werkt volledig offline behalve de optionele naamzoekopdracht.
- Geen automatische Nederlandse namen wanneer er geen internet is.
- Geen meervoudige projectgeschiedenis, GIS, metrische nauwkeurigheid, achtergrondanalyse bij gesloten app of uitgebreid voortgangsherstel.
- Niet gecompileerd/uitgevoerd op een echte Android-telefoon in deze werkomgeving; GitHub Actions moet de APK eerst succesvol bouwen, daarna volgt een toesteltest.

## APK bouwen op GitHub (geen computer noodzakelijk)

1. Maak op [GitHub](https://github.com/new) een **nieuw, leeg repository** `PlantScanAI` aan, bij voorkeur privé wanneer je opnames of testgegevens later gaat toevoegen. De bron bevat geen privéfoto's of video's.
2. Plaats de **inhoud van deze projectmap** in de hoofdmap van het nieuwe repository, inclusief de verborgen map `.github/workflows/android-apk.yml`.
3. Open het tabblad **Actions** en kies **Bouw Android APK** → **Run workflow**. Een push naar `main` of `master` start de workflow eveneens.
4. De workflow gebruikt Java 17, Android Gradle Plugin en downloadt Iris 9 met een SHA-256-controle.
5. Als de workflow **groen** is, open de run → **Artifacts** → `PlantScanAI-debug-apk`. Download en pak het artifact uit. Het bevat `app-debug.apk`.
6. Open `app-debug.apk` op je Android-telefoon en geef toestemming om voor deze bron apps te installeren (alleen als jij de bron vertrouwt).

**Belangrijk:** het ZIP-bestand van de bron is **zelf geen APK**. Het `.apk`-bestand bestaat pas nadat de Actions-workflow met succes is uitgevoerd. Een debug-APK is alleen voor eigen testgebruik, niet voor publicatie in de Play Store.

### Zonder PC bestanden naar GitHub kopiëren

Een praktische route is via GitHub Codespaces in de browser: maak eerst het lege repository, start een Codespace en upload het bron-ZIP naar de Codespace. In de terminal:

```bash
unzip PlantScanAI-source.zip -d /tmp/plantscan
cp -r /tmp/plantscan/PlantScanAI/. .
git add .
git commit -m "Eerste PlantScan AI Android-versie"
git push
```

Alternatief: upload de uitgepakte map met een GitHub-client die mappen en verborgen bestanden bewaart. Voorkom dat er een extra bovenliggende map in je repository ontstaat; `app/` en `.github/` moeten in de root staan.

## Lokale Android Studio-build

Vereist Android SDK Platform 35, JDK 17 en Gradle 8.9:

```bash
python3 tools/fetch_model.py
gradle :app:assembleDebug
```

De APK wordt geschreven naar `app/build/outputs/apk/debug/app-debug.apk`.

## Gratis model en juiste verwachting

Model: **Iris 9**, ontwikkeld door `brunopaiva15`. Modelbestanden onder **CC BY 4.0**, met auteursvermelding in app en PDF. Modelbron: https://huggingface.co/brunopaiva15/iris. Volgens het modelkaart-onderzoek: top-1 68,76% op testset, top-3 82,27% — dit zijn **geen** gegarandeerde accuratessecijfers voor tuinvideo's. De app beschouwt hoog vertrouwen als een *voorstel*, geen gevalideerde vaststelling.

Preprocessing in de Android-app volgt grofweg de officiële 448 → 366 → 320-pipeline en de vereiste 0–255 floatwaarden. De Android-resizer benadert de area-average reductie: het is geen pixel-perfecte reproductie van de trainingspipeline. Deze afwijking moet tijdens toetsing beoordeeld worden.

De workflow downloadt:
- `plants.tflite` (9 MB)
- `labels.txt`
- `model.json`

en controleert de model-**SHA-256**. Die binaire bestanden staan opzettelijk niet in het bron-ZIP.

## Privacy en limieten

De videoanalyse draait op het toestel. Geselecteerde foto's en de laatste projectgegevens worden lokaal opgeslagen in app-private opslag. Bij de optionele iNaturalist-zoekfunctie wordt alleen de door jou gekozen wetenschappelijke naam naar het publieke API-eindpunt gestuurd. Er worden geen video's of foto's automatisch geüpload. Android opslag- en mediapickers beheren de toegang zonder brede galerijrechten.

Groenherkenning kan bladeren onderscheiden van sommige achtergronden maar faalt onder andere bij bruine stengels, paarse bladkleuren, bloemen zonder zichtbaar blad, en grote gazons. Dicht op elkaar groeiende planten kunnen **niet betrouwbaar individueel geteld** worden. Gebruik handmatige selectie en aantalscorrectie.

## Technische stack

- Kotlin, native Android Views, minSdk 26, compileSdk 35
- TensorFlow Lite 2.16.1 met lokaal Iris 9 model
- Android MediaMetadataRetriever voor videoframes
- Android PdfDocument voor PDF-export
- Android `ACTION_VIDEO_CAPTURE` met FileProvider en `OpenDocument` voor video
- GitHub Actions voor Android debug-APK

## Volgende technische stap na eerste toesteltest

Introduceer een getraind instance-segmentation/detectiemodel voor plantenregio's, gevolgd door objecttracking met expliciete onzekerheidsregistratie. Voor een onafhankelijke online fallback: pas een dienst gebruiken nadat API, gratis gebruiksvoorwaarden en de licentie expliciet zijn geverifieerd; nooit een publieke API-sleutel in de app opnemen.

## Versie 0.2.0: herkenning met hogere beeldkwaliteit en veiligere voorstellen
- Analyseer videobeelden op maximaal 1000 px in plaats van 480 px.
- Lees dichtstbijzijnde werkelijke videoframes, niet uitsluitend sync/keyframes.
- Negeer videobeelden zonder gedetecteerde vegetatie in plaats van willekeurig de volledige foto te classificeren.
- Grote aaneengesloten groenvlakken worden onbekende plantengroepen, niet een willekeurige zekere soort.
- Alleen scores van minimaal 0,85 vullen de voorgestelde soort automatisch in. Lagere scores worden Onbekend en staan op de controlelijst.
- Op het resultaat kun je kiezen voor 'Extra videobeeld voor betere herkenning'. Selecteer dezelfde plant op het videobeeld. Wanneer de vorige uitsnede handmatig was geselecteerd, worden twee volledige modeldistributies gemiddeld. Bij een eerdere automatische groepering wordt de nieuwe, gerichte uitsnede opnieuw beoordeeld.
- Deze verbeteringen verbeteren de invoer en verminderen misleidende resultaten. De betrouwbaarheid is nog niet met eigen tuinvideo's gemeten. Het systeem is **geen echte individuele plantendetector**.
- Iris 9 blijft de basis. Iris 10 werd onderzocht, maar modelbestanden waren op 20 november 2026 niet beschikbaar op de gepubliceerde URL; daarom is geen werkende v10 in deze APK opgenomen.

### Praktijktest
Film één bekende plant met scherp zicht op blad en bloem. Selecteer de plant handmatig, controleer de beste vijf voorstellen en probeer daarna dezelfde plant vanuit een tweede hoek. Verzamel bij foutieve uitkomsten minimaal vijf gemarkeerde plantvoorbeelden voor een gerichte volgende verbetering.
