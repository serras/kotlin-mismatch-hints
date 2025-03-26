package com.serranofp.kotlin.mismatch.hints

import com.intellij.codeInsight.hints.declarative.HintFormat
import com.intellij.codeInsight.hints.declarative.InlayHintsCollector
import com.intellij.codeInsight.hints.declarative.InlayHintsProvider
import com.intellij.codeInsight.hints.declarative.InlayTreeSink
import com.intellij.codeInsight.hints.declarative.InlineInlayPosition
import com.intellij.codeInsight.hints.declarative.PresentationTreeBuilder
import com.intellij.codeInsight.hints.declarative.SharedBypassCollector
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.components.KaDiagnosticCheckerFilter
import org.jetbrains.kotlin.analysis.api.resolution.KaFunctionCall
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.psiUtil.endOffset

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
                renderTypeProblem(chooseBetterActualType(element, problem.actualType), problem.expectedType) { a, e ->
                    text(a)
                    text(" ⇏ ")
                    text(e)
                }
            }

            is TypeMismatch -> sink.hintAfter(ranges) {
                text(": ")
                renderTypeProblem(problem.typeA, problem.typeB) { a, b ->
                    text(a)
                    text(" ≠ ")
                    text(b)
                }
            }

            is TypeVarianceMismatch -> sink.hintBefore(ranges) {
                text(problem.expectedVariance.label)
                text(" ≠ ")
                text(problem.actualVariance.label)
            }

            is AmbiguousType -> sink.hintAfter(ranges) {
                text("<")
                text(problem.candidates.joinToString(" or ") { it.renderShort() })
                text(">")
            }

            else -> {}
        }

        fun KaSession.noneApplicableHint(element: KtElement, sink: InlayTreeSink) {
            val bestCandidates =
                element.resolveToCallCandidates().filter { it.isInBestCandidates }.map { it.candidate }
            val evenBetterCandidates =
                bestCandidates.filterIsInstance<KaFunctionCall<*>>()
                    .filter { !it.hasOptionalArguments() }
            // if we have nothing to show, or it is too complicated, bail out
            val evenBetterCandidatesCount = evenBetterCandidates.size
            if (evenBetterCandidatesCount == 0 || evenBetterCandidatesCount > Symbols.size || evenBetterCandidates.size < bestCandidates.size) {
                return
            }

            for (expression in evenBetterCandidates.first().argumentMapping.keys) {
                val expressionTypeStringShort = expression.expressionType?.renderShort() ?: "??"
                val expressionTypeStringQualified = expression.expressionType?.renderQualified() ?: "??"

                var somethingWrong = false
                var needsQualification = false
                val actualTypeStrings = mutableListOf<String>()
                for ((index, call) in evenBetterCandidates.withIndex()) {
                    val signature = call.argumentMapping[expression] ?: continue
                    val actualTypeStringShort = signature.returnType.renderShort()
                    val actualTypeStringQualified = signature.returnType.renderQualified()
                    when {
                        expressionTypeStringQualified == actualTypeStringQualified -> {
                            actualTypeStrings.add("${Symbols[index]} ✓")
                        }
                        expressionTypeStringShort == actualTypeStringShort -> {
                            actualTypeStrings.add("${Symbols[index]} ⇏ $actualTypeStringQualified")
                            somethingWrong = true
                            needsQualification = true
                        }
                        else -> {
                            actualTypeStrings.add("${Symbols[index]} ⇏ $actualTypeStringShort")
                            somethingWrong = true
                        }
                    }
                }

                // no problem => do not show hint
                if (!somethingWrong) continue

                sink.hintAfter(expression.endOffset) {
                    if (needsQualification) text(": $expressionTypeStringQualified")
                    else text(": $expressionTypeStringShort")

                }
                for (actualTypeString in actualTypeStrings) {
                    sink.hintAfter(expression.endOffset) {
                        text(actualTypeString)
                    }
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
    }
}