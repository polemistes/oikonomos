# Oikonomos

Android-app som holder orden på kvitteringer, fakturaer og enkelt regnskap for små norske foretak, som enkeltpersonforetak og små aksjeselskap uten ansatte.

Appen er et bilagsarkiv med oversikt og eksport. Den er ikke et fullt regnskapssystem, og den sender ingenting til myndighetene.

Appen er under utvikling og ligger ikke på Google Play.

## Hva den gjør

- **Kvitteringer:** skann, importer bilder og PDF-er, eller del dem inn fra andre apper. Appen foreslår leverandør, dato og beløp fra teksten.
- **Firmanavn fra Enhetsregisteret:** står organisasjonsnummeret på kvitteringen, slår appen det opp og foreslår det registrerte navnet.
- **Utenlandske kvitteringer:** appen kjenner igjen valutaen og regner om til kroner etter kursen på kvitteringens dato. Kvitteringer med gresk, arabisk, japansk og flere andre skrifter kan leses når du velger språket.
- **Avgjør senere:** en kvittering er uavklart til du fører den i regnskapet. Da får den bilagsnummer, og nummerrekken får aldri hull.
- **Fakturaer:** kunderegister, faktura som PDF med fortløpende nummer, og kreditnota.
- **Reiser og prosjekter:** samle bilag under det de gjaldt, med formål og kostnader.
- **Oversikt og eksport:** resultat per år, kategori og prosjekt, og eksport til CSV med vedlegg.
- **Sikkerhetskopi:** til en fil eller automatisk til en mappe du velger.

Alt lagres på telefonen, og tekstgjenkjenningen skjer på enheten. Bilagene dine og opplysninger om deg sendes aldri noe sted.

Appen bruker nettet bare til å hente åpne, offentlige data:

- firmanavn fra Enhetsregisteret, der bare organisasjonsnummeret sendes
- valutakurser fra Norges Bank, eller fra Frankfurter for valutaer Norges Bank ikke noterer, der bare valutakoden og datoen sendes
- språkmodeller for tekstgjenkjenning, som lastes ned når du velger et språk som trenger det

Oppslagene kan slås av i innstillingene.

## Bygge

Du trenger Android SDK og en `local.properties` med `sdk.dir`.

```
./gradlew testDebugUnitTest assembleDebug
./gradlew installDebug
```

Mer om oppbygningen står i [CLAUDE.md](CLAUDE.md).

## Lisens

Copyright © 2026 Robert Emil Berge

Oikonomos er fri programvare under GNU General Public License, versjon 3 eller senere (GPL-3.0-or-later). Se [LICENSE](LICENSE).

### Appikonet

Appikonet har en egen lisens og er ikke dekket av GPL. Det er laget av fotografiet [Metapontum Triobol 868740](https://commons.wikimedia.org/wiki/File:Metapontum_Triobol_868740.jpg) fra Wikimedia Commons, som viser baksiden av en sølvmynt fra Metapontum, preget rundt 430–400 f.Kr.

Fotografi: Classical Numismatic Group, Inc. http://www.cngcoins.com

Fotografiet og ikonet er lisensiert under [Creative Commons Navngivelse-DelPåSammeVilkår 2.5 Generisk](https://creativecommons.org/licenses/by-sa/2.5/) (CC BY-SA 2.5). Ikonet er filene `ic_launcher_foreground.png` under `app/src/main/res/mipmap-*`.
