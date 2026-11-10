package io.merkio.sqlportal.domain.access

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.merkio.sqlportal.domain.common.DomainException

class PermissionTest :
    FunSpec({
        fun p(s: String) = Permission.of(s)

        test("rejects blank, whitespace and wildcards") {
            for (bad in listOf("", "  ", "a b", "a.*")) shouldThrow<DomainException> { Permission.of(bad) }
            Permission.of(" a.b ").value shouldBe "a.b"
        }

        test("exact grant") {
            val set = PermissionSet(listOf("scripts.sales.read"))
            set.grants(p("scripts.sales.read")) shouldBe true
            set.grants(p("scripts.sales.write")) shouldBe false
        }

        test("prefix wildcard") {
            val set = PermissionSet(listOf("scripts.sales.*"))
            set.grants(p("scripts.sales.read")) shouldBe true
            set.grants(p("scripts.sales.deep.read")) shouldBe true
            set.grants(p("scripts.admin.read")) shouldBe false
            set.grants(p("scripts.sales")) shouldBe false
        }

        test("global wildcard and empty set") {
            PermissionSet.all().grants(p("anything.at.all")) shouldBe true
            PermissionSet().isEmpty() shouldBe true
            PermissionSet().grants(p("x")) shouldBe false
        }

        test("blank grants are dropped") {
            PermissionSet(listOf("", "  ", "a")).toList() shouldBe listOf("a")
        }
    })
