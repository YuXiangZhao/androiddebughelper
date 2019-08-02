// Copyright (c) 2018, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8;

import com.debughelper.tools.r8.Keep;

/** Enumeration of the possible output formats of compilation. */
@Keep
public enum OutputMode {

  /** Produce DEX files using standard indexed-multidex for programs larger that a single file. */
  DexIndexed,

  /** Produce a DEX file for each Java class-file input file. */
  DexFilePerClassFile,

  /** Produce Java class files. */
  ClassFile
}
