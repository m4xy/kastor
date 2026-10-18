package uk.m4xy.kastor.processor

import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import com.tschuchort.compiletesting.configureKsp
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

@OptIn(ExperimentalCompilerApi::class)
class DslProcessorTest {
    private fun compile(source: String): JvmCompilationResult = KotlinCompilation().apply {
        sources = listOf(SourceFile.kotlin("Model.kt", "package test\nimport uk.m4xy.kastor.*\n$source"))
        inheritClassPath = true
        messageOutputStream = System.out
        configureKsp {
            symbolProcessorProviders += DslProcessorProvider()
            withCompilation = true
        }
    }.compile()

    private fun assertError(source: String, message: String) {
        val result = compile(source)
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertContains(result.messages, message)
    }

    @Test
    fun `generated code compiles and is usable`() {
        val result = compile(
            """
            @AttributeGroup interface ExampleGroup { val x: Int }
            @Element("foo", function = "foo") interface Foo : ExampleGroup
            @Element("test", function = "bar") @Children(Foo::class)
            interface Test : ExampleGroup { @Required val someAttribute: String }

            val tree = TestElement.build<Unit>("a") { foo { x = 1 } }
            """,
        )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    }

    @Test
    fun `names used by generated code are free for elements and attributes`() {
        val result = compile(
            """
            @Element("apply", function = "apply") interface Apply { val put: Int }
            @Element("literal", function = "literal") interface Literal { @Required val let: String }
            @Element("required", function = "required") interface Required2
            @Element("misc", function = "mutableListOf") interface Misc { val buildMap: Int; val emptyList: Int }
            @Element("helper", function = "buildElement") interface Helper { val literalOrNull: Int }
            @Element("built", function = "build") interface Built
            @Element("root", function = "root")
            @Children(Apply::class, Literal::class, Required2::class, Misc::class, Helper::class, Built::class)
            interface Root

            val tree = RootElement.build<Unit> {
                apply { put = 1 }
                literal("x")
                required()
                mutableListOf { buildMap = 2 }
                buildElement { literalOrNull = 3 }
                build()
            }
            """,
        )
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    }

    @Test
    fun `dsl marker hides outer scopes`() {
        assertError(
            """
            @Element("leaf", function = "leaf") interface Leaf
            @Element("root", function = "root") @Children(Leaf::class)
            interface Root { val title: String }

            val tree = RootElement.build<Unit> { leaf { title = "no" } }
            """,
            "cannot be called in this context with an implicit receiver",
        )
    }

    @Test
    fun `element must be an interface`() = assertError(
        """@Element("a", function = "a") abstract class A""",
        "@Element can only be applied to interfaces",
    )

    @Test
    fun `children must be elements`() = assertError(
        """
        interface NotAnElement
        @Element("a", function = "a") @Children(NotAnElement::class) interface A
        """,
        "which is not an @Element interface",
    )

    @Test
    fun `attribute names must be unique`() = assertError(
        """
        @Element("a", function = "a")
        interface A { val first: String; @AttributeName("first") val second: String }
        """,
        "has several attributes named 'first'",
    )

    @Test
    fun `reserved property names are rejected`() = assertError(
        """@Element("a", function = "a") interface A { val children: String }""",
        "Attribute property 'children' on test.A is reserved",
    )

    @Test
    fun `child function can't clash with an attribute`() = assertError(
        """
        @Element("b", function = "label") interface B
        @Element("a", function = "a") @Children(B::class) interface A { val label: String }
        """,
        "Child function 'label' of test.B clashes with the attribute",
    )

    @Test
    fun `children can't have identical functions`() = assertError(
        """
        @Element("b", function = "item") interface B
        @Element("c", function = "item") interface C
        @Element("a", function = "a") @Children(B::class, C::class) interface A
        """,
        "have identical 'item' functions",
    )

    @Test
    fun `group attributes can't be redeclared`() = assertError(
        """
        @AttributeGroup interface G { val x: Int }
        @Element("a", function = "a") interface A : G { override val x: Int }
        """,
        "redeclares attribute 'x' from an attribute group",
    )

    @Test
    fun `public element can't use an internal group`() = assertError(
        """
        @AttributeGroup internal interface G { val x: Int }
        @Element("a", function = "a") interface A : G
        """,
        "Public test.A uses internal attribute group test.G",
    )
}
