// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.shaking;

import com.debughelper.tools.r8.errors.Unreachable;
import com.debughelper.tools.r8.graph.DexEncodedField;
import com.debughelper.tools.r8.graph.DexEncodedMethod;
import com.debughelper.tools.r8.graph.DexType;
import com.debughelper.tools.r8.shaking.ProguardConfigurationParser.IdentifierPatternWithWildcards;
import com.debughelper.tools.r8.utils.StringUtils;
import com.google.common.collect.Iterables;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

public class ProguardMemberRule {

  public static class Builder {

    private ProguardTypeMatcher annotation;
    private ProguardAccessFlags accessFlags = new ProguardAccessFlags();
    private ProguardAccessFlags negatedAccessFlags = new ProguardAccessFlags();
    private ProguardMemberType ruleType;
    private ProguardTypeMatcher type;
    private ProguardNameMatcher name;
    private List<ProguardTypeMatcher> arguments;
    private ProguardMemberRuleReturnValue returnValue;

    private Builder() {}

    public void setAnnotation(ProguardTypeMatcher annotation) {
      this.annotation = annotation;
    }

    public ProguardAccessFlags getAccessFlags() {
      return accessFlags;
    }

    public void setAccessFlags(ProguardAccessFlags flags) {
      accessFlags = flags;
    }

    public ProguardAccessFlags getNegatedAccessFlags() {
      return negatedAccessFlags;
    }

    public void setNegatedAccessFlags(ProguardAccessFlags flags) {
      negatedAccessFlags = flags;
    }

    public void setRuleType(ProguardMemberType ruleType) {
      this.ruleType = ruleType;
    }

    public ProguardTypeMatcher getTypeMatcher() {
      return type;
    }

    public void setTypeMatcher(ProguardTypeMatcher type) {
      this.type = type;
    }

    public void setName(ProguardConfigurationParser.IdentifierPatternWithWildcards identifierPatternWithWildcards) {
      this.name = ProguardNameMatcher.create(identifierPatternWithWildcards);
    }

    public void setArguments(List<ProguardTypeMatcher> arguments) {
      this.arguments = arguments;
    }

    public void setReturnValue(ProguardMemberRuleReturnValue value) {
      returnValue = value;
    }

    public boolean isValid() {
      return ruleType != null;
    }

    public ProguardMemberRule build() {
      assert isValid();
      return new ProguardMemberRule(annotation, accessFlags, negatedAccessFlags, ruleType, type,
          name, arguments, returnValue);
    }
  }

  private final ProguardTypeMatcher annotation;
  private final ProguardAccessFlags accessFlags;
  private final ProguardAccessFlags negatedAccessFlags;
  private final ProguardMemberType ruleType;
  private final ProguardTypeMatcher type;
  private final ProguardNameMatcher name;
  private final List<ProguardTypeMatcher> arguments;
  private final ProguardMemberRuleReturnValue returnValue;

  private ProguardMemberRule(
      ProguardTypeMatcher annotation,
      ProguardAccessFlags accessFlags,
      ProguardAccessFlags negatedAccessFlags,
      ProguardMemberType ruleType,
      ProguardTypeMatcher type,
      ProguardNameMatcher name,
      List<ProguardTypeMatcher> arguments,
      ProguardMemberRuleReturnValue returnValue) {
    this.annotation = annotation;
    this.accessFlags = accessFlags;
    this.negatedAccessFlags = negatedAccessFlags;
    this.ruleType = ruleType;
    this.type = type;
    this.name = name;
    this.arguments = arguments != null ? Collections.unmodifiableList(arguments) : null;
    this.returnValue = returnValue;
  }

  /**
   * Create a new empty builder.
   */
  public static Builder builder() {
    return new Builder();
  }

  public ProguardTypeMatcher getAnnotation() {
    return annotation;
  }

  public ProguardAccessFlags getAccessFlags() {
    return accessFlags;
  }

  public ProguardAccessFlags getNegatedAccessFlags() {
    return negatedAccessFlags;
  }

  public ProguardMemberType getRuleType() {
    return ruleType;
  }

  public ProguardTypeMatcher getType() {
    return type;
  }

  public ProguardNameMatcher getName() {
    return name;
  }

  public List<ProguardTypeMatcher> getArguments() {
    return arguments;
  }

  public boolean hasReturnValue() {
    return returnValue != null;
  }

  public ProguardMemberRuleReturnValue getReturnValue() {
    return returnValue;
  }

  public ProguardTypeMatcher getTypeMatcher() {
    return type;
  }

  public boolean matches(DexEncodedField field, DexStringCache stringCache) {
    switch (getRuleType()) {
      case ALL:
      case ALL_FIELDS:
        // Access flags check.
        if (!getAccessFlags().containsAll(field.accessFlags)
            || !getNegatedAccessFlags().containsNone(field.accessFlags)) {
          break;
        }
        // Annotations check.
        return RootSetBuilder.containsAnnotation(annotation, field.annotations);
      case FIELD:
        // Name check.
        String name = stringCache.lookupString(field.field.name);
        if (!getName().matches(name)) {
          break;
        }
        // Access flags check.
        if (!getAccessFlags().containsAll(field.accessFlags)
            || !getNegatedAccessFlags().containsNone(field.accessFlags)) {
          break;
        }
        // Type check.
        if (!this.type.matches(field.field.type)) {
          break;
        }
        // Annotations check
        if (!RootSetBuilder.containsAnnotation(annotation, field.annotations)) {
          break;
        }
        return true;
      case ALL_METHODS:
      case INIT:
      case CONSTRUCTOR:
      case METHOD:
        break;
    }
    return false;
  }

  public boolean matches(DexEncodedMethod method, DexStringCache stringCache) {
    switch (getRuleType()) {
      case ALL_METHODS:
        if (method.isClassInitializer()) {
          break;
        }
        // Fall through for all other methods.
      case ALL:
        // Access flags check.
        if (!getAccessFlags().containsAll(method.accessFlags)
            || !getNegatedAccessFlags().containsNone(method.accessFlags)) {
          break;
        }
        // Annotations check.
        return RootSetBuilder.containsAnnotation(annotation, method.annotations);
      case METHOD:
        // Check return type.
        if (!type.matches(method.method.proto.returnType)) {
          break;
        }
        // Fall through for access flags, name and arguments.
      case CONSTRUCTOR:
      case INIT:
        // Name check.
        String name = stringCache.lookupString(method.method.name);
        if (!getName().matches(name)) {
          break;
        }
        // Access flags check.
        if (!getAccessFlags().containsAll(method.accessFlags)
            || !getNegatedAccessFlags().containsNone(method.accessFlags)) {
          break;
        }
        // Annotations check.
        if (!RootSetBuilder.containsAnnotation(annotation, method.annotations)) {
          break;
        }
        // Parameter types check.
        List<ProguardTypeMatcher> arguments = getArguments();
        if (arguments.size() == 1 && arguments.get(0).isTripleDotPattern()) {
          return true;
        } else {
          DexType[] parameters = method.method.proto.parameters.values;
          if (parameters.length != arguments.size()) {
            break;
          }
          int i = 0;
          for (; i < parameters.length; i++) {
            if (!arguments.get(i).matches(parameters[i])) {
              break;
            }
          }
          if (i == parameters.length) {
            // All parameters matched.
            return true;
          }
        }
        break;
      case ALL_FIELDS:
      case FIELD:
        break;
    }
    return false;
  }

  Iterable<ProguardWildcard> getWildcards() {
    return Iterables.concat(
        ProguardTypeMatcher.getWildcardsOrEmpty(annotation),
        ProguardTypeMatcher.getWildcardsOrEmpty(type),
        ProguardNameMatcher.getWildcardsOrEmpty(name),
        arguments != null
            ? arguments.stream()
                .map(ProguardTypeMatcher::getWildcards)
                .flatMap(it -> StreamSupport.stream(it.spliterator(), false))
                ::iterator
            : Collections::emptyIterator
    );
  }

  ProguardMemberRule materialize() {
    return new ProguardMemberRule(
        getAnnotation() == null ? null : getAnnotation().materialize(),
        getAccessFlags(),
        getNegatedAccessFlags(),
        getRuleType(),
        getType() == null ? null : getType().materialize(),
        getName() == null ? null : getName().materialize(),
        getArguments() == null ? null :
            getArguments().stream()
                .map(ProguardTypeMatcher::materialize).collect(Collectors.toList()),
        getReturnValue());
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof ProguardMemberRule)) {
      return false;
    }

    ProguardMemberRule that = (ProguardMemberRule) o;

    if (annotation != null ? !annotation.equals(that.annotation) : that.annotation != null) {
      return false;
    }
    if (!accessFlags.equals(that.accessFlags)) {
      return false;
    }
    if (!negatedAccessFlags.equals(that.negatedAccessFlags)) {
      return false;
    }
    if (ruleType != that.ruleType) {
      return false;
    }
    if (name != null ? !name.equals(that.name) : that.name != null) {
      return false;
    }
    if (type != null ? !type.equals(that.type) : that.type != null) {
      return false;
    }
    return arguments != null ? arguments.equals(that.arguments) : that.arguments == null;
  }

  @Override
  public int hashCode() {
    int result = annotation != null ? annotation.hashCode() : 0;
    result = 31 * result + accessFlags.hashCode();
    result = 31 * result + negatedAccessFlags.hashCode();
    result = 31 * result + (ruleType != null ? ruleType.hashCode() : 0);
    result = 31 * result + (type != null ? type.hashCode() : 0);
    result = 31 * result + (name != null ? name.hashCode() : 0);
    result = 31 * result + (arguments != null ? arguments.hashCode() : 0);
    return result;
  }

  @Override
  public String toString() {
    StringBuilder result = new StringBuilder();
    ProguardKeepRule.appendNonEmpty(result, "@", annotation, " ");
    ProguardKeepRule.appendNonEmpty(result, null, accessFlags, " ");
    ProguardKeepRule
        .appendNonEmpty(result, null, negatedAccessFlags.toString().replace(" ", " !"), " ");
    switch (getRuleType()) {
      case ALL_FIELDS:
        result.append("<fields>");
        break;
      case ALL_METHODS:
        result.append("<methods>");
        break;
      case METHOD:
        result.append(getType());
        result.append(' ');
        // Fall through for rest of method signature.
      case CONSTRUCTOR:
      case INIT: {
        result.append(getName());
        result.append('(');
        result.append(StringUtils.join(getArguments(), ","));
        result.append(')');
        break;
      }
      case FIELD: {
        result.append(getType());
        result.append(' ');
        result.append(getName());
        break;
      }
      case ALL: {
        result.append("*");
        break;
      }
      default:
        throw new Unreachable("Unknown kind of member rule");
    }
    if (hasReturnValue()) {
      result.append(returnValue.toString());
    }
    return result.toString();
  }

  public static ProguardMemberRule defaultKeepAllRule() {
    ProguardMemberRule.Builder ruleBuilder = new ProguardMemberRule.Builder();
    ruleBuilder.setRuleType(ProguardMemberType.ALL);
    return ruleBuilder.build();
  }

}
