// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.ir.code;

import com.debughelper.tools.r8.cf.LoadStoreHelper;
import com.debughelper.tools.r8.code.FillArrayData;
import com.debughelper.tools.r8.code.FillArrayDataPayload;
import com.debughelper.tools.r8.dex.Constants;
import com.debughelper.tools.r8.errors.Unreachable;
import com.debughelper.tools.r8.graph.DexType;
import com.debughelper.tools.r8.ir.code.IRCode;
import com.debughelper.tools.r8.ir.code.Instruction;
import com.debughelper.tools.r8.ir.code.InstructionListIterator;
import com.debughelper.tools.r8.ir.conversion.CfBuilder;
import com.debughelper.tools.r8.ir.conversion.DexBuilder;
import com.debughelper.tools.r8.ir.optimize.Inliner.Constraint;
import com.debughelper.tools.r8.shaking.Enqueuer.AppInfoWithLiveness;
import com.debughelper.tools.r8.utils.InternalOptions;
import java.util.Arrays;

public class NewArrayFilledData extends com.debughelper.tools.r8.ir.code.Instruction {
  private static final String ERROR_MESSAGE =
      "Conversion from DEX to classfile not supported for NewArrayFilledData";

  public final int element_width;
  public final long size;
  public final short[] data;

  // Primitive array with fill-array-data. The type is not known from the original Dex instruction.
  public NewArrayFilledData(Value src, int element_width, long size, short[] data) {
    super(null, src);
    this.element_width = element_width;
    this.size = size;
    this.data = data;
  }

  public Value src() {
    return inValues.get(0);
  }

  public FillArrayDataPayload createPayload() {
    return new FillArrayDataPayload(element_width, size, data);
  }

  @Override
  public void buildDex(DexBuilder builder) {
    int src = builder.allocatedRegister(src(), getNumber());
    builder.addFillArrayData(this, new FillArrayData(src));
  }

  @Override
  public void buildCf(CfBuilder builder) {
    throw new Unreachable(ERROR_MESSAGE);
  }

  @Override
  public boolean identicalNonValueNonPositionParts(com.debughelper.tools.r8.ir.code.Instruction other) {
    if (!other.isNewArrayFilledData()) {
      return false;
    }
    NewArrayFilledData o = other.asNewArrayFilledData();
    return o.element_width == element_width
        && o.size == size
        && Arrays.equals(o.data, data);
  }

  @Override
  public int compareNonValueParts(Instruction other) {
    NewArrayFilledData o = other.asNewArrayFilledData();
    int result;
    result = element_width - o.element_width;
    if (result != 0) {
      return result;
    }
    result = Long.signum(size - o.size);
    if (result != 0) {
      return result;
    }
    assert data.length == o.data.length;
    for (int i = 0; i < data.length; i++) {
      result = data[i] - o.data[i];
      if (result != 0) {
        return result;
      }
    }
    return 0;
  }

  @Override
  public int maxInValueRegister() {
    return Constants.U8BIT_MAX;
  }

  @Override
  public int maxOutValueRegister() {
    assert false : "NewArrayFilledData defines no values.";
    return 0;
  }

  @Override
  public boolean canBeDeadCode(IRCode code, InternalOptions options) {
    // Side-effects its input values.
    return false;
  }

  @Override
  public boolean isNewArrayFilledData() {
    return true;
  }

  @Override
  public NewArrayFilledData asNewArrayFilledData() {
    return this;
  }

  @Override
  public Constraint inliningConstraint(AppInfoWithLiveness info, DexType invocationContext) {
    return Constraint.ALWAYS;
  }

  @Override
  public void insertLoadAndStores(InstructionListIterator it, LoadStoreHelper helper) {
    throw new Unreachable(ERROR_MESSAGE);
  }
}
