/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed under the License is
 * distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */

package az.petek.explorer.domain

import az.petek.explorer.support.Models
import az.petek.explorer.support.Models.action
import az.petek.explorer.support.Models.field
import az.petek.explorer.support.Models.form
import az.petek.explorer.support.Models.page
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/** The kind of a site and its gate come from what an anonymous visitor saw (Faza 17), never from the pages inside. */
class SiteKindsTest {
    private val inside = setOf("admin")

    private val login =
        page("/login", form(ActionKind.LOGIN, "login-submit", "/login", field("email", "email"), field("password", "password")))
    private val register =
        page("/register", form(ActionKind.REGISTER, "register-submit", "/register", field("email", "email"), field("password", "password")))

    @Test
    fun `a visitor's product and cart pages make a shop`() {
        val shop =
            Models.model(
                listOf(
                    page("/products", title = "Məhsullar", testIds = listOf("product-card")),
                    page("/cart", title = "Səbət"),
                    login,
                ),
                listOf(action("add-to-cart", ActionKind.CREATE, "/products", name = "Add to cart", allowed = setOf("anonymous"))),
            )

        val verdict = SiteKinds.of(shop)

        verdict.kind shouldBe SiteKind.SHOP
        verdict.reason shouldContain "cart"
        verdict.reason shouldContain "product"
    }

    @Test
    fun `a visitor's articles make a news site`() {
        val news = Models.model(listOf(page("/news", title = "Xəbərlər"), page("/articles/{id}", title = "Article")), emptyList())

        SiteKinds.of(news).kind shouldBe SiteKind.NEWS
    }

    @Test
    fun `content pages without any sign-in make a showcase`() {
        val showcase = Models.model(listOf(page("/", title = "Ana səhifə"), page("/about", title = "Haqqımızda")), emptyList())

        SiteKinds.of(showcase) shouldBe SiteKindVerdict(SiteKind.SHOWCASE, "2 content pages and no sign-in")
    }

    @Test
    fun `a portal whose pages inside list products and orders is still a sign-in system`() {
        val portal =
            Models.model(
                listOf(
                    login,
                    page("/products", title = "Products", reachableBy = inside, testIds = listOf("product-item")),
                    page("/orders", title = "Sifarişlər", reachableBy = inside, testIds = listOf("cart-summary")),
                ),
                listOf(action("checkout", ActionKind.SUBMIT, "/orders", name = "Checkout", allowed = inside)),
            )

        SiteKinds.of(portal) shouldBe
            SiteKindVerdict(SiteKind.SIGN_IN_SYSTEM, "an anonymous visitor sees only the sign-in and sign-up pages")
    }

    @Test
    fun `a site with nothing typical of any kind is other`() {
        SiteKinds.of(Models.model(emptyList(), emptyList())).kind shouldBe SiteKind.OTHER
    }

    @Test
    fun `a model without an anonymous crawl is judged by everything it has`() {
        val signedInOnly =
            Models.model(
                listOf(page("/products", title = "Products", reachableBy = inside), page("/cart", title = "Cart", reachableBy = inside)),
                emptyList(),
                roles = listOf("admin"),
            )

        SiteKinds.visitorPages(signedInOnly).map { it.urlPattern } shouldContainExactly listOf("/products", "/cart")
        SiteKinds.of(signedInOnly).kind shouldBe SiteKind.SHOP
        // Whether a guest sees anything is not known without an anonymous crawl.
        GateMaps.of(signedInOnly).guest shouldBe false
    }

    @Test
    fun `an open gate has no blockers`() {
        val gate = GateMaps.of(Models.model(listOf(login, register), emptyList()))

        gate.register.shouldNotBeNull().path shouldBe "/register"
        gate.login.shouldNotBeNull().path shouldBe "/login"
        gate.blockers.shouldBeEmpty()
    }

    @Test
    fun `a captcha on the sign-up form is said, not retried`() {
        val guarded =
            page(
                "/register",
                form(
                    ActionKind.REGISTER,
                    "register-submit",
                    "/register",
                    field("email", "email"),
                    field("g-recaptcha-response", required = true),
                ),
            )

        GateMaps.of(Models.model(listOf(login, guarded), emptyList())).blockers shouldContainExactly listOf(GateBlocker.CAPTCHA)
    }

    @Test
    fun `a sign-up that asks for an invitation code is invite only`() {
        val invited =
            page(
                "/register",
                form(ActionKind.REGISTER, "register-submit", "/register", field("email", "email"), field("invite_code", required = true)),
            )

        GateMaps.of(Models.model(listOf(login, invited), emptyList())).blockers shouldContainExactly listOf(GateBlocker.INVITE_ONLY)
    }

    @Test
    fun `a site without sign-in or sign-up has no gate, and one with sign-in only needs the owner's accounts`() {
        GateMaps.of(Models.model(listOf(page("/", title = "Ana səhifə")), emptyList())).blockers shouldContainExactly
            listOf(GateBlocker.NO_GATE)
        GateMaps.of(Models.model(listOf(login), emptyList())).blockers shouldContainExactly listOf(GateBlocker.NO_SIGN_UP)
    }

    @Test
    fun `an admin's add-user form inside is never taken for the site's sign-up`() {
        val addUser =
            page(
                "/admin/users",
                form(ActionKind.REGISTER, "user-submit", "/admin/users", field("email", "email"), field("password", "password")),
                reachableBy = inside,
            )

        val gate = GateMaps.of(Models.model(listOf(login, addUser), emptyList()))

        gate.register.shouldBeNull()
        gate.blockers shouldContainExactly listOf(GateBlocker.NO_SIGN_UP)
    }
}
