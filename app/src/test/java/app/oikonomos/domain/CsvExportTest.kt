package app.oikonomos.domain

import app.oikonomos.data.VoucherType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class CsvExportTest {
    private fun row(
        label: String,
        type: VoucherType,
        amountOre: Long,
        counterparty: String = "Butikk",
        category: String = "Materialer og varekjøp",
        account: String = "4000",
    ) = ExportRow(
        label = label,
        date = LocalDate.of(2026, 3, 4),
        type = type,
        counterparty = counterparty,
        description = "",
        category = category,
        account = account,
        amountOre = amountOre,
        vatRate = 25,
        vatOre = Money.vatPart(amountOre, 25),
        attachmentNames = listOf("$label-1.jpg"),
    )

    @Test
    fun writesOneLinePerVoucherWithoutVatColumnsWhenNotRegistered() {
        val csv = CsvExport.vouchers(listOf(row("2026-0001", VoucherType.EXPENSE, 123450)), vatRegistered = false)
        assertEquals(
            "Bilagsnr;Dato;Type;Motpart;Org.nr;Beskrivelse;Kategori;Konto;Beløp;Prosjekt;Reise;Vedlegg;Status\r\n" +
                "2026-0001;2026-03-04;Utgift;Butikk;;;Materialer og varekjøp;4000;1234,50;;;2026-0001-1.jpg;\r\n",
            csv,
        )
    }

    @Test
    fun includesVatColumnsWhenRegistered() {
        val csv = CsvExport.vouchers(listOf(row("2026-0001", VoucherType.EXPENSE, 10000)), vatRegistered = true)
        val lines = csv.trim().split("\r\n")
        assertEquals("Bilagsnr;Dato;Type;Motpart;Org.nr;Beskrivelse;Kategori;Konto;Beløp;MVA-sats;MVA;Prosjekt;Reise;Vedlegg;Status", lines[0])
        assertEquals("2026-0001;2026-03-04;Utgift;Butikk;;;Materialer og varekjøp;4000;100,00;25;20,00;;;2026-0001-1.jpg;", lines[1])
    }

    @Test
    fun listsOrgNumberAndAddsCurrencyColumnsWhenAVoucherIsInForeignCurrency() {
        val rows = listOf(
            row("2026-0001", VoucherType.EXPENSE, 10000).copy(orgNumber = "974760673"),
            // 27,50 euro etter Norges Banks kurs, og 3 480 yen der brukeren skrev inn det banken trakk.
            row("2026-0002", VoucherType.EXPENSE, 29066)
                .copy(currency = "EUR", foreignAmount = 2750, exchangeRateSource = "Norges Bank 13.11.2026"),
            row("2026-0003", VoucherType.EXPENSE, 21200).copy(currency = "JPY", foreignAmount = 348000),
        )
        val lines = CsvExport.vouchers(rows, vatRegistered = false).trim().split("\r\n")
        assertEquals(
            "Bilagsnr;Dato;Type;Motpart;Org.nr;Beskrivelse;Kategori;Konto;Beløp;Valuta;Beløp i valuta;Kurs;Kurskilde;Prosjekt;Reise;Vedlegg;Status",
            lines[0],
        )
        assertEquals("2026-0001;2026-03-04;Utgift;Butikk;974760673;;Materialer og varekjøp;4000;100,00;;;;;;;2026-0001-1.jpg;", lines[1])
        assertEquals(
            "2026-0002;2026-03-04;Utgift;Butikk;;;Materialer og varekjøp;4000;290,66;EUR;27,50;10,569455;Norges Bank 13.11.2026;;;2026-0002-1.jpg;",
            lines[2],
        )
        assertEquals(
            "2026-0003;2026-03-04;Utgift;Butikk;;;Materialer og varekjøp;4000;212,00;JPY;3480,00;0,060920;Skrevet inn;;;2026-0003-1.jpg;",
            lines[3],
        )
    }

    @Test
    fun quotesSeparatorsAndNeutralisesFormulas() {
        val csv = CsvExport.vouchers(
            listOf(row("2026-0001", VoucherType.EXPENSE, 100, counterparty = "Hansen; \"Sønn\""), row("2026-0002", VoucherType.EXPENSE, 100, counterparty = "=SUM(A1)")),
            vatRegistered = false,
        )
        val lines = csv.trim().split("\r\n")
        assertTrue(lines[1].startsWith("2026-0001;2026-03-04;Utgift;\"Hansen; \"\"Sønn\"\"\";;"))
        assertEquals("'=SUM(A1)", lines[2].split(";")[3])
    }

    @Test
    fun cancelledVouchersAreListedWithReasonButLeftOutOfTheSummary() {
        val rows = listOf(
            row("2026-0001", VoucherType.EXPENSE, 10000).copy(trip = "Berlin, utstilling", project = "Utstilling 2027"),
            row("2026-0002", VoucherType.EXPENSE, 99900).copy(cancelled = true, cancelReason = "Feil beløp"),
        )
        val listed = CsvExport.vouchers(rows, vatRegistered = false).trim().split("\r\n")
        assertTrue(listed[1].endsWith(";Utstilling 2027;Berlin, utstilling;2026-0001-1.jpg;"))
        assertTrue(listed[2].endsWith(";2026-0002-1.jpg;Annullert: Feil beløp"))
        val summary = CsvExport.summary(rows).trim().split("\r\n")
        assertEquals("Utgift;4000;Materialer og varekjøp;1;80,00", summary[1])
        assertEquals("Sum utgifter;;;;80,00", summary[4])
    }

    @Test
    fun listsInvoicesAndCreditNotesWithStatus() {
        val date = LocalDate.of(2026, 3, 4)
        val csv = CsvExport.invoices(
            listOf(
                InvoiceExportRow(12, false, date, date.plusDays(14), "Galleri Nord AS", 250000, 0, date.plusDays(10), "Betalt"),
                InvoiceExportRow(13, true, date.plusDays(20), date.plusDays(20), "Galleri Nord AS", -250000, 0, null, "Kreditnota"),
            ),
        )
        assertEquals(
            listOf(
                "Nummer;Type;Dato;Forfall;Kunde;Beløp;MVA;Betalt;Status",
                "12;Faktura;2026-03-04;2026-03-18;Galleri Nord AS;2500,00;0,00;2026-03-14;Betalt",
                "13;Kreditnota;2026-03-24;;Galleri Nord AS;-2500,00;0,00;;Kreditnota",
            ),
            csv.trim().split("\r\n"),
        )
    }

    @Test
    fun listsTripsWithPurposeAndCosts() {
        val csv = CsvExport.trips(
            listOf(
                TripExportRow(
                    name = "Berlin, utstilling",
                    fromDate = LocalDate.of(2026, 11, 1),
                    toDate = LocalDate.of(2026, 11, 10),
                    project = "Utstilling 2027",
                    note = "Møte med galleriet; befaring av lokalet.\nSå tre utstillinger.",
                    voucherCount = 4,
                    costOre = 1234500,
                ),
            ),
        )
        assertEquals(
            "Reise;Fra;Til;Prosjekt;Formål;Antall bilag;Kostnader uten MVA\r\n" +
                "Berlin, utstilling;2026-11-01;2026-11-10;Utstilling 2027;" +
                "\"Møte med galleriet; befaring av lokalet.\nSå tre utstillinger.\";4;12345,00\r\n",
            csv,
        )
    }

    @Test
    fun summarisesPerCategoryAndCalculatesResult() {
        val csv = CsvExport.summary(
            listOf(
                row("2026-0001", VoucherType.EXPENSE, 10000),
                row("2026-0002", VoucherType.EXPENSE, 5050),
                row("2026-0003", VoucherType.INCOME, 50000, category = "Salg og honorar", account = "3200"),
            ),
        )
        assertEquals(
            listOf(
                // Bilagene er ført med 25 % MVA, og sammendraget viser beløpene uten.
                "Type;Konto;Kategori;Antall bilag;Sum uten MVA",
                "Utgift;4000;Materialer og varekjøp;2;120,40",
                "Inntekt;3200;Salg og honorar;1;400,00",
                ";;;;",
                "Sum inntekter;;;;400,00",
                "Sum utgifter;;;;120,40",
                "Resultat;;;;279,60",
            ),
            csv.trim().split("\r\n"),
        )
    }
}
