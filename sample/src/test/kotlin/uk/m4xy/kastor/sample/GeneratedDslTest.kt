package uk.m4xy.kastor.sample

import uk.m4xy.kastor.Attribute
import uk.m4xy.kastor.Deferred
import uk.m4xy.kastor.DeferredAttributeException
import uk.m4xy.kastor.MissingAttributeException
import uk.m4xy.kastor.ResolvedNode
import uk.m4xy.kastor.resolve
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

class GeneratedDslTest {
    data class Ctx(val user: String, val scale: Int)

    @Test
    fun `builds a tree from values`() {
        val test = TestElement.build<Ctx>(AValue("root")) {
            x = X(1)
            foo { y = Y(2) }
            foo()
        }

        assertEquals(Attribute.Value(AValue("root")), test.someAttribute)
        assertEquals(Attribute.Value(X(1)), test.x)
        assertNull(test.y)
        assertEquals(2, test.children.size)
        val first = assertIs<TestChild.Foo<Ctx>>(test.children[0])
        assertEquals(Attribute.Value(Y(2)), first.element.y)
        assertEquals(listOf("someAttribute", "x"), test.attributes.keys.toList())
    }

    @Test
    fun `factories are applied on resolve`() {
        val test = TestElement.build<Ctx>({ AValue("hello ${it.user}") }) {
            x { X(it.scale * 10) }
        }

        assertEquals(
            ResolvedNode("test", mapOf("someAttribute" to AValue("hello ada"), "x" to X(30)), emptyList()),
            test.resolve(Ctx("ada", 3)),
        )
    }

    @Test
    fun `deferred required attribute is set by the block`() {
        val test = TestElement.build<Ctx>(Deferred) { someAttribute = AValue("later") }
        assertEquals(Attribute.Value(AValue("later")), test.someAttribute)

        val viaFactory = TestElement.build<Ctx>(Deferred) { someAttribute { AValue(it.user) } }
        assertEquals(AValue("bob"), viaFactory.someAttribute.resolve(Ctx("bob", 1)))
    }

    @Test
    fun `deferred required attribute that is never set fails the build`() {
        val error = assertFailsWith<MissingAttributeException> { TestElement.build<Ctx>(Deferred) }
        assertEquals("test" to "someAttribute", error.element to error.attribute)
    }

    @Test
    fun `reading a factory-backed attribute in the scope fails`() {
        assertFailsWith<DeferredAttributeException> {
            TestElement.build<Ctx>(AValue("a")) {
                x { X(it.scale) }
                println(x)
            }
        }
    }

    @Test
    fun `scope properties read back literals and clear with null`() {
        TestElement.build<Ctx>(AValue("a")) {
            assertEquals(AValue("a"), someAttribute)
            x = X(5)
            assertEquals(X(5), x)
            x = null
            assertNull(x)
        }
    }

    @Test
    fun `nested groups, renamed attributes and multiple required attributes`() {
        val page = SectionElement.build<Ctx> {
            id = "main"
            className = "page"
            title { "Welcome, ${it.user}" }
            section {
                style = "padding: 0"
                link("https://example.com") { target = "_blank" }
            }
            image({ "/img/${it.user}.png" }, { "Avatar of ${it.user}" }) {
                width { it.scale * 32 }
            }
            image(Deferred, Deferred) {
                src = "/logo.png"
                description = "Logo"
            }
        }

        assertEquals(
            ResolvedNode(
                "section",
                mapOf("title" to "Welcome, ada", "id" to "main", "class" to "page"),
                listOf(
                    ResolvedNode(
                        "section",
                        mapOf("style" to "padding: 0"),
                        listOf(ResolvedNode("a", mapOf("href" to "https://example.com", "target" to "_blank"), emptyList())),
                    ),
                    ResolvedNode("img", mapOf("src" to "/img/ada.png", "alt" to "Avatar of ada", "width" to 64), emptyList()),
                    ResolvedNode("img", mapOf("src" to "/logo.png", "alt" to "Logo"), emptyList()),
                ),
            ),
            page.resolve(Ctx("ada", 2)),
        )
    }

    @Test
    fun `children are typed by the parent's sealed hierarchy`() {
        val page = SectionElement.build<Unit> {
            link("/a")
            image("/b.png", "b")
        }
        val kinds = page.children.map {
            when (it) {
                is SectionChild.Section -> "section"
                is SectionChild.Link -> "link:${(it.element.href as Attribute.Value).value}"
                is SectionChild.Image -> "image"
            }
        }
        assertEquals(listOf("link:/a", "image"), kinds)
    }

    @Test
    fun `elements named like functions the generated code calls`() {
        val tree = CallsElement.build<Unit> {
            buildMap = 1
            apply {
                put = 2
                apply { put = 3 }
            }
            literal("text") { assertEquals("text", let) }
        }

        assertEquals(
            ResolvedNode(
                "calls",
                mapOf("buildMap" to 1),
                listOf(
                    ResolvedNode("apply", mapOf("put" to 2), listOf(ResolvedNode("apply", mapOf("put" to 3), emptyList()))),
                    ResolvedNode("literal", mapOf("let" to "text"), emptyList()),
                ),
            ),
            tree.resolve(Unit),
        )
    }
}
