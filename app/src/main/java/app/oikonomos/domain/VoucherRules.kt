package app.oikonomos.domain

import app.oikonomos.data.Voucher

/** Hva som kan gjøres med et bilag akkurat nå. */
data class VoucherPermissions(
    /** Alle felt og vedlegg kan endres. */
    val editAll: Boolean,
    val editCategory: Boolean,
    val editDescription: Boolean,
    /** Sletting fjerner bilaget helt, og er bare lov før det har fått nummer. */
    val delete: Boolean,
    /** Annullering lar bilaget stå med nummeret sitt, men tar det ut av summene. */
    val cancel: Boolean,
    /** Kvitteringen kan føres i regnskapet og få bilagsnummer. */
    val book: Boolean,
    /** Føringen kan angres, slik at bilaget blir en uavklart kvittering igjen. */
    val unbook: Boolean = false,
) {
    val anyEdit: Boolean get() = editAll || editCategory || editDescription

    companion object {
        val NONE = VoucherPermissions(
            editAll = false, editCategory = false, editDescription = false, delete = false, cancel = false, book = false,
        )

        /** For en kvittering som ikke er lagret ennå. */
        val NEW = NONE.copy(editAll = true, editCategory = true, editDescription = true, book = true)
    }
}

/**
 * En kvittering er uavklart til brukeren fører den i regnskapet. Først da får den bilagsnummer.
 * Etterpå kan den rettes, men ikke slettes, slik at nummerrekken aldri får hull.
 */
object VoucherRules {
    /**
     * [lastNumberInYear] er det høyeste bilagsnummeret i bilagets år. Bare det siste bilaget kan få
     * føringen angret, for da blir nummeret ledig for neste bilag og rekken får ikke hull.
     */
    fun permissions(voucher: Voucher, lastNumberInYear: Int? = null): VoucherPermissions = when {
        voucher.isCancelled -> VoucherPermissions.NONE
        voucher.isPending -> VoucherPermissions.NEW.copy(delete = true)
        // Beløp, dato og vedlegg på et bilag fra en faktura følger fakturaen, som rettes med kreditnota.
        voucher.invoiceId != null -> VoucherPermissions.NONE.copy(editCategory = true, editDescription = true)
        else -> VoucherPermissions.NONE.copy(
            editAll = true,
            editCategory = true,
            editDescription = true,
            cancel = true,
            unbook = voucher.number == lastNumberInYear,
        )
    }
}
