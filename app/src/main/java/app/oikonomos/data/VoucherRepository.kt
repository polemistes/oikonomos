package app.oikonomos.data

import androidx.room.withTransaction
import app.oikonomos.domain.VoucherRules
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

data class VoucherDraft(
    /** 0 for et nytt bilag. */
    val id: Long,
    val type: VoucherType,
    val date: LocalDate,
    /** 0 når beløpet ikke er fylt ut ennå, som er lov for en uavklart kvittering. */
    val amountOre: Long,
    val vatRate: Int,
    val vatOre: Long,
    val counterparty: String,
    val description: String,
    val categoryId: Long?,
    val attachments: List<StoredFile>,
    val tripId: Long? = null,
    val projectId: Long? = null,
    val counterpartyOrgNumber: String = "",
    /** ISO-kode for et bilag i fremmed valuta, tom for kroner. Da er [foreignAmount] beløpet i valutaen, i hundredeler. */
    val currency: String = "",
    val foreignAmount: Long = 0,
    val exchangeRateSource: String = "",
)

/** Et bilag i regnskapet hører til året det er ført i, og kan ikke flyttes til et annet år. */
class YearChangeException : Exception()

class VoucherRepository(private val db: AppDatabase, private val store: AttachmentStore) {
    private val dao = db.voucherDao()

    fun observeYear(year: Int): Flow<List<VoucherWithDetails>> = dao.observeByYear(year)

    fun observeYears(): Flow<List<Int>> = dao.observeYears()

    suspend fun get(id: Long): VoucherWithDetails? = dao.get(id)

    /** Bilagene som er ført i regnskapet for året, i nummerrekkefølge. */
    suspend fun listBooked(year: Int): List<VoucherWithDetails> = dao.listBookedByYear(year)

    /**
     * Lagrer en kvittering. Med [book] føres den i regnskapet og får neste bilagsnummer. Uten blir
     * den stående som uavklart. Et bilag som alt er ført, beholder nummeret sitt.
     */
    suspend fun save(draft: VoucherDraft, book: Boolean): Long {
        var removedFiles = emptyList<String>()
        val id = db.withTransaction {
            val year = draft.date.year
            val existing = if (draft.id != 0L) dao.get(draft.id) else null
            val old = existing?.voucher
            if (old != null) check(VoucherRules.permissions(old).editAll) { "Bilaget kan ikke endres" }
            if (old?.number != null && old.year != year) throw YearChangeException()

            val number = old?.number ?: if (book) dao.nextNumber(year) else null
            val voucher = Voucher(
                id = old?.id ?: 0,
                year = year,
                number = number,
                type = draft.type,
                date = draft.date,
                amountOre = draft.amountOre,
                vatRate = draft.vatRate,
                vatOre = draft.vatOre,
                counterparty = draft.counterparty.trim(),
                description = draft.description.trim(),
                categoryId = draft.categoryId,
                createdAt = old?.createdAt ?: System.currentTimeMillis(),
                tripId = draft.tripId,
                projectId = draft.projectId,
                counterpartyOrgNumber = draft.counterpartyOrgNumber,
                currency = draft.currency,
                foreignAmount = if (draft.currency.isEmpty()) 0 else draft.foreignAmount,
                exchangeRateSource = if (draft.currency.isEmpty()) "" else draft.exchangeRateSource,
            )
            val id = if (old == null) {
                dao.insert(voucher)
            } else {
                dao.update(voucher)
                removedFiles = existing.attachments.map { it.fileName } - draft.attachments.map { it.fileName }.toSet()
                old.id
            }
            dao.deleteAttachments(id)
            dao.insertAttachments(
                draft.attachments.mapIndexed { index, file ->
                    Attachment(voucherId = id, fileName = file.fileName, mimeType = file.mimeType, position = index)
                },
            )
            id
        }
        removedFiles.forEach(store::delete)
        return id
    }

    /** For bilag der bare kategori, prosjekt og beskrivelse kan endres, som bilag laget fra en faktura. */
    suspend fun updateClassification(id: Long, categoryId: Long?, description: String, projectId: Long?) {
        db.withTransaction {
            val voucher = dao.get(id)?.voucher ?: return@withTransaction
            val permissions = VoucherRules.permissions(voucher)
            check(permissions.editDescription) { "Bilaget kan ikke endres" }
            dao.updateClassification(
                id = id,
                categoryId = if (permissions.editCategory) categoryId else voucher.categoryId,
                description = description.trim(),
                projectId = if (permissions.editCategory) projectId else voucher.projectId,
            )
        }
    }

    /** Annullerer et bilag i regnskapet. Bilaget og vedleggene blir stående, slik at rettelsen kan spores. */
    suspend fun cancel(id: Long, reason: String) {
        db.withTransaction {
            val voucher = dao.get(id)?.voucher ?: return@withTransaction
            check(VoucherRules.permissions(voucher).cancel) { "Bilaget kan ikke annulleres" }
            dao.cancel(id, System.currentTimeMillis(), reason.trim())
        }
    }

    suspend fun recentCurrencies(): List<String> = dao.recentCurrencies(RECENT_CURRENCIES)

    /** Det høyeste bilagsnummeret i året, til reglene for hva som kan gjøres med et bilag. */
    suspend fun lastNumber(year: Int): Int? = dao.lastNumber(year)

    /**
     * Angrer føringen av årets siste bilag, så det blir en uavklart kvittering igjen. Nummeret blir
     * ledig for neste bilag. Gir false når bilaget ikke lenger er det siste.
     */
    suspend fun unbook(id: Long): Boolean = db.withTransaction {
        val voucher = dao.get(id)?.voucher ?: return@withTransaction false
        val allowed = VoucherRules.permissions(voucher, dao.lastNumber(voucher.year)).unbook
        if (allowed) dao.clearNumber(id)
        allowed
    }

    /** Sletter en uavklart kvittering og vedleggene dens. */
    suspend fun delete(id: Long) {
        val existing = dao.get(id) ?: return
        check(VoucherRules.permissions(existing.voucher).delete) { "Bilag i regnskapet kan ikke slettes" }
        dao.delete(id)
        existing.attachments.forEach { store.delete(it.fileName) }
    }

    fun observeTrips(): Flow<List<Trip>> = db.tripDao().observeAll()

    fun observeTrip(id: Long): Flow<Trip?> = db.tripDao().observe(id)

    /**
     * Bilagene på reisen, og bilag uten reise som kan høre til den. Mye betales før avreise og noe
     * etterpå, så søket går fra et år før reisen til tre måneder etter.
     */
    fun observeTripVouchers(trip: Trip): Flow<List<VoucherWithDetails>> =
        dao.observeForTrip(trip.id, trip.fromDate.minusYears(1), trip.toDate.plusMonths(3))

    fun observeVouchersOnTrips(): Flow<List<Voucher>> = dao.observeOnTrips()

    /** Lagrer reisen. Har den et prosjekt, får bilagene på reisen som mangler prosjekt, det samme. */
    suspend fun saveTrip(trip: Trip): Long = db.withTransaction {
        val id = if (trip.id == 0L) {
            db.tripDao().insert(trip)
        } else {
            db.tripDao().update(trip)
            trip.id
        }
        trip.projectId?.let { dao.inheritProject(id, it) }
        id
    }

    fun observeProjects(): Flow<List<Project>> = db.projectDao().observeAll()

    suspend fun saveProject(project: Project) {
        if (project.id == 0L) db.projectDao().insert(project) else db.projectDao().update(project)
    }

    /** Gir antall bilag og reiser som bruker prosjektet. Prosjektet slettes bare når tallet er 0. */
    suspend fun deleteProject(project: Project): Int = db.withTransaction {
        val used = db.projectDao().usageCount(project.id)
        if (used == 0) db.projectDao().delete(project)
        used
    }

    /** Sletter reisen. Bilagene blir stående, uten reise. */
    suspend fun deleteTrip(id: Long) = db.tripDao().delete(id)

    /**
     * Legger et bilag til en reise. En uavklart kvittering med beløp føres samtidig i regnskapet, for
     * det som hører til en jobbreise, er en kostnad i virksomheten. Mangler den kategori, får den reisekategorien.
     */
    suspend fun addToTrip(voucherId: Long, tripId: Long) {
        db.withTransaction {
            val old = dao.get(voucherId)?.voucher ?: return@withTransaction
            if (old.isCancelled) return@withTransaction
            val book = old.isPending && old.amountOre > 0
            val trip = db.tripDao().get(tripId)
            dao.update(
                old.copy(
                    tripId = tripId,
                    projectId = old.projectId ?: trip?.projectId,
                    number = if (book) dao.nextNumber(old.year) else old.number,
                    categoryId = old.categoryId ?: travelCategory().takeIf { old.type == VoucherType.EXPENSE },
                ),
            )
        }
    }

    suspend fun removeFromTrip(voucherId: Long) {
        db.withTransaction {
            val old = dao.get(voucherId)?.voucher ?: return@withTransaction
            dao.update(old.copy(tripId = null))
        }
    }

    /** Kategorien for reisekostnader: konto 7140 i standard kontoplan, eller en kategori med «reise» i navnet. */
    private suspend fun travelCategory(): Long? {
        val expenses = db.categoryDao().all().filter { it.type == VoucherType.EXPENSE && it.active }
        return (expenses.firstOrNull { it.account == "7140" } ?: expenses.firstOrNull { "reise" in it.name.lowercase() })?.id
    }

    suspend fun cleanUpOrphanFiles() {
        store.deleteOrphans(dao.allAttachmentFileNames().toSet())
    }

    private companion object {
        const val RECENT_CURRENCIES = 4
    }
}
