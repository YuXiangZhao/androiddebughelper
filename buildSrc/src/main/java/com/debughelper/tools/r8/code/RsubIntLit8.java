// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.code;

import com.debughelper.tools.r8.code.BytecodeStream;
import com.debughelper.tools.r8.ir.conversion.IRBuilder;
import com.debughelper.tools.r8.ir.code.NumericType;

public class RsubIntLit8 extends Format22b {

  public static final int OPCODE = 0xd9;
  public static final String NAME = "RsubIntLit8";
  public static final String SMALI_NAME = "rsub-int/lit8";

  RsubIntLit8(int high, BytecodeStream stream) {
    super(high, stream);
  }

  public RsubIntLit8(int dest, int register, int constant) {
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
    builder.addRsubLiteral(NumericType.INT, AA, BB, CC);
  }
}
