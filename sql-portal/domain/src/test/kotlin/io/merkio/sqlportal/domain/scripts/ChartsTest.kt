package io.merkio.sqlportal.domain.scripts

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.merkio.sqlportal.domain.common.Value
import io.merkio.sqlportal.domain.query.QueryResult

class ChartsTest :
    FunSpec({
        val result =
            QueryResult(
                listOf("region", "total", "orders"),
                listOf(
                    listOf(Value.Text("EU"), Value.Float(10.5), Value.Int(3)),
                    listOf(Value.Text("US"), Value.Null, Value.Int(4)),
                ),
            )

        fun def(kind: ChartKind) = ChartDefinition("t", kind, "region", listOf("total", "orders"))

        fun ChartOutcome.drawn() = shouldBeInstanceOf<ChartOutcome.Drawn>().data

        fun ChartOutcome.failure() = shouldBeInstanceOf<ChartOutcome.Failed>().message

        test("builds category series") {
            val data = def(ChartKind.BAR).build(result).drawn()
            data.labels shouldBe listOf("EU", "US")
            data.datasets[0].data shouldBe DatasetData.Values(listOf(10.5, null))
            data.datasets[1].data shouldBe DatasetData.Values(listOf(3.0, 4.0))
        }

        test("builds scatter points skipping non-numeric") {
            val r =
                QueryResult(
                    listOf("x", "total", "orders"),
                    listOf(
                        listOf(Value.Float(1.0), Value.Float(10.5), Value.Int(3)),
                        listOf(Value.Text("n/a"), Value.Null, Value.Int(4)),
                    ),
                )
            val data = ChartDefinition("t", ChartKind.SCATTER, "x", listOf("orders")).build(r).drawn()
            data.labels.shouldBeEmpty()
            data.datasets[0].data shouldBe DatasetData.Points(listOf(1.0 to 3.0))
        }

        test("reports missing columns") {
            def(ChartKind.LINE).copy(x = "nope").build(result).failure() shouldContain "'nope'"
            def(ChartKind.LINE).copy(y = listOf("ghost")).build(result).failure() shouldContain "'ghost'"
        }

        test("an empty result yields an empty chart") {
            val data = def(ChartKind.BAR).build(QueryResult()).drawn()
            data.labels.shouldBeEmpty()
            data.datasets shouldHaveSize 2
        }

        test("rejects empty y") {
            def(ChartKind.LINE).copy(y = emptyList()).build(result).failure() shouldContain "'y'"
        }
    })
