package pl.barometr.corpus.internal.structure

/**
 * A rank of Polish editorial unit, from the divisions a statute is organised into down
 * to the tiret nobody numbers.
 *
 * [level] is what makes nesting a comparison rather than a table of rules: a unit
 * belongs under the nearest open unit of a lower level, which is how a point ends up
 * inside the paragraph it was written under without anything having to say so.
 *
 * `ARTYKUL` and `PARAGRAF` share a level deliberately. A statute is divided into
 * articles and a regulation into paragraphs; they are the same rank of thing, and a
 * document never uses both as its basic unit.
 *
 * [wireName] is the stored form *and* the path segment prefix — one vocabulary rather
 * than two, so `art-12a/ust-2/pkt-3` reads as the same words a lawyer would say.
 */
enum class UnitKind(val level: Int, val wireName: String) {
    /** Everything before the first numbered unit: the title, the preamble, the citation of authority. */
    PREAMBLE(0, "preambula"),
    CZESC(1, "czesc"),
    KSIEGA(2, "ksiega"),
    TYTUL(3, "tyt"),
    DZIAL(4, "dz"),
    ROZDZIAL(5, "rozdz"),
    ODDZIAL(6, "oddz"),
    ARTYKUL(7, "art"),
    PARAGRAF(7, "par"),
    USTEP(8, "ust"),
    PUNKT(9, "pkt"),
    LITERA(10, "lit"),
    TIRET(11, "tir"),
    ;

    /** True for the ranks a document is *organised* into rather than the ones it says things in. */
    val isDivision: Boolean get() = level in CZESC.level..ODDZIAL.level
}
