package uk.m4xy.kastor

/** Restricts implicit receivers so an inner block can't silently call an outer element's DSL. */
@DslMarker
public annotation class KastorDsl

/** Supertype of every generated element scope. */
@KastorDsl
public interface Tag<out C>

/** Supertype of every generated attribute-group interface. */
public interface Attributes

/**
 * Passed in place of a required attribute to say that the block will set it.
 * [build][Node] fails with [MissingAttributeException] if the block never does.
 */
public object Deferred

/** An attribute's value: either known now, or computed from the context `C` on resolution. */
public sealed interface Attribute<in C, out T> {
    public fun resolve(context: C): T

    public data class Value<out T>(val value: T) : Attribute<Any?, T> {
        override fun resolve(context: Any?): T = value
    }

    public class Factory<in C, out T>(public val factory: (C) -> T) : Attribute<C, T> {
        override fun resolve(context: C): T = factory(context)
        override fun toString(): String = "Factory($factory)"
    }
}

/** A built element. Each `@Element` gets a generated data class implementing this. */
public interface Node<C> {
    public val tagName: String

    /** The attributes that were set, keyed by attribute name, in declaration order. */
    public val attributes: Map<String, Attribute<C, *>>
    public val children: List<Child<C>>
}

/** A child slot of a node. Each element gets a generated sealed hierarchy of these. */
public interface Child<C> {
    public val element: Node<C>
}

/** A node with all factories applied to a context. */
public data class ResolvedNode(
    val name: String,
    val attributes: Map<String, Any?>,
    val children: List<ResolvedNode>,
)

public fun <C> Node<C>.resolve(context: C): ResolvedNode = ResolvedNode(
    name = tagName,
    attributes = attributes.mapValues { (_, attribute) -> attribute.resolve(context) },
    children = children.map { it.element.resolve(context) },
)

public class MissingAttributeException(public val element: String, public val attribute: String) :
    IllegalStateException("Required attribute '$attribute' of element '$element' was never set")

public class DeferredAttributeException(public val element: String, public val attribute: String) :
    IllegalStateException(
        "Attribute '$attribute' of element '$element' is produced by a factory and has no value until resolved",
    )
