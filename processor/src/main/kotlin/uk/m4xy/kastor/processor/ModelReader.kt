package uk.m4xy.kastor.processor

import com.google.devtools.ksp.getDeclaredProperties
import com.google.devtools.ksp.getVisibility
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.Visibility
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ksp.toClassName
import com.squareup.kotlinpoet.ksp.toTypeName

internal object Annotations {
    const val ATTRIBUTE_GROUP = "uk.m4xy.kastor.AttributeGroup"
    const val ELEMENT = "uk.m4xy.kastor.Element"
    const val ATTRIBUTE_NAME = "uk.m4xy.kastor.AttributeName"
    const val CHILDREN = "uk.m4xy.kastor.Children"
    const val REQUIRED = "uk.m4xy.kastor.Required"
}

/**
 * Property names that would collide with members of the generated `Node` implementation or with
 * the `block` parameter of generated functions.
 */
private val RESERVED_PROPERTY_NAMES = setOf("tagName", "attributes", "children", "block")

/**
 * Reads annotated declarations into models, reporting problems through [logger].
 *
 * Results are cached per declaration, so a declaration referenced from several places (a group
 * used by many elements, an element that is a child of many parents) is only read, and its errors
 * only reported, once. A `null` result means an error was reported.
 */
internal class ModelReader(private val logger: KSPLogger) {
    private val groups = mutableMapOf<String, GroupModel?>()
    private val elements = mutableMapOf<String, ElementInfo?>()

    fun group(declaration: KSClassDeclaration): GroupModel? =
        groups.getOrPut(declaration.key) { readGroup(declaration) }

    fun element(declaration: KSClassDeclaration): ElementInfo? =
        elements.getOrPut(declaration.key) { readElement(declaration) }

    fun elementModel(declaration: KSClassDeclaration): ElementModel? {
        val info = element(declaration) ?: return null
        val children = readChildren(info) ?: return null
        return ElementModel(info, children)
    }

    private fun readGroup(declaration: KSClassDeclaration): GroupModel? {
        if (!checkShape(declaration, "@AttributeGroup")) return null
        val visibility = visibility(declaration) ?: return null
        val superGroups = directGroups(declaration).map { group(it) ?: return null }
        val inherited = superGroups.flatMap { it.allAttributes }.distinctBy { it.propertyName }
        val own = ownAttributes(declaration, inherited) ?: return null
        val all = own + inherited
        if (!checkAttributes(declaration, all)) return null

        val baseName = declaration.baseName
        val interfaceName = (baseName.removeSuffix("Group").ifEmpty { baseName }) + "Attributes"
        return GroupModel(
            declaration = declaration,
            attributesInterface = ClassName(declaration.packageName.asString(), interfaceName),
            visibility = visibility,
            superGroups = superGroups.map { it.attributesInterface },
            ownAttributes = own,
            allAttributes = all,
            sources = listOf(declaration) + superGroups.flatMap { it.sources },
        )
    }

    private fun readElement(declaration: KSClassDeclaration): ElementInfo? {
        val annotation = declaration.annotation(Annotations.ELEMENT)
        if (annotation == null) {
            logger.error("${declaration.key} is not annotated with @Element", declaration)
            return null
        }
        if (!checkShape(declaration, "@Element")) return null
        val visibility = visibility(declaration) ?: return null

        val elementName = annotation.argument<String>("name")
        val function = annotation.argument<String>("function")
        if (elementName.isNullOrBlank() || function.isNullOrBlank()) {
            logger.error("@Element on ${declaration.key} needs a non-blank name and function", declaration)
            return null
        }

        val groups = directGroups(declaration).map { group(it) ?: return null }
        for (group in groups) {
            if (!checkExposure(declaration, visibility, group.declaration, group.visibility, "attribute group")) {
                return null
            }
        }
        val groupAttributes = groups.flatMap { it.allAttributes }.distinctBy { it.propertyName }
        val own = ownAttributes(declaration, groupAttributes) ?: return null
        if (!checkAttributes(declaration, own + groupAttributes)) return null

        return ElementInfo(
            declaration = declaration,
            names = ElementNames(declaration.packageName.asString(), declaration.baseName),
            visibility = visibility,
            elementName = elementName,
            function = function,
            groups = groups,
            ownAttributes = own,
            groupAttributes = groupAttributes,
        )
    }

    private fun readChildren(info: ElementInfo): List<ElementInfo>? {
        val declaration = info.declaration
        val kinds = declaration.annotation(Annotations.CHILDREN)?.let { annotation ->
            when (val value = annotation.arguments.firstOrNull()?.value) {
                is KSType -> listOf(value)
                is List<*> -> value.filterIsInstance<KSType>()
                else -> emptyList()
            }
        }.orEmpty()

        val children = mutableListOf<ElementInfo>()
        var ok = true
        for (kind in kinds.distinct()) {
            val childDeclaration = kind.declaration as? KSClassDeclaration
            if (childDeclaration == null || childDeclaration.annotation(Annotations.ELEMENT) == null) {
                logger.error(
                    "@Children of ${declaration.key} lists ${kind.declaration.key}, which is not an @Element interface",
                    declaration,
                )
                ok = false
                continue
            }
            val child = element(childDeclaration)
            if (child == null) {
                ok = false
                continue
            }
            if (!checkExposure(declaration, info.visibility, childDeclaration, child.visibility, "child element")) {
                ok = false
                continue
            }
            children += child
        }
        if (!ok) return null

        // The scope declares every child's functions next to the element's own attribute members, and
        // the child sealed interface nests one class per child, named after the child.
        for ((baseName, clashing) in children.groupBy { it.names.baseName }) {
            if (clashing.size > 1) {
                logger.error(
                    "Children of ${declaration.key} share the name $baseName: ${clashing.joinToString { it.declaration.key }}",
                    declaration,
                )
                ok = false
            }
        }
        for ((_, clashing) in children.groupBy { it.function to it.requiredAttributes.map { a -> a.type } }) {
            if (clashing.size > 1) {
                logger.error(
                    "Children of ${declaration.key} have identical '${clashing.first().function}' functions: " +
                        clashing.joinToString { it.declaration.key },
                    declaration,
                )
                ok = false
            }
        }
        val attributeNames = info.allAttributes.map { it.propertyName }.toSet()
        for (child in children) {
            if (child.function in attributeNames) {
                logger.error(
                    "Child function '${child.function}' of ${child.declaration.key} clashes with the attribute " +
                        "of the same name on ${declaration.key}",
                    declaration,
                )
                ok = false
            }
        }
        return children.takeIf { ok }
    }

    /** Attributes of [declaration] that don't come from [inherited] group attributes. */
    private fun ownAttributes(
        declaration: KSClassDeclaration,
        inherited: List<AttributeModel>,
    ): List<AttributeModel>? {
        val inheritedNames = inherited.map { it.propertyName }.toSet()
        val overrides = declaration.getDeclaredProperties().filter { it.simpleName.asString() in inheritedNames }.toList()
        for (property in overrides) {
            logger.error(
                "${declaration.key} redeclares attribute '${property.simpleName.asString()}' from an attribute group",
                property,
            )
        }
        if (overrides.isNotEmpty()) return null

        val attributes = declaration.getAllProperties()
            .filter { it.simpleName.asString() !in inheritedNames }
            .map { attribute(it) }
            .toList()
        return attributes.takeIf { it.all { attribute -> attribute != null } }?.filterNotNull()
    }

    private fun attribute(property: KSPropertyDeclaration): AttributeModel? {
        val name = property.simpleName.asString()
        if (property.extensionReceiver != null) {
            logger.error("Attribute '$name' can't be an extension property", property)
            return null
        }
        val attributeName = property.annotation(Annotations.ATTRIBUTE_NAME)?.let { it.argument<String>("value") }
        if (attributeName != null && attributeName.isBlank()) {
            logger.error("@AttributeName on '$name' must not be blank", property)
            return null
        }
        return AttributeModel(
            propertyName = name,
            attributeName = attributeName ?: name,
            type = property.type.toTypeName(),
            required = property.annotation(Annotations.REQUIRED) != null,
        )
    }

    private fun checkAttributes(declaration: KSClassDeclaration, attributes: List<AttributeModel>): Boolean {
        var ok = true
        for (attribute in attributes) {
            if (attribute.propertyName in RESERVED_PROPERTY_NAMES) {
                logger.error(
                    "Attribute property '${attribute.propertyName}' on ${declaration.key} is reserved; rename the " +
                        "property and keep the attribute name with @AttributeName(\"${attribute.attributeName}\")",
                    declaration,
                )
                ok = false
            }
        }
        for ((name, clashing) in attributes.groupBy { it.attributeName }) {
            if (clashing.size > 1) {
                logger.error(
                    "${declaration.key} has several attributes named '$name': " +
                        clashing.joinToString { it.propertyName },
                    declaration,
                )
                ok = false
            }
        }
        return ok
    }

    private fun checkShape(declaration: KSClassDeclaration, annotation: String): Boolean {
        if (declaration.classKind != ClassKind.INTERFACE) {
            logger.error("$annotation can only be applied to interfaces, but ${declaration.key} is not one", declaration)
            return false
        }
        if (declaration.typeParameters.isNotEmpty()) {
            logger.error("$annotation interface ${declaration.key} can't have type parameters", declaration)
            return false
        }
        return true
    }

    private fun visibility(declaration: KSClassDeclaration): KModifier? = when (declaration.getVisibility()) {
        Visibility.PUBLIC -> KModifier.PUBLIC
        Visibility.INTERNAL -> KModifier.INTERNAL
        else -> {
            logger.error("${declaration.key} must be public or internal", declaration)
            null
        }
    }

    /** A public element's generated types can't extend or mention another element's internal ones. */
    private fun checkExposure(
        declaration: KSClassDeclaration,
        visibility: KModifier,
        referenced: KSClassDeclaration,
        referencedVisibility: KModifier,
        what: String,
    ): Boolean {
        if (visibility == KModifier.PUBLIC && referencedVisibility == KModifier.INTERNAL) {
            logger.error("Public ${declaration.key} uses internal $what ${referenced.key}", declaration)
            return false
        }
        return true
    }

    private fun directGroups(declaration: KSClassDeclaration): List<KSClassDeclaration> =
        declaration.superTypes
            .mapNotNull { it.resolve().declaration as? KSClassDeclaration }
            .filter { it.annotation(Annotations.ATTRIBUTE_GROUP) != null }
            .toList()
}

internal fun KSAnnotated.annotation(qualifiedName: String): KSAnnotation? {
    val shortName = qualifiedName.substringAfterLast('.')
    return annotations.firstOrNull {
        it.shortName.asString() == shortName &&
            it.annotationType.resolve().declaration.qualifiedName?.asString() == qualifiedName
    }
}

@Suppress("UNCHECKED_CAST")
private fun <T> KSAnnotation.argument(name: String): T? =
    arguments.firstOrNull { it.name?.asString() == name }?.value as T?

private val KSDeclaration.key: String
    get() = qualifiedName?.asString() ?: simpleName.asString()

/** Nested interfaces get their enclosing names prepended, so `Html.Body` generates `HtmlBodyScope`. */
private val KSClassDeclaration.baseName: String
    get() = toClassName().simpleNames.joinToString("")
