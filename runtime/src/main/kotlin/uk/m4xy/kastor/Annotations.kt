package uk.m4xy.kastor

import kotlin.reflect.KClass

/**
 * Marks an interface whose properties form a reusable group of attributes.
 *
 * For `@AttributeGroup interface ExampleGroup` the processor generates `ExampleAttributes<out C>`,
 * which every element scope that inherits the group extends.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
@MustBeDocumented
public annotation class AttributeGroup

/**
 * Marks an interface as an element of the tree.
 *
 * @param name the element's name in the model (e.g. the tag name).
 * @param function the name of the DSL function that adds this element to a parent.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
@MustBeDocumented
public annotation class Element(val name: String, val function: String)

/** Overrides the name an attribute is stored under, which defaults to the property name. */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.BINARY)
@MustBeDocumented
public annotation class AttributeName(val value: String)

/** Lists the [Element] interfaces that may appear as children of the annotated element. */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
@MustBeDocumented
public annotation class Children(vararg val kinds: KClass<*>)

/** Marks an attribute that must be supplied when the element is created. */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.BINARY)
@MustBeDocumented
public annotation class Required
