// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.debughelper.tools.r8.dex;

import com.debughelper.tools.r8.ProgramResource;
import com.debughelper.tools.r8.ResourceException;
import com.debughelper.tools.r8.origin.Origin;
import com.debughelper.tools.r8.utils.LebUtils;
import com.debughelper.tools.r8.utils.StreamUtils;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Base class for reading binary content.
 */
public abstract class BinaryReader {
  protected final com.debughelper.tools.r8.origin.Origin origin;
  protected final ByteBuffer buffer;

  protected BinaryReader(ProgramResource resource) throws ResourceException, IOException {
    this(resource.getOrigin(), StreamUtils.StreamToByteArrayClose(resource.getByteStream()));
  }

  protected BinaryReader(com.debughelper.tools.r8.origin.Origin origin, byte[] bytes) {
    assert origin != null;
    this.origin = origin;
    buffer = ByteBuffer.wrap(bytes);
  }

  public Origin getOrigin() {
    return origin;
  }

  abstract void setByteOrder();

  byte[] getByteArray(int size) {
    byte[] result = new byte[size];
    buffer.get(result);
    return result;
  }

  int getUleb128() {
    return com.debughelper.tools.r8.utils.LebUtils.parseUleb128(this);
  }

  int getSleb128() {
    return LebUtils.parseSleb128(this);
  }

  int getUleb128p1() {
    return getUleb128() - 1;
  }

  int getUint() {
    int result = buffer.getInt();
    assert result >= 0;  // Ensure the java int didn't overflow.
    return result;
  }

  int getUshort() {
    int result = buffer.getShort() & 0xffff;
    assert result >= 0;  // Ensure we have a non-negative number.
    return result;
  }

  short getShort() {
    return buffer.getShort();
  }

  int getUint(int offset) {
    int result = buffer.getInt(offset);
    assert result >= 0;  // Ensure the java int didn't overflow.
    return result;
  }

  public int getInt() {
    return buffer.getInt();
  }

  int position() {
    return buffer.position();
  }

  void position(int position) {
    buffer.position(position);
  }

  void align(int alignment) {
    assert (alignment & (alignment - 1)) == 0;   // Check alignment is power of 2.
    int p = buffer.position();
    p += (alignment - (p % alignment)) & (alignment - 1);
    buffer.position(p);
  }

  public byte get() {
    return buffer.get();
  }

  int getUbyte() {
    int result = buffer.get() & 0xff;
    assert result >= 0;  // Ensure we have a non-negative result.
    return result;
  }

  int end() {
    return buffer.capacity();
  }
}
