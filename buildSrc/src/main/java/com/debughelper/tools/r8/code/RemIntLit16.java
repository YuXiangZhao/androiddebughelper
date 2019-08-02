// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.code;

import com.debughelper.tools.r8.code.BytecodeStream;
import com.debughelper.tools.r8.code.Format22s;
import com.debughelper.tools.r8.ir.conversion.IRBuilder;
import com.debughelper.tools.r8.ir.code.NumericType;

public class RemIntLit16 extends Format22s {

  public static final int OPCODE = 0xd4;
  public static final String NAME = "RemIntLit16";
  public static final String SMALI_NAME = "rem-int/lit16";

  RemIntLit16(int high, BytecodeStream stream) {
    super(high, stream);
  }

  public RemIntLit16(int dest, int register, int constant) {
    super(dest, register, constant);
  }

  @Override
  public String getName() {
    return NAME;
  }

  @Override
  public String getSmaliName() {
    return SMALI_NAME;
  }

  @Override
  public int getOpcode() {
    return OPCODE;
  }

  @Override
  public void buildIR(IRBuilder builder) {
    builder.addRemLiteral(NumericType.INT, A, B, CCCC);
  }

  @Override
  public boolean canThrow() {
    return true;
  }
}
