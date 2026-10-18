package uk.m4xy.kastor

// Helpers called by generated builders. Public because generated code lives in the user's module.
//
// Generated code reaches these, and the standard library, only through calls that a member of a
// builder can't hijack: element functions and attributes are named by the user, and a member
// called `apply` or `literal` would otherwise win over the function the generated code means.

/** Implemented by every generated builder. */
public interface Builder<out E> {
    public fun build(): E
}

/** Runs [block] on [builder], then builds the element. */
public fun <B : Builder<E>, E> buildElement(builder: B, block: B.() -> Unit): E {
    builder.block()
    return builder.build()
}

/** The current value of an optional attribute slot, for a scope property getter. */
public fun <T> literalOrNull(attribute: Attribute<*, T>?, element: String, name: String): T? =
    attribute?.let { literal(it, element, name) }

/** The current value of a required attribute slot, for a scope property getter. */
public fun <T> literal(attribute: Attribute<*, T>?, element: String, name: String): T =
    when (val slot = required(attribute, element, name)) {
        is Attribute.Value -> slot.value
        is Attribute.Factory -> throw DeferredAttributeException(element, name)
    }

/** A required slot's attribute, failing if it was deferred and never set. */
public fun <C, T> required(attribute: Attribute<C, T>?, element: String, name: String): Attribute<C, T> =
    attribute ?: throw MissingAttributeException(element, name)
