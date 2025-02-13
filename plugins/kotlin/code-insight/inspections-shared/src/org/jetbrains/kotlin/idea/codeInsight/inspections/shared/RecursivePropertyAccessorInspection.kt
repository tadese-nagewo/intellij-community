// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.idea.codeInsight.inspections.shared

import com.intellij.codeInspection.ProblemsHolder
import com.intellij.codeInspection.util.InspectionMessage
import com.intellij.codeInspection.util.IntentionFamilyName
import com.intellij.modcommand.ModPsiUpdater
import com.intellij.openapi.project.Project
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.annotations.ApiStatus
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.resolution.successfulVariableAccessCall
import org.jetbrains.kotlin.analysis.api.resolution.symbol
import org.jetbrains.kotlin.analysis.api.symbols.KaClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaPropertySymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSyntheticJavaPropertySymbol
import org.jetbrains.kotlin.analysis.api.symbols.receiverType
import org.jetbrains.kotlin.idea.base.resources.KotlinBundle
import org.jetbrains.kotlin.idea.codeInsight.inspections.shared.RecursivePropertyAccessorInspection.Context
import org.jetbrains.kotlin.idea.codeinsight.api.applicable.inspections.KotlinApplicableInspectionBase
import org.jetbrains.kotlin.idea.codeinsight.api.applicable.inspections.KotlinModCommandQuickFix
import org.jetbrains.kotlin.idea.codeinsight.utils.resolveExpression
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.getParentOfType
import org.jetbrains.kotlin.psi.psiUtil.getStrictParentOfType
import org.jetbrains.kotlin.util.capitalizeDecapitalize.capitalizeAsciiOnly

class RecursivePropertyAccessorInspection : KotlinApplicableInspectionBase.Simple<KtSimpleNameExpression, Context>() {
  class Context(val isRecursivePropertyAccess: Boolean, val isRecursiveSyntheticPropertyAccess: Boolean)

  override fun getProblemDescription(
    element: KtSimpleNameExpression, context: Context,
  ): @InspectionMessage String = if (context.isRecursivePropertyAccess) {
    KotlinBundle.message("recursive.property.accessor")
  }
  else if (context.isRecursiveSyntheticPropertyAccess) {
    KotlinBundle.message("recursive.synthetic.property.accessor")
  }
  else {
    error("RecursivePropertyAccessorInspection.Context is expected to have true for one of its properties")
  }

  override fun createQuickFixes(
    element: KtSimpleNameExpression, context: Context,
  ): Array<KotlinModCommandQuickFix<KtSimpleNameExpression>> { // Skip if the property is an extension property.
    val isExtensionProperty = element.getStrictParentOfType<KtProperty>()?.receiverTypeReference != null
    if (isExtensionProperty) return emptyArray()

    return arrayOf(object : KotlinModCommandQuickFix<KtSimpleNameExpression>() {
      override fun getFamilyName(): @IntentionFamilyName String = KotlinBundle.message("replace.with.field.fix.text")

      override fun applyFix(
        project: Project, element: KtSimpleNameExpression, updater: ModPsiUpdater,
      ) {
        val factory = KtPsiFactory(project)
        element.replace(factory.createExpression("field"))
      }
    })
  }

  override fun buildVisitor(
    holder: ProblemsHolder, isOnTheFly: Boolean,
  ): KtVisitor<*, *> = object : KtVisitorVoid() {
    override fun visitSimpleNameExpression(expression: KtSimpleNameExpression) {
      visitTargetElement(expression, holder, isOnTheFly)
    }
  }

  override fun isApplicableByPsi(element: KtSimpleNameExpression): Boolean {
    return isApplicablePropertyAccessPsi(element) || isApplicableSyntheticPropertyAccessPsi(element)
  }

  override fun KaSession.prepareContext(element: KtSimpleNameExpression): Context? {
    val isRecursivePropertyAccess = element.isRecursivePropertyAccess(false)
    val isRecursiveSyntheticPropertyAccess = isRecursiveSyntheticPropertyAccess(element)
    if (!isRecursivePropertyAccess && !isRecursiveSyntheticPropertyAccess) return null

    return Context(isRecursivePropertyAccess, isRecursiveSyntheticPropertyAccess)
  }

  companion object {
    @ApiStatus.ScheduledForRemoval
    @Deprecated("use isRecursivePropertyAccess(KtElement, Boolean) instead",
                replaceWith = ReplaceWith("isRecursivePropertyAccess(element, false)"))
    fun isRecursivePropertyAccess(element: KtElement): Boolean = isRecursivePropertyAccess(element, false)

    fun isRecursivePropertyAccess(element: KtElement, anyRecursionTypes: Boolean): Boolean {
      if (element !is KtSimpleNameExpression) return false
      return isApplicablePropertyAccessPsi(element) && element.isRecursivePropertyAccess(anyRecursionTypes)
    }

    fun isRecursiveSyntheticPropertyAccess(element: KtElement): Boolean {
      if (element !is KtSimpleNameExpression) return false
      return isApplicableSyntheticPropertyAccessPsi(element) && element.isRecursiveSyntheticPropertyAccess()
    }

    private fun isApplicablePropertyAccessPsi(element: KtSimpleNameExpression): Boolean {
      val propertyAccessor = element.getParentOfType<KtDeclarationWithBody>(true) as? KtPropertyAccessor ?: return false
      if (element.text != propertyAccessor.property.name) return false
      return element.parent !is KtCallableReferenceExpression
    }

    private fun isApplicableSyntheticPropertyAccessPsi(element: KtSimpleNameExpression): Boolean {
      val namedFunction = element.getParentOfType<KtDeclarationWithBody>(true) as? KtNamedFunction ?: return false
      val name = namedFunction.name ?: return false
      val referencedName = element.text.capitalizeAsciiOnly()
      val isGetter = name == "get$referencedName"
      val isSetter = name == "set$referencedName"
      if (!isGetter && !isSetter) return false
      return element.parent !is KtCallableReferenceExpression
    }

    private fun KaSession.hasThisAsReceiver(expression: KtQualifiedExpression) =
      expression.receiverExpression.textMatches(KtTokens.THIS_KEYWORD.value)

    private fun KaSession.hasObjectReceiver(expression: KtQualifiedExpression): Boolean {
      val receiver = expression.receiverExpression as? KtReferenceExpression ?: return false
      val receiverAsClassSymbol = receiver.resolveExpression() as? KaClassSymbol ?: return false
      return receiverAsClassSymbol.classKind.isObject
    }

    private fun KtBinaryExpression?.isAssignmentTo(expression: KtSimpleNameExpression): Boolean =
      this != null && KtPsiUtil.isAssignment(this) && PsiTreeUtil.isAncestor(left, expression, false)

    private fun isSameAccessor(expression: KtSimpleNameExpression, isGetter: Boolean): Boolean {
      val binaryExpr = expression.getStrictParentOfType<KtBinaryExpression>()
      if (isGetter) {
        if (binaryExpr.isAssignmentTo(expression)) {
          return KtTokens.AUGMENTED_ASSIGNMENTS.contains(binaryExpr?.operationToken)
        }
        return true
      }
      else /* isSetter */ {
        if (binaryExpr.isAssignmentTo(expression)) {
          return true
        }
        val unaryExpr = expression.getStrictParentOfType<KtUnaryExpression>()
        if (unaryExpr?.operationToken.let { it == KtTokens.PLUSPLUS || it == KtTokens.MINUSMINUS }) {
          return true
        }
      }
      return false
    }

    private fun KtSimpleNameExpression.isRecursivePropertyAccess(anyRecursionTypes: Boolean): Boolean {
      val propertyAccessor = getParentOfType<KtDeclarationWithBody>(true) as? KtPropertyAccessor ?: return false
      analyze(this) {
        val propertySymbol = resolveToCall()?.successfulVariableAccessCall()?.symbol as? KaPropertySymbol ?: return false
        if (propertySymbol != propertyAccessor.property.symbol) return false

        (parent as? KtQualifiedExpression)?.let {
          val propertyReceiverType = propertySymbol.receiverType
          val receiverType = it.receiverExpression.expressionType
          if (anyRecursionTypes) {
            if (receiverType != null && propertyReceiverType != null && !receiverType.isSubtypeOf(propertyReceiverType)) {
              return false
            }
          }
          else {
            if (!hasThisAsReceiver(it) && !hasObjectReceiver(it) && (propertyReceiverType == null || receiverType?.isSubtypeOf(
                propertyReceiverType) == false)
            ) {
              return false
            }
          }
        }
      }
      return isSameAccessor(this, propertyAccessor.isGetter)
    }

    private fun KtSimpleNameExpression.isRecursiveSyntheticPropertyAccess(): Boolean {
      val namedFunction = getParentOfType<KtDeclarationWithBody>(true) as? KtNamedFunction ?: return false

      analyze(this) {
        val syntheticSymbol = mainReference.resolveToSymbol() as? KaSyntheticJavaPropertySymbol ?: return false
        val namedFunctionSymbol = namedFunction.symbol
        if (namedFunctionSymbol != syntheticSymbol.javaGetterSymbol && namedFunctionSymbol != syntheticSymbol.javaSetterSymbol) {
          return false
        }
      }

      val name = namedFunction.name ?: return false
      val referencedName = text.capitalizeAsciiOnly()
      val isGetter = name == "get$referencedName"
      return isSameAccessor(this, isGetter)
    }
  }
}
