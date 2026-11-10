package io.merkio.sqlportal.domain.scripts

import io.merkio.sqlportal.domain.query.QueryResult

enum class ChartKind(
    val label: String,
) {
    LINE("line"),
    BAR("bar"),
    PIE("pie"),
    DOUGHNUT("doughnut"),
    SCATTER("scatter"),
}

/** Declarative chart description from the configuration. */
data class ChartDefinition(
    val title: String,
    val kind: ChartKind,
    /** Column used for the labels (or X axis for scatter charts). */
    val x: String,
    /** Columns plotted as series. */
    val y: List<String>,
) {
    /** Extracts the series from a result set. A failure carries a user-facing message. */
    fun build(result: QueryResult): ChartOutcome {
        if (y.isEmpty()) return ChartOutcome.Failed("chart has no 'y' columns")
        if (result.rows.isEmpty()) {
            // Nothing to draw (and an empty result carries no column names).
            val empty = y.map { Dataset(it, DatasetData.Values(emptyList())) }
            return ChartOutcome.Drawn(ChartData(title, kind, emptyList(), empty))
        }
        val xIndex = result.columnIndex(x) ?: return missing(x)
        val yIndex = y.map { column -> column to (result.columnIndex(column) ?: return missing(column)) }
        val datasets =
            yIndex.map { (label, yi) ->
                val data =
                    if (kind == ChartKind.SCATTER) {
                        DatasetData.Points(
                            result.rows.mapNotNull { row ->
                                val px = row[xIndex].asDouble()
                                val py = row[yi].asDouble()
                                if (px != null && py != null) px to py else null
                            },
                        )
                    } else {
                        DatasetData.Values(result.rows.map { it[yi].asDouble() })
                    }
                Dataset(label, data)
            }
        val labels = if (kind == ChartKind.SCATTER) emptyList() else result.rows.map { it[xIndex].display() }
        return ChartOutcome.Drawn(ChartData(title, kind, labels, datasets))
    }

    private fun missing(column: String) = ChartOutcome.Failed("column '$column' not found in the result")
}

sealed interface DatasetData {
    data class Values(
        val values: List<Double?>,
    ) : DatasetData

    data class Points(
        val points: List<Pair<Double, Double>>,
    ) : DatasetData
}

data class Dataset(
    val label: String,
    val data: DatasetData,
)

/** Chart data prepared from a query result. */
data class ChartData(
    val title: String,
    val kind: ChartKind,
    val labels: List<String>,
    val datasets: List<Dataset>,
)

/** A chart ready to draw, or why it cannot be drawn. */
sealed interface ChartOutcome {
    data class Drawn(
        val data: ChartData,
    ) : ChartOutcome

    data class Failed(
        val message: String,
    ) : ChartOutcome
}
