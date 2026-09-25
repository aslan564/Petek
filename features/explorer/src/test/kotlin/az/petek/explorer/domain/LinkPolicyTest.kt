/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.explorer.domain

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.net.URI

class LinkPolicyTest {
    private val policy = LinkPolicy(SiteOrigin.of(URI("https://site.test")))

    private fun verdict(
        url: String,
        text: String = "",
    ) = policy.verdict(URI(url), text)

    @Test
    fun `same-origin pages are followed`() {
        verdict("https://site.test/tickets") shouldBe LinkVerdict.Follow
        verdict("https://site.test/tickets/t1?tab=history", "Noutbuk işləmir") shouldBe LinkVerdict.Follow
    }

    @Test
    fun `other hosts, ports and schemes are never followed`() {
        verdict("https://other.test/") shouldBe LinkVerdict.Skip(SkipReason.OTHER_ORIGIN)
        verdict("https://site.test:8443/") shouldBe LinkVerdict.Skip(SkipReason.OTHER_ORIGIN)
        verdict("http://site.test/") shouldBe LinkVerdict.Skip(SkipReason.OTHER_ORIGIN)
        verdict("ftp://site.test/file") shouldBe LinkVerdict.Skip(SkipReason.NOT_WEB)
    }

    @Test
    fun `logout and delete looking links are skipped by path or by text in both languages`() {
        verdict("https://site.test/logout") shouldBe LinkVerdict.Skip(SkipReason.UNSAFE)
        verdict("https://site.test/account/sign-out") shouldBe LinkVerdict.Skip(SkipReason.UNSAFE)
        verdict("https://site.test/tickets/t1/delete") shouldBe LinkVerdict.Skip(SkipReason.UNSAFE)
        verdict("https://site.test/x?action=remove") shouldBe LinkVerdict.Skip(SkipReason.UNSAFE)
        verdict("https://site.test/session/end", "Çıxış") shouldBe LinkVerdict.Skip(SkipReason.UNSAFE)
        verdict("https://site.test/tickets/t1/x", "Sil") shouldBe LinkVerdict.Skip(SkipReason.UNSAFE)
        verdict("https://site.test/newsletter", "Unsubscribe") shouldBe LinkVerdict.Skip(SkipReason.UNSAFE)
        verdict("https://site.test/p", "Log out") shouldBe LinkVerdict.Skip(SkipReason.UNSAFE)
    }

    @Test
    fun `percent-encoded, upper-case and ascii spellings of unsafe words are recognised too`() {
        verdict("https://site.test/%C3%A7%C4%B1x%C4%B1%C5%9F") shouldBe LinkVerdict.Skip(SkipReason.UNSAFE)
        verdict("https://site.test/account/log%6Fut") shouldBe LinkVerdict.Skip(SkipReason.UNSAFE)
        verdict("https://site.test/p", "ÇIXIŞ") shouldBe LinkVerdict.Skip(SkipReason.UNSAFE)
        verdict("https://site.test/hesab/cixis") shouldBe LinkVerdict.Skip(SkipReason.UNSAFE)
        verdict("https://site.test/p", "İMTİNA") shouldBe LinkVerdict.Skip(SkipReason.UNSAFE)
    }

    @Test
    fun `links that decide something are never followed, the pages that list decisions are`() {
        verdict("https://site.test/tickets/t1/approve") shouldBe LinkVerdict.Skip(SkipReason.UNSAFE)
        verdict("https://site.test/leave/7?do=reject") shouldBe LinkVerdict.Skip(SkipReason.UNSAFE)
        verdict("https://site.test/tickets/t1/x", "Təsdiqlə") shouldBe LinkVerdict.Skip(SkipReason.UNSAFE)
        verdict("https://site.test/tickets/t1/y", "Rədd et") shouldBe LinkVerdict.Skip(SkipReason.UNSAFE)
        verdict("https://site.test/approvals", "Approvals") shouldBe LinkVerdict.Follow
        verdict("https://site.test/tesdiqler", "Təsdiqlər") shouldBe LinkVerdict.Follow
    }

    @Test
    fun `words that merely contain a short unsafe word are not unsafe`() {
        verdict("https://site.test/silver", "Silver plan") shouldBe LinkVerdict.Follow
        verdict("https://site.test/dropdown-demo") shouldBe LinkVerdict.Follow
        verdict("https://site.test/login", "Daxil ol") shouldBe LinkVerdict.Follow
    }

    @Test
    fun `machine endpoints and downloads are not pages`() {
        verdict("https://site.test/api/tickets/t1/comments") shouldBe LinkVerdict.Skip(SkipReason.NOT_A_PAGE)
        verdict("https://site.test/test/otp/+99450") shouldBe LinkVerdict.Skip(SkipReason.NOT_A_PAGE)
        verdict("https://site.test/graphql") shouldBe LinkVerdict.Skip(SkipReason.NOT_A_PAGE)
        verdict("https://site.test/files/report.PDF") shouldBe LinkVerdict.Skip(SkipReason.DOWNLOAD)
        verdict("https://site.test/testimonials") shouldBe LinkVerdict.Follow
    }

    @Test
    fun `robots rules keep their paths out`() {
        val robots = RobotsRules.parse("User-agent: *\nDisallow: /admin\n")
        val guarded = LinkPolicy(SiteOrigin.of(URI("https://site.test")), robots)
        guarded.verdict(URI("https://site.test/admin/users")) shouldBe LinkVerdict.Skip(SkipReason.ROBOTS)
        guarded.verdict(URI("https://site.test/tickets")) shouldBe LinkVerdict.Follow
    }
}

class RobotsRulesTest {
    @Test
    fun `the most specific rule wins and allow wins a tie`() {
        val rules =
            RobotsRules.parse(
                """
                User-agent: *
                Disallow: /private
                Allow: /private/public
                Disallow: /*.json${'$'}
                Disallow:
                """.trimIndent(),
            )
        rules.allows("/private/secret") shouldBe false
        rules.allows("/private/public/page") shouldBe true
        rules.allows("/data/items.json") shouldBe false
        rules.allows("/data/items.json?x=1") shouldBe true
        rules.allows("/tickets") shouldBe true
        rules.disallowCount shouldBe 2
    }

    @Test
    fun `a group for petek replaces the generic group`() {
        val rules =
            RobotsRules.parse(
                """
                # comment
                User-agent: *
                Disallow: /

                User-agent: googlebot
                User-agent: PetekBot
                Disallow: /internal
                """.trimIndent(),
                agent = "petekbot",
            )
        rules.allows("/tickets") shouldBe true
        rules.allows("/internal/x") shouldBe false
    }

    @Test
    fun `a blank user agent line does not make a group apply to petek`() {
        val rules =
            RobotsRules.parse(
                """
                User-agent:
                Disallow: /

                User-agent: *
                Disallow: /admin
                """.trimIndent(),
            )

        rules.allows("/tickets") shouldBe true
        rules.allows("/admin/users") shouldBe false
    }

    @Test
    fun `no rules allow everything`() {
        RobotsRules.NONE.allows("/anything") shouldBe true
        RobotsRules.parse("garbage without colons").allows("/x") shouldBe true
    }
}

class KeywordsTest {
    @Test
    fun `instructions yield meaningful words without stop words`() {
        Keywords.of("Test the ticket approval and elanlar, please") shouldBe setOf("ticket", "approval", "elanlar")
        Keywords.of(null) shouldBe emptySet()
    }

    @Test
    fun `azerbaijani spellings fold to one word, upper case and ascii included`() {
        Keywords.fold("ÇIXIŞ") shouldBe "cixis"
        Keywords.fold("Çıxış") shouldBe "cixis"
        Keywords.fold("İMTİNA") shouldBe "imtina"
        Keywords.words("İMTİNA ET").map(Keywords::fold) shouldBe listOf("imtina", "et")
        Keywords.matches("müraciət", "muracietler") shouldBe true
        Keywords.containsStem("ƏLAVƏ ET", setOf("əlavə")) shouldBe true
        Keywords.containsPhrase("Sistemə daxil olun", "daxil ol") shouldBe false
        Keywords.containsPhrase("Hesaba DAXİL OL", "daxil ol") shouldBe true
    }

    @Test
    fun `words match by shared stems`() {
        Keywords.matches("ticket", "tickets") shouldBe true
        Keywords.matches("elanlar", "elan") shouldBe true
        Keywords.matches("an", "announcement") shouldBe false
        Keywords.score(setOf("ticket", "approval"), "/tickets/{id} Approve ticket") shouldBe 1
    }
}
