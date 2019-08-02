// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.code;

import com.debughelper.tools.r8.code.BytecodeStream;
import com.debughelper.tools.r8.code.Format22x;
import com.debughelper.tools.r8.ir.conversion.IRBuilder;
import com.debughelper.tools.r8.ir.code.ValueType;

public class MoveFrom16 extends com.debughelper.tools.r8.code.Format22x {

  public static final int OPCODE = 0x2;
  public static final String NAME = "MoveFrom16";
  public static final String SMALI_NAME = "move-from/16";

  MoveFrom16(int high, BytecodeStream stream) {
    super(high, stream);
  }

  public MoveFrom16(int dest, int src) {
    super(dest, src);
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
    builder.addMove(ValueType.INT_OR_FLOAT, AA, BBBB);
  }
}
