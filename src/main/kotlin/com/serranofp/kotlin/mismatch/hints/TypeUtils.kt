package com.serranofp.kotlin.mismatch.hints

import com.intellij.openapi.util.TextRange
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.renderer.types.impl.KaTypeRendererForSource
import org.jetbrains.kotlin.analysis.api.resolution.KaCallableMemberCall
import org.jetbrains.kotlin.analysis.api.signatures.KaCallableSignature
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaVariableSymbol
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.analysis.api.types.KaTypeParameterType
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.types.Variance

context(session: KaSession)
@OptIn(KaExperimentalApi::class)
fun KaType.renderShort(): String = with(session) {
    this@renderShort.render(renderer = KaTypeRendererForSource.WITH_SHORT_NAMES, position = Variance.INVARIANT)
}

fun renderNullability(type: KaType): String = if (type.nullability.isNullable) "nullable" else "non-nullable"

context(session: KaSession)
fun chooseBetterActualType(element: KtElement, problem: KaType): KaType = with(session) {
    val expression = (element as? KtExpression)?.expressionType ?: return problem
    if (expression.approximatedIsGenericInstantiationOf(problem)) return expression
    return problem
}

fun <S : KaCallableSymbol, C : KaCallableSignature<S>> KaCallableMemberCall<S, C>.hasOptionalArguments(): Boolean =
    when (val symbol = partiallyAppliedSymbol.signature.symbol) {
        is KaVariableSymbol -> false
        is KaFunctionSymbol -> symbol.valueParameters.any { it.hasDefaultValue }
    }

fun KaType.approximatedIsGenericInstantiationOf(other: KaType): Boolean {
    when (other) {
        is KaTypeParameterType -> return true
        else -> when {
            this is KaClassType && other is KaClassType -> {
                if (this.classId != other.classId) return false
                if (this.typeArguments.size != other.typeArguments.size) return false
                return this.typeArguments.zip(other.typeArguments).all { (a, b) ->
                    val aType = a.type ?: return false
                    val bType = b.type ?: return false
                    aType.approximatedIsGenericInstantiationOf(bType)
                }
            }

            else -> return false
        }
    }
}

val Collection<TextRange>.before : Int get() = this.first().startOffset
val Collection<TextRange>.after : Int get() = this.last().endOffset