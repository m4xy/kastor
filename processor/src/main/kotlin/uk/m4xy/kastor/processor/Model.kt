package uk.m4xy.kastor.processor

import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.TypeName

internal data class AttributeModel(
    /** Kotlin property name in the generated scope, builder and element. */
    val propertyName: String,
    /** Key the attribute is stored under in `Node.attributes`. */
    val attributeName: String,
    /** The declared type, nullability included. */
    val type: TypeName,
    val required: Boolean,
) {
    /** The type of the scope property: optional attributes can always be cleared with `null`. */
    val scopeType: TypeName get() = if (required) type else type.copy(nullable = true)
}

internal data class GroupModel(
    val declaration: KSClassDeclaration,
    val attributesInterface: ClassName,
    val visibility: KModifier,
    /** Attribute interfaces of the groups this group directly extends. */
    val superGroups: List<ClassName>,
    /** Attributes declared by this group and not by a super group. */
    val ownAttributes: List<AttributeModel>,
    /** Every attribute in this group, super groups included. */
    val allAttributes: List<AttributeModel>,
    /** Source declarations of this group and all its super groups. */
    val sources: List<KSClassDeclaration>,
)

/** Generated names for an element, derived from its source interface. */
internal data class ElementNames(val packageName: String, val baseName: String) {
    val scope = ClassName(packageName, "${baseName}Scope")
    val parent = ClassName(packageName, "${baseName}Parent")
    val child = ClassName(packageName, "${baseName}Child")
    val element = ClassName(packageName, "${baseName}Element")
    val builder = ClassName(packageName, "${baseName}Builder")
}

/** Everything about an element except its children, which is all a parent needs to know about it. */
internal data class ElementInfo(
    val declaration: KSClassDeclaration,
    val names: ElementNames,
    val visibility: KModifier,
    val elementName: String,
    val function: String,
    /** Groups the element's interface directly extends. */
    val groups: List<GroupModel>,
    val ownAttributes: List<AttributeModel>,
    /** Attributes inherited from groups, transitively. */
    val groupAttributes: List<AttributeModel>,
) {
    val allAttributes: List<AttributeModel> get() = ownAttributes + groupAttributes
    val requiredAttributes: List<AttributeModel> get() = allAttributes.filter { it.required }
}

internal data class ElementModel(
    val info: ElementInfo,
    val children: List<ElementInfo>,
)
