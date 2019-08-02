// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.shaking;

import com.debughelper.tools.r8.shaking.ProguardClassNameList;
import com.debughelper.tools.r8.shaking.ProguardClassSpecification;
import com.debughelper.tools.r8.shaking.ProguardMemberRule;

import java.util.List;

public class ProguardWhyAreYouKeepingRule extends ProguardConfigurationRule {

  public static class Builder extends ProguardClassSpecification.Builder {

    private Builder() {
    }

    public ProguardWhyAreYouKeepingRule build() {
      return new ProguardWhyAreYouKeepingRule(classAnnotation, classAccessFlags,
          negatedClassAccessFlags, classTypeNegated, classType, classNames, inheritanceAnnotation,
          inheritanceClassName, inheritanceIsExtends, memberRules);
    }
  }

  private ProguardWhyAreYouKeepingRule(
      ProguardTypeMatcher classAnnotation,
      ProguardAccessFlags classAccessFlags,
      ProguardAccessFlags negatedClassAccessFlags,
      boolean classTypeNegated,
      ProguardClassType classType,
      ProguardClassNameList classNames,
      ProguardTypeMatcher inheritanceAnnotation,
      ProguardTypeMatcher inheritanceClassName,
      boolean inheritanceIsExtends,
      List<ProguardMemberRule> memberRules) {
    super(classAnnotation, classAccessFlags, negatedClassAccessFlags, classTypeNegated, classType,
        classNames, inheritanceAnnotation, inheritanceClassName, inheritanceIsExtends, memberRules);
  }

  public static ProguardWhyAreYouKeepingRule.Builder builder() {
    return new ProguardWhyAreYouKeepingRule.Builder();
  }

  @Override
  String typeString() {
    return "whyareyoukeeping";
  }
}
