package uk.m4xy.kastor.sample

import uk.m4xy.kastor.AttributeGroup
import uk.m4xy.kastor.AttributeName
import uk.m4xy.kastor.Children
import uk.m4xy.kastor.Element
import uk.m4xy.kastor.Required

// A slightly bigger model: groups extending groups, renamed attributes, several required
// attributes, and an element that can contain itself.

@AttributeGroup
interface CoreGroup {
    val id: String
    @AttributeName("class") val className: String
}

@AttributeGroup
interface StyledGroup : CoreGroup {
    val style: String
}

@Element("section", function = "section")
@Children(Section::class, Link::class, Image::class)
interface Section : StyledGroup {
    val title: String
}

@Element("a", function = "link")
interface Link : CoreGroup {
    @Required val href: String
    val target: String?
}

@Element("img", function = "image")
interface Image : StyledGroup {
    @Required val src: String
    @Required @AttributeName("alt") val description: String
    val width: Int
}
