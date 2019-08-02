// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.ir.code;

import com.debughelper.tools.r8.cf.code.CfLogicalBinop;
import com.debughelper.tools.r8.code.ShlInt;
import com.debughelper.tools.r8.code.ShlInt2Addr;
import com.debughelper.tools.r8.code.ShlIntLit8;
import com.debughelper.tools.r8.code.ShlLong;
import com.debughelper.tools.r8.code.ShlLong2Addr;
import com.debughelper.tools.r8.errors.Unreachable;
import com.debughelper.tools.r8.ir.code.Instruction;
import com.debughelper.tools.r8.ir.code.NumericType;

public class Shl extends LogicalBinop {

  public Shl(NumericType type, Value dest, Value left, Value right) {
    super(type, dest, left, right);
  }

  @Override
  boolean fitsInDexInstruction(Value value) {
    // The shl instruction only has the /lit8 variant.
    return fitsInLit8Instruction(value);
  }

  @Override
  public boolean isCommutative() {
    return false;
  }

  @Override
  public boolean isShl() {
    return true;
  }

  @Override
  public Shl asShl() {
    return this;
  }

  @Override
  public com.debughelper.tools.r8.code.Instruction CreateInt(int dest, int left, int right) {
    return new ShlInt(dest, left, right);
  }

  @Override
  public com.debughelper.tools.r8.code.Instruction CreateLong(int dest, int left, int right) {
    return new ShlLong(dest, left, right);
  }

  @Override
  public com.debughelper.tools.r8.code.Instruction CreateInt2Addr(int left, int right) {
    return new ShlInt2Addr(left, right);
  }

  @Override
  public com.debughelper.tools.r8.code.Instruction CreateLong2Addr(int left, int right) {
    return new ShlLong2Addr(left, right);
  }

  @Override
  public com.debughelper.tools.r8.code.Instruction CreateIntLit8(int dest, int left, int constant) {
    return new ShlIntLit8(dest, left, constant);
  }

  @Override
  public com.debughelper.tools.r8.code.Instruction CreateIntLit16(int dest, int left, int constant) {
    throw new Unreachable("Unsupported instruction ShlIntLit16");
  }

  @Override
  public boolean identicalNonValueNonPositionParts(com.debughelper.tools.r8.ir.code.Instruction other) {
    return other.isShl() && other.asShl().type == type;
  }

  @Override
  public int compareNonValueParts(Instruction other) {
    return type.ordinal() - other.asShl().type.ordinal();
  }

  @Override
  int foldIntegers(int left, int right) {
    return left << right;
  }

  @Override
  long foldLongs(long left, long right) {
    return left << right;
  }

  @Override
  CfLogicalBinop.Opcode getCfOpcode() {
    return CfLogicalBinop.Opcode.Shl;
  }
}
