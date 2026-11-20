package app.oikonomos.data

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.LocalDate

enum class VoucherType { EXPENSE, INCOME }

@Serializable
@Entity(tableName = "category")
data class Category(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** Kontonummer i standard kontoplan (NS 4102), brukt i eksporten. */
    val account: String = "",
    val type: VoucherType,
    val active: Boolean = true,
)

/**
 * Et bilag: en utgift eller inntekt med dokumentasjon. Det er en uavklart kvittering til det føres
 * i regnskapet. Da får det bilagsnummer, og etterpå kan det annulleres, men ikke slettes.
 */
@Serializable
@Entity(
    tableName = "voucher",
    foreignKeys = [
        ForeignKey(
            entity = Category::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.SET_NULL,
        ),
        ForeignKey(
            entity = Invoice::class,
            parentColumns = ["id"],
            childColumns = ["invoiceId"],
            onDelete = ForeignKey.SET_NULL,
        ),
        ForeignKey(
            entity = Trip::class,
            parentColumns = ["id"],
            childColumns = ["tripId"],
            onDelete = ForeignKey.SET_NULL,
        ),
        ForeignKey(
            entity = Project::class,
            parentColumns = ["id"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index("categoryId"), Index("invoiceId"), Index("tripId"), Index("projectId"),
        Index(value = ["year", "number"], unique = true),
    ],
)
data class Voucher(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Regnskapsåret, altså året i [date]. Bilagsnummeret løper fra 1 innenfor hvert år. */
    val year: Int,
    /** Bilagsnummeret, null for en uavklart kvittering som ikke er ført i regnskapet. */
    val number: Int?,
    val type: VoucherType,
    @Serializable(with = LocalDateSerializer::class) val date: LocalDate,
    /** Totalbeløp i øre, inkludert eventuell MVA. Negativt for en kreditnota, 0 når det ikke er fylt ut. */
    val amountOre: Long,
    val vatRate: Int = 0,
    val vatOre: Long = 0,
    val counterparty: String = "",
    val description: String = "",
    val categoryId: Long? = null,
    val createdAt: Long,
    /** Satt når bilaget er laget ved utstedelse av en faktura. Beløp og dato er da låst til fakturaen. */
    val invoiceId: Long? = null,
    /** Tidspunktet bilaget ble annullert. Et annullert bilag blir stående, men teller ikke med i summene. */
    val cancelledAt: Long? = null,
    val cancelReason: String = "",
    /** Reisen bilaget hører til, om noen. */
    val tripId: Long? = null,
    /** Prosjektet bilaget hører til, om noe. */
    val projectId: Long? = null,
) {
    /** «2026-0007», tomt for en uavklart kvittering. */
    val label: String get() = number?.let { voucherLabel(year, it) }.orEmpty()
    val isPending: Boolean get() = number == null
    val isCancelled: Boolean get() = cancelledAt != null

    /** Bare bilag som er ført og ikke annullert, teller med i summer og oversikter. */
    val counts: Boolean get() = !isPending && !isCancelled

    /** Beløpet uten MVA, som er det som teller som inntekt eller kostnad i resultatet. */
    val netOre: Long get() = amountOre - vatOre
}

fun voucherLabel(year: Int, number: Int): String = "$year-${number.toString().padStart(4, '0')}"

@Serializable
@Entity(
    tableName = "attachment",
    foreignKeys = [
        ForeignKey(
            entity = Voucher::class,
            parentColumns = ["id"],
            childColumns = ["voucherId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("voucherId")],
)
data class Attachment(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val voucherId: Long,
    /** Filnavn i appens vedleggsmappe. */
    val fileName: String,
    val mimeType: String,
    val position: Int,
)

data class VoucherWithDetails(
    @Embedded val voucher: Voucher,
    @Relation(parentColumn = "categoryId", entityColumn = "id") val category: Category?,
    @Relation(parentColumn = "id", entityColumn = "voucherId") val attachments: List<Attachment>,
    @Relation(parentColumn = "tripId", entityColumn = "id") val trip: Trip? = null,
    @Relation(parentColumn = "projectId", entityColumn = "id") val project: Project? = null,
) {
    val sortedAttachments: List<Attachment> get() = attachments.sortedBy { it.position }
}

/** En reise som bilag kan samles under, for eksempel en utstilling eller et arbeidsopphold. */
@Serializable
@Entity(
    tableName = "trip",
    foreignKeys = [
        ForeignKey(
            entity = Project::class,
            parentColumns = ["id"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("projectId")],
)
data class Trip(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    @Serializable(with = LocalDateSerializer::class) val fromDate: LocalDate,
    @Serializable(with = LocalDateSerializer::class) val toDate: LocalDate,
    /** Formålet med reisen og hva som ble gjort, som dokumentasjon på at den hørte til virksomheten. */
    val note: String = "",
    val projectId: Long? = null,
)

/**
 * Et prosjekt som bilag og reiser kan samles under, for eksempel en utstilling eller et oppdrag.
 * Det viser hva en kostnad gjaldt, og gir inntekter og kostnader per prosjekt.
 */
@Serializable
@Entity(tableName = "project")
data class Project(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val note: String = "",
    val active: Boolean = true,
)

enum class CustomerType { BUSINESS, PRIVATE }

@Serializable
@Entity(tableName = "customer")
data class Customer(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: CustomerType = CustomerType.BUSINESS,
    val orgNumber: String = "",
    val address: String = "",
    val postalCode: String = "",
    val city: String = "",
    val email: String = "",
    /** Kundens faste referanse («Deres ref.»), foreslått på nye fakturaer. */
    val reference: String = "",
)

/**
 * En utgående faktura eller kreditnota. Den er en kladd til den utstedes. Da får den nummer, og
 * innholdet kan ikke lenger endres. Feil rettes ved å kreditere den og utstede en ny.
 */
@Serializable
@Entity(
    tableName = "invoice",
    foreignKeys = [
        ForeignKey(
            entity = Customer::class,
            parentColumns = ["id"],
            childColumns = ["customerId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index("customerId"), Index("creditedInvoiceId"), Index(value = ["number"], unique = true)],
)
data class Invoice(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Fakturanummeret, null for en kladd. Fakturaer og kreditnotaer deler én nummerserie uten hull. */
    val number: Int? = null,
    val customerId: Long,
    @Serializable(with = LocalDateSerializer::class) val issueDate: LocalDate,
    @Serializable(with = LocalDateSerializer::class) val dueDate: LocalDate,
    @Serializable(with = LocalDateSerializer::class) val deliveryDate: LocalDate,
    val deliveryPlace: String = "",
    val customerReference: String = "",
    val note: String = "",
    /** Satt på en kreditnota: fakturaen den reverserer. */
    val creditedInvoiceId: Long? = null,
    /** Totalbeløp i øre inkludert MVA. Negativt på en kreditnota. */
    val totalOre: Long = 0,
    val vatOre: Long = 0,
    @Serializable(with = LocalDateSerializer::class) val paidDate: LocalDate? = null,
    val createdAt: Long,
) {
    val isDraft: Boolean get() = number == null
    val isCreditNote: Boolean get() = creditedInvoiceId != null
}

@Serializable
@Entity(
    tableName = "invoice_line",
    foreignKeys = [
        ForeignKey(
            entity = Invoice::class,
            parentColumns = ["id"],
            childColumns = ["invoiceId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("invoiceId")],
)
data class InvoiceLine(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val invoiceId: Long = 0,
    val position: Int = 0,
    val description: String,
    /** Antall i tusendeler, så 1,5 timer er 1500. Negativt på en kreditnota. */
    val quantityMilli: Long,
    /** Pris per enhet i øre, uten MVA. */
    val unitPriceOre: Long,
    val vatRate: Int = 0,
)

data class InvoiceWithLines(
    @Embedded val invoice: Invoice,
    @Relation(parentColumn = "customerId", entityColumn = "id") val customer: Customer,
    @Relation(parentColumn = "id", entityColumn = "invoiceId") val lines: List<InvoiceLine>,
) {
    val sortedLines: List<InvoiceLine> get() = lines.sortedBy { it.position }
}

/** En rad i fakturalisten. */
data class InvoiceListItem(
    @Embedded val invoice: Invoice,
    val customerName: String,
    /** Sant når det er utstedt en kreditnota for fakturaen. */
    val credited: Boolean,
)

object LocalDateSerializer : KSerializer<LocalDate> {
    override val descriptor = PrimitiveSerialDescriptor("LocalDate", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: LocalDate) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): LocalDate = LocalDate.parse(decoder.decodeString())
}
