// Copyright (c) 2018, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.debughelper.tools.r8.ir.optimize.lambda.kotlin;

import com.debughelper.tools.r8.ir.code.Invoke.Type;
import com.debughelper.tools.r8.ir.optimize.lambda.kotlin.KotlinLambdaGroupClassBuilder;
import com.debughelper.tools.r8.code.Instruction;
import com.debughelper.tools.r8.code.InvokeDirect;
import com.debughelper.tools.r8.code.InvokeDirectRange;
import com.debughelper.tools.r8.code.ReturnVoid;
import com.debughelper.tools.r8.graph.AppInfoWithSubtyping;
import com.debughelper.tools.r8.graph.DexClass;
import com.debughelper.tools.r8.graph.DexEncodedField;
import com.debughelper.tools.r8.graph.DexEncodedMethod;
import com.debughelper.tools.r8.graph.DexField;
import com.debughelper.tools.r8.graph.DexItemFactory;
import com.debughelper.tools.r8.graph.DexMethod;
import com.debughelper.tools.r8.graph.DexProto;
import com.debughelper.tools.r8.graph.DexType;
import com.debughelper.tools.r8.graph.EnclosingMethodAttribute;
import com.debughelper.tools.r8.graph.InnerClassAttribute;
import com.debughelper.tools.r8.ir.code.Invoke;
import com.debughelper.tools.r8.ir.code.ValueType;
import com.debughelper.tools.r8.ir.optimize.lambda.LambdaGroup;
import com.debughelper.tools.r8.ir.optimize.lambda.LambdaGroupClassBuilder;
import com.debughelper.tools.r8.ir.synthetic.SyntheticSourceCode;
import com.debughelper.tools.r8.kotlin.Kotlin;
import com.debughelper.tools.r8.utils.ThrowingConsumer;
import com.google.common.collect.Lists;
import java.util.function.IntFunction;

// Represents a j-style lambda group created to combine several lambda classes
// generated by kotlin compiler for kotlin lambda expressions passed to java receivers,
// like:
//
//      --- Java --------------------------------------------------------------
//      public static void acceptString(Supplier<String> s) {
//        // ...
//      }
//      -----------------------------------------------------------------------
//      acceptString({ "A" })
//      -----------------------------------------------------------------------
//
// Regular stateless j-style lambda class structure looks like below:
// NOTE: stateless j-style lambdas do not always have INSTANCE field.
//
// -----------------------------------------------------------------------------------------------
// signature <T:Ljava/lang/Object;>Ljava/lang/Object;
//                    Ljava/util/function/Supplier<Ljava/lang/String;>;
// final class lambdas/LambdasKt$foo$4 implements java/util/function/Supplier {
//
//     public synthetic bridge get()Ljava/lang/Object;
//
//     public final get()Ljava/lang/String;
//       @Lorg/jetbrains/annotations/NotNull;() // invisible
//
//     <init>()V
//
//     public final static Llambdas/LambdasKt$foo$4; INSTANCE
//
//     static <clinit>()V
//
//     OUTERCLASS lambdas/LambdasKt foo (Ljava/lang/String;I)Lkotlin/jvm/functions/Function0;
//     final static INNERCLASS lambdas/LambdasKt$foo$4 null null
// }
// -----------------------------------------------------------------------------------------------
//
// Regular stateful j-style lambda class structure looks like below:
//
// -----------------------------------------------------------------------------------------------
// signature <T:Ljava/lang/Object;>
//                Ljava/lang/Object;Ljava/util/function/Supplier<Ljava/lang/String;>;
// declaration: lambdas/LambdasKt$foo$5<T> implements java.util.function.Supplier<java.lang.String>
// final class lambdas/LambdasKt$foo$5 implements java/util/function/Supplier  {
//
//     public synthetic bridge get()Ljava/lang/Object;
//
//     public final get()Ljava/lang/String;
//       @Lorg/jetbrains/annotations/NotNull;() // invisible
//
//     <init>(Ljava/lang/String;I)V
//
//     final synthetic Ljava/lang/String; $m
//     final synthetic I $v
//
//     OUTERCLASS lambdas/LambdasKt foo (Ljava/lang/String;I)Lkotlin/jvm/functions/Function0;
//     final static INNERCLASS lambdas/LambdasKt$foo$5 null null
// }
// -----------------------------------------------------------------------------------------------
//
// Key j-style lambda class details:
//   - extends java.lang.Object
//   - implements *any* functional interface (Kotlin does not seem to support scenarios when
//     lambda can implement multiple interfaces).
//   - lambda class is created as an anonymous inner class
//   - lambda class carries generic signature and kotlin metadata attribute
//   - class instance fields represent captured values and have an instance constructor
//     with matching parameters initializing them (see the second class above)
//   - stateless lambda *may* be implemented as a singleton with a static field storing the
//     only instance and initialized in static class constructor (see the first class above)
//   - main lambda method usually matches an exact lambda signature and may have
//     generic signature attribute and nullability parameter annotations
//   - optional bridge method created to satisfy interface implementation and
//     forwarding call to lambda main method
//
final class JStyleLambdaGroup extends KotlinLambdaGroup {
  private JStyleLambdaGroup(GroupId id) {
    super(id);
  }

  @Override
  protected LambdaGroupClassBuilder getBuilder(com.debughelper.tools.r8.graph.DexItemFactory factory) {
    return new ClassBuilder(factory, "java-style lambda group");
  }

  @Override
  public ThrowingConsumer<DexClass, LambdaStructureError> lambdaClassValidator(
          com.debughelper.tools.r8.kotlin.Kotlin kotlin, com.debughelper.tools.r8.graph.AppInfoWithSubtyping appInfo) {
    return new ClassValidator(kotlin, appInfo);
  }

  @Override
  protected String getGroupSuffix() {
    return "js$";
  }

  // Specialized group id.
  final static class GroupId extends KotlinLambdaGroupId {
    GroupId(String capture, com.debughelper.tools.r8.graph.DexType iface,
            String pkg, String signature, DexEncodedMethod mainMethod,
            InnerClassAttribute inner, EnclosingMethodAttribute enclosing) {
      super(capture, iface, pkg, signature, mainMethod, inner, enclosing);
    }

    @Override
    public boolean equals(Object obj) {
      return obj instanceof GroupId && computeEquals((KotlinLambdaGroupId) obj);
    }

    @Override
    String getLambdaKindDescriptor() {
      return "Kotlin j-style lambda group";
    }

    @Override
    public LambdaGroup createGroup() {
      return new JStyleLambdaGroup(this);
    }
  }

  // Specialized class validator.
  private class ClassValidator extends KotlinLambdaClassValidator {
    ClassValidator(Kotlin kotlin, AppInfoWithSubtyping appInfo) {
      super(kotlin, JStyleLambdaGroup.this, appInfo);
    }

    @Override
    int getInstanceInitializerSize(DexEncodedField[] captures) {
      return captures.length + 2;
    }

    @Override
    int validateInstanceInitializerEpilogue(
            Instruction[] instructions, int index)
        throws LambdaStructureError {
      if (!(instructions[index] instanceof InvokeDirect
              || instructions[index] instanceof InvokeDirectRange)
          || instructions[index].getMethod() != kotlin.factory.objectMethods.constructor) {
        throw structureError(LAMBDA_INIT_CODE_VERIFICATION_FAILED);
      }
      index++;
      if (!(instructions[index] instanceof ReturnVoid)) {
        throw structureError(LAMBDA_INIT_CODE_VERIFICATION_FAILED);
      }
      return index + 1;
    }
  }

  // Specialized class builder.
  private final class ClassBuilder extends com.debughelper.tools.r8.ir.optimize.lambda.kotlin.KotlinLambdaGroupClassBuilder<JStyleLambdaGroup> {
    ClassBuilder(com.debughelper.tools.r8.graph.DexItemFactory factory, String origin) {
      super(JStyleLambdaGroup.this, factory, origin);
    }

    @Override
    protected com.debughelper.tools.r8.graph.DexType getSuperClassType() {
      return factory.objectType;
    }

    @Override
    SyntheticSourceCode createInstanceInitializerSourceCode(
            com.debughelper.tools.r8.graph.DexType groupClassType, com.debughelper.tools.r8.graph.DexProto initializerProto) {
      return new InstanceInitializerSourceCode(
          factory, groupClassType, group.getLambdaIdField(factory),
          id -> group.getCaptureField(factory, id), initializerProto);
    }
  }

  // Specialized instance initializer code.
  private static final class InstanceInitializerSourceCode
      extends KotlinInstanceInitializerSourceCode {
    private final DexMethod objectInitializer;

    InstanceInitializerSourceCode(DexItemFactory factory, DexType lambdaGroupType,
                                  com.debughelper.tools.r8.graph.DexField idField, IntFunction<DexField> fieldGenerator, DexProto proto) {
      super(lambdaGroupType, idField, fieldGenerator, proto);
      this.objectInitializer = factory.objectMethods.constructor;
    }

    @Override
    void prepareSuperConstructorCall(int receiverRegister) {
      add(builder -> builder.addInvoke(Invoke.Type.DIRECT, objectInitializer, objectInitializer.proto,
          Lists.newArrayList(ValueType.OBJECT), Lists.newArrayList(receiverRegister)));
    }
  }
}
