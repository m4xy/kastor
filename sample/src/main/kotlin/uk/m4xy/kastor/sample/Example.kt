package uk.m4xy.kastor.sample

import uk.m4xy.kastor.AttributeGroup
import uk.m4xy.kastor.AttributeName
import uk.m4xy.kastor.Children
import uk.m4xy.kastor.Element
import uk.m4xy.kastor.Required

data class AValue(val text: String)
data class X(val value: Int)
data class Y(val value: Int)

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
