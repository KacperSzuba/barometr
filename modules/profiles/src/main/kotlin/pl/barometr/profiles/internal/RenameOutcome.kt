package pl.barometr.profiles.internal

/**
 * Why a rename did or did not happen.
 *
 * Three outcomes rather than a boolean, because "the name is taken" and "there is no
 * such profile" become different status codes and collapsing them would report a
 * profile deleted mid-request as a naming conflict.
 */
enum class RenameOutcome { RENAMED, NAME_TAKEN, NO_SUCH_PROFILE }
