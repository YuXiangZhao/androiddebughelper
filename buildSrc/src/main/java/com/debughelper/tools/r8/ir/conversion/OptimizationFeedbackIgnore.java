// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.debughelper.tools.r8.ir.conversion;

import com.debughelper.tools.r8.graph.DexEncodedMethod;
import com.debughelper.tools.r8.graph.DexEncodedMethod.ClassInlinerEligibility;
import com.debughelper.tools.r8.ir.conversion.OptimizationFeedback;
import com.debughelper.tools.r8.ir.optimize.Inliner.Constraint;

public class OptimizationFeedbackIgnore implements OptimizationFeedback {

  @Override
  public void methodReturnsArgument(com.debughelper.tools.r8.graph.DexEncodedMethod method, int argument) {}

  @Override
  public void methodReturnsConstant(com.debughelper.tools.r8.graph.DexEncodedMethod method, long value) {}

  @Override
  public void methodNeverReturnsNull(com.debughelper.tools.r8.graph.DexEncodedMethod method) {}

  @Override
  public void methodNeverReturnsNormally(com.debughelper.tools.r8.graph.DexEncodedMethod method) {}

  @Override
  public void markProcessed(com.debughelper.tools.r8.graph.DexEncodedMethod method, Constraint state) {}

  @Override
  public void markCheckNullReceiverBeforeAnySideEffect(com.debughelper.tools.r8.graph.DexEncodedMethod method, boolean mark) {}

  @Override
  public void markTriggerClassInitBeforeAnySideEffect(com.debughelper.tools.r8.graph.DexEncodedMethod method, boolean mark) {}

  @Override
  public void setClassInlinerEligibility(
          com.debughelper.tools.r8.graph.DexEncodedMethod method, com.debughelper.tools.r8.graph.DexEncodedMethod.ClassInlinerEligibility eligibility) {
  }

  @Override
  public void setInitializerEnablingJavaAssertions(com.debughelper.tools.r8.graph.DexEncodedMethod method) {
  }
}
