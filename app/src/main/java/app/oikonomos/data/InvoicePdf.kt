package app.oikonomos.data

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import app.oikonomos.domain.Formats
import app.oikonomos.domain.InvoiceCalc
import app.oikonomos.domain.InvoiceTotals
import app.oikonomos.domain.Money
import java.io.File
import java.time.format.DateTimeFormatter

/** Alt som står på en utstedt faktura eller kreditnota. */
data class InvoiceDocument(
    val seller: AppSettings,
    val customer: Customer,
    /** Fakturaen med nummeret den er tildelt. */
    val invoice: Invoice,
    val lines: List<InvoiceLine>,
    val totals: InvoiceTotals,
    /** Fakturaen som reverseres, når dokumentet er en kreditnota. */
    val credited: Invoice? = null,
)

/**
 * Tegner fakturaen som en A4-side i PDF. Innholdet følger bokføringsforskriften § 5-1: nummer og dato,
 * partene, hva som er levert, tid og sted for levering, vederlag og forfall, og eventuell MVA.
 */
object InvoicePdf {
    private const val PAGE_WIDTH = 595
    private const val PAGE_HEIGHT = 842
    private const val LEFT = 48f
    private const val RIGHT = 547f
    private const val BOTTOM = 780f
    private val GRAY = Color.rgb(90, 90, 90)
    private val RULE = Color.rgb(170, 170, 170)
    private val dateFormat = DateTimeFormatter.ofPattern("dd.MM.yyyy")

    fun write(document: InvoiceDocument, target: File) {
        val pdf = PdfDocument()
        try {
            Renderer(pdf, document).render()
            target.outputStream().use(pdf::writeTo)
        } finally {
            pdf.close()
        }
    }

    private class Renderer(private val pdf: PdfDocument, private val doc: InvoiceDocument) {
        private val isCreditNote = doc.credited != null
        private val showVat = doc.seller.vatRegistered
        private var pageNumber = 0
        private lateinit var page: PdfDocument.Page
        private lateinit var canvas: Canvas
        private var y = 0f

        fun render() {
            startPage()
            header()
            parties()
            lines()
            totals()
            if (!isCreditNote) payment()
            notes()
            finishPage()
        }

        private fun startPage() {
            pageNumber++
            page = pdf.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create())
            canvas = page.canvas
            y = 60f
        }

        private fun finishPage() {
            val footer = listOf(doc.seller.companyName, sellerOrgLine()).filter { it.isNotBlank() }.joinToString("  ·  ")
            canvas.drawText(footer, PAGE_WIDTH / 2f, 812f, paint(8f, GRAY, align = Paint.Align.CENTER))
            pdf.finishPage(page)
        }

        /** Begynner på ny side når det ikke er plass til [height] punkter til. */
        private fun ensureSpace(height: Float, onNewPage: () -> Unit = {}) {
            if (y + height > BOTTOM) {
                finishPage()
                startPage()
                onNewPage()
            }
        }

        private fun header() {
            val title = if (isCreditNote) "KREDITNOTA" else "FAKTURA"
            canvas.drawText(title, RIGHT, 68f, paint(22f, bold = true, align = Paint.Align.RIGHT))

            val seller = doc.seller
            canvas.drawText(fit(seller.companyName, paint(14f, bold = true), 320f), LEFT, y, paint(14f, bold = true))
            y += 17f
            val details = seller.address.lines() +
                listOf("${seller.postalCode} ${seller.city}".trim(), sellerOrgLine()) +
                listOfNotNull("Foretaksregisteret".takeIf { seller.limitedCompany }) +
                listOf(seller.email, seller.phone)
            for (line in details.filter { it.isNotBlank() }) {
                canvas.drawText(fit(line, paint(9f, GRAY), 320f), LEFT, y, paint(9f, GRAY))
                y += 12f
            }
            y = maxOf(y, 100f) + 30f
        }

        private fun sellerOrgLine(): String {
            val number = Formats.orgNumber(doc.seller.orgNumber)
            if (number.isBlank()) return ""
            return "Org.nr. $number" + if (doc.seller.vatRegistered) " MVA" else ""
        }

        private fun parties() {
            val top = y
            val customer = doc.customer
            canvas.drawText(if (isCreditNote) "Kreditnota til" else "Faktura til", LEFT, y, paint(8f, GRAY))
            y += 15f
            canvas.drawText(fit(customer.name, paint(11f, bold = true), 270f), LEFT, y, paint(11f, bold = true))
            y += 15f
            val details = customer.address.lines() + listOf(
                "${customer.postalCode} ${customer.city}".trim(),
                Formats.orgNumber(customer.orgNumber).let { if (it.isBlank()) "" else "Org.nr. $it" },
            )
            for (line in details.filter { it.isNotBlank() }) {
                canvas.drawText(fit(line, paint(10f), 270f), LEFT, y, paint(10f))
                y += 13f
            }
            val leftBottom = y

            val invoice = doc.invoice
            val rows = buildList {
                add((if (isCreditNote) "Kreditnotanummer" else "Fakturanummer") to invoice.number.toString())
                add((if (isCreditNote) "Dato" else "Fakturadato") to invoice.issueDate.format(dateFormat))
                doc.credited?.let { add("Gjelder faktura" to "${it.number} av ${it.issueDate.format(dateFormat)}") }
                if (!isCreditNote) {
                    add("Forfallsdato" to invoice.dueDate.format(dateFormat))
                    add("Levert" to invoice.deliveryDate.format(dateFormat))
                    if (invoice.deliveryPlace.isNotBlank()) add("Leveringssted" to invoice.deliveryPlace)
                }
                if (invoice.customerReference.isNotBlank()) add("Deres referanse" to invoice.customerReference)
            }
            y = top
            for ((label, value) in rows) {
                canvas.drawText(label, 340f, y, paint(10f, GRAY))
                canvas.drawText(fit(value, paint(10f), 110f), RIGHT, y, paint(10f, align = Paint.Align.RIGHT))
                y += 15f
            }
            y = maxOf(y, leftBottom) + 28f
        }

        private fun lines() {
            val tableHeader = {
                val label = paint(8.5f, GRAY, bold = true)
                val labelRight = paint(8.5f, GRAY, bold = true, align = Paint.Align.RIGHT)
                canvas.drawText("Beskrivelse", LEFT, y, label)
                canvas.drawText("Antall", 345f, y, labelRight)
                canvas.drawText("Pris", 425f, y, labelRight)
                if (showVat) canvas.drawText("MVA", 470f, y, labelRight)
                canvas.drawText("Beløp", RIGHT, y, labelRight)
                y += 6f
                rule()
                y += 15f
            }
            tableHeader()
            val text = paint(10f)
            val number = paint(10f, align = Paint.Align.RIGHT)
            for (line in doc.lines) {
                val description = wrap(line.description, text, 250f)
                ensureSpace(description.size * 13f + 6f, tableHeader)
                canvas.drawText(InvoiceCalc.formatQuantity(line.quantityMilli), 345f, y, number)
                canvas.drawText(Money.format(line.unitPriceOre), 425f, y, number)
                if (showVat) canvas.drawText("${line.vatRate} %", 470f, y, number)
                canvas.drawText(Money.format(InvoiceCalc.lineNet(line)), RIGHT, y, number)
                for (part in description) {
                    canvas.drawText(part, LEFT, y, text)
                    y += 13f
                }
                y += 6f
            }
            y -= 8f
            rule()
            y += 20f
        }

        private fun totals() {
            val totals = doc.totals
            ensureSpace(40f + totals.vatGroups.size * 15f)
            val label = paint(10f, GRAY, align = Paint.Align.RIGHT)
            val value = paint(10f, align = Paint.Align.RIGHT)
            if (showVat) {
                canvas.drawText("Sum uten MVA", 440f, y, label)
                canvas.drawText(Money.format(totals.netOre), RIGHT, y, value)
                y += 15f
                for (group in totals.vatGroups) {
                    canvas.drawText("MVA ${group.rate} % av ${Money.format(group.baseOre)}", 440f, y, label)
                    canvas.drawText(Money.format(group.vatOre), RIGHT, y, value)
                    y += 15f
                }
                y += 4f
            }
            canvas.drawText(
                if (isCreditNote) "Kreditert beløp" else "Å betale",
                440f, y, paint(12f, bold = true, align = Paint.Align.RIGHT),
            )
            canvas.drawText(Money.formatKr(totals.grossOre), RIGHT, y, paint(12f, bold = true, align = Paint.Align.RIGHT))
            y += 36f
        }

        private fun payment() {
            val rows = buildList {
                add("Forfallsdato" to doc.invoice.dueDate.format(dateFormat))
                if (doc.seller.bankAccount.isNotBlank()) add("Kontonummer" to Formats.bankAccount(doc.seller.bankAccount))
                add("Merk betalingen med" to "Faktura ${doc.invoice.number}")
            }
            ensureSpace(20f + rows.size * 15f)
            canvas.drawText("Betaling", LEFT, y, paint(10f, bold = true))
            y += 16f
            for ((label, value) in rows) {
                canvas.drawText(label, LEFT, y, paint(10f, GRAY))
                canvas.drawText(value, LEFT + 130f, y, paint(10f))
                y += 15f
            }
            y += 14f
        }

        private fun notes() {
            val text = paint(10f)
            for (note in listOf(doc.invoice.note, doc.seller.invoiceNote).filter { it.isNotBlank() }) {
                for (part in wrap(note, text, RIGHT - LEFT)) {
                    ensureSpace(13f)
                    canvas.drawText(part, LEFT, y, text)
                    y += 13f
                }
                y += 8f
            }
        }

        private fun rule() {
            canvas.drawLine(LEFT, y, RIGHT, y, Paint().apply { color = RULE; strokeWidth = 0.7f })
        }
    }

    private fun paint(
        size: Float,
        color: Int = Color.BLACK,
        bold: Boolean = false,
        align: Paint.Align = Paint.Align.LEFT,
    ) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        // Uten disse tilpasses bokstavbreddene til hele piksler, som gir ujevn avstand i en PDF.
        isSubpixelText = true
        isLinearText = true
        textSize = size
        this.color = color
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        textAlign = align
    }

    /** Korter ned teksten med «…» når den er bredere enn [maxWidth]. */
    private fun fit(text: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        val count = paint.breakText(text, true, maxWidth - paint.measureText("…"), null)
        return text.take(count).trimEnd() + "…"
    }

    /** Deler teksten i linjer som får plass på [maxWidth]. Linjeskift i teksten beholdes. */
    private fun wrap(text: String, paint: Paint, maxWidth: Float): List<String> {
        val result = mutableListOf<String>()
        for (paragraph in text.trim().lines()) {
            var current = ""
            for (word in paragraph.split(' ').filter { it.isNotEmpty() }) {
                var rest = word
                // Et ord som er bredere enn linjen, deles der det må.
                while (paint.measureText(rest) > maxWidth) {
                    if (current.isNotEmpty()) {
                        result += current
                        current = ""
                    }
                    val count = paint.breakText(rest, true, maxWidth, null).coerceAtLeast(1)
                    result += rest.take(count)
                    rest = rest.drop(count)
                }
                val candidate = if (current.isEmpty()) rest else "$current $rest"
                if (paint.measureText(candidate) <= maxWidth) {
                    current = candidate
                } else {
                    result += current
                    current = rest
                }
            }
            result += current
        }
        return result.ifEmpty { listOf("") }
    }
}
