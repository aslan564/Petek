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

import az.petek.evidence.domain.EvidenceTier

/**
 * The small-bug cards of docs/LINK_ONLY_SWARM.md section 6 (Faza 19): the general catalogue every site gets and the
 * first three of each kind of site. A card is checked by the [patterns] that cover it, on top of the blind patterns of
 * Faza 13, and takes their evidence tier; a card code cannot check yet says why ([notYet]). No card is ever judged by
 * the AI (AGENTS.md rule 2). [partly] names what a covering pattern leaves out of the card.
 */
enum class SmallBugCard(
    val title: String,
    /** The kinds of site the card is for; empty: every site (the general catalogue). */
    val kinds: Set<SiteKind> = emptySet(),
    val patterns: Set<TestPattern> = emptySet(),
    val notYet: String? = null,
    val partly: String? = null,
    /** Checked by the setup's sign-ups through the site's own form (they open the confirmation), not by an idea. */
    val bySignUp: Boolean = false,
) {
    DOUBLE_SUBMIT("two clicks write the same thing twice", patterns = setOf(TestPattern.IDEMPOTENCY)),
    BACK_RESUBMITS(
        "the back button sends an old form again",
        notYet = "telling a form sent again after going back from a new one needs the site's own count of what was written",
    ),
    ERROR_LOST_ON_RELOAD(
        "an error message is gone after a reload while the server keeps the failed state",
        notYet = "it needs a submission that fails on purpose and a way to read the server's state back",
    ),
    KEYBOARD_COVERS_SUBMIT(
        "the phone keyboard covers the submit button",
        notYet = "a browser cannot show the on-screen keyboard; the phone layout itself is checked (MOBILE_VIEWPORT)",
    ),
    CONFIRMATION_LINK(
        "a confirmation link works twice, or answers 404 the first time",
        partly = "its first use is every sign-up of the setup; a second use of the same link is not tried",
        bySignUp = true,
    ),
    OTP_PASTE(
        "a code pasted with spaces or dashes is refused",
        notYet = "codes are typed as the mailbox gives them",
    ),
    DEEP_LINK_AFTER_SIGN_IN(
        "the address asked for is forgotten after signing in",
        notYet = "the sign-in flows open the sign-in page themselves, so the address the site would return to is not kept",
    ),
    WWW_SESSION(
        "https and www are separate sessions",
        notYet = "it needs both the bare and the www host of the site on the test stage",
    ),
    NOTIFICATION_COUNT(
        "content arrives but the notification count stays 0",
        patterns = setOf(TestPattern.REALTIME),
        partly = "the content's live delivery is measured; the count itself only where a scenario names its element",
    ),
    TWO_TABS(
        "signed out in one tab, the other still shows what needs a session",
        patterns = setOf(TestPattern.SESSION_EXPIRY),
        partly = "a session without its cookies is checked; a session ended from another tab is not",
    ),
    DELETED_OBJECT(
        "the address of a deleted object answers an empty 200 instead of 404",
        notYet = "drafts never delete; it is checked only where a scenario the owner wrote deletes",
    ),
    SAVE_DROPS_FIELD(
        "save does not send one of the fields",
        notYet = "a created object is checked for its marker text only, not for every field",
    ),
    EMAIL_CASE(
        "an e-mail in other letter case opens a second account",
        notYet = "a second sign-up whose refusal code could tell from any other failure is needed",
    ),
    AUTOFILL(
        "a field the password manager fills does not enable the button",
        notYet = "a browser's password manager cannot be driven by a test",
    ),

    STOCK_RACE(
        "two buyers take the last item",
        kinds = setOf(SiteKind.SHOP),
        notYet = "it needs a product's stock: the site's test API or a product the owner names",
    ),
    CART_MERGE(
        "the cart is lost or doubled when the buyer signs in",
        kinds = setOf(SiteKind.SHOP),
        notYet = "it needs the cart's count element and a sign-in in the middle of a step",
    ),
    COUPON_ONCE(
        "a coupon is used more than once",
        kinds = setOf(SiteKind.SHOP),
        notYet = "it needs a test coupon from the owner",
    ),
    PUBLISHED_REACHES_ALL(
        "what is published reaches everyone",
        kinds = setOf(SiteKind.NEWS),
        patterns = setOf(TestPattern.REALTIME),
        partly = "measured where the trial touch saw a new object reach the others live",
    ),
    DRAFT_LEAK(
        "a draft is seen by others",
        kinds = setOf(SiteKind.NEWS),
        patterns = setOf(TestPattern.DIRECT_URL),
    ),
    COMMENT_TWICE(
        "a comment is posted twice",
        kinds = setOf(SiteKind.NEWS),
        patterns = setOf(TestPattern.IDEMPOTENCY),
    ),
    DEAD_LINKS(
        "a link leads nowhere",
        kinds = setOf(SiteKind.SHOWCASE),
        patterns = setOf(TestPattern.BROKEN_LINKS, TestPattern.OUTBOUND_LINKS),
        partly = "links are checked; a button that does nothing is not, as code cannot tell that without clicking it",
    ),
    LANGUAGE_MIRRORS(
        "the language versions of a page differ",
        kinds = setOf(SiteKind.SHOWCASE),
        patterns = setOf(TestPattern.LANGUAGE_MIRRORS),
    ),
    EMPTY_LIST(
        "a list shows nothing",
        kinds = setOf(SiteKind.SHOWCASE),
        patterns = setOf(TestPattern.EMPTY_LISTS),
    ),
    ;

    /** The strongest evidence the card's check can reach; null for a card code does not check yet. */
    val evidence: EvidenceTier? get() = patterns.map { it.evidence }.minByOrNull { it.ordinal }
}

/** How a draft stands on one card. */
enum class CardState {
    /** Steps of this draft check it. */
    CHECKED,

    /** Code checks it, but nothing the explorer saw on this site called for it in this draft. */
    NOT_CALLED_FOR,

    /** Code does not check it yet. */
    NOT_YET,
}

data class CardCoverage(
    val card: SmallBugCard,
    val state: CardState,
    /** The steps that check it, or why it is not checked. */
    val detail: String,
)

/** Where a draft stands on the cards of its site's kind (the general catalogue and the kind's own). */
object SmallBugCatalog {
    fun cardsFor(kind: SiteKind): List<SmallBugCard> = SmallBugCard.entries.filter { it.kinds.isEmpty() || kind in it.kinds }

    /** [signUps] are the setup's steps that sign testers up through the site's own form. */
    fun coverage(
        model: SiteModel,
        kind: SiteKind,
        covered: List<CoveredIdea>,
        skipped: List<SkippedIdea>,
        signUps: List<String>,
    ): List<CardCoverage> =
        cardsFor(kind).map { card ->
            val notYet = card.notYet
            if (notYet != null) return@map CardCoverage(card, CardState.NOT_YET, notYet)
            if (card.bySignUp) {
                val otp = GateMaps.of(model).otp
                return@map when {
                    otp != OtpKind.EMAIL_LINK -> {
                        CardCoverage(card, CardState.NOT_CALLED_FOR, "the explorer saw no confirmation link (${otp.name.lowercase()})")
                    }

                    signUps.isEmpty() -> {
                        CardCoverage(card, CardState.NOT_CALLED_FOR, "no tester signs up through the site's form in this draft")
                    }

                    else -> {
                        CardCoverage(
                            card,
                            CardState.CHECKED,
                            "steps ${signUps.joinToString()}" + (card.partly?.let { "; partly: $it" } ?: ""),
                        )
                    }
                }
            }
            val checking = covered.filter { it.idea.pattern in card.patterns && about(card, model, it.idea) }
            if (checking.isNotEmpty()) {
                val steps = checking.flatMap { it.stepIds }.distinct().joinToString()
                return@map CardCoverage(card, CardState.CHECKED, "steps $steps" + (card.partly?.let { "; partly: $it" } ?: ""))
            }
            val reason = skipped.firstOrNull { it.idea.pattern in card.patterns && about(card, model, it.idea) }?.reason
            CardCoverage(card, CardState.NOT_CALLED_FOR, reason ?: "nothing the explorer saw on this site calls for it")
        }

    /** Whether [idea] is about what [card] is: a draft's address, a comment's double post. */
    private fun about(
        card: SmallBugCard,
        model: SiteModel,
        idea: TestIdea,
    ): Boolean {
        val action = model.action(idea.actionId)
        return when (card) {
            SmallBugCard.DRAFT_LEAK -> action != null && Drafts.saves(action)
            SmallBugCard.COMMENT_TWICE -> action != null && model.page(action.pageId)?.urlPattern?.let(UrlPatterns::hasId) == true
            else -> true
        }
    }
}
