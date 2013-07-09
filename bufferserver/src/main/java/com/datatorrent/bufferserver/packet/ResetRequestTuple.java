/*
 *  Copyright (c) 2012-2013 Malhar, Inc.
 *  All Rights Reserved.
 */
package com.datatorrent.bufferserver.packet;

/**
 *
 * @author Chetan Narsude <chetan@datatorrent.com>
 */
public class ResetRequestTuple extends GenericRequestTuple
{
  public ResetRequestTuple(byte[] array, int offset, int length)
  {
    super(array, offset, length);
  }

  public static byte[] getSerializedRequest(String version, String identifier, long startingWindowId)
  {
    return GenericRequestTuple.getSerializedRequest(version, identifier, startingWindowId, MessageType.RESET_REQUEST_VALUE);
  }

}