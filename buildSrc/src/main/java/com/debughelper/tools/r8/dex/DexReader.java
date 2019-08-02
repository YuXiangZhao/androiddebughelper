// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.dex;

import static com.debughelper.tools.r8.dex.Constants.DEX_FILE_MAGIC_PREFIX;

import com.debughelper.tools.r8.ProgramResource;
import com.debughelper.tools.r8.ResourceException;
import com.debughelper.tools.r8.errors.CompilationError;
import com.debughelper.tools.r8.origin.Origin;
import com.debughelper.tools.r8.utils.DexVersion;

import java.io.IOException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * {@link BinaryReader} for Dex content.
 */
public class DexReader extends BinaryReader {

  private final int version;

  public DexReader(ProgramResource resource) throws ResourceException, IOException {
    super(resource);
    version = parseMagic(buffer);
  }

  /**
   * Returns a File that contains the bytes provided as argument. Used for testing.
   *
   * @param bytes contents of the file
   */
  DexReader(Origin origin, byte[] bytes) {
    super(origin, bytes);
    version = parseMagic(buffer);
  }

  // Parse the magic header and determine the dex file version.
  private int parseMagic(ByteBuffer buffer) {
    try {
      buffer.get();
      buffer.rewind();
    } catch (BufferUnderflowException e) {
      throw new com.debughelper.tools.r8.errors.CompilationError("Dex file is empty", origin);
    }
    int index = 0;
    for (byte prefixByte : Constants.DEX_FILE_MAGIC_PREFIX) {
      if (buffer.get(index++) != prefixByte) {
        throw new com.debughelper.tools.r8.errors.CompilationError("Dex file has invalid header", origin);
      }
    }
    if (buffer.get(index++) != '0' || buffer.get(index++) != '3') {
      throw new com.debughelper.tools.r8.errors.CompilationError("Dex file has invalid version number", origin);
    }
    byte versionByte = buffer.get(index++);
    int version;
    switch (versionByte) {
      case '9':
        version = com.debughelper.tools.r8.utils.DexVersion.V39.getIntValue();
        break;
      case '8':
        version =  com.debughelper.tools.r8.utils.DexVersion.V38.getIntValue();
        break;
      case '7':
        version =  com.debughelper.tools.r8.utils.DexVersion.V37.getIntValue();
        break;
      case '5':
        version =  DexVersion.V35.getIntValue();
        break;
      default:
        throw new com.debughelper.tools.r8.errors.CompilationError("Dex file has invalid version number", origin);
    }
    if (buffer.get(index++) != '\0') {
      throw new com.debughelper.tools.r8.errors.CompilationError("Dex file has invalid header", origin);
    }
    return version;
  }

  @Override
  void setByteOrder() {
    // Make sure we set the right endian for reading.
    buffer.order(ByteOrder.LITTLE_ENDIAN);
    int endian = buffer.getInt(Constants.ENDIAN_TAG_OFFSET);
    if (endian == Constants.REVERSE_ENDIAN_CONSTANT) {
      buffer.order(ByteOrder.BIG_ENDIAN);
    } else {
      if (endian != Constants.ENDIAN_CONSTANT) {
        throw new CompilationError("Unable to determine endianess for reading dex file.");
      }
    }
  }

  int getDexVersion() {
    return version;
  }
}
