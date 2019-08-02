// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.graph;

import com.debughelper.tools.r8.dex.IndexedItemCollection;
import com.debughelper.tools.r8.dex.MixedSectionCollection;
import com.debughelper.tools.r8.graph.DexItem;
import com.debughelper.tools.r8.graph.DexMethod;
import com.debughelper.tools.r8.graph.DexValue;

public class DexAnnotationElement extends DexItem {

  public final DexString name;
  public final com.debughelper.tools.r8.graph.DexValue value;

  public DexAnnotationElement(DexString name, DexValue value) {
    this.name = name;
    this.value = value;
  }

  @Override
  public int hashCode() {
    return name.hashCode() + value.hashCode() * 3;
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (other instanceof DexAnnotationElement) {
      DexAnnotationElement o = (DexAnnotationElement) other;
      return name.equals(o.name) && value.equals(o.value);
    }
    return false;
  }

  @Override
  public String toString() {
    return name + "=" + value;
  }

  @Override
  public void collectIndexedItems(IndexedItemCollection indexedItems,
                                  DexMethod method, int instructionOffset) {
    name.collectIndexedItems(indexedItems, method, instructionOffset);
    value.collectIndexedItems(indexedItems, method, instructionOffset);
  }

  @Override
  void collectMixedSectionItems(MixedSectionCollection mixedItems) {
    // Should never be visited.
    assert false;
  }

}
