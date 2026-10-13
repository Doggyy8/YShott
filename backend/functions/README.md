# Accountdienst in voorbereiding

Deze dienst is nog niet gedeployed. Er is nog geen Supabase-project in de gekozen YShott-organisatie. De connector biedt projectprijscontrole en kostenbevestiging op dit moment niet aan; projectaanmaak is daarom nog geblokkeerd. De huidige Android-APK blijft lokaal voorbeeldmodus.

De Edge Function gebruikt geverifieerde Supabase Auth-sessies. De gekozen viercijferige PIN gaat nooit rechtstreeks als providerwachtwoord naar Supabase. Een uitsluitend voor de server toegankelijke databasefunctie leidt een lang wachtwoord af met HMAC-SHA256 en een private willekeurige pepper. De server begrenst pogingen per genormaliseerd e-mailadres, per korte periode en per dag. E-mailherstel blijft mogelijk bij een PIN-blokkade.

De pepper wordt éénmaal in de private database gegenereerd. Alleen de backend-service heeft leesrechten. Verlies of rotatie van deze pepper vereist e-mailherstel van bestaande accounts; behandel beveiligde serverback-ups daarom zorgvuldig. Geef de pepper of afgeleide wachtwoorden nooit aan de Android-app.

De API-acties zijn register, verify, login, recover en reset. Bevestiging en herstel gebruiken de tijdelijke code uit de e-mail; dagelijks inloggen gebruikt de zelfgekozen vier cijfers. Een herstel vereist een door Supabase gecontroleerde herstelcode voordat het providerwachtwoord wordt gewijzigd. Andere refresh-sessies worden ingetrokken. Reeds uitgegeven access tokens kunnen geldig blijven tot hun korte vervaltijd.

Voor deze credential-entrypoint wordt de gateway-JWT-controle bij uitrol uitgezet, omdat iemand vóór het inloggen nog geen gebruiker-JWT heeft. De handler implementeert zelf authenticatie met rate limiting, e-mailbewijs en Supabase Auth credentialcontrole. Authenticated gezinsfuncties blijven door de database op auth.uid() en actief lidmaatschap gecontroleerd. Schakel deze uitzondering niet in voor andere endpoints.

## Uitrol en integratie die nog nodig zijn

1. Maak het gratis Supabase-project in organisatie YShott, in een EU-regio.
2. Pas backend/schema.sql en daarna backend/auth.sql toe via gecontroleerde migraties. De CI-fixture hoort nooit in productie.
3. Deploy index.ts, handler.ts en deno.json als yshott-auth. Lees de standaard publiceerbare en geheime projectkeys uitsluitend uit de serveromgeving. Legacy keys zijn alleen een compatibiliteitsfallback.
4. Activeer e-mailbevestiging. Zet de access-tokenlevensduur op 15 minuten. Configureer een e-maildienst voor echte aflevering aan gezinsleden.
5. Gebruik de inhoud van backend/email/confirmation.html en recovery.html voor de bijbehorende Auth-mailtemplates. Test aflevering aan twee echte accounts.
6. Verbind de Android-login, bewaar access/refresh tokens versleuteld en sluit gezinsaanvragen en synchronisatie aan.
7. Test nieuwe registratie, onjuiste PIN, verlopen codes, terugkeer na herstel, gezinspartnergoedkeuring en co-ouderprivacy met het echte project.

De SQL- en Deno-tests gebruiken een wegwerpdatabase en een nep-Auth-provider. Ze bewijzen de lokale regels en afhandeling; zij bewijzen nog geen daadwerkelijke e-mailaflevering of live inloggen. Tokens, PINs en geheimen mogen niet gelogd of in GitHub opgeslagen worden.
