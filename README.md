# Oikonomos

Android-app som holder orden på kvitteringer, fakturaer og enkelt regnskap for små norske foretak, som enkeltpersonforetak og små aksjeselskap uten ansatte.

Appen er et bilagsarkiv med oversikt og eksport. Den er ikke et fullt regnskapssystem, og den sender ingenting til myndighetene.

Appen er under utvikling og ligger ikke på Google Play.

## Hva den gjør

- **Kvitteringer:** skann, importer bilder og PDF-er, eller del dem inn fra andre apper. Appen foreslår leverandør, dato og beløp fra teksten.
- **Avgjør senere:** en kvittering er uavklart til du fører den i regnskapet. Da får den bilagsnummer, og nummerrekken får aldri hull.
- **Fakturaer:** kunderegister, faktura som PDF med fortløpende nummer, og kreditnota.
- **Reiser og prosjekter:** samle bilag under det de gjaldt, med formål og kostnader.
- **Oversikt og eksport:** resultat per år, kategori og prosjekt, og eksport til CSV med vedlegg.
- **Sikkerhetskopi:** til en fil eller automatisk til en mappe du velger.

Appen har ikke nettilgang. Alt lagres på telefonen, og tekstgjenkjenningen skjer på enheten.

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
