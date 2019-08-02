// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.shaking;

import com.debughelper.tools.r8.dex.Constants;
import com.debughelper.tools.r8.errors.Unreachable;
import com.debughelper.tools.r8.graph.AppInfo;
import com.debughelper.tools.r8.graph.DexAnnotation;
import com.debughelper.tools.r8.graph.DexAnnotationSet;
import com.debughelper.tools.r8.graph.DexApplication;
import com.debughelper.tools.r8.graph.DexClass;
import com.debughelper.tools.r8.graph.DexEncodedField;
import com.debughelper.tools.r8.graph.DexEncodedMethod;
import com.debughelper.tools.r8.graph.DexField;
import com.debughelper.tools.r8.graph.DexItem;
import com.debughelper.tools.r8.graph.DexLibraryClass;
import com.debughelper.tools.r8.graph.DexMethod;
import com.debughelper.tools.r8.graph.DexProgramClass;
import com.debughelper.tools.r8.graph.DexType;
import com.debughelper.tools.r8.graph.DirectMappedDexApplication;
import com.debughelper.tools.r8.logging.Log;
import com.debughelper.tools.r8.shaking.DexStringCache;
import com.debughelper.tools.r8.shaking.Enqueuer.AppInfoWithLiveness;
import com.debughelper.tools.r8.shaking.ProguardAlwaysInlineRule;
import com.debughelper.tools.r8.shaking.ProguardAssumeValuesRule;
import com.debughelper.tools.r8.shaking.ProguardCheckDiscardRule;
import com.debughelper.tools.r8.shaking.ProguardConfigurationRule;
import com.debughelper.tools.r8.shaking.ProguardIdentifierNameStringRule;
import com.debughelper.tools.r8.shaking.ProguardKeepPackageNamesRule;
import com.debughelper.tools.r8.shaking.ProguardKeepRuleModifiers;
import com.debughelper.tools.r8.shaking.ProguardMemberRule;
import com.debughelper.tools.r8.shaking.ProguardTypeMatcher;
import com.debughelper.tools.r8.shaking.ProguardWhyAreYouKeepingRule;
import com.debughelper.tools.r8.utils.InternalOptions;
import com.debughelper.tools.r8.utils.MethodSignatureEquivalence;
import com.debughelper.tools.r8.utils.StringDiagnostic;
import com.debughelper.tools.r8.utils.ThreadUtils;
import com.google.common.base.Equivalence.Wrapper;
import com.google.common.collect.Sets;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public class RootSetBuilder {

  private final AppInfo appInfo;
  private final DirectMappedDexApplication application;
  private final Collection<com.debughelper.tools.r8.shaking.ProguardConfigurationRule> rules;
  private final Map<DexItem, ProguardKeepRule> noShrinking = new IdentityHashMap<>();
  private final Set<DexItem> noOptimization = Sets.newIdentityHashSet();
  private final Set<DexItem> noObfuscation = Sets.newIdentityHashSet();
  private final Set<DexItem> reasonAsked = Sets.newIdentityHashSet();
  private final Set<DexItem> keepPackageName = Sets.newIdentityHashSet();
  private final Set<com.debughelper.tools.r8.shaking.ProguardConfigurationRule> rulesThatUseExtendsOrImplementsWrong =
      Sets.newIdentityHashSet();
  private final Set<DexItem> checkDiscarded = Sets.newIdentityHashSet();
  private final Set<DexItem> alwaysInline = Sets.newIdentityHashSet();
  private final Map<DexItem, Map<DexItem, ProguardKeepRule>> dependentNoShrinking =
      new IdentityHashMap<>();
  private final Map<DexItem, com.debughelper.tools.r8.shaking.ProguardMemberRule> noSideEffects = new IdentityHashMap<>();
  private final Map<DexItem, com.debughelper.tools.r8.shaking.ProguardMemberRule> assumedValues = new IdentityHashMap<>();
  private final Set<DexItem> identifierNameStrings = Sets.newIdentityHashSet();
  private final InternalOptions options;

  private final com.debughelper.tools.r8.shaking.DexStringCache dexStringCache = new com.debughelper.tools.r8.shaking.DexStringCache();
  private final Set<ProguardIfRule> ifRules = Sets.newIdentityHashSet();

  public RootSetBuilder(
      AppInfo appInfo,
      DexApplication application,
      List<com.debughelper.tools.r8.shaking.ProguardConfigurationRule> rules,
      InternalOptions options) {
    this.appInfo = appInfo;
    this.application = application.asDirect();
    this.rules = rules == null ? null : Collections.unmodifiableCollection(rules);
    this.options = options;
  }

  RootSetBuilder(
      AppInfo appInfo,
      Set<ProguardIfRule> ifRules,
      InternalOptions options) {
    this.appInfo = appInfo;
    this.application = appInfo.app.asDirect();
    this.rules = Collections.unmodifiableCollection(ifRules);
    this.options = options;
  }

  private boolean anySuperTypeMatches(
      DexType type,
      Function<DexType, DexClass> definitionFor,
      com.debughelper.tools.r8.shaking.ProguardTypeMatcher name,
      com.debughelper.tools.r8.shaking.ProguardTypeMatcher annotation) {
    while (type != null) {
      DexClass clazz = definitionFor.apply(type);
      if (clazz == null) {
        // TODO(herhut): Warn about broken supertype chain?
        return false;
      }
      if (name.matches(clazz.type) && containsAnnotation(annotation, clazz.annotations)) {
        return true;
      }
      type = clazz.superType;
    }
    return false;
  }

  private boolean anyImplementedInterfaceMatches(
      DexClass clazz,
      Function<DexType, DexClass> definitionFor,
      com.debughelper.tools.r8.shaking.ProguardTypeMatcher className,
      com.debughelper.tools.r8.shaking.ProguardTypeMatcher annotation) {
    if (clazz == null) {
      return false;
    }
    for (DexType iface : clazz.interfaces.values) {
      DexClass ifaceClass = definitionFor.apply(iface);
      if (ifaceClass == null) {
        // TODO(herhut): Warn about broken supertype chain?
        return false;
      }
      // TODO(herhut): Maybe it would be better to do this breadth first.
      if ((className.matches(iface) && containsAnnotation(annotation, ifaceClass.annotations))
          || anyImplementedInterfaceMatches(ifaceClass, definitionFor, className, annotation)) {
        return true;
      }
    }
    if (clazz.superType == null) {
      return false;
    }
    DexClass superClass = definitionFor.apply(clazz.superType);
    if (superClass == null) {
      // TODO(herhut): Warn about broken supertype chain?
      return false;
    }
    return anyImplementedInterfaceMatches(superClass, definitionFor, className, annotation);
  }

  // Process a class with the keep rule.
  private void process(
      DexClass clazz,
      com.debughelper.tools.r8.shaking.ProguardConfigurationRule rule,
      ProguardIfRule ifRule) {
    if (rule.getClassType().matches(clazz) == rule.getClassTypeNegated()) {
      return;
    }
    if (!rule.getClassAccessFlags().containsAll(clazz.accessFlags)) {
      return;
    }
    if (!rule.getNegatedClassAccessFlags().containsNone(clazz.accessFlags)) {
      return;
    }
    if (!containsAnnotation(rule.getClassAnnotation(), clazz.annotations)) {
      return;
    }
    // In principle it should make a difference whether the user specified in a class
    // spec that a class either extends or implements another type. However, proguard
    // seems not to care, so users have started to use this inconsistently. We are thus
    // inconsistent, as well, but tell them.
    // TODO(herhut): One day make this do what it says.
    if (rule.hasInheritanceClassName()) {
      boolean extendsExpected =
          anySuperTypeMatches(
              clazz.superType,
              application::definitionFor,
              rule.getInheritanceClassName(),
              rule.getInheritanceAnnotation());
      boolean implementsExpected = false;
      if (!extendsExpected) {
        implementsExpected =
            anyImplementedInterfaceMatches(
                clazz,
                application::definitionFor,
                rule.getInheritanceClassName(),
                rule.getInheritanceAnnotation());
      }
      if (!extendsExpected && !implementsExpected) {
        return;
      }
      // Warn if users got it wrong, but only warn once.
      if (extendsExpected && !rule.getInheritanceIsExtends()) {
        if (rulesThatUseExtendsOrImplementsWrong.add(rule)) {
          options.reporter.warning(
              new StringDiagnostic(
                  "The rule `" + rule + "` uses implements but actually matches extends."));
        }
      } else if (implementsExpected && rule.getInheritanceIsExtends()) {
        if (rulesThatUseExtendsOrImplementsWrong.add(rule)) {
          options.reporter.warning(
              new StringDiagnostic(
                  "The rule `" + rule + "` uses extends but actually matches implements."));
        }
      }
    }

    if (rule.getClassNames().matches(clazz.type)) {
      Collection<com.debughelper.tools.r8.shaking.ProguardMemberRule> memberKeepRules = rule.getMemberRules();
      if (rule instanceof ProguardKeepRule) {
        switch (((ProguardKeepRule) rule).getType()) {
          case KEEP_CLASS_MEMBERS: {
            // If we're handling -if consequent part, that means precondition already met.
            DexType precondition = ifRule != null ? null : clazz.type;
            markMatchingVisibleMethods(clazz, memberKeepRules, rule, precondition);
            markMatchingFields(clazz, memberKeepRules, rule, precondition);
            break;
          }
          case KEEP_CLASSES_WITH_MEMBERS: {
            if (!allRulesSatisfied(memberKeepRules, clazz)) {
              break;
            }
            // fallthrough;
          }
          case KEEP: {
            markClass(clazz, rule);
            markMatchingVisibleMethods(clazz, memberKeepRules, rule, null);
            markMatchingFields(clazz, memberKeepRules, rule, null);
            break;
          }
          case CONDITIONAL:
            assert rule instanceof ProguardIfRule;
            throw new Unreachable("-if rule will be evaluated separately, not here.");
        }
      } else if (rule instanceof com.debughelper.tools.r8.shaking.ProguardCheckDiscardRule) {
        if (memberKeepRules.isEmpty()) {
          markClass(clazz, rule);
        } else {
          markMatchingFields(clazz, memberKeepRules, rule, clazz.type);
          markMatchingMethods(clazz, memberKeepRules, rule, clazz.type);
        }
      } else if (rule instanceof com.debughelper.tools.r8.shaking.ProguardWhyAreYouKeepingRule
          || rule instanceof com.debughelper.tools.r8.shaking.ProguardKeepPackageNamesRule) {
        markClass(clazz, rule);
        markMatchingVisibleMethods(clazz, memberKeepRules, rule, null);
        markMatchingFields(clazz, memberKeepRules, rule, null);
      } else if (rule instanceof ProguardAssumeNoSideEffectRule) {
        markMatchingVisibleMethods(clazz, memberKeepRules, rule, null);
        markMatchingFields(clazz, memberKeepRules, rule, null);
      } else if (rule instanceof com.debughelper.tools.r8.shaking.ProguardAlwaysInlineRule) {
        markMatchingMethods(clazz, memberKeepRules, rule, null);
      } else if (rule instanceof com.debughelper.tools.r8.shaking.ProguardAssumeValuesRule) {
        markMatchingVisibleMethods(clazz, memberKeepRules, rule, null);
        markMatchingFields(clazz, memberKeepRules, rule, null);
      } else {
        assert rule instanceof com.debughelper.tools.r8.shaking.ProguardIdentifierNameStringRule;
        markMatchingFields(clazz, memberKeepRules, rule, null);
        markMatchingMethods(clazz, memberKeepRules, rule, null);
      }
    }
  }

  private void runPerRule(
      ExecutorService executorService,
      List<Future<?>> futures,
      com.debughelper.tools.r8.shaking.ProguardConfigurationRule rule,
      ProguardIfRule ifRule) {
    List<DexType> specifics = rule.getClassNames().asSpecificDexTypes();
    if (specifics != null) {
      // This keep rule only lists specific type matches.
      // This means there is no need to iterate over all classes.
      for (DexType type : specifics) {
        DexClass clazz = application.definitionFor(type);
        // Ignore keep rule iff it does not reference a class in the app.
        if (clazz != null) {
          process(clazz, rule, ifRule);
        }
      }
    } else {
      futures.add(executorService.submit(() -> {
        for (DexProgramClass clazz : application.classes()) {
          process(clazz, rule, ifRule);
        }
        if (rule.applyToLibraryClasses()) {
          for (DexLibraryClass clazz : application.libraryClasses()) {
            process(clazz, rule, ifRule);
          }
        }
      }));
    }
  }

  public RootSet run(ExecutorService executorService) throws ExecutionException {
    application.timing.begin("Build root set...");
    try {
      List<Future<?>> futures = new ArrayList<>();
      // Mark all the things explicitly listed in keep rules.
      if (rules != null) {
        for (com.debughelper.tools.r8.shaking.ProguardConfigurationRule rule : rules) {
          if (rule instanceof ProguardIfRule) {
            ProguardIfRule ifRule = (ProguardIfRule) rule;
            ifRules.add(ifRule);
          } else {
            runPerRule(executorService, futures, rule, null);
          }
        }
        ThreadUtils.awaitFutures(futures);
      }
    } finally {
      application.timing.end();
    }
    return new RootSet(
        noShrinking,
        noOptimization,
        noObfuscation,
        reasonAsked,
        keepPackageName,
        checkDiscarded,
        alwaysInline,
        noSideEffects,
        assumedValues,
        dependentNoShrinking,
        identifierNameStrings,
        ifRules);
  }

  ConsequentRootSet runForIfRules(
      ExecutorService executorService,
      Set<DexType> liveTypes,
      Set<DexEncodedMethod> liveMethods,
      Set<DexEncodedField> liveFields) throws ExecutionException {
    application.timing.begin("Find consequent items for -if rules...");
    Function<DexType, DexClass> definitionForWithLiveTypes = type -> {
      DexClass clazz = appInfo.definitionFor(type);
      if (clazz != null && liveTypes.contains(clazz.type)) {
        return clazz;
      }
      return null;
    };
    try {
      List<Future<?>> futures = new ArrayList<>();
      if (rules != null) {
        for (com.debughelper.tools.r8.shaking.ProguardConfigurationRule rule : rules) {
          assert rule instanceof ProguardIfRule;
          ProguardIfRule ifRule = (ProguardIfRule) rule;
          // Depending on which types trigger the -if rule, the application of the subsequent
          // -keep rule may vary (due to back references). So, we need to try all pairs of -if rule
          // and live types.
          for (DexType currentLiveType : liveTypes) {
            if (ifRule.hasInheritanceClassName()) {
              if (!satisfyInheritanceRule(currentLiveType, definitionForWithLiveTypes, ifRule)) {
                // Try another live type since the current one doesn't satisfy the inheritance rule.
                continue;
              }
            }
            if (ifRule.getClassNames().matches(currentLiveType)) {
              Collection<com.debughelper.tools.r8.shaking.ProguardMemberRule> memberKeepRules = ifRule.getMemberRules();
              if (memberKeepRules.isEmpty()) {
                ProguardIfRule materializedRule = ifRule.materialize();
                runPerRule(
                    executorService, futures, materializedRule.subsequentRule, materializedRule);
                // No member rule to satisfy. Move on to the next live type.
                continue;
              }
              Set<DexItem> filteredFields = liveFields.stream()
                  .filter(f -> f.field.getHolder() == currentLiveType)
                  .collect(Collectors.toSet());
              Set<DexItem> filteredMethods = liveMethods.stream()
                  .filter(m -> m.method.getHolder() == currentLiveType)
                  .collect(Collectors.toSet());
              // If the number of member rules to hold is more than live members, we can't make it.
              if (filteredFields.size() + filteredMethods.size() < memberKeepRules.size()) {
                continue;
              }
              // Depending on which members trigger the -if rule, the application of the subsequent
              // -keep rule may vary (due to back references). So, we need to try literally all
              // combinations of live members.
              // TODO(b/79486261): Some of those are equivalent from the point of view of -if rule.
              Set<Set<DexItem>> combinationsOfMembers = Sets.combinations(
                  Sets.union(filteredFields, filteredMethods), memberKeepRules.size());
              for (Set<DexItem> combination : combinationsOfMembers) {
                Set<DexEncodedField> fieldsInCombination = combination.stream()
                    .filter(item -> item instanceof DexEncodedField)
                    .map(item -> (DexEncodedField) item)
                    .collect(Collectors.toSet());
                Set<DexEncodedMethod> methodsInCombination = combination.stream()
                    .filter(item -> item instanceof DexEncodedMethod)
                    .map(item -> (DexEncodedMethod) item)
                    .collect(Collectors.toSet());
                // Member rules are combined as AND logic: if found unsatisfied member rule, this
                // combination of live members is not a good fit.
                boolean satisfied = true;
                for (com.debughelper.tools.r8.shaking.ProguardMemberRule memberRule : memberKeepRules) {
                  if (!ruleSatisfiedByFields(memberRule, fieldsInCombination)
                      && !ruleSatisfiedByMethods(memberRule, methodsInCombination)) {
                    satisfied = false;
                    break;
                  }
                }
                if (satisfied) {
                  ProguardIfRule materializedRule = ifRule.materialize();
                  runPerRule(
                      executorService, futures, materializedRule.subsequentRule, materializedRule);
                }
              }
            }
          }
        }
        ThreadUtils.awaitFutures(futures);
      }
    } finally {
      application.timing.end();
    }
    return new ConsequentRootSet(noShrinking, noOptimization, noObfuscation);
  }

  private void markMatchingVisibleMethods(
      DexClass clazz,
      Collection<com.debughelper.tools.r8.shaking.ProguardMemberRule> memberKeepRules,
      com.debughelper.tools.r8.shaking.ProguardConfigurationRule rule,
      DexType onlyIfClassKept) {
    Set<Wrapper<DexMethod>> methodsMarked = new HashSet<>();
    Arrays.stream(clazz.directMethods()).forEach(method ->
        markMethod(method, memberKeepRules, methodsMarked, rule, onlyIfClassKept));
    while (clazz != null) {
      Arrays.stream(clazz.virtualMethods()).forEach(method ->
          markMethod(method, memberKeepRules, methodsMarked, rule, onlyIfClassKept));
      clazz = clazz.superType == null ? null : application.definitionFor(clazz.superType);
    }
  }

  private void markMatchingMethods(
      DexClass clazz,
      Collection<com.debughelper.tools.r8.shaking.ProguardMemberRule> memberKeepRules,
      com.debughelper.tools.r8.shaking.ProguardConfigurationRule rule,
      DexType onlyIfClassKept) {
    Arrays.stream(clazz.directMethods()).forEach(method ->
        markMethod(method, memberKeepRules, null, rule, onlyIfClassKept));
    Arrays.stream(clazz.virtualMethods()).forEach(method ->
        markMethod(method, memberKeepRules, null, rule, onlyIfClassKept));
  }

  private void markMatchingFields(
      DexClass clazz,
      Collection<com.debughelper.tools.r8.shaking.ProguardMemberRule> memberKeepRules,
      com.debughelper.tools.r8.shaking.ProguardConfigurationRule rule,
      DexType onlyIfClassKept) {
    clazz.forEachField(field -> markField(field, memberKeepRules, rule, onlyIfClassKept));
  }

  // TODO(67934426): Test this code.
  public static void writeSeeds(
      AppInfoWithLiveness appInfo, PrintStream out, Predicate<DexType> include) {
    for (DexItem seed : appInfo.getPinnedItems()) {
      if (seed instanceof DexType) {
        if (include.test((DexType) seed)) {
          out.println(seed.toSourceString());
        }
      } else if (seed instanceof DexField) {
        DexField field = ((DexField) seed);
        if (include.test(field.clazz)) {
          out.println(
              field.clazz.toSourceString()
                  + ": "
                  + field.type.toSourceString()
                  + " "
                  + field.name.toSourceString());
        }
      } else if (seed instanceof DexMethod) {
        DexMethod method = (DexMethod) seed;
        if (!include.test(method.holder)) {
          continue;
        }
        out.print(method.holder.toSourceString() + ": ");
        DexEncodedMethod encodedMethod = appInfo.definitionFor(method);
        if (encodedMethod.accessFlags.isConstructor()) {
          if (encodedMethod.accessFlags.isStatic()) {
            out.print(Constants.CLASS_INITIALIZER_NAME);
          } else {
            String holderName = method.holder.toSourceString();
            String constrName = holderName.substring(holderName.lastIndexOf('.') + 1);
            out.print(constrName);
          }
        } else {
          out.print(
              method.proto.returnType.toSourceString() + " " + method.name.toSourceString());
        }
        boolean first = true;
        out.print("(");
        for (DexType param : method.proto.parameters.values) {
          if (!first) {
            out.print(",");
          }
          first = false;
          out.print(param.toSourceString());
        }
        out.println(")");
      } else {
        throw new Unreachable();
      }
    }
    out.close();
  }

  private boolean satisfyInheritanceRule(
      DexType type,
      Function<DexType, DexClass> definitionFor,
      com.debughelper.tools.r8.shaking.ProguardConfigurationRule rule) {
    DexClass clazz = definitionFor.apply(type);
    if (clazz == null) {
      return false;
    }
    return
        anySuperTypeMatches(
            clazz.superType,
            definitionFor,
            rule.getInheritanceClassName(),
            rule.getInheritanceAnnotation())
        || anyImplementedInterfaceMatches(
            clazz,
            definitionFor,
            rule.getInheritanceClassName(),
            rule.getInheritanceAnnotation());
  }

  private boolean allRulesSatisfied(Collection<com.debughelper.tools.r8.shaking.ProguardMemberRule> memberKeepRules,
      DexClass clazz) {
    for (com.debughelper.tools.r8.shaking.ProguardMemberRule rule : memberKeepRules) {
      if (!ruleSatisfied(rule, clazz)) {
        return false;
      }
    }
    return true;
  }

  /**
   * Checks whether the given rule is satisfied by this clazz, not taking superclasses into
   * account.
   */
  private boolean ruleSatisfied(com.debughelper.tools.r8.shaking.ProguardMemberRule rule, DexClass clazz) {
    return ruleSatisfiedByMethods(rule, clazz.directMethods())
        || ruleSatisfiedByMethods(rule, clazz.virtualMethods())
        || ruleSatisfiedByFields(rule, clazz.staticFields())
        || ruleSatisfiedByFields(rule, clazz.instanceFields());
  }

  private boolean ruleSatisfiedByMethods(
      com.debughelper.tools.r8.shaking.ProguardMemberRule rule,
      Iterable<DexEncodedMethod> methods) {
    if (rule.getRuleType().includesMethods()) {
      for (DexEncodedMethod method : methods) {
        if (rule.matches(method, dexStringCache)) {
          return true;
        }
      }
    }
    return false;
  }

  private boolean ruleSatisfiedByMethods(com.debughelper.tools.r8.shaking.ProguardMemberRule rule, DexEncodedMethod[] methods) {
    if (rule.getRuleType().includesMethods()) {
      for (DexEncodedMethod method : methods) {
        if (rule.matches(method, dexStringCache)) {
          return true;
        }
      }
    }
    return false;
  }

  private boolean ruleSatisfiedByFields(
      com.debughelper.tools.r8.shaking.ProguardMemberRule rule,
      Iterable<DexEncodedField> fields) {
    if (rule.getRuleType().includesFields()) {
      for (DexEncodedField field : fields) {
        if (rule.matches(field, dexStringCache)) {
          return true;
        }
      }
    }
    return false;
  }

  private boolean ruleSatisfiedByFields(com.debughelper.tools.r8.shaking.ProguardMemberRule rule, DexEncodedField[] fields) {
    if (rule.getRuleType().includesFields()) {
      for (DexEncodedField field : fields) {
        if (rule.matches(field, dexStringCache)) {
          return true;
        }
      }
    }
    return false;
  }

  static boolean containsAnnotation(ProguardTypeMatcher classAnnotation,
                                    DexAnnotationSet annotations) {
    if (classAnnotation == null) {
      return true;
    }
    if (annotations.isEmpty()) {
      return false;
    }
    for (DexAnnotation annotation : annotations.annotations) {
      if (classAnnotation.matches(annotation.annotation.type)) {
        return true;
      }
    }
    return false;
  }

  private void markMethod(
      DexEncodedMethod method,
      Collection<com.debughelper.tools.r8.shaking.ProguardMemberRule> rules,
      Set<Wrapper<DexMethod>> methodsMarked,
      com.debughelper.tools.r8.shaking.ProguardConfigurationRule context,
      DexItem precondition) {
    if ((methodsMarked != null)
        && methodsMarked.contains(MethodSignatureEquivalence.get().wrap(method.method))) {
      return;
    }
    for (com.debughelper.tools.r8.shaking.ProguardMemberRule rule : rules) {
      if (rule.matches(method, dexStringCache)) {
        if (Log.ENABLED) {
          Log.verbose(getClass(), "Marking method `%s` due to `%s { %s }`.", method, context,
              rule);
        }
        if (methodsMarked != null) {
          methodsMarked.add(MethodSignatureEquivalence.get().wrap(method.method));
        }
        addItemToSets(method, context, rule, precondition);
      }
    }
  }

  private void markField(
      DexEncodedField field,
      Collection<com.debughelper.tools.r8.shaking.ProguardMemberRule> rules,
      com.debughelper.tools.r8.shaking.ProguardConfigurationRule context,
      DexItem precondition) {
    for (com.debughelper.tools.r8.shaking.ProguardMemberRule rule : rules) {
      if (rule.matches(field, dexStringCache)) {
        if (Log.ENABLED) {
          Log.verbose(getClass(), "Marking field `%s` due to `%s { %s }`.", field, context,
              rule);
        }
        addItemToSets(field, context, rule, precondition);
      }
    }
  }

  private void markClass(DexClass clazz, com.debughelper.tools.r8.shaking.ProguardConfigurationRule rule) {
    if (Log.ENABLED) {
      Log.verbose(getClass(), "Marking class `%s` due to `%s`.", clazz.type, rule);
    }
    addItemToSets(clazz, rule, null, null);
  }

  private void includeDescriptor(DexItem item, DexType type, ProguardKeepRule context) {
    if (type.isArrayType()) {
      type = type.toBaseType(application.dexItemFactory);
    }
    if (type.isPrimitiveType()) {
      return;
    }
    DexClass definition = appInfo.definitionFor(type);
    if (definition == null || definition.isLibraryClass()) {
      return;
    }
    // Keep the type if the item is also kept.
    dependentNoShrinking.computeIfAbsent(item, x -> new IdentityHashMap<>())
        .put(definition, context);
    // Unconditionally add to no-obfuscation, as that is only checked for surviving items.
    noObfuscation.add(type);
  }

  private void includeDescriptorClasses(DexItem item, ProguardKeepRule context) {
    if (item instanceof DexEncodedMethod) {
      DexMethod method = ((DexEncodedMethod) item).method;
      includeDescriptor(item, method.proto.returnType, context);
      for (DexType value : method.proto.parameters.values) {
        includeDescriptor(item, value, context);
      }
    } else if (item instanceof DexEncodedField) {
      DexField field = ((DexEncodedField) item).field;
      includeDescriptor(item, field.type, context);
    } else {
      assert item instanceof DexClass;
    }
  }

  private synchronized void addItemToSets(
      DexItem item,
      ProguardConfigurationRule context,
      com.debughelper.tools.r8.shaking.ProguardMemberRule rule,
      DexItem precondition) {
    if (context instanceof ProguardKeepRule) {
      ProguardKeepRule keepRule = (ProguardKeepRule) context;
      ProguardKeepRuleModifiers modifiers = keepRule.getModifiers();
      if (!modifiers.allowsShrinking) {
        if (precondition != null) {
          dependentNoShrinking.computeIfAbsent(precondition, x -> new IdentityHashMap<>())
              .put(item, keepRule);
        } else {
          noShrinking.put(item, keepRule);
        }
      }
      if (!modifiers.allowsOptimization) {
        noOptimization.add(item);
      }
      if (!modifiers.allowsObfuscation) {
        if (item instanceof DexClass) {
          noObfuscation.add(((DexClass) item).type);
        } else {
          noObfuscation.add(item);
        }
      }
      if (modifiers.includeDescriptorClasses) {
        includeDescriptorClasses(item, keepRule);
      }
    } else if (context instanceof ProguardAssumeNoSideEffectRule) {
      noSideEffects.put(item, rule);
    } else if (context instanceof ProguardWhyAreYouKeepingRule) {
      reasonAsked.add(item);
    } else if (context instanceof ProguardKeepPackageNamesRule) {
      keepPackageName.add(item);
    } else if (context instanceof ProguardAssumeValuesRule) {
      assumedValues.put(item, rule);
    } else if (context instanceof ProguardCheckDiscardRule) {
      checkDiscarded.add(item);
    } else if (context instanceof ProguardAlwaysInlineRule) {
      alwaysInline.add(item);
    } else if (context instanceof ProguardIdentifierNameStringRule) {
      if (item instanceof DexEncodedField) {
        identifierNameStrings.add(((DexEncodedField) item).field);
      } else if (item instanceof DexEncodedMethod) {
        identifierNameStrings.add(((DexEncodedMethod) item).method);
      }
    }
  }

  public static class RootSet {

    public final Map<DexItem, ProguardKeepRule> noShrinking;
    public final Set<DexItem> noOptimization;
    public final Set<DexItem> noObfuscation;
    public final Set<DexItem> reasonAsked;
    public final Set<DexItem> keepPackageName;
    public final Set<DexItem> checkDiscarded;
    public final Set<DexItem> alwaysInline;
    public final Map<DexItem, com.debughelper.tools.r8.shaking.ProguardMemberRule> noSideEffects;
    public final Map<DexItem, com.debughelper.tools.r8.shaking.ProguardMemberRule> assumedValues;
    private final Map<DexItem, Map<DexItem, ProguardKeepRule>> dependentNoShrinking;
    public final Set<DexItem> identifierNameStrings;
    public final Set<ProguardIfRule> ifRules;

    private boolean isTypeEncodedMethodOrEncodedField(DexItem item) {
      assert item instanceof DexType
          || item instanceof DexEncodedMethod
          || item instanceof DexEncodedField;
      return item instanceof DexType
          || item instanceof DexEncodedMethod
          || item instanceof DexEncodedField;
    }

    private boolean legalNoObfuscationItems(Set<DexItem> items) {
      assert items.stream().allMatch(this::isTypeEncodedMethodOrEncodedField);
      return true;
    }

    private boolean legalDependentNoShrinkingItems(
        Map<DexItem, Map<DexItem, ProguardKeepRule>> dependentNoShrinking) {
      assert dependentNoShrinking.keySet().stream()
          .allMatch(this::isTypeEncodedMethodOrEncodedField);
      return true;
    }

    private RootSet(
        Map<DexItem, ProguardKeepRule> noShrinking,
        Set<DexItem> noOptimization,
        Set<DexItem> noObfuscation,
        Set<DexItem> reasonAsked,
        Set<DexItem> keepPackageName,
        Set<DexItem> checkDiscarded,
        Set<DexItem> alwaysInline,
        Map<DexItem, com.debughelper.tools.r8.shaking.ProguardMemberRule> noSideEffects,
        Map<DexItem, ProguardMemberRule> assumedValues,
        Map<DexItem, Map<DexItem, ProguardKeepRule>> dependentNoShrinking,
        Set<DexItem> identifierNameStrings,
        Set<ProguardIfRule> ifRules) {
      this.noShrinking = Collections.unmodifiableMap(noShrinking);
      this.noOptimization = noOptimization;
      this.noObfuscation = noObfuscation;
      this.reasonAsked = Collections.unmodifiableSet(reasonAsked);
      this.keepPackageName = Collections.unmodifiableSet(keepPackageName);
      this.checkDiscarded = Collections.unmodifiableSet(checkDiscarded);
      this.alwaysInline = Collections.unmodifiableSet(alwaysInline);
      this.noSideEffects = Collections.unmodifiableMap(noSideEffects);
      this.assumedValues = Collections.unmodifiableMap(assumedValues);
      this.dependentNoShrinking = dependentNoShrinking;
      this.identifierNameStrings = Collections.unmodifiableSet(identifierNameStrings);
      this.ifRules = Collections.unmodifiableSet(ifRules);
      assert legalNoObfuscationItems(noObfuscation);
      assert legalDependentNoShrinkingItems(dependentNoShrinking);
    }

    Map<DexItem, ProguardKeepRule> getDependentItems(DexItem item) {
      assert item instanceof DexType
          || item instanceof DexEncodedMethod
          || item instanceof DexEncodedField;
      return Collections
          .unmodifiableMap(dependentNoShrinking.getOrDefault(item, Collections.emptyMap()));
    }

    private boolean isStaticMember(Entry<DexItem, ProguardKeepRule> entry) {
      if (entry.getKey() instanceof DexEncodedMethod) {
        return ((DexEncodedMethod) entry.getKey()).accessFlags.isStatic();
      }
      if (entry.getKey() instanceof DexEncodedField) {
        return ((DexEncodedField) entry.getKey()).accessFlags.isStatic();
      }
      return false;
    }

    Map<DexItem, ProguardKeepRule> getDependentStaticMembers(DexItem item) {
      assert item instanceof DexType;
      return getDependentItems(item).entrySet().stream()
          .filter(this::isStaticMember)
          .collect(Collectors.toMap(Entry::getKey, Entry::getValue));
    }

    @Override
    public String toString() {
      StringBuilder builder = new StringBuilder();
      builder.append("RootSet");

      builder.append("\nnoShrinking: " + noShrinking.size());
      builder.append("\nnoOptimization: " + noOptimization.size());
      builder.append("\nnoObfuscation: " + noObfuscation.size());
      builder.append("\nreasonAsked: " + reasonAsked.size());
      builder.append("\nkeepPackageName: " + keepPackageName.size());
      builder.append("\ncheckDiscarded: " + checkDiscarded.size());
      builder.append("\nnoSideEffects: " + noSideEffects.size());
      builder.append("\nassumedValues: " + assumedValues.size());
      builder.append("\ndependentNoShrinking: " + dependentNoShrinking.size());
      builder.append("\nidentifierNameStrings: " + identifierNameStrings.size());
      builder.append("\nifRules: " + ifRules.size());

      builder.append("\n\nNo Shrinking:");
      noShrinking.keySet().stream()
          .sorted(Comparator.comparing(DexItem::toSourceString))
          .forEach(a -> builder
              .append("\n").append(a.toSourceString()).append(" ").append(noShrinking.get(a)));
      builder.append("\n");
      return builder.toString();
    }
  }

  // A partial RootSet that becomes live due to the enabled -if rule.
  static class ConsequentRootSet {
    final Map<DexItem, ProguardKeepRule> noShrinking;
    final Set<DexItem> noOptimization;
    final Set<DexItem> noObfuscation;

    private ConsequentRootSet(
        Map<DexItem, ProguardKeepRule> noShrinking,
        Set<DexItem> noOptimization,
        Set<DexItem> noObfuscation) {
      this.noShrinking = Collections.unmodifiableMap(noShrinking);
      this.noOptimization = Collections.unmodifiableSet(noOptimization);
      this.noObfuscation = Collections.unmodifiableSet(noObfuscation);
    }
  }
}
