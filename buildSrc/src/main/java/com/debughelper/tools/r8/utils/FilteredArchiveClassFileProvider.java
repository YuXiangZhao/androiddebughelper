// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.utils;

import com.debughelper.tools.r8.ArchiveClassFileProvider;
import com.debughelper.tools.r8.shaking.FilteredClassPath;

import java.io.IOException;

// Internal filtered class-file provider.
class FilteredArchiveClassFileProvider extends ArchiveClassFileProvider {

  FilteredArchiveClassFileProvider(FilteredClassPath archive) throws IOException {
    super(archive.getPath(), entry -> archive.matchesFile(entry));
  }
}
