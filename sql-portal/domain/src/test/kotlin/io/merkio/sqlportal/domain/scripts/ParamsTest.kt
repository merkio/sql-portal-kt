package io.merkio.sqlportal.domain.scripts

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.merkio.sqlportal.domain.common.DomainException
import io.merkio.sqlportal.domain.common.Value

class ParamsTest :
    FunSpec({
        fun def(kind: ParamKind) = ParamDefinition("p", kind)

        fun ParamDefinition.error(raw: String?) = shouldThrow<FieldException> { parse(raw) }.error.message

        test("required without value fails") {
            val d = def(ParamKind.TEXT).copy(required = true)
            d.error(null) shouldBe "this field is required"
            d.error("  ") shouldBe "this field is required"
        }

        test("optional without value is null") {
            def(ParamKind.TEXT).parse(null) shouldBe Value.Null
        }

        test("default is used when blank") {
            val d = def(ParamKind.INTEGER).copy(default = "5")
            d.parse(null) shouldBe Value.Int(5)
            d.parse("") shouldBe Value.Int(5)
            d.parse("9") shouldBe Value.Int(9)
        }

        test("integer parsing and range") {
            val d = def(ParamKind.INTEGER).copy(min = 1.0, max = 10.0)
            d.parse("3") shouldBe Value.Int(3)
            d.error("0") shouldContain "at least 1"
            d.error("11") shouldBe "must be at most 10"
            d.error("x") shouldContain "integer"
            d.error("1.5") shouldContain "integer"
        }

        test("number parsing") {
            val d = def(ParamKind.NUMBER)
            d.parse("2.5") shouldBe Value.Float(2.5)
            d.error("NaN") shouldContain "finite"
            d.error("inf") shouldContain "finite"
            d.error("abc") shouldContain "not a number"
            d.error("1.5d") shouldContain "not a number"
        }

        test("boolean semantics") {
            val d = def(ParamKind.BOOLEAN)
            d.parse(null) shouldBe Value.Bool(false)
            d.parse("on") shouldBe Value.Bool(true)
            d.parse("TRUE") shouldBe Value.Bool(true)
            d.parse("0") shouldBe Value.Bool(false)
            d.error("maybe") shouldContain "not a valid boolean"
            def(ParamKind.BOOLEAN).copy(default = "true").parse(null) shouldBe Value.Bool(true)
        }

        test("date parsing") {
            val d = def(ParamKind.DATE)
            d.parse("2024-02-29") shouldBe Value.Text("2024-02-29")
            d.error("2023-02-29") shouldContain "not a date"
            d.error("29/02/2024") shouldContain "not a date"
        }

        test("select only accepts listed options") {
            val d = def(ParamKind.SELECT).copy(options = listOf(SelectOption("a", "A")))
            d.parse("a") shouldBe Value.Text("a")
            d.error("b") shouldContain "not an allowed option"
        }

        test("text length and pattern") {
            def(ParamKind.TEXT).copy(maxLength = 3).error("abcd") shouldContain "at most 3 characters"
            val d = def(ParamKind.TEXT).copy(maxLength = 10, pattern = "^[a-z]+$")
            d.parse("abc") shouldBe Value.Text("abc")
            d.error("ab1") shouldContain "expected format"
        }

        test("validate checks name, options, range, pattern and default") {
            for (bad in listOf(
                ParamDefinition("1bad", ParamKind.TEXT),
                ParamDefinition("has space", ParamKind.TEXT),
                def(ParamKind.SELECT),
                def(ParamKind.INTEGER).copy(min = 5.0, max = 1.0),
                def(ParamKind.TEXT).copy(pattern = "("),
                def(ParamKind.INTEGER).copy(default = "abc"),
            )) {
                shouldThrow<DomainException> { bad.validate() }
            }
            def(ParamKind.INTEGER).copy(default = "3").validate()
        }

        test("satisfied without input") {
            def(ParamKind.TEXT).isSatisfiedWithoutInput shouldBe true
            def(ParamKind.TEXT).copy(required = true).isSatisfiedWithoutInput shouldBe false
            def(ParamKind.TEXT).copy(required = true, default = "x").isSatisfiedWithoutInput shouldBe true
        }
    })
