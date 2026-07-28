package promptengine.domain.fragment

/** 永続化層からの [Fragment] 復元材料一式（ADR-0008）。[Fragment.restore] にのみ渡す。 */
data class FragmentMemento(
    val key: FragmentKey,
    val versions: List<FragmentVersionMemento>,
    val rowVersion: Long,
)
