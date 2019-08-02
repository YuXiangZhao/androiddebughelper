// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.shaking;

import com.debughelper.tools.r8.shaking.ProguardClassNameList;
import com.debughelper.tools.r8.shaking.ProguardClassSpecification;
import com.debughelper.tools.r8.shaking.ProguardMemberRule;
import com.debughelper.tools.r8.utils.StringUtils;
import com.google.common.collect.Iterables;
import java.util.Collections;
import java.util.List;
import java.util.stream.StreamSupport;

public abstract class ProguardConfigurationRule extends ProguardClassSpecification {
  ProguardConfigurationRule(
      ProguardTypeMatcher classAnnotation,
      ProguardAccessFlags classAccessFlags,
      ProguardAccessFlags negatedClassAccessFlags,
      boolean classTypeNegated,
      ProguardClassType classType,
      com.debughelper.tools.r8.shaking.ProguardClassNameList classNames,
      ProguardTypeMatcher inheritanceAnnotation,
      ProguardTypeMatcher inheritanceClassName,
      boolean inheritanceIsExtends,
      List<com.debughelper.tools.r8.shaking.ProguardMemberRule> memberRules) {
    super(classAnnotation, classAccessFlags, negatedClassAccessFlags, classTypeNegated, classType,
        classNames, inheritanceAnnotation, inheritanceClassName, inheritanceIsExtends, memberRules);
  }

  abstract String typeString();

  String modifierString() {
    return null;
  }

  public boolean applyToLibraryClasses() {
    return false;
  }

  protected Iterable<ProguardWildcard> getWildcards() {
    List<com.debughelper.tools.r8.shaking.ProguardMemberRule> memberRules = getMemberRules();
    return Iterables.concat(
        ProguardTypeMatcher.getWildcardsOrEmpty(getClassAnnotation()),
        ProguardClassNameList.getWildcardsOrEmpty(getClassNames()),
        ProguardTypeMatcher.getWildcardsOrEmpty(getInheritanceAnnotation()),
        ProguardTypeMatcher.getWildcardsOrEmpty(getInheritanceClassName()),
        memberRules != null
            ? memberRules.stream()
                .map(ProguardMemberRule::getWildcards)
                .flatMap(it -> StreamSupport.stream(it.spliterator(), false))
                ::iterator
            : Collections::emptyIterator
    );
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof ProguardConfigurationRule)) {
      return false;
    }
    ProguardKeepRule that = (ProguardKeepRule) o;
    return super.equals(that);
  }

  @Override
  public int hashCode() {
    return super.hashCode();
  }

  @Override
  protected StringBuilder append(StringBuilder builder, boolean includeMemberRules) {
    builder.append("-");
    builder.append(typeString());
    StringUtils.appendNonEmpty(builder, ",", modifierString(), null);
    builder.append(' ');
    super.append(builder, includeMemberRules);
    return builder;
  }
}
