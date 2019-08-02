// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.utils;

import com.debughelper.tools.r8.CompilationFailedException;
import com.debughelper.tools.r8.DiagnosticsHandler;
import com.debughelper.tools.r8.ResourceException;
import com.debughelper.tools.r8.StringConsumer;
import com.debughelper.tools.r8.errors.CompilationError;
import com.debughelper.tools.r8.origin.Origin;
import com.debughelper.tools.r8.origin.PathOrigin;

import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.Paths;
import java.util.function.Consumer;

public abstract class ExceptionUtils {

  public static final int STATUS_ERROR = 1;

  public static void withConsumeResourceHandler(
          Reporter reporter, StringConsumer consumer, String data) {
    withConsumeResourceHandler(reporter, handler -> consumer.accept(data, handler));
  }

  public static void withConsumeResourceHandler(
      Reporter reporter, Consumer<DiagnosticsHandler> consumer) {
    // Unchecked exceptions simply propagate out, aborting the compilation forcefully.
    consumer.accept(reporter);
    // Fail fast for now. We might consider delaying failure since consumer failure does not affect
    // the compilation. We might need to be careful to correctly identify errors so as to exit
    // compilation with an error code.
    reporter.failIfPendingErrors();
  }

  public interface CompileAction {
    void run() throws IOException, com.debughelper.tools.r8.errors.CompilationError, com.debughelper.tools.r8.ResourceException;
  }

  public static void withD8CompilationHandler(Reporter reporter, CompileAction action)
      throws com.debughelper.tools.r8.CompilationFailedException {
    withCompilationHandler(reporter, action);
  }

  public static void withR8CompilationHandler(Reporter reporter, CompileAction action)
      throws com.debughelper.tools.r8.CompilationFailedException {
    withCompilationHandler(reporter, action);
  }

  public static void withCompilationHandler(Reporter reporter, CompileAction action)
      throws com.debughelper.tools.r8.CompilationFailedException {
    try {
      try {
        action.run();
      } catch (IOException e) {
        throw reporter.fatalError(new ExceptionDiagnostic(e, extractIOExceptionOrigin(e)));
      } catch (CompilationError e) {
        throw reporter.fatalError(e);
      } catch (ResourceException e) {
        throw reporter.fatalError(new ExceptionDiagnostic(e, e.getOrigin()));
      }
      reporter.failIfPendingErrors();
    } catch (AbortException e) {
      throw new com.debughelper.tools.r8.CompilationFailedException(e);
    }
  }

  public interface MainAction {
    void run() throws com.debughelper.tools.r8.CompilationFailedException;
  }

  public static void withMainProgramHandler(MainAction action) {
    try {
      action.run();
    } catch (CompilationFailedException | AbortException e) {
      // Detail of the errors were already reported
      System.err.println("Compilation failed");
      System.exit(STATUS_ERROR);
    } catch (RuntimeException e) {
      System.err.println("Compilation failed with an internal error.");
      Throwable cause = e.getCause() == null ? e : e.getCause();
      cause.printStackTrace();
      System.exit(STATUS_ERROR);
    }
  }

  // We should try to avoid the use of this extraction as it signifies a point where we don't have
  // enough context to associate a specific origin with an IOException. Concretely, we should move
  // towards always catching IOException and rethrowing CompilationError with proper origins.
  public static com.debughelper.tools.r8.origin.Origin extractIOExceptionOrigin(IOException e) {
    if (e instanceof FileSystemException) {
      FileSystemException fse = (FileSystemException) e;
      if (fse.getFile() != null && !fse.getFile().isEmpty()) {
        return new PathOrigin(Paths.get(fse.getFile()));
      }
    }
    return Origin.unknown();
  }

}
