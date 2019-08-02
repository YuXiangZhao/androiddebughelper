// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.ir.code;

import com.debughelper.tools.r8.cf.LoadStoreHelper;
import com.debughelper.tools.r8.cf.TypeVerificationHelper;
import com.debughelper.tools.r8.cf.code.CfStore;
import com.debughelper.tools.r8.graph.DexType;
import com.debughelper.tools.r8.ir.code.Instruction;
import com.debughelper.tools.r8.ir.code.InstructionListIterator;
import com.debughelper.tools.r8.ir.code.Move;
import com.debughelper.tools.r8.ir.conversion.CfBuilder;

/**
 * Instruction introducing an SSA value with attached local information.
 *
 * <p>All instructions may have attached local information (defined as the local information of
 * their outgoing value). This instruction is needed to mark a transition of an existing value (with
 * a possible local attached) to a new value that has a local (possibly the same one). If all
 * ingoing values end up having the same local this can be safely removed.
 *
 * <p>For valid debug info, this instruction should have at least one debug user, denoting the end
 * of its range, and thus it should be live.
 */
public class DebugLocalWrite extends Move {

  public DebugLocalWrite(Value dest, Value src) {
    super(dest, src);
    assert dest.hasLocalInfo();
    assert dest.getLocalInfo() != src.getLocalInfo() || src.isPhi();
  }

  @Override
  public boolean isDebugLocalWrite() {
    return true;
  }

  @Override
  public DebugLocalWrite asDebugLocalWrite() {
    return this;
  }

  @Override
  public boolean isOutConstant() {
    return false;
  }

  @Override
  public boolean identicalNonValueNonPositionParts(Instruction other) {
    return other.isDebugLocalWrite();
  }

  @Override
  public boolean hasInvariantVerificationType() {
    return false;
  }

  @Override
  public DexType computeVerificationType(TypeVerificationHelper helper) {
    return helper.getType(src());
  }

  @Override
  public void insertLoadAndStores(InstructionListIterator it, LoadStoreHelper helper) {
    helper.loadInValues(this, it);
    // A local-write does not have an outgoing stack value, but in writes directly to the local.
  }

  @Override
  public void buildCf(CfBuilder builder) {
    builder.add(new CfStore(outType(), builder.getLocalRegister(outValue())));
  }
}
