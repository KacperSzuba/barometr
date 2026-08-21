package pl.barometr.legislative.internal

/**
 * What the Sejm's stage types and names mean in this model.
 *
 * The register types its own stages, which is why this is a translation rather than
 * an interpretation — with one exception the type does not carry: all three readings
 * arrive as `SejmReading`, and only the name says which one. Reading them as one stage
 * would flatten the middle of the path, where most of what a user waits for happens.
 */
object SejmStageVocabulary {

    /** Null when the register described something this model has no name for. */
    fun stageOf(stageType: String?, stageName: String): LegislativeStage? = when (stageType) {
        "Start" -> LegislativeStage.SUBMITTED_TO_SEJM
        "ReadingReferral" -> LegislativeStage.REFERRED_TO_FIRST_READING
        // `Reading` is the same event held in committee rather than in the chamber.
        "SejmReading", "Reading" -> readingOf(stageName)
        "CommitteeWork" -> LegislativeStage.COMMITTEE_WORK
        "SenatePosition" -> LegislativeStage.SENATE_POSITION
        "ToPresident" -> LegislativeStage.SENT_TO_PRESIDENT
        "PresidentSignature" -> LegislativeStage.PRESIDENT_SIGNED
        "PresidentToTribunal" -> LegislativeStage.PRESIDENT_TO_TRIBUNAL
        // "Uchwalono" and "Odrzucono" arrive here, dateless: a verdict on the whole
        // passage rather than a stage it went through. They are read as the draft's
        // outcome instead — see [DraftOutcome].
        "End" -> null
        else -> null
    }

    /**
     * Prefix rather than substring, so "II czytanie" is never read as the first one —
     * the difference between the two is a month of a bill's life.
     */
    private fun readingOf(stageName: String): LegislativeStage? = when {
        stageName.startsWith("III czytanie") -> LegislativeStage.THIRD_READING
        stageName.startsWith("II czytanie") -> LegislativeStage.SECOND_READING
        stageName.startsWith("I czytanie") -> LegislativeStage.FIRST_READING
        else -> null
    }

}
