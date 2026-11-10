package io.merkio.sqlportal.common.template

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain

class TemplatesTest :
    FunSpec({
        val templates = Templates(TemplatesTest::class.java, "/templates")

        test("html templates escape values, import macros and pass trusted fragments") {
            val html =
                templates.render(
                    "page.ftlh",
                    mapOf(
                        "title" to "<b>'x'</b>",
                        "count" to 12345,
                        "trusted" to "<i>ok</i>",
                    ),
                )
            html shouldContain "<h1>&lt;b&gt;&#39;x&#39;&lt;/b&gt;</h1>"
            html shouldContain "<span>12345</span>"
            html shouldContain "<i>ok</i>"
        }

        test("broken and unknown templates are reported") {
            shouldThrow<TemplateException> { templates.check(listOf("broken.ftlh")) }.message shouldContain
                "broken.ftlh"
            shouldThrow<TemplateException> { templates.render("nope.ftlh", emptyMap()) }
            shouldThrow<TemplateException> { templates.render("page.ftlh", emptyMap()) }
            templates.check(listOf("page.ftlh", "parts.ftlh"))
        }

        test("content versions follow the content") {
            contentVersion("a", "b") shouldBe contentVersion("a", "b")
            contentVersion("a", "b") shouldNotBe contentVersion("ab")
            contentVersion("a").length shouldBe 16
        }
    })
