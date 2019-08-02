// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.graph;

import com.debughelper.tools.r8.dex.IndexedItemCollection;
import com.debughelper.tools.r8.graph.DexItemFactory;
import com.debughelper.tools.r8.graph.DexType;
import com.debughelper.tools.r8.naming.NamingLens;

import org.objectweb.asm.ClassWriter;

/** Representation of an entry in the Java InnerClasses attribute table. */
public class InnerClassAttribute {

  // Access flags to the inner class as declared in the program source.
  private final int access;

  // Type of the inner class.
  private final com.debughelper.tools.r8.graph.DexType inner;

  // Type of the enclosing class, null if the inner class is top-level, local or anonymous.
  private final com.debughelper.tools.r8.graph.DexType outer;

  // Short name of the inner class, null if the inner class is anonymous.
  private final DexString innerName;

  // Create a named inner-class attribute, but with some arbitrary/unknown name.
  // This is needed to partially map back to the Java attribute structures when reading DEX inputs.
  public static InnerClassAttribute createUnknownNamedInnerClass(com.debughelper.tools.r8.graph.DexType inner, com.debughelper.tools.r8.graph.DexType outer) {
    return new InnerClassAttribute(0, inner, outer, DexItemFactory.unknownTypeName);
  }

  public InnerClassAttribute(int access, com.debughelper.tools.r8.graph.DexType inner, com.debughelper.tools.r8.graph.DexType outer, DexString innerName) {
    assert inner != null;
    this.access = access;
    this.inner = inner;
    this.outer = outer;
    this.innerName = innerName;
  }

  public boolean isNamed() {
    return innerName != null;
  }

  public boolean isAnonymous() {
    return innerName == null;
  }

  public int getAccess() {
    return access;
  }

  public com.debughelper.tools.r8.graph.DexType getInner() {
    return inner;
  }

  public DexType getOuter() {
    return outer;
  }

  public DexString getInnerName() {
    return innerName;
  }

  public void write(ClassWriter writer, NamingLens lens) {
    String internalName = lens.lookupInternalName(inner);
    String simpleName = lens.lookupSimpleName(inner, innerName);
    writer.visitInnerClass(
        internalName,
        outer == null ? null : lens.lookupInternalName(outer),
        innerName == null ? null : simpleName,
        access);
  }

  public void collectIndexedItems(IndexedItemCollection indexedItems) {
    inner.collectIndexedItems(indexedItems);
    if (outer != null) {
      outer.collectIndexedItems(indexedItems);
    }
    if (innerName != null) {
      innerName.collectIndexedItems(indexedItems);
    }
  }
}
