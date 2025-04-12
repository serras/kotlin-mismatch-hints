package com.serranofp.kotlin.mismatch.hints

import com.intellij.codeInsight.hints.declarative.HintFormat
import com.intellij.codeInsight.hints.declarative.InlayActionData
import com.intellij.codeInsight.hints.declarative.InlayHintsCollector
import com.intellij.codeInsight.hints.declarative.InlayHintsProvider
import com.intellij.codeInsight.hints.declarative.InlayTreeSink
import com.intellij.codeInsight.hints.declarative.InlineInlayPosition
import com.intellij.codeInsight.hints.declarative.PresentationTreeBuilder
import com.intellij.codeInsight.hints.declarative.SharedBypassCollector
import com.intellij.codeInsight.hints.declarative.StringInlayActionPayload
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.components.KaDiagnosticCheckerFilter
import org.jetbrains.kotlin.analysis.api.resolution.KaFunctionCall
import org.jetbrains.kotlin.analysis.api.types.KaCapturedType
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.analysis.api.types.KaDefinitelyNotNullType
import org.jetbrains.kotlin.analysis.api.types.KaFlexibleType
import org.jetbrains.kotlin.analysis.api.types.KaFunctionType
import org.jetbrains.kotlin.analysis.api.types.KaIntersectionType
import org.jetbrains.kotlin.analysis.api.types.KaStarTypeProjection
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.analysis.api.types.KaTypeArgumentWithVariance
import org.jetbrains.kotlin.analysis.api.types.KaTypeProjection
import org.jetbrains.kotlin.idea.codeInsight.hints.KotlinFqnDeclarativeInlayActionHandler
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.psiUtil.endOffset
import org.jetbrains.kotlin.types.Variance

@OptIn(KaExperimentalApi::class)
class MismatchInlayHintProvider : InlayHintsProvider {
    companion object {
        const val PROVIDER_ID : String = "kotlin.mismatch"
    }

    override fun createCollector(file: PsiFile, editor: Editor): InlayHintsCollector? = Collector()

    private class Collector : SharedBypassCollector {
        override fun collectFromElement(element: PsiElement, sink: InlayTreeSink) {
            if (element !is KtElement) return
            analyze(element) {
                for (diagnostic in element.diagnostics(KaDiagnosticCheckerFilter.ONLY_COMMON_CHECKERS)) {
                    if (diagnostic.textRanges.isEmpty()) continue
                    val problem = asProblem(diagnostic) ?: continue
                    problemHint(problem, element, diagnostic.textRanges, sink)
                }
            }
        }

        fun KaSession.problemHint(problem: Problem, element: KtElement, ranges: Collection<TextRange>, sink: InlayTreeSink) = when (problem) {
            is NoneApplicable -> noneApplicableHint(element, sink)

            is ExpectedActualTypeMismatch if problem.dueToNullability -> sink.hintAfter(ranges) {
                text(": ")
                text(renderNullability(problem.actualType))
                text(" ⇏ ")
                text(renderNullability(problem.expectedType))
            }

            is ExpectedActualTypeMismatch -> sink.hintAfter(ranges) {
                text(": ")
                val actualType = chooseBetterActualType(element, problem.actualType)
                val expectedType = problem.expectedType
                val renderQualified = actualType.renderShort() == expectedType.renderShort()
                type(actualType, renderQualified)
                text(" ⇏ ")
                type(expectedType, renderQualified)
            }

            is TypeMismatch -> sink.hintAfter(ranges) {
                text(": ")
                val renderQualified = problem.typeA.renderShort() == problem.typeB.renderShort()
                type(problem.typeA, renderQualified)
                text(" ≠ ")
                type(problem.typeB, renderQualified)
            }

            is TypeVarianceMismatch -> sink.hintBefore(ranges) {
                text(problem.expectedVariance.label)
                text(" ≠ ")
                text(problem.actualVariance.label)
            }

            is AmbiguousType -> sink.hintAfter(ranges) {
                text("<")
                val shortCandidateNames = problem.candidates.map { it.renderShort() }
                problem.candidates.withSeparator(
                    separator = { text(" or ") }
                ) { candidate ->
                    val candidateShortName = candidate.renderShort()
                    val renderQualified = shortCandidateNames.filter { it == candidateShortName }.size > 1
                    type(candidate, renderQualified)
                }
                text(">")
            }

            else -> {}
        }

        private val NONE_APPLICABLE_MAX_OVERLOADS = 4
        private val BOLDFACE_NUMBERS = listOf(
            "\uD835\uDFD9", "\uD835\uDFDA", "\uD835\uDFDB", "\uD835\uDFDC"
        )

        fun KaSession.noneApplicableHint(element: KtElement, sink: InlayTreeSink) {
            val bestCandidates =
                element.resolveToCallCandidates().filter { it.isInBestCandidates }.map { it.candidate }
            val evenBetterCandidates =
                bestCandidates.filterIsInstance<KaFunctionCall<*>>()
                    .filter { !it.hasOptionalArguments() }
            // if we have nothing to show, or it is too complicated, bail out
            val evenBetterCandidatesCount = evenBetterCandidates.size
            if (evenBetterCandidatesCount == 0 || evenBetterCandidatesCount > NONE_APPLICABLE_MAX_OVERLOADS || evenBetterCandidates.size < bestCandidates.size) {
                return
            }

            for (expression in evenBetterCandidates.first().argumentMapping.keys) {
                val expressionType = expression.expressionType
                val expressionTypeStringShort = expressionType?.renderShort() ?: "??"
                val expressionTypeStringQualified = expressionType?.renderQualified() ?: "??"

                var somethingWrong = false
                var needsQualification = false
                val actualTypeStrings = mutableListOf<PresentationTreeBuilder.() -> Unit>()
                for ((index, call) in evenBetterCandidates.withIndex()) {
                    val signature = call.argumentMapping[expression] ?: continue
                    val actualTypeStringShort = signature.returnType.renderShort()
                    val actualTypeStringQualified = signature.returnType.renderQualified()

                    when {
                        expressionTypeStringQualified == actualTypeStringQualified -> {
                            actualTypeStrings.add {
                                text("⟦${BOLDFACE_NUMBERS[index]}⟧ ✓")
                            }
                        }
                        else -> {
                            val thisOneNeedsQualification = expressionTypeStringShort == actualTypeStringShort
                            actualTypeStrings.add {
                                text("⟦${BOLDFACE_NUMBERS[index]}⟧ ⇏ ")
                                type(signature.returnType, thisOneNeedsQualification)
                            }
                            somethingWrong = true
                            needsQualification = needsQualification || thisOneNeedsQualification
                        }
                    }
                }

                // no problem => do not show hint
                if (!somethingWrong) continue

                sink.hintAfter(expression.endOffset) {
                    text(": ")
                    if (expressionType == null) { text("??") }
                    else { type(expressionType, needsQualification) }
                }

                for (actualTypeString in actualTypeStrings) {
                    sink.hintAfter(expression.endOffset, actualTypeString)
                }
            }
        }

        fun InlayTreeSink.hintBefore(ranges: Collection<TextRange>, builder: PresentationTreeBuilder.() -> Unit) =
            addPresentation(
                InlineInlayPosition(ranges.before, relatedToPrevious = false),
                hintFormat = HintFormat.default,
                builder = builder
            )

        fun InlayTreeSink.hintAfter(ranges: Collection<TextRange>, builder: PresentationTreeBuilder.() -> Unit) =
            this.hintAfter(ranges.after, builder)

        fun InlayTreeSink.hintAfter(offset: Int, builder: PresentationTreeBuilder.() -> Unit) =
            addPresentation(
                InlineInlayPosition(offset, relatedToPrevious = true),
                hintFormat = HintFormat.default,
                builder = builder
            )

        context(session: KaSession)
        fun PresentationTreeBuilder.type(type: KaType, renderQualified: Boolean) {
            when (type) {
                is KaFunctionType -> {
                    type.receiverType?.let {
                        type(it, renderQualified)
                        text(".")
                    }
                    text("(")
                    type.parameterTypes.withSeparator(
                        separator = { text(", ") }
                    ) { type(it, renderQualified) }
                    text(" -> ")
                    type(type.returnType, renderQualified)
                }
                is KaClassType -> {
                    type.symbol.classId?.let { classId ->
                        val action = InlayActionData(
                            StringInlayActionPayload(classId.asFqNameString()),
                            handlerId = KotlinFqnDeclarativeInlayActionHandler.HANDLER_NAME
                        )
                        text(classId.render(renderQualified), action)
                    } ?: text("??")

                    if (type.typeArguments.isNotEmpty()) {
                        text("<")
                        type.typeArguments.withSeparator(
                            separator = { text(", ") }
                        ) { projection(it, renderQualified) }
                        text(">")
                    }
                }
                is KaDefinitelyNotNullType -> {
                    type(type.original, renderQualified)
                    text(" & Any")
                }
                is KaIntersectionType -> {
                    type.conjuncts.withSeparator(
                        separator = { text(" & ") }
                    ) { type(it, renderQualified) }
                }
                is KaFlexibleType -> {
                    type(type.lowerBound, renderQualified)
                    text(" .. ")
                    type(type.upperBound, renderQualified)
                }
                is KaCapturedType -> {
                    projection(type.projection, renderQualified)
                }
                else -> text(type.renderShort())
            }
            if (type.nullability.isNullable) {
                text("?")
            }
        }

        context(session: KaSession)
        fun PresentationTreeBuilder.projection(projection: KaTypeProjection, renderQualified: Boolean) {
            when (projection) {
                is KaStarTypeProjection -> {
                    text("*")
                    text(" : ")
                }
                is KaTypeArgumentWithVariance if projection.variance != Variance.INVARIANT -> {
                    text(projection.variance.label)
                    text(" ")
                }
                else -> { }
            }
            projection.type?.let { type(it, renderQualified) }
        }

        fun <A> Iterable<A>.withSeparator(
            separator: () -> Unit,
            block: (A) -> Unit
        ) {
            firstOrNull()?.let { block(it) }
            drop(1).forEach {
                separator()
                block(it)
            }
        }
    }
}