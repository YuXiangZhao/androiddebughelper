// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.graph;

import com.debughelper.tools.r8.dex.Constants;
import com.debughelper.tools.r8.dex.IndexedItemCollection;
import com.debughelper.tools.r8.graph.Descriptor;
import com.debughelper.tools.r8.graph.DexField;
import com.debughelper.tools.r8.graph.DexItem;
import com.debughelper.tools.r8.graph.DexType;
import com.debughelper.tools.r8.graph.IndexedDexItem;
import com.debughelper.tools.r8.graph.JarApplicationReader;
import com.debughelper.tools.r8.graph.ObjectToOffsetMapping;
import com.debughelper.tools.r8.graph.PresortedComparable;
import com.debughelper.tools.r8.ir.code.Invoke.Type;
import com.debughelper.tools.r8.errors.Unreachable;
import com.debughelper.tools.r8.ir.code.Invoke;
import com.debughelper.tools.r8.naming.NamingLens;

import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;

public class DexMethodHandle extends IndexedDexItem implements
        PresortedComparable<DexMethodHandle> {

  public enum MethodHandleType {
    STATIC_PUT((short) 0x00),
    STATIC_GET((short) 0x01),
    INSTANCE_PUT((short) 0x02),
    INSTANCE_GET((short) 0x03),
    INVOKE_STATIC((short) 0x04),
    INVOKE_INSTANCE((short) 0x05),
    INVOKE_CONSTRUCTOR((short) 0x06),
    INVOKE_DIRECT((short) 0x07),
    INVOKE_INTERFACE((short) 0x08),
    // Internal method handle needed by lambda desugaring.
    INVOKE_SUPER((short) 0x09);

    private final short value;

    MethodHandleType(short value) {
      this.value = value;
    }

    public short getValue() {
      return value;
    }

    public static MethodHandleType getKind(int value) {
      MethodHandleType kind;

      switch (value) {
        case 0x00:
          kind = STATIC_PUT;
          break;
        case 0x01:
          kind = STATIC_GET;
          break;
        case 0x02:
          kind = INSTANCE_PUT;
          break;
        case 0x03:
          kind = INSTANCE_GET;
          break;
        case 0x04:
          kind = INVOKE_STATIC;
          break;
        case 0x05:
          kind = INVOKE_INSTANCE;
          break;
        case 0x06:
          kind = INVOKE_CONSTRUCTOR;
          break;
        case 0x07:
          kind = INVOKE_DIRECT;
          break;
        case 0x08:
          kind = INVOKE_INTERFACE;
          break;
        case 0x09:
          kind = INVOKE_SUPER;
          break;
        default:
          throw new AssertionError();
      }

      assert kind.getValue() == value;
      return kind;
    }

    public static MethodHandleType fromAsmHandle(
            Handle handle, com.debughelper.tools.r8.graph.JarApplicationReader application, com.debughelper.tools.r8.graph.DexType clazz) {
      switch (handle.getTag()) {
        case Opcodes.H_GETFIELD:
          return MethodHandleType.INSTANCE_GET;
        case Opcodes.H_GETSTATIC:
          return MethodHandleType.STATIC_GET;
        case Opcodes.H_PUTFIELD:
          return MethodHandleType.INSTANCE_PUT;
        case Opcodes.H_PUTSTATIC:
          return MethodHandleType.STATIC_PUT;
        case Opcodes.H_INVOKESPECIAL:
          assert !handle.getName().equals(Constants.INSTANCE_INITIALIZER_NAME);
          assert !handle.getName().equals(Constants.CLASS_INITIALIZER_NAME);
          com.debughelper.tools.r8.graph.DexType owner = application.getTypeFromName(handle.getOwner());
          if (owner == clazz) {
            return MethodHandleType.INVOKE_DIRECT;
          } else {
            return MethodHandleType.INVOKE_SUPER;
          }
        case Opcodes.H_INVOKEVIRTUAL:
          return MethodHandleType.INVOKE_INSTANCE;
        case Opcodes.H_INVOKEINTERFACE:
          return MethodHandleType.INVOKE_INTERFACE;
        case Opcodes.H_INVOKESTATIC:
          return MethodHandleType.INVOKE_STATIC;
        case Opcodes.H_NEWINVOKESPECIAL:
          return MethodHandleType.INVOKE_CONSTRUCTOR;
        default:
          throw new com.debughelper.tools.r8.errors.Unreachable("MethodHandle tag is not supported: " + handle.getTag());
      }
    }

    public boolean isFieldType() {
      return isStaticPut() || isStaticGet() || isInstancePut() || isInstanceGet();
    }

    public boolean isMethodType() {
      return isInvokeStatic() || isInvokeInstance() || isInvokeInterface() || isInvokeSuper()
          || isInvokeConstructor() || isInvokeDirect();
    }

    public boolean isStaticPut() {
      return this == MethodHandleType.STATIC_PUT;
    }

    public boolean isStaticGet() {
      return this == MethodHandleType.STATIC_GET;
    }

    public boolean isInstancePut() {
      return this == MethodHandleType.INSTANCE_PUT;
    }

    public boolean isInstanceGet() {
      return this == MethodHandleType.INSTANCE_GET;
    }

    public boolean isInvokeStatic() {
      return this == MethodHandleType.INVOKE_STATIC;
    }

    public boolean isInvokeDirect() {
      return this == MethodHandleType.INVOKE_DIRECT;
    }

    public boolean isInvokeInstance() {
      return this == MethodHandleType.INVOKE_INSTANCE;
    }

    public boolean isInvokeInterface() {
      return this == MethodHandleType.INVOKE_INTERFACE;
    }

    public boolean isInvokeSuper() {
      return this == MethodHandleType.INVOKE_SUPER;
    }

    public boolean isInvokeConstructor() {
      return this == MethodHandleType.INVOKE_CONSTRUCTOR;
    }

    public Invoke.Type toInvokeType() {
      assert isMethodType();
      switch (this) {
        case INVOKE_STATIC:
          return Invoke.Type.STATIC;
        case INVOKE_INSTANCE:
          return Invoke.Type.VIRTUAL;
        case INVOKE_CONSTRUCTOR:
          return Invoke.Type.DIRECT;
        case INVOKE_DIRECT:
          return Invoke.Type.DIRECT;
        case INVOKE_INTERFACE:
          return Invoke.Type.INTERFACE;
        case INVOKE_SUPER:
          return Invoke.Type.SUPER;
        default:
          throw new com.debughelper.tools.r8.errors.Unreachable("DexMethodHandle with unexpected type: " + this);
      }
    }
  }

  public MethodHandleType type;
  public com.debughelper.tools.r8.graph.Descriptor<? extends com.debughelper.tools.r8.graph.DexItem, ? extends com.debughelper.tools.r8.graph.Descriptor<?,?>> fieldOrMethod;

  public DexMethodHandle(
      MethodHandleType type,
      com.debughelper.tools.r8.graph.Descriptor<? extends com.debughelper.tools.r8.graph.DexItem, ? extends com.debughelper.tools.r8.graph.Descriptor<?,?>> fieldOrMethod) {
    this.type = type;
    this.fieldOrMethod = fieldOrMethod;
  }

  public static DexMethodHandle fromAsmHandle(
          Handle handle, JarApplicationReader application, DexType clazz) {
    MethodHandleType methodHandleType = MethodHandleType.fromAsmHandle(handle, application, clazz);
    com.debughelper.tools.r8.graph.Descriptor<? extends DexItem, ? extends Descriptor<?, ?>> descriptor =
        methodHandleType.isFieldType()
            ? application.getField(handle.getOwner(), handle.getName(), handle.getDesc())
            : application.getMethod(handle.getOwner(), handle.getName(), handle.getDesc());
    return application.getMethodHandle(methodHandleType, descriptor);
  }

  @Override
  public int computeHashCode() {
    return type.hashCode() + fieldOrMethod.computeHashCode() * 7;
  }

  @Override
  public boolean computeEquals(Object other) {
    if (other instanceof DexMethodHandle) {
      DexMethodHandle o = (DexMethodHandle) other;
      return type.equals(o.type) && fieldOrMethod.equals(o.fieldOrMethod);
    }
    return false;
  }

  @Override
  public String toString() {
    StringBuilder builder = new StringBuilder("MethodHandle: {")
            .append(type)
            .append(", ")
            .append(fieldOrMethod.toSourceString())
            .append("}");
    return builder.toString();
  }

  @Override
  public void collectIndexedItems(IndexedItemCollection indexedItems,
      DexMethod method, int instructionOffset) {
    if (indexedItems.addMethodHandle(this)) {
      fieldOrMethod.collectIndexedItems(indexedItems, method, instructionOffset);
    }
  }

  @Override
  public int getOffset(ObjectToOffsetMapping mapping) {
    return mapping.getOffsetFor(this);
  }

  // TODO(mikaelpeltier): Adapt syntax when invoke-custom will be available into smali.
  @Override
  public String toSmaliString() {
    return toString();
  }

  public boolean isFieldHandle() {
    return type.isFieldType();
  }

  public boolean isMethodHandle() {
    return type.isMethodType();
  }

  public boolean isStaticHandle() {
    return type.isStaticPut() || type.isStaticGet() || type.isInvokeStatic();
  }

  public DexMethod asMethod() {
    assert isMethodHandle();
    return (DexMethod) fieldOrMethod;
  }

  public com.debughelper.tools.r8.graph.DexField asField() {
    assert isFieldHandle();
    return (com.debughelper.tools.r8.graph.DexField) fieldOrMethod;
  }

  @Override
  public int slowCompareTo(DexMethodHandle other) {
    int result = type.getValue() - other.type.getValue();
    if (result == 0) {
      if (isFieldHandle()) {
        result = asField().slowCompareTo(other.asField());
      } else {
        assert isMethodHandle();
        result = asMethod().slowCompareTo(other.asMethod());
      }
    }
    return result;
  }

  @Override
  public int slowCompareTo(DexMethodHandle other, com.debughelper.tools.r8.naming.NamingLens namingLens) {
    int result = type.getValue() - other.type.getValue();
    if (result == 0) {
      if (isFieldHandle()) {
        result = asField().slowCompareTo(other.asField(), namingLens);
      } else {
        assert isMethodHandle();
        result = asMethod().slowCompareTo(other.asMethod(), namingLens);
      }
    }
    return result;
  }

  @Override
  public int layeredCompareTo(DexMethodHandle other, com.debughelper.tools.r8.naming.NamingLens namingLens) {
    int result = type.getValue() - other.type.getValue();
    if (result == 0) {
      if (isFieldHandle()) {
        result = asField().layeredCompareTo(other.asField(), namingLens);
      } else {
        assert isMethodHandle();
        result = asMethod().layeredCompareTo(other.asMethod(), namingLens);
      }
    }
    return result;
  }

  @Override
  public int compareTo(DexMethodHandle other) {
    return slowCompareTo(other);
  }

  public Handle toAsmHandle(NamingLens lens) {
    String owner;
    String name;
    String desc;
    boolean itf;
    if (isMethodHandle()) {
      DexMethod method = asMethod();
      owner = lens.lookupInternalName(method.holder);
      name = lens.lookupName(method).toString();
      desc = method.proto.toDescriptorString(lens);
      if (method.holder.toDescriptorString().equals("Ljava/lang/invoke/LambdaMetafactory;")) {
        itf = false;
      } else {
        itf = method.holder.isInterface();
      }
    } else {
      assert isFieldHandle();
      DexField field = asField();
      owner = lens.lookupInternalName(field.clazz);
      name = lens.lookupName(field).toString();
      desc = lens.lookupDescriptor(field.type).toString();
      itf = field.clazz.isInterface();
    }
    return new Handle(getAsmTag(), owner, name, desc, itf);
  }

  private int getAsmTag() {
    switch (type) {
      case INVOKE_STATIC:
        return Opcodes.H_INVOKESTATIC;
      case INVOKE_CONSTRUCTOR:
        return Opcodes.H_NEWINVOKESPECIAL;
      case INVOKE_INSTANCE:
        return Opcodes.H_INVOKEVIRTUAL;
      case INVOKE_SUPER:
      case INVOKE_DIRECT:
        return Opcodes.H_INVOKESPECIAL;
      case STATIC_GET:
        return Opcodes.H_GETSTATIC;
      case STATIC_PUT:
        return Opcodes.H_PUTSTATIC;
      case INSTANCE_GET:
        return Opcodes.H_GETFIELD;
      case INSTANCE_PUT:
        return Opcodes.H_PUTFIELD;
      case INVOKE_INTERFACE:
        return Opcodes.H_INVOKEINTERFACE;
      default:
        throw new Unreachable();
    }
  }
}
