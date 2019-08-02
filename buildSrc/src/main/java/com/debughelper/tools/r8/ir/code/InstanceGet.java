// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.debughelper.tools.r8.ir.code;

import com.debughelper.tools.r8.cf.LoadStoreHelper;
import com.debughelper.tools.r8.cf.TypeVerificationHelper;
import com.debughelper.tools.r8.cf.code.CfFieldInstruction;
import com.debughelper.tools.r8.code.Iget;
import com.debughelper.tools.r8.code.IgetBoolean;
import com.debughelper.tools.r8.code.IgetByte;
import com.debughelper.tools.r8.code.IgetChar;
import com.debughelper.tools.r8.code.IgetObject;
import com.debughelper.tools.r8.code.IgetShort;
import com.debughelper.tools.r8.code.IgetWide;
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
import com.debughelper.tools.r8.ir.code.MemberType;
import com.debughelper.tools.r8.ir.conversion.CfBuilder;
import com.debughelper.tools.r8.ir.conversion.DexBuilder;
import java.util.function.Function;
import org.objectweb.asm.Opcodes;

public class InstanceGet extends FieldInstruction {

  public InstanceGet(MemberType type, Value dest, Value object, DexField field) {
    super(type, field, dest, object);
  }

  public Value dest() {
    return outValue;
  }

  public Value object() {
    assert inValues.size() == 1;
    return inValues.get(0);
  }

  @Override
  public void buildDex(DexBuilder builder) {
    int destRegister = builder.allocatedRegister(dest(), getNumber());
    int objectRegister = builder.allocatedRegister(object(), getNumber());
    com.debughelper.tools.r8.code.Instruction instruction;
    switch (type) {
      case INT:
      case FLOAT:
      case INT_OR_FLOAT:
        instruction = new Iget(destRegister, objectRegister, field);
        break;
      case LONG:
      case DOUBLE:
      case LONG_OR_DOUBLE:
        instruction = new IgetWide(destRegister, objectRegister, field);
        break;
      case OBJECT:
        instruction = new IgetObject(destRegister, objectRegister, field);
        break;
      case BOOLEAN:
        instruction = new IgetBoolean(destRegister, objectRegister, field);
        break;
      case BYTE:
        instruction = new IgetByte(destRegister, objectRegister, field);
        break;
      case CHAR:
        instruction = new IgetChar(destRegister, objectRegister, field);
        break;
      case SHORT:
        instruction = new IgetShort(destRegister, objectRegister, field);
        break;
      default:
        throw new Unreachable("Unexpected type " + type);
    }
    builder.add(this, instruction);
  }

  @Override
  public boolean instructionTypeCanThrow() {
    return true;
  }

  @Override
  public int maxInValueRegister() {
    return Constants.U4BIT_MAX;
  }

  @Override
  public int maxOutValueRegister() {
    return Constants.U4BIT_MAX;
  }

  @Override
  public boolean identicalNonValueNonPositionParts(com.debughelper.tools.r8.ir.code.Instruction other) {
    if (!other.isInstanceGet()) {
      return false;
    }
    InstanceGet o = other.asInstanceGet();
    return o.field == field && o.type == type;
  }

  @Override
  public int compareNonValueParts(Instruction other) {
    InstanceGet o = other.asInstanceGet();
    int result = field.slowCompareTo(o.field);
    if (result != 0) {
      return result;
    }
    return type.ordinal() - o.type.ordinal();
  }

  @Override
  DexEncodedField lookupTarget(DexType type, AppInfo appInfo) {
    return appInfo.lookupInstanceTarget(type, field);
  }

  @Override
  public boolean isInstanceGet() {
    return true;
  }

  @Override
  public InstanceGet asInstanceGet() {
    return this;
  }

  @Override
  public String toString() {
    return super.toString() + "; field: " + field.toSourceString();
  }

  @Override
  public TypeLatticeElement evaluate(
      AppInfo appInfo, Function<Value, TypeLatticeElement> getLatticeElement) {
    return TypeLatticeElement.fromDexType(field.type, true);
  }

  @Override
  public boolean hasInvariantVerificationType() {
    return true;
  }

  @Override
  public DexType computeVerificationType(TypeVerificationHelper helper) {
    return field.type;
  }

  @Override
  public void insertLoadAndStores(InstructionListIterator it, LoadStoreHelper helper) {
    helper.loadInValues(this, it);
    helper.storeOutValue(this, it);
  }

  @Override
  public void buildCf(CfBuilder builder) {
    builder.add(new CfFieldInstruction(Opcodes.GETFIELD, field, builder.resolveField(field)));
  }

  @Override
  public boolean throwsNpeIfValueIsNull(Value value) {
    return object() == value;
  }
}
