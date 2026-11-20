# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Oikonomos is an Android app for keeping receipts, invoices and simple accounts for a small Norwegian business (sole proprietorship or small limited company without employees). It is a voucher archive with overviews and exports, not a full accounting system: it does not submit anything to the authorities.

The app is in Norwegian. All user-facing text lives in `app/src/main/res/values/strings.xml`. Code identifiers are English; code comments are Norwegian.

## Commands

Gradle must run on Android Studio's bundled JDK when the system Java is newer than Gradle supports (set `JAVA_HOME`, for example `/opt/android-studio/jbr`). `local.properties` with `sdk.dir` is required and is not checked in.

```
JAVA_HOME=/opt/android-studio/jbr ./gradlew testDebugUnitTest assembleDebug   # unit tests and debug APK
JAVA_HOME=/opt/android-studio/jbr ./gradlew testDebugUnitTest --tests 'app.oikonomos.domain.MoneyTest'
JAVA_HOME=/opt/android-studio/jbr ./gradlew testDebugUnitTest --tests 'app.oikonomos.domain.MoneyTest.vatPartOfGrossAmount'
JAVA_HOME=/opt/android-studio/jbr ./gradlew installDebug                      # to a connected device
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. With `-q`, a successful build prints nothing; check the test result XML in `app/build/test-results/testDebugUnitTest/` for counts.

There are only JVM unit tests (`app/src/test`), and they cover `domain/` only. Everything that touches Android is verified by running the app.

### Running it in an emulator without a window

```
emulator -avd <name> -read-only -no-window -no-snapshot -no-audio -gpu swiftshader_indirect -skin 1080x2340 -port 5584
adb -s emulator-5584 shell wm density 420
```

`-read-only` discards everything on exit, so an existing AVD is left untouched. Drive the UI with `adb shell input` and read the screen with `adb exec-out uiautomator dump /dev/tty` or `screencap`.

Limits found so far:
- Google's document scanner module cannot be downloaded in the emulator, so scanning falls back to the camera app there.
- Sharing a file into the app with `am start -a android.intent.action.SEND` fails on URI permission. Test imports through the file picker, with files pushed to `/sdcard/Download`.
- `adb push` right after boot can fail with "Operation not permitted". Wait some seconds and retry.
- Give every `adb` call a timeout. The emulator has hung mid-test.

Generated invoice PDFs can be checked by copying them out with `adb exec-out run-as app.oikonomos cat files/attachments/<name>.pdf` and rendering with `pdftoppm`.

## Architecture

Single module `app`, package `app.oikonomos`. Kotlin, Jetpack Compose (Material 3), Room with KSP, DataStore, WorkManager, ML Kit. Versions are in `gradle/libs.versions.toml`. The Android Gradle plugin's built-in Kotlin support is used, so there is no `kotlin-android` plugin.

- `domain/` is plain Kotlin with no Android imports: money, receipt parsing, invoice calculation, voucher rules, overview sums, CSV, backup snapshot naming. Rules and calculations belong here, with tests.
- `data/` holds Room entities and DAOs (`Entities.kt`, `AppDatabase.kt`), the repositories, backup, bulk import and the invoice PDF.
- `ocr/` wraps ML Kit text recognition and the document scanner.
- `ui/` has one package per area. Each screen has a ViewModel in the same or a neighbouring file.

Dependencies are wired by hand in `AppContainer` (`OikonomosApp.kt`). ViewModels are created in `ui/Navigation.kt`, which also defines the routes and the bottom tabs. `MainActivity` shows `SetupScreen` until the first-run questions are answered.

### Concepts that span several files

**Money** is whole øre in a `Long`. A voucher's `amountOre` is the gross amount including VAT; sums in results use `netOre`. Parsing and formatting go through `domain/Money`.

**Voucher lifecycle.** A voucher (`bilag`) starts as an undecided receipt with `number == null`. It is not counted anywhere and can be edited and deleted. When it is booked it gets the next number for its year. From then on it can be corrected but never deleted, only cancelled (`cancelledAt`), so the number series has no gaps. A booked voucher cannot move to another year. `domain/VoucherRules.permissions` decides what is allowed, `VoucherRepository` enforces it, and the form reads the same permissions. Use `Voucher.counts` when summing.

**Invoices.** A draft becomes an issued invoice in one transaction in `InvoiceRepository.issue`: next invoice number, PDF from `InvoicePdf`, and an income voucher with the PDF as its attachment. An issued invoice cannot be changed; `credit` issues a credit note that reverses it in full. A voucher that comes from an invoice only allows category, project and description to change. Invoices and credit notes share one number series.

**Attachments** are files in `filesDir/attachments` with random names, referenced by `attachment` rows. A file is never modified after it is stored. Backup relies on this.

**Trips and projects** group vouchers. Adding an undecided receipt with an amount to a trip also books it. Vouchers inherit the trip's project when they have none.

**Backup** has two forms, both in `data/Backup.kt`. The zip is made on request. The folder copy is kept up to date by `AutoBackup` and `BackupWorker`: a Room invalidation tracker sets a pending flag, and `MainActivity.onStop` requests the work. In the folder, attachments are append-only under `vedlegg/`, and each copy of the data is a small timestamped JSON file, pruned by `domain/BackupSnapshots`. Restore replaces everything and inserts in foreign-key order.

`BackupData` serialises the Room entities directly. A new entity or field needs a default value, so older backups still load, and must be added to `snapshot`, `commit` and the invalidation tracker's table list.

**Settings** are in DataStore. `AppSettings` describes the business and is part of the backup. `BackupState` belongs to the device and is not.

**Database changes.** Schemas are exported to `app/schemas`. The app is installed on a real phone, so do not edit an existing schema file: raise the version and add an `AutoMigration` (or a written migration) in `AppDatabase`.

**Receipt suggestions.** `ReceiptParser` works on OCR lines with positions, because receipts are laid out in columns. OCR output has quirks such as a space after a decimal comma. When a real receipt is read wrongly, add its lines as a test case before changing the patterns.

### UI conventions

- Text field state is Compose `mutableStateOf` inside the ViewModel, not a `StateFlow`. A flow lags behind typing.
- A form with more than a couple of fields uses `FormDialog` (`ui/Common.kt`), a full-screen dialog with Save in the top bar. Buttons at the bottom of an ordinary dialog end up behind the keyboard.
- One-off messages go through a `Channel` of `UiMessage` and are shown as snackbars.

## Decisions to respect

- The app has no internet permission and never sends anything. Invoices are shared as PDF through the system share sheet.
- E-invoicing (EHF/Peppol) is deliberately out of scope. The app shows a notice on invoices to business customers instead.
- Defaults must not be tailored to one kind of business. Where businesses differ, such as VAT registration, the first-run screen asks, and the answer can be changed in settings.
- Invoice content follows bokføringsforskriften § 5-1. Check the current rules before changing what an invoice contains.
