// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.cf.code;

import com.debughelper.tools.r8.cf.CfPrinter;
import com.debughelper.tools.r8.cf.code.CfLabel;
import com.debughelper.tools.r8.graph.DexType;
import com.debughelper.tools.r8.graph.UseRegistry;
import com.debughelper.tools.r8.ir.conversion.CfSourceCode;
import com.debughelper.tools.r8.ir.conversion.CfState;
import com.debughelper.tools.r8.ir.conversion.IRBuilder;
import com.debughelper.tools.r8.naming.NamingLens;
import org.objectweb.asm.MethodVisitor;

public abstract class CfInstruction {

  public abstract void write(MethodVisitor visitor, NamingLens lens);

  public abstract void print(CfPrinter printer);

  @Override
  public String toString() {
    CfPrinter printer = new CfPrinter();
    print(printer);
    return printer.toString();
  }

  public void registerUse(UseRegistry registry, DexType clazz) {
    // Intentionally empty.
  }

  public CfLabel getTarget() {
    return null;
  }

  /** Return true if this instruction is CfReturn or CfReturnVoid. */
  public boolean isReturn() {
    return false;
  }

  /** Return true if this instruction is CfIf or CfIfCmp. */
  public boolean isConditionalJump() {
    return false;
  }

  /** Return true if this instruction or its DEX equivalent can throw. */
  public boolean canThrow() {
    return false;
  }

  public abstract void buildIR(IRBuilder builder, CfState state, CfSourceCode code);

  /** Return true if this instruction directly emits IR instructions. */
  public boolean emitsIR() {
    return true;
  }
}
