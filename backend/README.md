# Online basis voor YShott

Deze map bevat de eerste databasebasis. Deze is nog niet op Supabase uitgerold en nog niet verbonden met de Android-app. De bestaande APK blijft voorbeeldmodus.

## Wat deze basis doet

- Een geverifieerde volwassene maakt een huishouden met een unieke, roterende uitnodigingscode.
- Een partner vraagt deelname aan; een beheerder moet dit goedkeuren.
- Een co-ouder koppelt een eigen huishouden. Dit geeft geen toegang tot boodschappen, gezinschat of de volledige planner van het andere huishouden.
- Co-ouders zien standaard uitsluitend bezet/vrij. Een expliciet gedeelde afspraak toont volledige details. Intrekken geldt bij de volgende serverquery zonder te wachten op een nieuw toegangstoken.
- De planner gebruikt versienummers om onbedoeld overschrijven door twee apparaten te voorkomen. Een conflict moet in de app opgelost worden voordat opnieuw opgeslagen wordt.
- De database beperkt codepogingen per account en bewaart uitsluitend hashes van uitnodigingscodes.
- Alle tabellen hebben RLS. Clients mogen gegevens lezen waarvoor zij bevoegd zijn. Wijzigingen verlopen door functies met gecontroleerde rechten.

## Verificatie

GitHub Actions gebruikt een wegwerpdatabase met PostgreSQL 17. De Auth-fixture bootst uitsluitend auth.uid() en de benodigde uservelden na. De tests controleren echte SQL-rechten, familie-isolatie, beheerdergoedkeuring, co-ouderinzage, intrekken en versieconflicten. Dit vervangt geen integratietest met een live Supabase Auth-project.

## Uitrol

1. Kies de Supabase-organisatie met de gebruiker en controleer de prijs; het afgesproken maandbudget is EUR 0.
2. Maak het gratis project en genereer met de Supabase CLI een migratiebestand voor de definitieve schema-uitrol.
3. Pas de beoordeelde SQL toe, voer dezelfde toegangscontroles uit en controleer Supabase security advisors.
4. Verbind de Android-app met de openbare project-URL en publiceerbare sleutel. De service role hoort nooit in de APK of openbare broncode.
5. Verifieer registratie, e-mailaflevering, gekozen pincode, herstel, gezinsgoedkeuring en synchronisatie met twee echte accounts en twee huishoudens.

## Nog te bouwen

Deze basis implementeert nog geen e-mail/pincode-authenticatie, aparte kindersessies, gedeelde kinderprofielen, verblijfsschema-goedkeuring tussen twee ouders, externe agenda-import of abonnementslinks, push, locatie, online chat of AI-receptzoeken.

De gekozen viercijferige pincode moet door een serverdienst met pogingsbeperking en een private server-secret worden gecontroleerd. Hij mag niet rechtstreeks als Supabase-wachtwoord worden gebruikt. E-mailverificatie en herstel vereisen echte aflevering via een geschikte e-maildienst. Kindersessies krijgen beperkte serverrechten; een lokaal gefilterd ouderaccount vervangt die rechten niet.

De eerste kalenderfuncties ondersteunen losse afspraken. Herhalingen, wijzigingen per voorkomen en externe kalenderbronnen worden als afzonderlijke uitbreidingen toegevoegd. De Android-plannersnapshot en de gedeelde kalendertabellen moeten bij integratie samen bijgewerkt worden; deze map claimt nog geen synchronisatie.
