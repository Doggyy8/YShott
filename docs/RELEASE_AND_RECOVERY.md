# YShott testen, herstellen en bijwerken

## Huidige levering

De broncode staat in de branch codex/android-app. De pull request bevat de actuele bouwstatus. Het resultaat YShott-test-apk in een geslaagde Android build is een ZIP met app-debug.apk. Pak de ZIP uit en open de APK op Android. Geef alleen de gebruikte browser of bestandsapp toestemming om deze APK te installeren.

Deze APK werkt in lokale voorbeeldmodus. Accounts, e-mail, cloudsync, externe agenda's, AI, locatie en push zijn nog niet actief. De eerste databasebasis is afzonderlijk met een wegwerpdatabase getest; dat maakt deze APK nog geen online versie.

## Gegevens bewaren

De planner bewaart lokale gegevens versleuteld met Android Keystore. Bij een nieuwe installatie is de sleutel anders. Het overzetten van alleen planner-demo.enc naar een andere telefoon herstelt de gegevens daarom niet.

Gebruik Meer > Instellingen > Back-up opslaan voordat je een testversie vervangt of de app verwijdert. De JSON-export bevat de leesbare planner; bewaar hem privé. Herstellen via dezelfde instellingen controleert de inhoud voordat deze de huidige planner vervangt.

Een geëxporteerde back-up bevat geen Android-sleutel, online toegangstoken of herstelbare ouderpincode. E-mailherstel wordt aangesloten wanneer het echte accountbeheer klaar is.

## Updates

Een debug-APK uit een nieuwe GitHub-run kan met een andere testsleutel ondertekend zijn. Android kan die installatie dan niet als update over de vorige zetten. Maak eerst een back-up. Verwijder de bestaande testapp alleen als je de export hebt en kies na de installatie Back-up herstellen.

Voor echte gebruiksversies is één vaste releasesleutel nodig, onder beheer van de eigenaar. Bewaar een beveiligde kopie buiten GitHub en voeg hem via een geheim toe aan het bouwproces. Plaats hem nooit in deze openbare repository, een openbaar bouwresultaat of de APK. Alle toekomstige updates moeten hetzelfde pakket nl.yshott.app, dezelfde sleutel en een hoger versionCode gebruiken.

## Procedure voor een wijziging

1. Maak een nieuwe broncodebranch.
2. Wijzig alleen de benodigde app- en serveronderdelen.
3. Laat de relevante Android- en databasecontroles slagen.
4. Test dat afspraken, taken, recepten en back-ups blijven werken na een app-update.
5. Test online wijzigingen met twee echte gebruikers en twee gescheiden huishoudens zodra die diensten actief zijn.
6. Bouw en onderteken de release met de vaste sleutel; noteer versie, commit, bouwresultaat en bekende beperkingen.
7. Bewaar de vorige werkende broncode en servermigraties zodat een fout hersteld kan worden. Een Android-downgrade vereist een aparte herstelroute; overschrijf geen gebruikersgegevens om dat af te dwingen.

## Serverherstel

De map backend/schema.sql is voorbereiding voor de eerste uitrol. Na projectkeuze wordt een echte migratie gegenereerd en de uitrol gecontroleerd. Bewaar daarna migratiegeschiedenis en periodieke exports van de eigen database volgens het gekozen gratis quotum. Servergeheimen blijven buiten exports die met testers worden gedeeld.

Het maandbudget blijft EUR 0. Betaalde functies of quota-uitbreidingen worden niet vanzelf geactiveerd.
