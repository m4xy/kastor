package uk.m4xy.kastor.processor

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.validate
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.ksp.writeTo

internal class DslProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
) : SymbolProcessor {
    override fun process(resolver: Resolver): List<KSAnnotated> {
        val reader = ModelReader(logger)
        val deferred = mutableListOf<KSAnnotated>()

        fun eachDeclaration(annotation: String, generate: (KSClassDeclaration) -> Unit) {
            for (symbol in resolver.getSymbolsWithAnnotation(annotation)) {
                when {
                    !symbol.validate() -> deferred += symbol
                    symbol is KSClassDeclaration -> generate(symbol)
                    else -> logger.error("@${annotation.substringAfterLast('.')} can only be applied to interfaces", symbol)
                }
            }
        }

        eachDeclaration(Annotations.ATTRIBUTE_GROUP) { declaration ->
            val group = reader.group(declaration) ?: return@eachDeclaration
            write(generateGroup(group), group.sources)
        }
        eachDeclaration(Annotations.ELEMENT) { declaration ->
            val model = reader.elementModel(declaration) ?: return@eachDeclaration
            // A builder implements its children's parent functions, so it depends on their declarations too.
            val sources = (listOf(model.info) + model.children).flatMap { info ->
                listOf(info.declaration) + info.groups.flatMap { it.sources }
            }
            write(generateElement(model), sources)
        }
        return deferred
    }

    private fun write(file: FileSpec, sources: List<KSClassDeclaration>) {
        val files = sources.mapNotNull { it.containingFile }.distinct()
        file.writeTo(codeGenerator, Dependencies(aggregating = false, *files.toTypedArray()))
    }
}
