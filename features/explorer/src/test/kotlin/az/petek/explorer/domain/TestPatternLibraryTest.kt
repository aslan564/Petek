package az.petek.explorer.domain

import az.petek.explorer.support.Models
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

class TestPatternLibraryTest {
    private val library = TestPatternLibrary()

    private fun ideasFor(action: ActionModel): List<TestIdea> =
        library.ideas(Models.model(listOf(Models.page("/p", reachableBy = setOf("manager"))), listOf(action)))

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("table")
    fun `every action kind gets the patterns of the library table`(
        kind: ActionKind,
        expected: Set<TestPattern>,
    ) {
        ideasFor(Models.action("a", kind, "/p")).map { it.pattern }.toSet() shouldBe expected
    }

    @Test
    fun `a create whose result arrived live also gets a realtime idea naming the receivers`() {
        val ideas = ideasFor(Models.action("a", ActionKind.CREATE, "/p", realtime = true, trial = Models.trial(setOf("employee"))))

        ideas.map { it.pattern } shouldContainExactlyInAnyOrder
            listOf(TestPattern.HAPPY_PATH, TestPattern.REALTIME, TestPattern.IDEMPOTENCY, TestPattern.BOUNDARY)
        ideas.single { it.pattern == TestPattern.REALTIME }.rationale shouldContain "employee receive it live"
    }

    @Test
    fun `an observed role difference adds a permission idea with a higher priority and the refused roles`() {
        val plain = ideasFor(Models.action("a", ActionKind.APPROVE, "/p")).single { it.pattern == TestPattern.PERMISSION }
        val observed =
            ideasFor(Models.action("a", ActionKind.APPROVE, "/p", forbidden = setOf("employee"))).single {
                it.pattern ==
                    TestPattern.PERMISSION
            }
        val create = ideasFor(Models.action("a", ActionKind.CREATE, "/p", forbidden = setOf("employee")))

        observed.priority shouldBe plain.priority + TestPatternLibrary.OBSERVED_DIFFERENCE_BONUS
        observed.roles shouldContainExactly listOf("employee")
        observed.rationale shouldContain "employee were not offered it"
        create.map { it.pattern } shouldContainExactlyInAnyOrder
            listOf(TestPattern.HAPPY_PATH, TestPattern.BOUNDARY, TestPattern.IDEMPOTENCY, TestPattern.PERMISSION)
    }

    @Test
    fun `navigation and other actions never get ideas, even with a role difference`() {
        ideasFor(Models.action("a", ActionKind.OTHER, "/p", forbidden = setOf("employee"))).shouldBeEmpty()
    }

    @Test
    fun `instructions lift the matching action above the others, by name more than by page`() {
        val model =
            Models.model(
                listOf(
                    Models.page("/tickets", reachableBy = setOf("employee"), purpose = "Müraciətlər"),
                    Models.page("/announcements", reachableBy = setOf("admin"), purpose = "Elanlar"),
                ),
                listOf(
                    Models.action("ticket-submit", ActionKind.CREATE, "/tickets", name = "Göndər"),
                    Models.action("announcement-submit", ActionKind.CREATE, "/announcements", name = "Dərc et"),
                ),
            )

        val byName = library.ideas(model, "Dərc etmə axını")
        val byPage = library.ideas(model, "elanlar")
        val none = library.ideas(model)

        byName.first().actionId shouldBe "announcement-submit"
        byName.first().priority shouldBe TestPatternLibrary.BASE_PRIORITY.getValue(TestPattern.HAPPY_PATH) + TestPatternLibrary.NAME_BOOST
        byName.first().rationale shouldContain "matches the owner's instructions"
        byPage.first().actionId shouldBe "announcement-submit"
        byPage.first().priority shouldBe TestPatternLibrary.BASE_PRIORITY.getValue(TestPattern.HAPPY_PATH) + TestPatternLibrary.PAGE_BOOST
        none.first().actionId shouldBe "announcement-submit"
        none.map { it.priority } shouldBe none.map { it.priority }.sortedDescending()
    }

    @Test
    fun `ideas are ordered by priority, then action id, then pattern`() {
        val ideas = library.ideas(Models.kadro())

        ideas.zipWithNext().forEach { (a, b) ->
            (a.priority > b.priority || (a.priority == b.priority && a.actionId <= b.actionId)) shouldBe true
        }
        ideas.count { it.pattern == TestPattern.RACE } shouldBe 2
    }

    companion object {
        @JvmStatic
        fun table(): List<Arguments> =
            listOf(
                Arguments.of(ActionKind.CREATE, setOf(TestPattern.HAPPY_PATH, TestPattern.BOUNDARY, TestPattern.IDEMPOTENCY)),
                Arguments.of(ActionKind.APPROVE, setOf(TestPattern.RACE, TestPattern.PERMISSION)),
                Arguments.of(ActionKind.REJECT, setOf(TestPattern.RACE, TestPattern.PERMISSION)),
                Arguments.of(ActionKind.DELETE, setOf(TestPattern.PERMISSION, TestPattern.IDEMPOTENCY)),
                Arguments.of(ActionKind.UPDATE, setOf(TestPattern.HAPPY_PATH, TestPattern.PERMISSION)),
                Arguments.of(ActionKind.ASSIGN, setOf(TestPattern.HAPPY_PATH, TestPattern.PERMISSION)),
                Arguments.of(ActionKind.REGISTER, setOf(TestPattern.HAPPY_PATH, TestPattern.BOUNDARY)),
                Arguments.of(ActionKind.LOGIN, setOf(TestPattern.HAPPY_PATH, TestPattern.BOUNDARY)),
                Arguments.of(ActionKind.SUBMIT, setOf(TestPattern.HAPPY_PATH, TestPattern.BOUNDARY)),
                Arguments.of(ActionKind.NAVIGATE, emptySet<TestPattern>()),
                Arguments.of(ActionKind.OTHER, emptySet<TestPattern>()),
            )
    }
}
