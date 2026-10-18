package uk.m4xy.kastor.sample

import uk.m4xy.kastor.Children
import uk.m4xy.kastor.Element
import uk.m4xy.kastor.Required

// Elements and attributes named like the functions generated code calls, which must still work.

@Element("apply", function = "apply")
@Children(Apply::class)
interface Apply {
    val put: Int
}

@Element("literal", function = "literal")
interface Literal {
    @Required val let: String
}

@Element("calls", function = "calls")
@Children(Apply::class, Literal::class)
interface Calls {
    val buildMap: Int
}
