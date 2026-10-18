package uk.m4xy.kastor

// Helpers called by generated builders. Public because generated code lives in the user's module.

/** The current value of an attribute slot, for a scope property getter. */
public fun <T> Attribute<*, T>.literal(element: String, attribute: String): T = when (this) {
    is Attribute.Value -> value
    is Attribute.Factory -> throw DeferredAttributeException(element, attribute)
}

/** A required slot's attribute, failing if it was deferred and never set. */
public fun <C, T> Attribute<C, T>?.required(element: String, attribute: String): Attribute<C, T> =
    this ?: throw MissingAttributeException(element, attribute)
