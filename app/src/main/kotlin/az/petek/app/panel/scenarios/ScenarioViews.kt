package az.petek.app.panel.scenarios

import az.petek.dashboard.domain.DiffLineKind
import az.petek.dashboard.domain.DiffLineView
import az.petek.dashboard.domain.DiffView
import az.petek.dashboard.domain.ScenarioVersionView
import az.petek.dashboard.domain.ScenarioView
import az.petek.scenarios.domain.DiffLineType
import az.petek.scenarios.domain.ScenarioStatus
import az.petek.scenarios.domain.ScenarioTransitionException
import az.petek.scenarios.domain.ScenarioVersion
import az.petek.scenarios.domain.YamlDiff
import az.petek.dashboard.domain.ScenarioSource as ViewSource
import az.petek.dashboard.domain.ScenarioStatus as ViewStatus

/** Maps the scenario catalog's versions and diffs into the "Ssenarilər" screen's views, and its refusals into words. */
internal object ScenarioViews {
    fun version(version: ScenarioVersion): ScenarioVersionView =
        ScenarioVersionView(
            id = version.id.value,
            name = version.name,
            version = version.version,
            status = ViewStatus.valueOf(version.status.name),
            source = ViewSource.valueOf(version.source.name),
            parentId = version.parentId?.value,
            note = version.note,
            createdAt = version.createdAt,
            approvedAt = version.approvedAt,
            frozenAt = version.frozenAt,
        )

    fun scenario(version: ScenarioVersion): ScenarioView = ScenarioView(version(version), version.yaml)

    /** A hunk header line, then its lines with their numbers in the old and the new text. */
    fun diff(
        from: ScenarioVersion,
        to: ScenarioVersion,
        diff: YamlDiff,
    ): DiffView =
        DiffView(
            from = version(from),
            to = version(to),
            lines =
                diff.hunks.flatMap { hunk ->
                    listOf(DiffLineView(DiffLineKind.HUNK, hunk.header, null, null)) +
                        hunk.lines.map { line ->
                            DiffLineView(
                                kind =
                                    when (line.type) {
                                        DiffLineType.CONTEXT -> DiffLineKind.CONTEXT
                                        DiffLineType.ADDED -> DiffLineKind.ADDED
                                        DiffLineType.REMOVED -> DiffLineKind.REMOVED
                                    },
                                text = line.text,
                                oldNumber = line.oldLine,
                                newNumber = line.newLine,
                            )
                        }
                },
        )

    /** Why a review step is not possible, for the owner. */
    fun refusal(error: ScenarioTransitionException): String {
        val label = error.version.label
        return when (error.version.status) {
            ScenarioStatus.SUPERSEDED -> {
                if (error.action == "approve") {
                    "$label köhnəlib: yerinə başqa versiya təsdiqlənib. Onun mətnindən yeni layihə yaradın."
                } else {
                    "$label köhnəlib; yalnız təsdiqlənmiş versiya dondurula bilər."
                }
            }

            ScenarioStatus.DRAFT -> {
                "$label qaralamadır: əvvəlcə təsdiqləyin, sonra dondurun."
            }

            else -> {
                "$label üçün bu addım mümkün deyil."
            }
        }
    }
}
