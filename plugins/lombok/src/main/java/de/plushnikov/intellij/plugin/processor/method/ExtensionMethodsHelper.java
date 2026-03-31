package de.plushnikov.intellij.plugin.processor.method;

import com.intellij.lang.java.JavaLanguage;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.JavaResolveResult;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiPrimitiveType;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.PsiSubstitutor;
import com.intellij.psi.PsiType;
import com.intellij.psi.PsiTypeParameter;
import com.intellij.psi.augment.PsiExtensionMethod;
import com.intellij.psi.infos.MethodCandidateInfo;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import com.intellij.psi.util.MethodSignatureBackedByPsiMethod;
import com.intellij.psi.util.PsiModificationTracker;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.PsiTypesUtil;
import com.intellij.util.containers.ContainerUtil;
import de.plushnikov.intellij.plugin.LombokClassNames;
import de.plushnikov.intellij.plugin.psi.LombokExtensionMethod;
import de.plushnikov.intellij.plugin.psi.LombokLightParameter;
import de.plushnikov.intellij.plugin.util.PsiAnnotationSearchUtil;
import de.plushnikov.intellij.plugin.util.PsiAnnotationUtil;
import de.plushnikov.intellij.plugin.util.PsiClassUtil;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

@NotNullByDefault
public final class ExtensionMethodsHelper {
  private static final Logger LOG = Logger.getInstance(ExtensionMethodsHelper.class);

  public static List<PsiExtensionMethod> getExtensionMethods(PsiClass targetClass, String nameHint, PsiElement place) {
    if (!(place instanceof PsiMethodCallExpression methodCallExpression)) {
      return Collections.emptyList();
    }
    PsiReferenceExpression methodExpression = methodCallExpression.getMethodExpression();
    PsiExpression qualifierExpression = methodExpression.getQualifierExpression();
    if (qualifierExpression == null ||
        !nameHint.equals(methodExpression.getReferenceName()) ||
        qualifierExpression instanceof PsiReferenceExpression && ((PsiReferenceExpression)qualifierExpression).resolve() instanceof PsiClass) {
      return Collections.emptyList();
    }

    return collectExtensionMethods(targetClass, place, (providerClass, extensionMethodImpl) -> createExtensionMethod(
      providerClass, extensionMethodImpl, targetClass, methodCallExpression));
  }

  private static List<PsiExtensionMethod> collectExtensionMethods(PsiClass targetClass,
                                                                  PsiElement place,
                                                                  BiFunction<PsiClass, PsiMethod, @Nullable PsiExtensionMethod> extensionMethodFactory) {
    List<PsiExtensionMethod> result = new ArrayList<>();
    @Nullable PsiClass context = PsiTreeUtil.getContextOfType(place, PsiClass.class);
    while (context != null) {
      Set<PsiClass> providerClasses = findProviderClasses(context);
      if (!providerClasses.isEmpty()) {
        List<PsiExtensionMethod> psiMethods = new ArrayList<>();
        for (PsiClass providerClass : providerClasses) {
          for (PsiMethod extensionMethodImpl : findExtensionMethodImplCandidatesCached(providerClass)) {
            ContainerUtil.addIfNotNull(psiMethods, extensionMethodFactory.apply(providerClass, extensionMethodImpl));
          }
        }
        result.addAll(filterOutByInstanceMethodSignatures(psiMethods, targetClass));
      }
      context = PsiTreeUtil.getContextOfType(context, PsiClass.class);
    }

    return result;
  }

  /**
   * Returns classes providing static extension methods that are enabled within the body of `psiClass`
   * by reading information from the `@ExtensionMethod` annotation attached to `psiClass`, if present.
   */
  private static Set<PsiClass> findProviderClasses(PsiClass psiClass) {
    @Nullable PsiAnnotation annotation = PsiAnnotationSearchUtil.findAnnotation(psiClass, LombokClassNames.EXTENSION_METHOD);
    if (annotation == null) return Set.of();
    return PsiAnnotationUtil.getAnnotationValues(annotation, PsiAnnotation.DEFAULT_REFERENCED_METHOD_NAME, PsiType.class, List.of())
      .stream()
      .filter(PsiClassType.class::isInstance)
      .map(PsiClassType.class::cast)
      .map(PsiClassType::resolve)
      .filter(Objects::nonNull)
      .collect(Collectors.toSet());
  }

  /// Returns `methods` filtered out by instance method signatures already present in `targetClass`
  private static List<PsiExtensionMethod> filterOutByInstanceMethodSignatures(List<PsiExtensionMethod> methods,
                                                                              PsiClass targetClass) {
    return methods
      .stream()
      .map(method -> MethodSignatureBackedByPsiMethod.create(method, PsiSubstitutor.EMPTY))
      .distinct()
      .filter(methodSignature -> !targetClass.getVisibleSignatures().contains(methodSignature))
      .map(methodSignature -> (PsiExtensionMethod)methodSignature.getMethod())
      .toList();
  }

  private static List<PsiMethod> findExtensionMethodImplCandidatesCached(PsiClass providerClass) {
    return CachedValuesManager.getCachedValue(providerClass, () -> CachedValueProvider.Result
      .create(findExtensionMethodImplCandidates(providerClass), PsiModificationTracker.MODIFICATION_COUNT));
  }

  /// Finds potential extension method implementation candidates from the provided class by inspecting its static methods.
  /// Filters methods that are public, have at least one parameter, and whose first parameter is not a primitive type.
  ///
  /// @param providerClass the class to scan for extension method candidates.
  /// @return a list of static methods from the provider class that are eligible as extension method candidates.
  private static List<PsiMethod> findExtensionMethodImplCandidates(PsiClass providerClass) {
    final List<PsiMethod> result = new ArrayList<>();
    for (PsiMethod staticMethod : PsiClassUtil.collectClassStaticMethodsIntern(providerClass)) {
      if (staticMethod.hasModifierProperty(PsiModifier.PUBLIC)) {
        PsiParameter[] parameters = staticMethod.getParameterList().getParameters();
        if (parameters.length > 0 && !(parameters[0].getType() instanceof PsiPrimitiveType)) {
          result.add(staticMethod);
        }
      }
    }
    return result;
  }

  /// Creates a [PsiExtensionMethod] that represents an extension method binding `extensionMethodImpl` from `providerClass`
  /// to an instance method of `targetClass`.
  /// Ensures that the provided static method matches the method invoked by the
  /// given call expression and resolves its applicability or returns null otherwise.
  private static @Nullable PsiExtensionMethod createExtensionMethod(PsiClass providerClass,
                                                          PsiMethod extensionMethodImpl,
                                                          PsiClass targetClass,
                                                          PsiMethodCallExpression callExpression) {
    if (!extensionMethodImpl.getName().equals(callExpression.getMethodExpression().getReferenceName())) return null;
    PsiMethodCallExpression staticMethodCall;
    try {
      StringBuilder args = new StringBuilder(Objects.requireNonNull(callExpression.getMethodExpression().getQualifierExpression()).getText());
      PsiExpression[] expressions = callExpression.getArgumentList().getExpressions();
      if (expressions.length > 0) {
        args.append(", ");
      }
      args.append(StringUtil.join(expressions, expression -> expression.getText(), ","));

      staticMethodCall = (PsiMethodCallExpression)JavaPsiFacade.getElementFactory(extensionMethodImpl.getProject())
        .createExpressionFromText(providerClass.getQualifiedName() + "." + extensionMethodImpl.getName() + "(" + args + ")", callExpression);
    }
    catch (ProcessCanceledException e) {
      throw e;
    }
    catch (Throwable e) {
      LOG.error(e);
      return null;
    }

    JavaResolveResult result = staticMethodCall.resolveMethodGenerics();
    if (!(result instanceof MethodCandidateInfo)) return null;
    PsiMethod method = ((MethodCandidateInfo)result).getElement();
    if (!method.equals(extensionMethodImpl) || !((MethodCandidateInfo)result).isApplicable()) return null;
    PsiSubstitutor substitutor = result.getSubstitutor();

    return createLightMethod(extensionMethodImpl, targetClass, substitutor);
  }

  private static PsiExtensionMethod createLightMethod(final PsiMethod staticMethod,
                                                      final PsiClass targetClass,
                                                      final PsiSubstitutor substitutor) {
    final LombokExtensionMethod lightMethod = new LombokExtensionMethod(staticMethod);
    lightMethod.addModifiers(PsiModifier.PUBLIC);
    PsiParameter[] parameters = staticMethod.getParameterList().getParameters();

    if (targetClass.isInterface()) {
      lightMethod.addModifier(PsiModifier.DEFAULT);
    }

    lightMethod.setMethodReturnType(substitutor.substitute(staticMethod.getReturnType()));

    for (int i = 1, length = parameters.length; i < length; i++) {
      PsiParameter parameter = parameters[i];
      final LombokLightParameter lombokLightParameter =
        new LombokLightParameter(parameter.getName(), substitutor.substitute(parameter.getType()), lightMethod, JavaLanguage.INSTANCE);
      lombokLightParameter.setParent(lightMethod);
      lightMethod.addParameter(lombokLightParameter);
    }

    PsiClassType[] thrownTypes = staticMethod.getThrowsList().getReferencedTypes();
    for (PsiClassType thrownType : thrownTypes) {
      lightMethod.addException((PsiClassType)substitutor.substitute(thrownType));
    }

    PsiTypeParameter[] staticMethodTypeParameters = staticMethod.getTypeParameters();
    HashSet<PsiTypeParameter> initialTypeParameters = ContainerUtil.newHashSet(staticMethodTypeParameters);
    Arrays.stream(staticMethodTypeParameters)
      .filter(typeParameter -> PsiTypesUtil.mentionsTypeParameters(substitutor.substitute(typeParameter), initialTypeParameters))
      .forEach(lightMethod::addTypeParameter);

    lightMethod.setNavigationElement(staticMethod);
    lightMethod.setContainingClass(targetClass);
    return lightMethod;
  }

}
