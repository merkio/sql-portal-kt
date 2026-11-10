package io.merkio.sqlportal.domain.common

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class ValueTest :
    FunSpec({
        test("numeric view") {
            Value.Int(3).asDouble() shouldBe 3.0
            Value.Float(2.5).asDouble() shouldBe 2.5
            Value.Text(" 7.5 ").asDouble() shouldBe 7.5
            Value.Float(Double.NaN).asDouble().shouldBeNull()
            Value.Text("abc").asDouble().shouldBeNull()
            Value.Text("1.5d").asDouble().shouldBeNull()
            Value.Null.asDouble().shouldBeNull()
            Value.Bool(true).asDouble() shouldBe 1.0
        }

        test("display formats") {
            Value.Null.display() shouldBe ""
            Value.Float(2.0).display() shouldBe "2.0"
            Value.Float(2.25).display() shouldBe "2.25"
            Value.Float(1234.5678).display() shouldBe "1234.5678"
            Value.Float(1e20).display() shouldBe "100000000000000000000"
            Value.Int(-4).display() shouldBe "-4"
            Value.Text("x").display() shouldBe "x"
        }
    })
