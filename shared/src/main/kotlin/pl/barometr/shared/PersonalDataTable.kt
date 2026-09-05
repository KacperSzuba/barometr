package pl.barometr.shared

/**
 * One table of somebody's data, as the context that owns it chooses to show it.
 *
 * Rows are strings because the reader is a person exercising a right, not a program
 * reconstructing a database: `"created_at": "2026-03-30T12:20:00Z"` is what an export is
 * for, and the alternative — every context agreeing on a serialisation format — would be
 * a shared dependency bought for nothing.
 */
data class PersonalDataTable(val name: String, val rows: List<Map<String, String?>>)
