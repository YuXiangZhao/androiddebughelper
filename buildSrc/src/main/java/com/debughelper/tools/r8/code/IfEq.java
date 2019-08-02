// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.code;

import com.debughelper.tools.r8.code.BytecodeStream;
import com.debughelper.tools.r8.code.Format22t;
import com.debughelper.tools.r8.ir.code.If.Type;
import com.debughelper.tools.r8.ir.code.If;
import com.debughelper.tools.r8.ir.code.ValueType;

public class IfEq extends Format22t {

  public static final int OPCODE = 0x32;
  public static final String NAME = "IfEq";
  public static final String SMALI_NAME = "if-eq";

  IfEq(int high, BytecodeStream stream) {
    super(high, stream);
  }

  public IfEq(int register1, int register2, int offset) {
    super(register1, register2, offset);
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
  public If.Type getType() {
    return If.Type.EQ;
  }

  @Override
  public com.debughelper.tools.r8.ir.code.ValueType getOperandType() {
    return ValueType.INT_OR_FLOAT_OR_NULL;
  }
}
