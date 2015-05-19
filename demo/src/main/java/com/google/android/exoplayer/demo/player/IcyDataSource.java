/*
 * Copyright (C) 2014 The Android Open Source Project
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
package com.google.android.exoplayer.demo.player;

import com.google.android.exoplayer.C;
import com.google.android.exoplayer.upstream.DataSpec;
import com.google.android.exoplayer.upstream.DefaultHttpDataSource;
import com.google.android.exoplayer.upstream.HttpDataSource;
import com.google.android.exoplayer.upstream.TransferListener;
import com.google.android.exoplayer.util.Predicate;
import com.spoledge.aacdecoder.IcyInputStream;
import com.spoledge.aacdecoder.PlayerCallback;

import android.media.AudioTrack;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;


/**
 * A {@link DefaultHttpDataSource} that uses Android's {@link DefaultHttpDataSource}.
 */
public class IcyDataSource extends DefaultHttpDataSource {
  static String TAG = "IcyDataSource";
  boolean metadataEnabled = true;

  public IcyDataSource(String userAgent, Predicate<String> contentTypePredicate) {
    super(userAgent, contentTypePredicate);
  }


  @Override
  protected HttpURLConnection makeConnection(DataSpec dataSpec) throws IOException {
    Log.i(TAG, "makeConnection[" + dataSpec.position + "-" + dataSpec.length);

    URL url = new URL(dataSpec.uri.toString());
    HttpURLConnection connection = (HttpURLConnection) url.openConnection();
    connection.setRequestProperty("Icy-Metadata", "1");
    return connection;
  }

  /**
   * Gets the input stream from the connection.
   * Actually returns the underlying stream or IcyInputStream.
   */
  @Override
  protected InputStream getInputStream( HttpURLConnection conn ) throws IOException {
    String smetaint = conn.getHeaderField( "icy-metaint" );
    InputStream ret = conn.getInputStream();

    if (!metadataEnabled) {
      Log.i( TAG, "Metadata not enabled" );
    }
    else if (smetaint != null) {
      int period = -1;
      try {
        period = Integer.parseInt( smetaint );
      }
      catch (Exception e) {
        Log.e( TAG, "The icy-metaint '" + smetaint + "' cannot be parsed: '" + e );
      }

      if (period > 0) {
        Log.i( TAG, "The dynamic metainfo is sent every " + period + " bytes" );

        ret = new IcyInputStream( ret, period, playerCallback, null );
      }
    }
    else Log.i( TAG, "This stream does not provide dynamic metainfo" );

    return ret;
  }

  PlayerCallback playerCallback = new PlayerCallback() {

    @Override
    public void playerStarted() {
      Log.i( TAG, "playerStarted" );
    }

    @Override
    public void playerPCMFeedBuffer(boolean isPlaying, int audioBufferSizeMs, int audioBufferCapacityMs) {
      Log.i( TAG, "playerPCMFeedBuffer" );
    }

    @Override
    public void playerStopped(int perf) {
      Log.i( TAG, "playerStopped" );
    }

    @Override
    public void playerException(Throwable t) {
      Log.i( TAG, "playerException" );
    }

    @Override
    public void playerMetadata(String key, String value) {
      Log.i( TAG, "playerMetadata " + key + " : " + value);
    }

    @Override
    public void playerAudioTrackCreated(AudioTrack audioTrack) {
      Log.i( TAG, "playerMetadata" );
    }
  };
}
