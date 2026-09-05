package pl.barometr.shared

/**
 * Everything one context holds about one person.
 *
 * [category] names the context in the export somebody downloads — `alerts`, `profiles` —
 * because "which of your systems is this from" is the first question anybody reading one
 * asks.
 */
data class PersonalDataExtract(val category: String, val tables: List<PersonalDataTable>)
