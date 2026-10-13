# YShott webversie

Mobiele webversie met hetzelfde ontwerp en dezelfde PlannerData-velden als de Android-voorbeeldapp. Vandaag, dag/week/maandagenda, afspraken/herhalingen, eigen verblijfsschema, taken met oudercontrole, punten/beloningen, boodschappen/vaste artikelen, recepten/kookstappen/timer en lokale voorbeeldberichten werken in de browser.

## Status
Dit is een lokale voorbeeldversie. Er worden geen accounts aangemaakt en geen e-mails of online berichten verstuurd. Web en Android synchroniseren nog niet. Supabase, externe agenda-abonnementen, echte co-oudergoedkeuringen, AI recepten en locatie/push moeten worden aangesloten op de gedeelde gratis backend. Medicatie is uitgesloten.

## Openen
Download het resultaat YShott-web van de geslaagde workflow Web checks and package. Pak de ZIP volledig uit. Open index.html in een recente browser. Als je browser opslag van lokale bestanden beperkt, serveer de map via localhost:

    python3 -m http.server 8000

Open vervolgens http://localhost:8000. Voor telefoons is HTTPS-hosting de normale gebruiksroute.

## Gratis publiceren
GitHub Pages is voorbereid met .github/workflows/web-pages.yml. Binnen deze openbare repository:
1. Zet Settings > Pages > Build and deployment > Source op GitHub Actions.
2. Voeg de geteste webwijzigingen aan main toe.
3. Laat Publish web version slagen of start die workflow handmatig.
4. Gebruik uitsluitend de URL die de geslaagde publicatie teruggeeft.

De gekoppelde GitHub-tools kunnen Pages-instellingen momenteel niet wijzigen. Er is geen betaalde hosting of eigen domein besteld. De voorbereidende webworkflow publiceert geen privégezinsgegevens: die blijven lokaal in de browser. De echte toegang tot gezinsgegevens moet later serverzijdig worden gecontroleerd.

## Opslag, PIN en herstel
De planner is AES-GCM versleuteld met een niet-exporteerbare WebCrypto-sleutel in IndexedDB. Data staat als versleutelde tekst in localStorage. Wis je browsergegevens niet zonder een back-up: de sleutel is aan de browser/oorsprong gebonden. Een andere URL of browser krijgt een aparte planner.

De ouderpincode is lokaal een PBKDF2-afleiding met willekeurig zout; vijf verkeerde pogingen geven vijftien minuten wachttijd. Dit is een lokale kindmodusgrens, geen serverauthenticatie of bescherming tegen iemand met volledige toegang tot het browserprofiel. Een corrupt of onleesbaar bestaand bestand wordt niet stil vervangen.

Meer > Instellingen bevat JSON-back-ups en ICS-export. JSON is leesbaar en moet privé worden bewaard. De brondata kan door de Android-voorbeeldapp worden geïmporteerd. De ouderpincode en browsercryptosleutel worden niet geëxporteerd. Een ICS-bestand is een eenmalige export; een intrekbare abonnementslink is nog niet actief.

## Tests en updates
Node 22:
    node --test web/tests/model.test.cjs

De webworkflow test daarnaast Chromium op mobiele en desktopmaten, herladen/versleutelde opslag, agendaweergaven, receptingrediënten, kindpincode en oudergoedkeuring van punten. Wijzig bij shell-updates ook de versie in sw.js. De service worker cachet uitsluitend statische appbestanden en nooit API-antwoorden.

Nunito is onder de SIL Open Font License meegeleverd; zie assets/Nunito-OFL.txt. De kindpictogrammen zijn voor YShott gegenereerd.
