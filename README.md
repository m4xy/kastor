# Kastor

Kastor is a Kotlin Symbol Processing (KSP) processor that turns an annotated description of an abstract syntax tree into a type-safe Kotlin DSL for building it.

You describe your grammar as annotated interfaces:

```kotlin
@AttributeGroup
interface ExampleGroup {
    val x: X
    val y: Y
}

@Element("foo", function = "foo")
interface Foo : ExampleGroup

@Element("test", function = "bar")
@Children(Foo::class)
interface Test : ExampleGroup {
    @Required val someAttribute: AValue
}
```

Kastor generates scopes, parent interfaces, a sealed hierarchy for children, immutable element classes and builders, so you can write:

```kotlin
val tree = TestElement.build<Ctx>(AValue("root")) {
    x { ctx -> X(ctx.scale) }   // computed from a context when the tree is resolved
    foo { y = Y(2) }
}

val resolved = tree.resolve(Ctx(scale = 3))
```

## Annotations

| Annotation | Target | Meaning |
| --- | --- | --- |
| `@AttributeGroup` | interface | A reusable set of attributes. Generates `<Name>Attributes<C>`, dropping a `Group` suffix from the name. |
| `@Element(name, function)` | interface | An element of the tree. `name` is the element's name in the model; `function` is the name of the DSL function that adds it to a parent. |
| `@Children(vararg kinds)` | element | The `@Element` interfaces that may appear inside this element. |
| `@Required` | property | The attribute must be supplied, as a value, a factory, or `Deferred`. |
| `@AttributeName(value)` | property | The name the attribute is stored under, if it differs from the property name. |

## What gets generated

For `@Element` `Test`:

- `TestScope<out C>`: the block receiver. It has a nullable `var` per optional attribute, a non-null `var` per required one, and a `name(factory: (C) -> T)` function for every attribute.
- `TestParent<out C>`: the `bar(...)` functions that add a `Test` to a parent. Required attributes are passed all as values, all as factories `(C) -> T`, or all as `Deferred`, meaning the block sets them.
- `TestChild<C>`: a sealed interface with one data class per allowed child.
- `TestElement<C>`: an immutable data class implementing `Node<C>`, with `TestElement.build(...)` to start a tree.
- `TestBuilder<C>`: implements the scope and builds the element.

`Node.resolve(context)` applies every factory and returns a plain `ResolvedNode` tree.

## Modules

- `runtime`: the annotations and the base types that generated code uses.
- `processor`: the KSP processor, written with KotlinPoet.
- `sample`: example models with tests of the generated DSL.

## Using it

```kotlin
plugins {
    id("com.google.devtools.ksp") version "2.3.12"
}

dependencies {
    implementation("uk.m4xy.kastor:runtime:0.1.0-SNAPSHOT")
    ksp("uk.m4xy.kastor:processor:0.1.0-SNAPSHOT")
}
```

Kastor isn't published yet, so for now use it from a Gradle included build (`includeBuild("../kastor")` in `settings.gradle.kts`).

## Building

```sh
./gradlew build
```

This needs JDK 17 or newer. It uses Kotlin 2.4.20, KSP 2.3.12 and KotlinPoet 2.4.0.
