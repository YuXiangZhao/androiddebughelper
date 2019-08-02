// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.ir.code;

import com.debughelper.tools.r8.cf.LoadStoreHelper;
import com.debughelper.tools.r8.cf.TypeVerificationHelper;
import com.debughelper.tools.r8.cf.code.CfFieldInstruction;
import com.debughelper.tools.r8.code.Sget;
import com.debughelper.tools.r8.code.SgetBoolean;
import com.debughelper.tools.r8.code.SgetByte;
import com.debughelper.tools.r8.code.SgetChar;
import com.debughelper.tools.r8.code.SgetObject;
import com.debughelper.tools.r8.code.SgetShort;
import com.debughelper.tools.r8.code.SgetWide;
import com.debughelper.tools.r8.dex.Constants;
import com.debughelper.tools.r8.errors.Unreachable;
import com.debughelper.tools.r8.graph.AppInfo;
import com.debughelper.tools.r8.graph.DexEncodedField;
import com.debughelper.tools.r8.graph.DexField;
import com.debughelper.tools.r8.graph.DexType;
import com.debughelper.tools.r8.ir.analysis.type.TypeLatticeElement;
import com.debughelper.tools.r8.ir.code.FieldInstruction;
import com.debughelper.tools.r8.ir.code.Instruction;
import com.debughelper.tools.r8.ir.code.InstructionListIterator;
import com.debughelper.tools.r8.ir.conversion.CfBuilder;
import com.debughelper.tools.r8.ir.conversion.DexBuilder;
import java.util.function.Function;
import org.objectweb.asm.Opcodes;

public class StaticGet extends FieldInstruction {

  public StaticGet(MemberType type, Value dest, DexField field) {
    super(type, field, dest, (Value) null);
  }

  public Value dest() {
    return outValue;
  }

  @Override
  public void buildDex(DexBuilder builder) {
    com.debughelper.tools.r8.code.Instruction instruction;
    int dest = builder.allocatedRegister(dest(), getNumber());
    switch (type) {
      case INT:
      case FLOAT:
      case INT_OR_FLOAT:
        instruction = new Sget(dest, field);
        break;
      case LONG:
      case DOUBLE:
      case LONG_OR_DOUBLE:
        instruction = new SgetWide(dest, field);
        break;
      case OBJECT:
        instruction = new SgetObject(dest, field);
        break;
      case BOOLEAN:
        instruction = new SgetBoolean(dest, field);
        break;
      case BYTE:
        instruction = new SgetByte(dest, field);
        break;
      case CHAR:
        instruction = new SgetChar(dest, field);
        break;
      case SHORT:
        instruction = new SgetShort(dest, field);
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
    return Constants.U8BIT_MAX;
  }

  @Override
  public boolean identicalNonValueNonPositionParts(com.debughelper.tools.r8.ir.code.Instruction other) {
    if (!other.isStaticGet()) {
      return false;
    }
    StaticGet o = other.asStaticGet();
    return o.field == field && o.type == type;
  }

  @Override
  public int compareNonValueParts(Instruction other) {
    StaticGet o = other.asStaticGet();
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
  public boolean isStaticGet() {
    return true;
  }

  @Override
  public StaticGet asStaticGet() {
    return this;
  }

  @Override
  public void insertLoadAndStores(InstructionListIterator it, LoadStoreHelper helper) {
    helper.storeOutValue(this, it);
  }

  @Override
  public void buildCf(CfBuilder builder) {
    builder.add(new CfFieldInstruction(Opcodes.GETSTATIC, field, builder.resolveField(field)));
  }

  @Override
  public DexType computeVerificationType(TypeVerificationHelper helper) {
    return field.type;
  }

  @Override
  public TypeLatticeElement evaluate(
      AppInfo appInfo, Function<Value, TypeLatticeElement> getLatticeElement) {
    return TypeLatticeElement.fromDexType(field.type, true);
  }

  @Override
  public boolean triggersInitializationOfClass(DexType klass) {
    return field.clazz == klass;
  }
}
