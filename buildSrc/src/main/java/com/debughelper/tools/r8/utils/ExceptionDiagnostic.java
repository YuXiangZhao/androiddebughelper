// Copyright (c) 2018, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.debughelper.tools.r8.utils;

import com.debughelper.tools.r8.utils.DiagnosticWithThrowable;
import com.debughelper.tools.r8.ResourceException;
import com.debughelper.tools.r8.origin.Origin;
import com.debughelper.tools.r8.position.Position;

import java.io.FileNotFoundException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.NoSuchFileException;

public class ExceptionDiagnostic extends DiagnosticWithThrowable {

  private final com.debughelper.tools.r8.origin.Origin origin;

  public ExceptionDiagnostic(Throwable e, com.debughelper.tools.r8.origin.Origin origin) {
    super(e);
    this.origin = origin;
  }

  public ExceptionDiagnostic(ResourceException e) {
    this(e, e.getOrigin());
  }

  @Override
  public Origin getOrigin() {
    return origin;
  }

  @Override
  public com.debughelper.tools.r8.position.Position getPosition() {
    return Position.UNKNOWN;
  }

  @Override
  public String getDiagnosticMessage() {
    Throwable e = getThrowable();
    if (e instanceof NoSuchFileException || e instanceof FileNotFoundException) {
      return "File not found: " + e.getMessage();
    }
    if (e instanceof FileAlreadyExistsException) {
      return "File already exists: " + e.getMessage();
    }
    return e.getMessage();
  }
}
