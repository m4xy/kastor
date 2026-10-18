package uk.m4xy.kastor.processor

import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.LIST
import com.squareup.kotlinpoet.LambdaTypeName
import com.squareup.kotlinpoet.MAP
import com.squareup.kotlinpoet.MUTABLE_LIST
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.STAR
import com.squareup.kotlinpoet.STRING
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.TypeVariableName
import com.squareup.kotlinpoet.UNIT
import com.squareup.kotlinpoet.joinToCode

private object Runtime {
    private const val PACKAGE = "uk.m4xy.kastor"
    val TAG = ClassName(PACKAGE, "Tag")
    val ATTRIBUTES = ClassName(PACKAGE, "Attributes")
    val ATTRIBUTE = ClassName(PACKAGE, "Attribute")
    val VALUE = ATTRIBUTE.nestedClass("Value")
    val FACTORY = ATTRIBUTE.nestedClass("Factory")
    val DEFERRED = ClassName(PACKAGE, "Deferred")
    val NODE = ClassName(PACKAGE, "Node")
    val CHILD = ClassName(PACKAGE, "Child")
    val BUILDER = ClassName(PACKAGE, "Builder")
    val BUILD_ELEMENT = MemberName(PACKAGE, "buildElement")
    val LITERAL = MemberName(PACKAGE, "literal")
    val LITERAL_OR_NULL = MemberName(PACKAGE, "literalOrNull")
    val REQUIRED = MemberName(PACKAGE, "required")
}

// Standard library functions are referenced as members too, so KotlinPoet qualifies them where a
// generated member has the same name. Extension functions are avoided: KotlinPoet can't qualify those.
private object Stdlib {
    val BUILD_MAP = MemberName("kotlin.collections", "buildMap")
    val EMPTY_LIST = MemberName("kotlin.collections", "emptyList")
    val MUTABLE_LIST_OF = MemberName("kotlin.collections", "mutableListOf")
}

private val C = TypeVariableName("C")
private val OUT_C = TypeVariableName("C", KModifier.OUT)

/**
 * How a parent function receives an element's required attributes. One overload is generated per
 * shape rather than one per combination, so `n` required attributes give three overloads, not 3ⁿ.
 */
private enum class Shape { VALUES, FACTORIES, DEFERRED }

private fun suppressions() = AnnotationSpec.builder(Suppress::class)
    .useSiteTarget(AnnotationSpec.UseSiteTarget.FILE)
    .addMember("%S, %S, %S", "UNUSED_PARAMETER", "RedundantVisibilityModifier", "unused")
    .build()

private fun ClassName.of(type: TypeName) = parameterizedBy(type)
private fun attributeOf(attribute: AttributeModel) = Runtime.ATTRIBUTE.parameterizedBy(C, attribute.type)
private fun factoryOf(attribute: AttributeModel) = LambdaTypeName.get(parameters = arrayOf(C), returnType = attribute.type)
private fun slotName(attribute: AttributeModel) = "_${attribute.propertyName}"

// ---------------------------------------------------------------------------------------------
// Attribute groups
// ---------------------------------------------------------------------------------------------

internal fun generateGroup(group: GroupModel): FileSpec {
    val type = TypeSpec.interfaceBuilder(group.attributesInterface)
        .addModifiers(group.visibility)
        .addTypeVariable(OUT_C)
        .addSuperinterface(Runtime.ATTRIBUTES)
        .apply { group.superGroups.forEach { addSuperinterface(it.of(C)) } }
        .apply { group.ownAttributes.forEach { addAttributeMembers(it, abstract = true) } }
        .build()
    return FileSpec.builder(group.attributesInterface)
        .addAnnotation(suppressions())
        .addType(type)
        .build()
}

/** The scope property and factory function for [attribute]; implemented by builders. */
private fun TypeSpec.Builder.addAttributeMembers(
    attribute: AttributeModel,
    abstract: Boolean,
    elementName: CodeBlock? = null,
) {
    val property = PropertySpec.builder(attribute.propertyName, attribute.scopeType).mutable()
    val factory = FunSpec.builder(attribute.propertyName).addParameter("factory", factoryOf(attribute))
    if (abstract) {
        factory.addModifiers(KModifier.ABSTRACT)
        factory.addKdoc("Sets [%N] to a value computed from the context when the tree is resolved.", attribute.propertyName)
    } else {
        val slot = slotName(attribute)
        val literal = if (attribute.required) Runtime.LITERAL else Runtime.LITERAL_OR_NULL
        val getter = CodeBlock.of("return %M(%N, %L, %S)", literal, slot, elementName, attribute.propertyName)
        val setter = if (attribute.required) {
            CodeBlock.of("%N = %T(value)", slot, Runtime.VALUE)
        } else {
            CodeBlock.of("%N = if (value == null) null else %T(value)", slot, Runtime.VALUE)
        }
        property.addModifiers(KModifier.OVERRIDE)
            .getter(FunSpec.getterBuilder().addStatement("%L", getter).build())
            .setter(FunSpec.setterBuilder().addParameter("value", attribute.scopeType).addStatement("%L", setter).build())
        factory.addModifiers(KModifier.OVERRIDE).addStatement("%N = %T(factory)", slot, Runtime.FACTORY)
    }
    addProperty(property.build())
    addFunction(factory.build())
}

// ---------------------------------------------------------------------------------------------
// Elements
// ---------------------------------------------------------------------------------------------

internal fun generateElement(model: ElementModel): FileSpec {
    val info = model.info
    return FileSpec.builder(info.names.packageName, "${info.names.baseName}Dsl")
        .addAnnotation(suppressions())
        .addType(scope(model))
        .addType(parent(info))
        .addType(child(model))
        .addType(element(info))
        .addType(builder(model))
        .build()
}

private fun scope(model: ElementModel): TypeSpec {
    val info = model.info
    return TypeSpec.interfaceBuilder(info.names.scope)
        .addModifiers(info.visibility)
        .addKdoc("Receiver of the block that configures a `<%L>` element.", info.elementName)
        .addTypeVariable(OUT_C)
        .addSuperinterface(Runtime.TAG.of(C))
        .apply { model.children.forEach { addSuperinterface(it.names.parent.of(C)) } }
        .apply { info.groups.forEach { addSuperinterface(it.attributesInterface.of(C)) } }
        .apply { info.ownAttributes.forEach { addAttributeMembers(it, abstract = true) } }
        .build()
}

private fun parent(info: ElementInfo): TypeSpec =
    TypeSpec.interfaceBuilder(info.names.parent)
        .addModifiers(info.visibility)
        .addKdoc("Scope that can contain `<%L>` elements.", info.elementName)
        .addTypeVariable(OUT_C)
        .apply {
            for (shape in shapes(info)) {
                addFunction(
                    FunSpec.builder(info.function)
                        .addModifiers(KModifier.ABSTRACT)
                        .addParameters(requiredParameters(info, shape))
                        .addParameter(blockParameter(info, withDefault = true))
                        .build(),
                )
            }
        }
        .build()

private fun child(model: ElementModel): TypeSpec {
    val info = model.info
    return TypeSpec.interfaceBuilder(info.names.child)
        .addModifiers(info.visibility, KModifier.SEALED)
        .addKdoc("The elements a `<%L>` element can contain.", info.elementName)
        .addTypeVariable(C)
        .addSuperinterface(Runtime.CHILD.of(C))
        .apply {
            for (child in model.children) {
                addType(
                    TypeSpec.classBuilder(child.names.baseName)
                        .addModifiers(KModifier.DATA)
                        .addTypeVariable(C)
                        .addSuperinterface(info.names.child.of(C))
                        .primaryConstructor(
                            FunSpec.constructorBuilder().addParameter("element", child.names.element.of(C)).build(),
                        )
                        .addProperty(
                            PropertySpec.builder("element", child.names.element.of(C), KModifier.OVERRIDE)
                                .initializer("element")
                                .build(),
                        )
                        .build(),
                )
            }
        }
        .build()
}

private fun element(info: ElementInfo): TypeSpec {
    val names = info.names
    val childList = LIST.of(names.child.of(C))
    val constructor = FunSpec.constructorBuilder()
    val properties = mutableListOf<PropertySpec>()
    for (attribute in info.allAttributes) {
        val type = attributeOf(attribute).copy(nullable = !attribute.required)
        constructor.addParameter(
            ParameterSpec.builder(attribute.propertyName, type)
                .apply { if (!attribute.required) defaultValue("null") }
                .build(),
        )
        properties += PropertySpec.builder(attribute.propertyName, type).initializer(attribute.propertyName).build()
    }
    constructor.addParameter(ParameterSpec.builder("children", childList).defaultValue("%M()", Stdlib.EMPTY_LIST).build())
    properties += PropertySpec.builder("children", childList, KModifier.OVERRIDE).initializer("children").build()

    val attributeMap = CodeBlock.builder().beginControlFlow("return %M", Stdlib.BUILD_MAP)
    for (attribute in info.allAttributes) {
        if (attribute.required) {
            attributeMap.addStatement("put(%S, %N)", attribute.attributeName, attribute.propertyName)
        } else {
            attributeMap.addStatement("if (%N != null) put(%S, %N)", attribute.propertyName, attribute.attributeName, attribute.propertyName)
        }
    }
    attributeMap.endControlFlow()

    val companion = TypeSpec.companionObjectBuilder()
        .addProperty(PropertySpec.builder("NAME", STRING, KModifier.CONST).initializer("%S", info.elementName).build())
        .apply {
            for (shape in shapes(info)) {
                addFunction(
                    FunSpec.builder("build")
                        .addKdoc("Builds a standalone `<%L>` element, e.g. the root of a tree.", info.elementName)
                        .addTypeVariable(C)
                        .addParameters(requiredParameters(info, shape))
                        .addParameter(blockParameter(info, withDefault = true))
                        .returns(names.element.of(C))
                        .addStatement("return %L", buildExpression(info, shape))
                        .build(),
                )
            }
        }
        .build()

    return TypeSpec.classBuilder(names.element)
        .addModifiers(info.visibility, KModifier.DATA)
        .addKdoc("A built `<%L>` element.", info.elementName)
        .addTypeVariable(C)
        .addSuperinterface(Runtime.NODE.of(C))
        .primaryConstructor(constructor.build())
        .addProperties(properties)
        .addProperty(
            PropertySpec.builder("tagName", STRING, KModifier.OVERRIDE)
                .getter(FunSpec.getterBuilder().addStatement("return %T.NAME", names.element).build())
                .build(),
        )
        .addProperty(
            PropertySpec.builder("attributes", MAP.parameterizedBy(STRING, Runtime.ATTRIBUTE.parameterizedBy(C, STAR)))
                .addModifiers(KModifier.OVERRIDE)
                .getter(FunSpec.getterBuilder().addCode(attributeMap.build()).build())
                .build(),
        )
        .addType(companion)
        .build()
}

private fun builder(model: ElementModel): TypeSpec {
    val info = model.info
    val names = info.names
    val elementName = CodeBlock.of("%T.NAME", names.element)

    val constructor = FunSpec.constructorBuilder()
    val slots = info.allAttributes.map { attribute ->
        val type = attributeOf(attribute).copy(nullable = true)
        val slot = PropertySpec.builder(slotName(attribute), type, KModifier.PRIVATE).mutable()
        if (attribute.required) {
            constructor.addParameter(attribute.propertyName, type)
            slot.initializer("%N", attribute.propertyName)
        } else {
            slot.initializer("null")
        }
        slot.build()
    }
    val children = PropertySpec.builder("_children", MUTABLE_LIST.of(names.child.of(C)), KModifier.PRIVATE)
        .initializer("%M()", Stdlib.MUTABLE_LIST_OF)
        .build()

    val arguments = CodeBlock.builder()
    for (attribute in info.allAttributes) {
        if (attribute.required) {
            arguments.addStatement(
                "%N = %M(%N, %L, %S),",
                attribute.propertyName, Runtime.REQUIRED, slotName(attribute), elementName, attribute.propertyName,
            )
        } else {
            arguments.addStatement("%N = %N,", attribute.propertyName, slotName(attribute))
        }
    }
    arguments.addStatement("children = _children.toList(),")
    val build = FunSpec.builder("build")
        .addModifiers(KModifier.OVERRIDE)
        .returns(names.element.of(C))
        .addCode("return %T(\n⇥%L⇤)\n", names.element, arguments.build())
        .build()

    return TypeSpec.classBuilder(names.builder)
        .addModifiers(info.visibility)
        .addKdoc(
            "Builds a `<%L>` element. A `null` required attribute is [deferred][%T] and must be set by the block.",
            info.elementName, Runtime.DEFERRED,
        )
        .addTypeVariable(C)
        .addSuperinterface(names.scope.of(C))
        .addSuperinterface(Runtime.BUILDER.of(names.element.of(C)))
        .primaryConstructor(constructor.build())
        .addProperties(slots)
        .addProperty(children)
        .apply { info.allAttributes.forEach { addAttributeMembers(it, abstract = false, elementName = elementName) } }
        .apply {
            for (child in model.children) {
                for (shape in shapes(child)) {
                    addFunction(
                        FunSpec.builder(child.function)
                            .addModifiers(KModifier.OVERRIDE)
                            .addParameters(requiredParameters(child, shape))
                            .addParameter(blockParameter(child, withDefault = false))
                            .addStatement(
                                "_children += %T(%L)",
                                names.child.nestedClass(child.names.baseName),
                                buildExpression(child, shape),
                            )
                            .build(),
                    )
                }
            }
        }
        .addFunction(build)
        .build()
}

private fun shapes(info: ElementInfo): List<Shape?> =
    if (info.requiredAttributes.isEmpty()) listOf(null) else Shape.entries

private fun requiredParameters(info: ElementInfo, shape: Shape?): List<ParameterSpec> =
    info.requiredAttributes.map { attribute ->
        val type = when (shape) {
            Shape.VALUES, null -> attribute.type
            Shape.FACTORIES -> factoryOf(attribute)
            Shape.DEFERRED -> Runtime.DEFERRED
        }
        ParameterSpec.builder(attribute.propertyName, type).build()
    }

private fun blockParameter(info: ElementInfo, withDefault: Boolean): ParameterSpec =
    ParameterSpec.builder("block", LambdaTypeName.get(receiver = info.names.scope.of(C), returnType = UNIT))
        .apply { if (withDefault) defaultValue("{}") }
        .build()

/** `buildElement(FooBuilder<C>(<required attribute slots>), block)` for the given parameter [shape]. */
private fun buildExpression(info: ElementInfo, shape: Shape?): CodeBlock {
    val arguments = info.requiredAttributes.map { attribute ->
        when (shape) {
            Shape.VALUES, null -> CodeBlock.of("%T(%N)", Runtime.VALUE, attribute.propertyName)
            Shape.FACTORIES -> CodeBlock.of("%T(%N)", Runtime.FACTORY, attribute.propertyName)
            Shape.DEFERRED -> CodeBlock.of("null")
        }
    }
    return CodeBlock.of("%M(%T<%T>(%L), block)", Runtime.BUILD_ELEMENT, info.names.builder, C, arguments.joinToCode())
}
