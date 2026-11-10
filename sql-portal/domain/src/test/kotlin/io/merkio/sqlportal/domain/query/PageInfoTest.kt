package io.merkio.sqlportal.domain.query

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class PageInfoTest :
    FunSpec({
        fun plan(
            number: Int,
            size: Int,
            total: Long,
        ) = PageInfo.plan(PageRequest(number, size), total)

        test("splits rows into pages") {
            val p = plan(2, 25, 60)
            Triple(p.number, p.pages, p.offset) shouldBe Triple(2, 3, 25L)
            (p.firstRow to p.lastRow) shouldBe (26L to 50L)
            plan(3, 25, 60).let { (it.firstRow to it.lastRow) shouldBe (51L to 60L) }
        }

        test("clamps out of range pages") {
            plan(0, 25, 60).number shouldBe 1
            plan(99, 25, 60).number shouldBe 3
        }

        test("an empty result has one empty page") {
            val p = plan(4, 25, 0)
            (p.number to p.pages) shouldBe (1 to 1)
            (p.firstRow to p.lastRow) shouldBe (0L to 0L)
        }

        test("a zero size is treated as one row per page") {
            val p = plan(1, 0, 3)
            (p.size to p.pages) shouldBe (1 to 3)
        }
    })
