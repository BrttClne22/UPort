/*
 * Copyright 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.exoplayer.rtsp.reader;

import static androidx.media3.exoplayer.rtsp.reader.RtpReaderUtils.toSampleTimeUs;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.Util;
import androidx.media3.container.OpusUtil;
import androidx.media3.exoplayer.rtsp.RtpPacket;
import androidx.media3.exoplayer.rtsp.RtpPayloadFormat;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.TrackOutput;
import java.util.List;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/**
 * Parses an OPUS byte stream carried on RTP packets and extracts individual samples. Refer to
 * RFC7845 for more details.
 */
/* package */ final class RtpOpusReader implements RtpPayloadReader {
  private static final String TAG = "RtpOpusReader";
  /* Opus uses a fixed 48KHz media clock RFC7845 Section 4. */
  private static final int MEDIA_CLOCK_FREQUENCY = 48_000;

  private final RtpPayloadFormat payloadFormat;

  private @MonotonicNonNull TrackOutput trackOutput;

  /**
   * First received RTP timestamp. All RTP timestamps are dimension-less, the time base is defined
   * by {@link #MEDIA_CLOCK_FREQUENCY}.
   */
  private long firstReceivedTimestamp;

  private long startTimeOffsetUs;
  private int previousSequenceNumber;
  private boolean foundOpusIDHeader;
  private boolean foundOpusCommentHeader;

  /** Creates an instance. */
  public RtpOpusReader(RtpPayloadFormat payloadFormat) {
    this.payloadFormat = payloadFormat;
    this.firstReceivedTimestamp = C.INDEX_UNSET;
    this.previousSequenceNumber = C.INDEX_UNSET;
  }

  // RtpPayloadReader implementation.

  @Override
  public void createTracks(ExtractorOutput extractorOutput, int trackId) {
    trackOutput = extractorOutput.track(trackId, C.TRACK_TYPE_AUDIO);
    trackOutput.format(payloadFormat.format);
  }

  @Override
  public void onReceivingFirstPacket(long timestamp, int sequenceNumber) {
    this.firstReceivedTimestamp = timestamp;
  }

  @Override
  public void consume(
      ParsableByteArray data, long timestamp, int sequenceNumber, boolean rtpMarker) {
    checkNotNull(trackOutput);

    /* RFC7845 Section 3.
     * +---------+ +----------------+ +--------------------+ +-----
     * |ID Header| | Comment Header | |Audio Data Packet 1 | | ...
     * +---------+ +----------------+ +--------------------+ +-----
     */
    if (!foundOpusIDHeader) {
      // UPort patch: RFC 7587 Opus-over-RTP (what UniFi Protect sends) carries no in-band
      // OpusHead/OpusTags packets; the codec setup comes from the SDP. Upstream Media3 1.11.1
      // insisted on an in-band ID header and failed the whole stream. Accept either form.
      if (isOpusIdHeader(data)) {
        List<byte[]> initializationData = OpusUtil.buildInitializationData(data.getData());
        Format.Builder formatBuilder = payloadFormat.format.buildUpon();
        formatBuilder.setInitializationData(initializationData);
        trackOutput.format(formatBuilder.build());
        foundOpusIDHeader = true;
        previousSequenceNumber = sequenceNumber;
        return;
      }
      byte[] synthesized = synthesizeOpusIdHeader(payloadFormat.format.channelCount);
      Format.Builder formatBuilder = payloadFormat.format.buildUpon();
      formatBuilder.setInitializationData(OpusUtil.buildInitializationData(synthesized));
      trackOutput.format(formatBuilder.build());
      foundOpusIDHeader = true;
      foundOpusCommentHeader = true;
      // Fall through: this packet is already audio.
    } else if (!foundOpusCommentHeader) {
      // Comment Header RFC7845 Section 5.2. Skip it if present; treat anything else as audio.
      if (data.limit() >= 8 && peekString(data, 8).equals("OpusTags")) {
        foundOpusCommentHeader = true;
        previousSequenceNumber = sequenceNumber;
        return;
      }
      foundOpusCommentHeader = true;
    }
    {
      // Check that this packet is in the sequence of the previous packet.
      int expectedSequenceNumber = RtpPacket.getNextSequenceNumber(previousSequenceNumber);
      if (sequenceNumber != expectedSequenceNumber) {
        Log.w(
            TAG,
            Util.formatInvariant(
                "Received RTP packet with unexpected sequence number. Expected: %d; received: %d.",
                expectedSequenceNumber, sequenceNumber));
      }

      // sending opus data.
      int size = data.bytesLeft();
      trackOutput.sampleData(data, size);
      long timeUs =
          toSampleTimeUs(
              startTimeOffsetUs, timestamp, firstReceivedTimestamp, MEDIA_CLOCK_FREQUENCY);
      trackOutput.sampleMetadata(
          timeUs, C.BUFFER_FLAG_KEY_FRAME, size, /* offset*/ 0, /* cryptoData*/ null);
    }
    previousSequenceNumber = sequenceNumber;
  }

  @Override
  public void seek(long nextRtpTimestamp, long timeUs) {
    firstReceivedTimestamp = nextRtpTimestamp;
    startTimeOffsetUs = timeUs;
  }

  // Internal methods.

  /**
   * Validates the OPUS ID Header at {@code data}'s current position, throws {@link
   * IllegalArgumentException} if the header is invalid.
   *
   * <p>{@code data}'s position does not change after returning.
   */
  private static void validateOpusIdHeader(ParsableByteArray data) {
    int currPosition = data.getPosition();
    int sampleSize = data.limit();
    checkArgument(sampleSize > 18, "ID Header has insufficient data");
    String header = data.readString(8);
    // Identification header RFC7845 Section 5.1.
    checkArgument(header.equals("OpusHead"), "ID Header missing");
    checkArgument(data.readUnsignedByte() == 1, "version number must always be 1");
    data.setPosition(currPosition);
  }

  /** Whether {@code data} starts with an RFC 7845 OpusHead packet. Position is unchanged. */
  private static boolean isOpusIdHeader(ParsableByteArray data) {
    return data.limit() > 18 && peekString(data, 8).equals("OpusHead");
  }

  private static String peekString(ParsableByteArray data, int length) {
    int position = data.getPosition();
    if (data.bytesLeft() < length) return "";
    String value = data.readString(length);
    data.setPosition(position);
    return value;
  }

  /** Builds a minimal RFC 7845 OpusHead for an RFC 7587 stream (48 kHz, mapping family 0). */
  static byte[] synthesizeOpusIdHeader(int channelCount) {
    int channels = channelCount == 1 ? 1 : 2;
    byte[] header = new byte[19];
    byte[] magic = "OpusHead".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    System.arraycopy(magic, 0, header, 0, 8);
    header[8] = 1; // version
    header[9] = (byte) channels;
    // pre-skip (bytes 10-11) = 0: live RTP has no encoder delay to trim.
    int rate = MEDIA_CLOCK_FREQUENCY;
    header[12] = (byte) rate;
    header[13] = (byte) (rate >> 8);
    header[14] = (byte) (rate >> 16);
    header[15] = (byte) (rate >> 24);
    // output gain (16-17) = 0, channel mapping family (18) = 0.
    return header;
  }
}
