package io.embodit.tracelight.domain

enum class LanguagePref(val tag: String) { SYSTEM("system"), ZH("zh"), EN("en") }

/** The drawing hand decides which side of the sheet the panel stands on (design `panels.settings`). */
enum class Hand(val tag: String) { RIGHT("right"), LEFT("left") }

enum class LineColor(val tag: String) { BLUE("blue"), RED("red") }

data class Settings(
    val language: LanguagePref = LanguagePref.SYSTEM,
    val hand: Hand = Hand.RIGHT,
    val lineColor: LineColor = LineColor.BLUE,
    /** Guide-line strength 20 - 100 % (design `dataModel.settings.opacity`). */
    val opacity: Int = 70,
)

/** Where the sheet lay last time (design `dataModel.frame`): a world anchor at its centre + the fitted pose. */
data class SavedFrame(val anchorId: String, val pose: PaperPose, val anchorPos: Vec3, val alignedAtMs: Long)
