// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.utils;

import com.debughelper.tools.r8.dex.ApplicationReader.ProgramClassConflictResolver;
import com.debughelper.tools.r8.graph.ClassKind;
import com.debughelper.tools.r8.graph.DexProgramClass;
import com.debughelper.tools.r8.graph.DexType;
import com.debughelper.tools.r8.errors.CompilationError;
import com.debughelper.tools.r8.ir.desugar.LambdaRewriter;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/** Represents a collection of library classes. */
public class ProgramClassCollection extends ClassMap<DexProgramClass> {

  public static ProgramClassCollection create(
      List<DexProgramClass> classes, ProgramClassConflictResolver conflictResolver) {
    // We have all classes preloaded, but not necessarily without conflicts.
    ConcurrentHashMap<DexType, Supplier<DexProgramClass>> map = new ConcurrentHashMap<>();
    for (DexProgramClass clazz : classes) {
      map.merge(
          clazz.type, clazz, (a, b) -> conflictResolver.resolveClassConflict(a.get(), b.get()));
    }
    return new ProgramClassCollection(map);
  }

  private ProgramClassCollection(ConcurrentHashMap<DexType, Supplier<DexProgramClass>> classes) {
    super(classes, null);
  }

  @Override
  public String toString() {
    return "program classes: " + super.toString();
  }

  @Override
  DexProgramClass resolveClassConflict(DexProgramClass a, DexProgramClass b) {
    return resolveClassConflictImpl(a, b);
  }

  @Override
  Supplier<DexProgramClass> getTransparentSupplier(DexProgramClass clazz) {
    return clazz;
  }

  @Override
  ClassKind getClassKind() {
    return ClassKind.PROGRAM;
  }

  public static DexProgramClass resolveClassConflictImpl(DexProgramClass a, DexProgramClass b) {
    // Currently only allow collapsing synthetic lambda classes.
    if (a.originatesFromDexResource()
        && b.originatesFromDexResource()
        && a.accessFlags.isSynthetic()
        && b.accessFlags.isSynthetic()
        && com.debughelper.tools.r8.ir.desugar.LambdaRewriter.hasLambdaClassPrefix(a.type)
        && LambdaRewriter.hasLambdaClassPrefix(b.type)) {
      return a;
    }
    throw new CompilationError("Program type already present: " + a.type.toSourceString());
  }
}
