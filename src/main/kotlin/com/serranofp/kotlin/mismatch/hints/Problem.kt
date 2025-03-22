package com.serranofp.kotlin.mismatch.hints

import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.diagnostics.KaDiagnosticWithPsi
import org.jetbrains.kotlin.analysis.api.fir.diagnostics.KaFirDiagnostic
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbol
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.types.Variance

sealed interface Problem
data class ExpectedActualTypeMismatch(val expectedType: KaType, val actualType: KaType, val dueToNullability: Boolean) : Problem
data class TypeMismatch(val typeA: KaType, val typeB: KaType) : Problem
data class TypeVarianceMismatch(val expectedVariance: Variance, val actualVariance: Variance) : Problem
data class NoneApplicable(val candidates: List<KaSymbol>) : Problem
data class AmbiguousType(val candidates: List<KaType>) : Problem
data class AmbiguousCandidate(val candidates: List<KaSymbol>) : Problem

@Suppress("UNCHECKED_CAST")
fun KaSession.asProblem(element: KaDiagnosticWithPsi<*>): Problem? = when (element) {
    is KaFirDiagnostic.TypeMismatch -> ExpectedActualTypeMismatch(element.expectedType, element.actualType, element.isMismatchDueToNullability)
    is KaFirDiagnostic.TypeMismatchWhenFlexibilityChanges -> ExpectedActualTypeMismatch(element.expectedType, element.actualType, false)
    is KaFirDiagnostic.JavaTypeMismatch -> ExpectedActualTypeMismatch(element.expectedType, element.actualType, false)
    is KaFirDiagnostic.ArgumentTypeMismatch -> ExpectedActualTypeMismatch(element.expectedType, element.actualType, element.isMismatchDueToNullability)
    is KaFirDiagnostic.InitializerTypeMismatch -> ExpectedActualTypeMismatch(element.expectedType, element.actualType, element.isMismatchDueToNullability)
    is KaFirDiagnostic.AssignmentTypeMismatch -> ExpectedActualTypeMismatch(element.expectedType, element.actualType, element.isMismatchDueToNullability)
    is KaFirDiagnostic.ResultTypeMismatch -> ExpectedActualTypeMismatch(element.expectedType, element.actualType, false)
    is KaFirDiagnostic.ReturnTypeMismatch -> ExpectedActualTypeMismatch(element.expectedType, element.actualType, element.isMismatchDueToNullability)
    is KaFirDiagnostic.ConditionTypeMismatch -> ExpectedActualTypeMismatch(builtinTypes.boolean, element.actualType, element.isMismatchDueToNullability)
    is KaFirDiagnostic.ThrowableTypeMismatch -> ExpectedActualTypeMismatch(builtinTypes.throwable, element.actualType, element.isMismatchDueToNullability)
    is KaFirDiagnostic.NullForNonnullType -> ExpectedActualTypeMismatch(element.expectedType, builtinTypes.nullableNothing, false)
    is KaFirDiagnostic.UpperBoundViolated -> ExpectedActualTypeMismatch(element.expectedUpperBound, element.actualUpperBound, false)
    is KaFirDiagnostic.IncompatibleTypes -> TypeMismatch(element.typeA, element.typeB)
    is KaFirDiagnostic.IncompatibleTypesWarning -> TypeMismatch(element.typeA, element.typeB)
    is KaFirDiagnostic.TypeVarianceConflictError -> TypeVarianceMismatch(element.typeParameterVariance, element.variance)
    is KaFirDiagnostic.TypeVarianceConflictInExpandedType -> TypeVarianceMismatch(element.typeParameterVariance, element.variance)
    is KaFirDiagnostic.NoneApplicable -> NoneApplicable(element.candidates)
    is KaFirDiagnostic.InapplicableCandidate -> NoneApplicable(listOf(element.candidate))
    is KaFirDiagnostic.AmbiguousSuper -> AmbiguousType(element.candidates)
    is KaFirDiagnostic.OverloadResolutionAmbiguity -> AmbiguousCandidate(element.candidates)
    else -> null
}