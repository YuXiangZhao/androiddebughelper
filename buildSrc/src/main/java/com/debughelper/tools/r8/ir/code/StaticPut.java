// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.ir.code;

import com.debughelper.tools.r8.cf.LoadStoreHelper;
import com.debughelper.tools.r8.cf.code.CfFieldInstruction;
import com.debughelper.tools.r8.code.Sput;
import com.debughelper.tools.r8.code.SputBoolean;
import com.debughelper.tools.r8.code.SputByte;
import com.debughelper.tools.r8.code.SputChar;
import com.debughelper.tools.r8.code.SputObject;
import com.debughelper.tools.r8.code.SputShort;
import com.debughelper.tools.r8.code.SputWide;
import com.debughelper.tools.r8.dex.Constants;
import com.debughelper.tools.r8.errors.Unreachable;
import com.debughelper.tools.r8.graph.AppInfo;
import com.debughelper.tools.r8.graph.DexEncodedField;
import com.debughelper.tools.r8.graph.DexField;
import com.debughelper.tools.r8.graph.DexType;
import com.debughelper.tools.r8.ir.code.FieldInstruction;
import com.debughelper.tools.r8.ir.code.Instruction;
import com.debughelper.tools.r8.ir.code.InstructionListIterator;
import com.debughelper.tools.r8.ir.code.MemberType;
import com.debughelper.tools.r8.ir.conversion.CfBuilder;
import com.debughelper.tools.r8.ir.conversion.DexBuilder;
import org.objectweb.asm.Opcodes;

public class StaticPut extends FieldInstruction {

  public StaticPut(MemberType type, Value source, DexField field) {
    super(type, field, null, source);
  }

  public Value inValue() {
    assert inValues.size() == 1;
    return inValues.get(0);
  }

  @Override
  public void buildDex(DexBuilder builder) {
    com.debughelper.tools.r8.code.Instruction instruction;
    int src = builder.allocatedRegister(inValue(), getNumber());
    switch (type) {
      case INT:
      case FLOAT:
      case INT_OR_FLOAT:
        instruction = new Sput(src, field);
        break;
      case LONG:
      case DOUBLE:
      case LONG_OR_DOUBLE:
        instruction = new SputWide(src, field);
        break;
      case OBJECT:
        instruction = new SputObject(src, field);
        break;
      case BOOLEAN:
        instruction = new SputBoolean(src, field);
        break;
      case BYTE:
        instruction = new SputByte(src, field);
        break;
      case CHAR:
        instruction = new SputChar(src, field);
        break;
      case SHORT:
        instruction = new SputShort(src, field);
        break;
      default:
        throw new Unreachable("Unexpected type " + type);
    }
    builder.add(this, instruction);
  }

  @Override
  public boolean instructionTypeCanThrow() {
    // This can cause <clinit> to run.
    return true;
  }

  @Override
  public int maxInValueRegister() {
    return Constants.U8BIT_MAX;
  }

  @Override
  public int maxOutValueRegister() {
    assert false : "StaticPut instructions define no values.";
    return 0;
  }

  @Override
  public boolean identicalNonValueNonPositionParts(com.debughelper.tools.r8.ir.code.Instruction other) {
    if (!other.isStaticPut()) {
      return false;
    }
    StaticPut o = other.asStaticPut();
    return o.field == field && o.type == type;
  }

  @Override
  public int compareNonValueParts(Instruction other) {
    StaticPut o = other.asStaticPut();
    int result;
    result = field.slowCompareTo(o.field);
    if (result != 0) {
      return result;
    }
    return type.ordinal() - o.type.ordinal();
  }

  @Override
  DexEncodedField lookupTarget(DexType type, AppInfo appInfo) {
    return appInfo.lookupStaticTarget(type, field);
  }

  @Override
  public String toString() {
    return super.toString() + "; field: " + field.toSourceString();
  }

  @Override
  public boolean isStaticPut() {
    return true;
  }

  @Override
  public StaticPut asStaticPut() {
    return this;
  }

  @Override
  public void insertLoadAndStores(InstructionListIterator it, LoadStoreHelper helper) {
    helper.loadInValues(this, it);
  }

  @Override
  public void buildCf(CfBuilder builder) {
    builder.add(new CfFieldInstruction(Opcodes.PUTSTATIC, field, builder.resolveField(field)));
  }

  @Override
  public boolean triggersInitializationOfClass(DexType klass) {
    return field.clazz == klass;
  }
}
