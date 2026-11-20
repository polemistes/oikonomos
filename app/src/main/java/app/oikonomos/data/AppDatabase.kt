package app.oikonomos.data

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.Update
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

@Dao
interface VoucherDao {
    @Transaction
    @Query("SELECT * FROM voucher WHERE year = :year ORDER BY date DESC, number DESC")
    fun observeByYear(year: Int): Flow<List<VoucherWithDetails>>

    @Transaction
    @Query("SELECT * FROM voucher WHERE year = :year AND number IS NOT NULL ORDER BY number")
    suspend fun listBookedByYear(year: Int): List<VoucherWithDetails>

    @Query("SELECT DISTINCT year FROM voucher ORDER BY year DESC")
    fun observeYears(): Flow<List<Int>>

    @Transaction
    @Query("SELECT * FROM voucher WHERE id = :id")
    suspend fun get(id: Long): VoucherWithDetails?

    @Query("SELECT COALESCE(MAX(number), 0) + 1 FROM voucher WHERE year = :year")
    suspend fun nextNumber(year: Int): Int

    @Insert
    suspend fun insert(voucher: Voucher): Long

    @Update
    suspend fun update(voucher: Voucher)

    @Query("DELETE FROM voucher WHERE id = :id")
    suspend fun delete(id: Long)

    @Insert
    suspend fun insertAttachments(attachments: List<Attachment>)

    @Query("DELETE FROM attachment WHERE voucherId = :voucherId")
    suspend fun deleteAttachments(voucherId: Long)

    @Query("SELECT fileName FROM attachment")
    suspend fun allAttachmentFileNames(): List<String>

    @Query("SELECT * FROM voucher ORDER BY id")
    suspend fun allVouchers(): List<Voucher>

    @Query("SELECT * FROM attachment ORDER BY id")
    suspend fun allAttachments(): List<Attachment>

    @Insert
    suspend fun insertVouchers(vouchers: List<Voucher>)

    @Query("DELETE FROM voucher")
    suspend fun clearVouchers()

    @Transaction
    @Query("SELECT * FROM voucher WHERE invoiceId = :invoiceId")
    suspend fun forInvoice(invoiceId: Long): VoucherWithDetails?

    @Query("UPDATE voucher SET categoryId = :categoryId, description = :description, projectId = :projectId WHERE id = :id")
    suspend fun updateClassification(id: Long, categoryId: Long?, description: String, projectId: Long?)

    /** Bilag på reisen som ikke har prosjekt, får reisens prosjekt. */
    @Query("UPDATE voucher SET projectId = :projectId WHERE tripId = :tripId AND projectId IS NULL")
    suspend fun inheritProject(tripId: Long, projectId: Long)

    @Query("UPDATE voucher SET cancelledAt = :at, cancelReason = :reason WHERE id = :id AND cancelledAt IS NULL")
    suspend fun cancel(id: Long, at: Long, reason: String)

    /** Bilagene på reisen, og bilag uten reise som er datert mellom [from] og [to] og derfor kan høre til den. */
    @Transaction
    @Query(
        """
        SELECT * FROM voucher
        WHERE tripId = :tripId OR (tripId IS NULL AND cancelledAt IS NULL AND date BETWEEN :from AND :to)
        ORDER BY date, id
        """,
    )
    fun observeForTrip(tripId: Long, from: LocalDate, to: LocalDate): Flow<List<VoucherWithDetails>>

    /** Alle bilag som er lagt til en reise, til summene i reiselisten. */
    @Query("SELECT * FROM voucher WHERE tripId IS NOT NULL")
    fun observeOnTrips(): Flow<List<Voucher>>

    @Query("SELECT * FROM voucher WHERE tripId IS NOT NULL")
    suspend fun allOnTrips(): List<Voucher>
}

@Dao
interface ProjectDao {
    @Query("SELECT * FROM project ORDER BY active DESC, name COLLATE NOCASE")
    fun observeAll(): Flow<List<Project>>

    @Query("SELECT * FROM project ORDER BY id")
    suspend fun all(): List<Project>

    @Insert
    suspend fun insert(project: Project): Long

    @Insert
    suspend fun insertAll(projects: List<Project>)

    @Update
    suspend fun update(project: Project)

    @Delete
    suspend fun delete(project: Project)

    @Query("SELECT (SELECT COUNT(*) FROM voucher WHERE projectId = :id) + (SELECT COUNT(*) FROM trip WHERE projectId = :id)")
    suspend fun usageCount(id: Long): Int

    @Query("DELETE FROM project")
    suspend fun clear()
}

@Dao
interface TripDao {
    @Query("SELECT * FROM trip ORDER BY fromDate DESC, id DESC")
    fun observeAll(): Flow<List<Trip>>

    @Query("SELECT * FROM trip WHERE id = :id")
    fun observe(id: Long): Flow<Trip?>

    @Query("SELECT * FROM trip WHERE id = :id")
    suspend fun get(id: Long): Trip?

    @Query("SELECT * FROM trip ORDER BY id")
    suspend fun all(): List<Trip>

    @Insert
    suspend fun insert(trip: Trip): Long

    @Insert
    suspend fun insertAll(trips: List<Trip>)

    @Update
    suspend fun update(trip: Trip)

    @Query("DELETE FROM trip WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM trip")
    suspend fun clear()
}

@Dao
interface InvoiceDao {
    /** Kladder først, deretter utstedte med høyeste nummer øverst. */
    @Query(
        """
        SELECT i.*, c.name AS customerName,
            EXISTS(SELECT 1 FROM invoice k WHERE k.creditedInvoiceId = i.id AND k.number IS NOT NULL) AS credited
        FROM invoice i JOIN customer c ON c.id = i.customerId
        ORDER BY i.number IS NOT NULL, i.number DESC, i.id DESC
        """,
    )
    fun observeAll(): Flow<List<InvoiceListItem>>

    @Query(
        """
        SELECT i.*, c.name AS customerName,
            EXISTS(SELECT 1 FROM invoice k WHERE k.creditedInvoiceId = i.id AND k.number IS NOT NULL) AS credited
        FROM invoice i JOIN customer c ON c.id = i.customerId
        WHERE i.number IS NOT NULL AND i.issueDate BETWEEN :from AND :to
        ORDER BY i.number
        """,
    )
    suspend fun issuedBetween(from: LocalDate, to: LocalDate): List<InvoiceListItem>

    @Transaction
    @Query("SELECT * FROM invoice WHERE id = :id")
    suspend fun get(id: Long): InvoiceWithLines?

    @Query("SELECT EXISTS(SELECT 1 FROM invoice WHERE creditedInvoiceId = :id AND number IS NOT NULL)")
    suspend fun isCredited(id: Long): Boolean

    @Query("SELECT MAX(number) FROM invoice")
    suspend fun maxNumber(): Int?

    @Insert
    suspend fun insert(invoice: Invoice): Long

    @Update
    suspend fun update(invoice: Invoice)

    @Query("DELETE FROM invoice WHERE id = :id AND number IS NULL")
    suspend fun deleteDraft(id: Long)

    @Insert
    suspend fun insertLines(lines: List<InvoiceLine>)

    @Query("DELETE FROM invoice_line WHERE invoiceId = :invoiceId")
    suspend fun deleteLines(invoiceId: Long)

    @Query("SELECT * FROM invoice ORDER BY id")
    suspend fun allInvoices(): List<Invoice>

    @Query("SELECT * FROM invoice_line ORDER BY id")
    suspend fun allLines(): List<InvoiceLine>

    @Insert
    suspend fun insertInvoices(invoices: List<Invoice>)

    @Query("DELETE FROM invoice")
    suspend fun clear()
}

@Dao
interface CustomerDao {
    @Query("SELECT * FROM customer ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<Customer>>

    @Query("SELECT * FROM customer ORDER BY id")
    suspend fun all(): List<Customer>

    @Insert
    suspend fun insert(customer: Customer): Long

    @Insert
    suspend fun insertAll(customers: List<Customer>)

    @Update
    suspend fun update(customer: Customer)

    @Delete
    suspend fun delete(customer: Customer)

    @Query("SELECT COUNT(*) FROM invoice WHERE customerId = :id")
    suspend fun usageCount(id: Long): Int

    @Query("DELETE FROM customer")
    suspend fun clear()
}

@Dao
interface CategoryDao {
    @Query("SELECT * FROM category ORDER BY type, account, name")
    fun observeAll(): Flow<List<Category>>

    @Query("SELECT * FROM category ORDER BY id")
    suspend fun all(): List<Category>

    @Insert
    suspend fun insert(category: Category): Long

    @Insert
    suspend fun insertAll(categories: List<Category>)

    @Update
    suspend fun update(category: Category)

    @Delete
    suspend fun delete(category: Category)

    @Query("SELECT COUNT(*) FROM voucher WHERE categoryId = :id")
    suspend fun usageCount(id: Long): Int

    @Query("DELETE FROM category")
    suspend fun clear()
}

class Converters {
    @TypeConverter
    fun dateToString(date: LocalDate?): String? = date?.toString()

    @TypeConverter
    fun stringToDate(value: String?): LocalDate? = value?.let(LocalDate::parse)
}

/**
 * Skjemaet for hver versjon ligger i app/schemas. Når tabellene endres, økes versjonen, og
 * overgangen legges til her, slik at data på telefoner som alt har appen, blir med videre.
 */
@Database(
    entities = [
        Category::class, Voucher::class, Attachment::class, Customer::class, Invoice::class, InvoiceLine::class, Trip::class,
        Project::class,
    ],
    version = 3,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3)],
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun voucherDao(): VoucherDao
    abstract fun categoryDao(): CategoryDao
    abstract fun invoiceDao(): InvoiceDao
    abstract fun customerDao(): CustomerDao
    abstract fun tripDao(): TripDao
    abstract fun projectDao(): ProjectDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "oikonomos.db")
                .addCallback(SeedCategories)
                .build()
    }
}

/** Startkategorier for et lite foretak, med kontonumre fra standard kontoplan. Brukeren kan endre dem fritt. */
private object SeedCategories : RoomDatabase.Callback() {
    private val defaults = listOf(
        Triple("Salg og honorar", "3200", VoucherType.INCOME),
        Triple("Tilskudd og stipend", "3400", VoucherType.INCOME),
        Triple("Annen inntekt", "3900", VoucherType.INCOME),
        Triple("Materialer og varekjøp", "4000", VoucherType.EXPENSE),
        Triple("Leie av lokale", "6300", VoucherType.EXPENSE),
        Triple("Programvare og abonnement", "6420", VoucherType.EXPENSE),
        Triple("Utstyr og verktøy", "6500", VoucherType.EXPENSE),
        Triple("Regnskap og rådgivning", "6700", VoucherType.EXPENSE),
        Triple("Kontorkostnader", "6800", VoucherType.EXPENSE),
        Triple("Kurs og faglitteratur", "6860", VoucherType.EXPENSE),
        Triple("Telefon og internett", "6900", VoucherType.EXPENSE),
        Triple("Porto og frakt", "6940", VoucherType.EXPENSE),
        Triple("Reise", "7140", VoucherType.EXPENSE),
        Triple("Markedsføring", "7320", VoucherType.EXPENSE),
        Triple("Forsikring", "7500", VoucherType.EXPENSE),
        Triple("Bank og gebyrer", "7770", VoucherType.EXPENSE),
        Triple("Annen kostnad", "7790", VoucherType.EXPENSE),
    )

    override fun onCreate(db: SupportSQLiteDatabase) {
        for ((name, account, type) in defaults) {
            db.execSQL(
                "INSERT INTO category (name, account, type, active) VALUES (?, ?, ?, 1)",
                arrayOf<Any>(name, account, type.name),
            )
        }
    }
}
