/*
 * Copyright (C) 2016 The Android Open Source Project
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
package com.google.android.exoplayer2.demo;

import static com.google.android.exoplayer2.util.Assertions.checkNotNull;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.util.Pair;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.ExoPlaybackException;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.PlaybackParameters;
import com.google.android.exoplayer2.PlaybackPreparer;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.RenderersFactory;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.analytics.AnalyticsListener;
import com.google.android.exoplayer2.audio.AudioAttributes;
import com.google.android.exoplayer2.decoder.DecoderCounters;
import com.google.android.exoplayer2.drm.FrameworkMediaDrm;
import com.google.android.exoplayer2.ext.ima.ImaAdsLoader;
import com.google.android.exoplayer2.mediacodec.MediaCodecRenderer.DecoderInitializationException;
import com.google.android.exoplayer2.mediacodec.MediaCodecUtil.DecoderQueryException;
import com.google.android.exoplayer2.metadata.Metadata;
import com.google.android.exoplayer2.offline.DownloadRequest;
import com.google.android.exoplayer2.source.BehindLiveWindowException;
import com.google.android.exoplayer2.source.DefaultMediaSourceFactory;
import com.google.android.exoplayer2.source.LoadEventInfo;
import com.google.android.exoplayer2.source.MediaLoadData;
import com.google.android.exoplayer2.source.MediaSourceFactory;
import com.google.android.exoplayer2.source.TrackGroupArray;
import com.google.android.exoplayer2.source.ads.AdsLoader;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.trackselection.MappingTrackSelector.MappedTrackInfo;
import com.google.android.exoplayer2.trackselection.TrackSelectionArray;
import com.google.android.exoplayer2.ui.DebugTextViewHelper;
import com.google.android.exoplayer2.ui.StyledPlayerControlView;
import com.google.android.exoplayer2.ui.StyledPlayerView;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.util.ErrorMessageProvider;
import com.google.android.exoplayer2.util.Log;
import com.google.android.exoplayer2.util.Util;
import java.io.IOException;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** An activity that plays media using {@link SimpleExoPlayer}. */
public class PlayerActivity extends AppCompatActivity
    implements OnClickListener, PlaybackPreparer, StyledPlayerControlView.VisibilityListener {

  // Saved instance state keys.

  private static final String KEY_TRACK_SELECTOR_PARAMETERS = "track_selector_parameters";
  private static final String KEY_WINDOW = "window";
  private static final String KEY_POSITION = "position";
  private static final String KEY_AUTO_PLAY = "auto_play";

  private static final CookieManager DEFAULT_COOKIE_MANAGER;

  static {
    DEFAULT_COOKIE_MANAGER = new CookieManager();
    DEFAULT_COOKIE_MANAGER.setCookiePolicy(CookiePolicy.ACCEPT_ORIGINAL_SERVER);
  }

  protected StyledPlayerView playerView;
  protected LinearLayout debugRootView;
  protected TextView debugTextView;
  protected SimpleExoPlayer player;

  private boolean isShowingTrackSelectionDialog;
  private Button selectTracksButton;
  private DataSource.Factory dataSourceFactory;
  private List<MediaItem> mediaItems;
  private DefaultTrackSelector trackSelector;
  private DefaultTrackSelector.Parameters trackSelectorParameters;
  private DebugTextViewHelper debugViewHelper;
  private TrackGroupArray lastSeenTrackGroupArray;
  private boolean startAutoPlay;
  private int startWindow;
  private long startPosition;

  // Fields used only for ad playback. The ads loader is loaded via reflection.

  private AdsLoader adsLoader;
  private Uri loadedAdTagUri;

  // Activity lifecycle

  @Override
  public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    dataSourceFactory = DemoUtil.getDataSourceFactory(/* context= */ this);
    if (CookieHandler.getDefault() != DEFAULT_COOKIE_MANAGER) {
      CookieHandler.setDefault(DEFAULT_COOKIE_MANAGER);
    }

    setContentView();
    debugRootView = findViewById(R.id.controls_root);
    debugTextView = findViewById(R.id.debug_text_view);
    selectTracksButton = findViewById(R.id.select_tracks_button);
    selectTracksButton.setOnClickListener(this);

    playerView = findViewById(R.id.player_view);
    playerView.setControllerVisibilityListener(this);
    playerView.setErrorMessageProvider(new PlayerErrorMessageProvider());
    playerView.requestFocus();

    if (savedInstanceState != null) {
      trackSelectorParameters = savedInstanceState.getParcelable(KEY_TRACK_SELECTOR_PARAMETERS);
      startAutoPlay = savedInstanceState.getBoolean(KEY_AUTO_PLAY);
      startWindow = savedInstanceState.getInt(KEY_WINDOW);
      startPosition = savedInstanceState.getLong(KEY_POSITION);
    } else {
      DefaultTrackSelector.ParametersBuilder builder =
          new DefaultTrackSelector.ParametersBuilder(/* context= */ this);
      trackSelectorParameters = builder.build();
      clearStartPosition();
    }
  }

  @Override
  public void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    releasePlayer();
    releaseAdsLoader();
    clearStartPosition();
    setIntent(intent);
  }

  @Override
  public void onStart() {
    super.onStart();
    if (Util.SDK_INT > 23) {
      initializePlayer();
      if (playerView != null) {
        playerView.onResume();
      }
    }
  }

  @Override
  public void onResume() {
    super.onResume();
    if (Util.SDK_INT <= 23 || player == null) {
      initializePlayer();
      if (playerView != null) {
        playerView.onResume();
      }
    }
  }

  @Override
  public void onPause() {
    super.onPause();
    if (Util.SDK_INT <= 23) {
      if (playerView != null) {
        playerView.onPause();
      }
      releasePlayer();
    }
  }

  @Override
  public void onStop() {
    super.onStop();
    if (Util.SDK_INT > 23) {
      if (playerView != null) {
        playerView.onPause();
      }
      releasePlayer();
    }
  }

  @Override
  public void onDestroy() {
    super.onDestroy();
    releaseAdsLoader();
  }

  @Override
  public void onRequestPermissionsResult(
      int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    if (grantResults.length == 0) {
      // Empty results are triggered if a permission is requested while another request was already
      // pending and can be safely ignored in this case.
      return;
    }
    if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
      initializePlayer();
    } else {
      showToast(R.string.storage_permission_denied);
      finish();
    }
  }

  @Override
  public void onSaveInstanceState(@NonNull Bundle outState) {
    super.onSaveInstanceState(outState);
    updateTrackSelectorParameters();
    updateStartPosition();
    outState.putParcelable(KEY_TRACK_SELECTOR_PARAMETERS, trackSelectorParameters);
    outState.putBoolean(KEY_AUTO_PLAY, startAutoPlay);
    outState.putInt(KEY_WINDOW, startWindow);
    outState.putLong(KEY_POSITION, startPosition);
  }

  // Activity input

  @Override
  public boolean dispatchKeyEvent(KeyEvent event) {
    // See whether the player view wants to handle media or DPAD keys events.
    return playerView.dispatchKeyEvent(event) || super.dispatchKeyEvent(event);
  }

  // OnClickListener methods

  @Override
  public void onClick(View view) {
    if (view == selectTracksButton
        && !isShowingTrackSelectionDialog
        && TrackSelectionDialog.willHaveContent(trackSelector)) {
      isShowingTrackSelectionDialog = true;
      TrackSelectionDialog trackSelectionDialog =
          TrackSelectionDialog.createForTrackSelector(
              trackSelector,
              /* onDismissListener= */ dismissedDialog -> isShowingTrackSelectionDialog = false);
      trackSelectionDialog.show(getSupportFragmentManager(), /* tag= */ null);
    }
  }

  // PlaybackPreparer implementation

  @Override
  public void preparePlayback() {
    player.prepare();
  }

  // PlayerControlView.VisibilityListener implementation

  @Override
  public void onVisibilityChange(int visibility) {
    debugRootView.setVisibility(visibility);
  }

  // Internal methods

  protected void setContentView() {
    setContentView(R.layout.player_activity);
  }

  /** @return Whether initialization was successful. */
  protected boolean initializePlayer() {
    //Log.d("ISSUE #7942", "initializePlayer-start    " + new SimpleDateFormat("HH:mm:ss.SSS").format(Calendar.getInstance().getTime()));
    if (player == null) {
      Intent intent = getIntent();

      mediaItems = createMediaItems(intent);
      if (mediaItems.isEmpty()) {
        return false;
      }

      boolean preferExtensionDecoders =
          intent.getBooleanExtra(IntentUtil.PREFER_EXTENSION_DECODERS_EXTRA, false);
      RenderersFactory renderersFactory =
          DemoUtil.buildRenderersFactory(/* context= */ this, preferExtensionDecoders);
      MediaSourceFactory mediaSourceFactory =
          new DefaultMediaSourceFactory(dataSourceFactory)
              .setAdsLoaderProvider(this::getAdsLoader)
              .setAdViewProvider(playerView);

      trackSelector = new DefaultTrackSelector(/* context= */ this);
      trackSelector.setParameters(trackSelectorParameters);
      lastSeenTrackGroupArray = null;
      player =
          new SimpleExoPlayer.Builder(/* context= */ this, renderersFactory)
              .setMediaSourceFactory(mediaSourceFactory)
              .setTrackSelector(trackSelector)
              .build();
      player.addListener(new PlayerEventListener());
//      player.addAnalyticsListener(new EventLogger(trackSelector));
      player.addAnalyticsListener(new CustomAnalyticsListener());
      player.setAudioAttributes(AudioAttributes.DEFAULT, /* handleAudioFocus= */ true);
      player.setPlayWhenReady(startAutoPlay);
      playerView.setPlayer(player);
      playerView.setPlaybackPreparer(this);
      debugViewHelper = new DebugTextViewHelper(player, debugTextView);
      debugViewHelper.start();
    }
    boolean haveStartPosition = startWindow != C.INDEX_UNSET;
    if (haveStartPosition) {
      player.seekTo(startWindow, startPosition);
    }
    player.setMediaItems(mediaItems, /* resetPosition= */ !haveStartPosition);
    player.seekTo(3600000L); // case: resume playback at 1hr
    player.prepare();
    updateButtonVisibility();
    //Log.d("ISSUE #7942", "initializePlayer-end      " + new SimpleDateFormat("HH:mm:ss.SSS").format(Calendar.getInstance().getTime()));
    return true;
  }

  private List<MediaItem> createMediaItems(Intent intent) {
    String action = intent.getAction();
    boolean actionIsListView = IntentUtil.ACTION_VIEW_LIST.equals(action);
    if (!actionIsListView && !IntentUtil.ACTION_VIEW.equals(action)) {
      showToast(getString(R.string.unexpected_intent_action, action));
      finish();
      return Collections.emptyList();
    }

    List<MediaItem> mediaItems =
        createMediaItems(intent, DemoUtil.getDownloadTracker(/* context= */ this));
    boolean hasAds = false;
    for (int i = 0; i < mediaItems.size(); i++) {
      MediaItem mediaItem = mediaItems.get(i);

      if (!Util.checkCleartextTrafficPermitted(mediaItem)) {
        showToast(R.string.error_cleartext_not_permitted);
        return Collections.emptyList();
      }
      if (Util.maybeRequestReadExternalStoragePermission(/* activity= */ this, mediaItem)) {
        // The player will be reinitialized if the permission is granted.
        return Collections.emptyList();
      }

      MediaItem.DrmConfiguration drmConfiguration =
          checkNotNull(mediaItem.playbackProperties).drmConfiguration;
      if (drmConfiguration != null) {
        if (Util.SDK_INT < 18) {
          showToast(R.string.error_drm_unsupported_before_api_18);
          finish();
          return Collections.emptyList();
        } else if (!FrameworkMediaDrm.isCryptoSchemeSupported(drmConfiguration.uuid)) {
          showToast(R.string.error_drm_unsupported_scheme);
          finish();
          return Collections.emptyList();
        }
      }
      hasAds |= mediaItem.playbackProperties.adTagUri != null;
    }
    if (!hasAds) {
      releaseAdsLoader();
    }
    return mediaItems;
  }

  private AdsLoader getAdsLoader(Uri adTagUri) {
    if (mediaItems.size() > 1) {
      showToast(R.string.unsupported_ads_in_playlist);
      releaseAdsLoader();
      return null;
    }
    if (!adTagUri.equals(loadedAdTagUri)) {
      releaseAdsLoader();
      loadedAdTagUri = adTagUri;
    }
    // The ads loader is reused for multiple playbacks, so that ad playback can resume.
    if (adsLoader == null) {
      adsLoader = new ImaAdsLoader(/* context= */ PlayerActivity.this, adTagUri);
    }
    adsLoader.setPlayer(player);
    return adsLoader;
  }

  protected void releasePlayer() {
    if (player != null) {
      updateTrackSelectorParameters();
      updateStartPosition();
      debugViewHelper.stop();
      debugViewHelper = null;
      player.release();
      player = null;
      mediaItems = Collections.emptyList();
      trackSelector = null;
    }
    if (adsLoader != null) {
      adsLoader.setPlayer(null);
    }
  }

  private void releaseAdsLoader() {
    if (adsLoader != null) {
      adsLoader.release();
      adsLoader = null;
      loadedAdTagUri = null;
      playerView.getOverlayFrameLayout().removeAllViews();
    }
  }

  private void updateTrackSelectorParameters() {
    if (trackSelector != null) {
      trackSelectorParameters = trackSelector.getParameters();
    }
  }

  private void updateStartPosition() {
    if (player != null) {
      startAutoPlay = player.getPlayWhenReady();
      startWindow = player.getCurrentWindowIndex();
      startPosition = Math.max(0, player.getContentPosition());
    }
  }

  protected void clearStartPosition() {
    startAutoPlay = true;
    startWindow = C.INDEX_UNSET;
    startPosition = C.TIME_UNSET;
  }

  // User controls

  private void updateButtonVisibility() {
    selectTracksButton.setEnabled(
        player != null && TrackSelectionDialog.willHaveContent(trackSelector));
  }

  private void showControls() {
    debugRootView.setVisibility(View.VISIBLE);
  }

  private void showToast(int messageId) {
    showToast(getString(messageId));
  }

  private void showToast(String message) {
    Toast.makeText(getApplicationContext(), message, Toast.LENGTH_LONG).show();
  }

  private static boolean isBehindLiveWindow(ExoPlaybackException e) {
    if (e.type != ExoPlaybackException.TYPE_SOURCE) {
      return false;
    }
    Throwable cause = e.getSourceException();
    while (cause != null) {
      if (cause instanceof BehindLiveWindowException) {
        return true;
      }
      cause = cause.getCause();
    }
    return false;
  }

  private class PlayerEventListener implements Player.EventListener {

    @Override
    public void onPlaybackStateChanged(@Player.State int playbackState) {
      if (playbackState == Player.STATE_ENDED) {
        showControls();
      }
      updateButtonVisibility();
      //Log.d("ISSUE #7942", "onPlaybackStateChanged(" + playbackState + ") " + new SimpleDateFormat("HH:mm:ss.SSS").format(Calendar.getInstance().getTime()) + " (2 = preparing | 3 = buffering)");
    }

    @Override
    public void onPlayerError(@NonNull ExoPlaybackException e) {
      if (isBehindLiveWindow(e)) {
        clearStartPosition();
        initializePlayer();
      } else {
        updateButtonVisibility();
        showControls();
      }
    }

    @Override
    @SuppressWarnings("ReferenceEquality")
    public void onTracksChanged(
        @NonNull TrackGroupArray trackGroups, @NonNull TrackSelectionArray trackSelections) {
      updateButtonVisibility();
      if (trackGroups != lastSeenTrackGroupArray) {
        MappedTrackInfo mappedTrackInfo = trackSelector.getCurrentMappedTrackInfo();
        if (mappedTrackInfo != null) {
          if (mappedTrackInfo.getTypeSupport(C.TRACK_TYPE_VIDEO)
              == MappedTrackInfo.RENDERER_SUPPORT_UNSUPPORTED_TRACKS) {
            showToast(R.string.error_unsupported_video);
          }
          if (mappedTrackInfo.getTypeSupport(C.TRACK_TYPE_AUDIO)
              == MappedTrackInfo.RENDERER_SUPPORT_UNSUPPORTED_TRACKS) {
            showToast(R.string.error_unsupported_audio);
          }
        }
        lastSeenTrackGroupArray = trackGroups;
      }
    }
  }

  private class PlayerErrorMessageProvider implements ErrorMessageProvider<ExoPlaybackException> {

    @Override
    @NonNull
    public Pair<Integer, String> getErrorMessage(@NonNull ExoPlaybackException e) {
      String errorString = getString(R.string.error_generic);
      if (e.type == ExoPlaybackException.TYPE_RENDERER) {
        Exception cause = e.getRendererException();
        if (cause instanceof DecoderInitializationException) {
          // Special case for decoder initialization failures.
          DecoderInitializationException decoderInitializationException =
              (DecoderInitializationException) cause;
          if (decoderInitializationException.codecInfo == null) {
            if (decoderInitializationException.getCause() instanceof DecoderQueryException) {
              errorString = getString(R.string.error_querying_decoders);
            } else if (decoderInitializationException.secureDecoderRequired) {
              errorString =
                  getString(
                      R.string.error_no_secure_decoder, decoderInitializationException.mimeType);
            } else {
              errorString =
                  getString(R.string.error_no_decoder, decoderInitializationException.mimeType);
            }
          } else {
            errorString =
                getString(
                    R.string.error_instantiating_decoder,
                    decoderInitializationException.codecInfo.name);
          }
        }
      }
      return Pair.create(0, errorString);
    }
  }

  private static List<MediaItem> createMediaItems(Intent intent, DownloadTracker downloadTracker) {
    List<MediaItem> mediaItems = new ArrayList<>();
    for (MediaItem item : IntentUtil.createMediaItemsFromIntent(intent)) {
      @Nullable
      DownloadRequest downloadRequest =
          downloadTracker.getDownloadRequest(checkNotNull(item.playbackProperties).uri);
      if (downloadRequest != null) {
        MediaItem.Builder builder = item.buildUpon();
        builder
            .setMediaId(downloadRequest.id)
            .setUri(downloadRequest.uri)
            .setCustomCacheKey(downloadRequest.customCacheKey)
            .setMimeType(downloadRequest.mimeType)
            .setStreamKeys(downloadRequest.streamKeys)
            .setDrmKeySetId(downloadRequest.keySetId);
        mediaItems.add(builder.build());
      } else {
        mediaItems.add(item);
      }
    }
    return mediaItems;
  }

  private class CustomAnalyticsListener implements AnalyticsListener {

    @Override
    public void onPlayerStateChanged(EventTime eventTime, boolean playWhenReady,
        int playbackState) {
    }

    @Override
    public void onPlaybackStateChanged(EventTime eventTime, int state) {
      Log.d("ISSUE #7942", "=> onPlaybackStateChanged    " + new SimpleDateFormat("HH:mm:ss.SSS").format(eventTime.realtimeMs));
    }

    @Override
    public void onPlayWhenReadyChanged(EventTime eventTime, boolean playWhenReady, int reason) {
      Log.d("ISSUE #7942", "=> onPlayWhenReadyChanged    " + new SimpleDateFormat("HH:mm:ss.SSS").format(eventTime.realtimeMs));
    }

    @Override
    public void onPlaybackSuppressionReasonChanged(EventTime eventTime,
        int playbackSuppressionReason) {
      Log.d("ISSUE #7942", "=> onPlaybackSuppressionReasonChanged " + new SimpleDateFormat("HH:mm:ss.SSS").format(eventTime.realtimeMs));
    }

    @Override
    public void onIsPlayingChanged(EventTime eventTime, boolean isPlaying) {
      Log.d("ISSUE #7942", "=> onIsPlayingChanged        " + new SimpleDateFormat("HH:mm:ss.SSS").format(eventTime.realtimeMs));
    }

    @Override
    public void onTimelineChanged(EventTime eventTime, int reason) {
      Log.d("ISSUE #7942", "=> onTimelineChanged         " + new SimpleDateFormat("HH:mm:ss.SSS").format(eventTime.realtimeMs) + " -> " + reason + "(1 = SOURCE_UPDATE | 0 = PLAYLIST_CHANGED)");
    }

    @Override
    public void onMediaItemTransition(EventTime eventTime, @Nullable MediaItem mediaItem,
        int reason) {
      Log.d("ISSUE #7942", "=> onMediaItemTransition     " + new SimpleDateFormat("HH:mm:ss.SSS").format(eventTime.realtimeMs));
    }

    @Override
    public void onPositionDiscontinuity(EventTime eventTime, int reason) {
      Log.d("ISSUE #7942", "=> onPositionDiscontinuity   " + new SimpleDateFormat("HH:mm:ss.SSS").format(eventTime.realtimeMs));
    }

    @Override
    public void onSeekStarted(EventTime eventTime) {
      Log.d("ISSUE #7942", "=> onSeekStarted             " + new SimpleDateFormat("HH:mm:ss.SSS").format(eventTime.realtimeMs));
    }

    @Override
    public void onSeekProcessed(EventTime eventTime) {
      Log.d("ISSUE #7942", "=> onSeekProcessed           " + new SimpleDateFormat("HH:mm:ss.SSS").format(eventTime.realtimeMs));
    }

    @Override
    public void onPlaybackParametersChanged(EventTime eventTime,
        PlaybackParameters playbackParameters) {

    }

    @Override
    public void onRepeatModeChanged(EventTime eventTime, int repeatMode) {

    }

    @Override
    public void onShuffleModeChanged(EventTime eventTime, boolean shuffleModeEnabled) {

    }

    @Override
    public void onIsLoadingChanged(EventTime eventTime, boolean isLoading) {
      Log.d("ISSUE #7942", "=> onIsLoadingChanged        " + new SimpleDateFormat("HH:mm:ss.SSS").format(eventTime.realtimeMs) + " -> " + isLoading);
    }

    @Override
    public void onLoadingChanged(EventTime eventTime, boolean isLoading) {

    }

    @Override
    public void onPlayerError(EventTime eventTime, ExoPlaybackException error) {

    }

    @Override
    public void onTracksChanged(EventTime eventTime, TrackGroupArray trackGroups,
        TrackSelectionArray trackSelections) {
      Log.d("ISSUE #7942", "=> onPlayWhenReadyChanged    " + new SimpleDateFormat("HH:mm:ss.SSS").format(eventTime.realtimeMs));
    }

    @Override
    public void onLoadStarted(EventTime eventTime, LoadEventInfo loadEventInfo,
        MediaLoadData mediaLoadData) {
      Log.d("ISSUE #7942", "=> onTracksChanged           " + new SimpleDateFormat("HH:mm:ss.SSS").format(eventTime.realtimeMs));
    }

    @Override
    public void onLoadCompleted(EventTime eventTime, LoadEventInfo loadEventInfo,
        MediaLoadData mediaLoadData) {
      Log.d("ISSUE #7942", "=> onLoadCompleted           " + new SimpleDateFormat("HH:mm:ss.SSS").format(eventTime.realtimeMs));
    }

    @Override
    public void onLoadCanceled(EventTime eventTime, LoadEventInfo loadEventInfo,
        MediaLoadData mediaLoadData) {
      Log.d("ISSUE #7942", "=> onLoadCanceled            " + new SimpleDateFormat("HH:mm:ss.SSS").format(eventTime.realtimeMs));
    }

    @Override
    public void onLoadError(EventTime eventTime, LoadEventInfo loadEventInfo,
        MediaLoadData mediaLoadData, IOException error, boolean wasCanceled) {
      Log.d("ISSUE #7942", "=> onLoadError               " + new SimpleDateFormat("HH:mm:ss.SSS").format(eventTime.realtimeMs));
    }

    @Override
    public void onDownstreamFormatChanged(EventTime eventTime, MediaLoadData mediaLoadData) {
      Log.d("ISSUE #7942", "=> onDownstreamFormatChanged " + new SimpleDateFormat("HH:mm:ss.SSS").format(eventTime.realtimeMs));
    }

    @Override
    public void onUpstreamDiscarded(EventTime eventTime, MediaLoadData mediaLoadData) {
      Log.d("ISSUE #7942", "=> onUpstreamDiscarded      " + new SimpleDateFormat("HH:mm:ss.SSS").format(eventTime.realtimeMs));
    }

    @Override
    public void onBandwidthEstimate(EventTime eventTime, int totalLoadTimeMs, long totalBytesLoaded,
        long bitrateEstimate) {

    }

    @Override
    public void onMetadata(EventTime eventTime, Metadata metadata) {
      Log.d("ISSUE #7942", "=> onMetadata                " + new SimpleDateFormat("HH:mm:ss.SSS").format(eventTime.realtimeMs));
    }

    @Override
    public void onDecoderEnabled(EventTime eventTime, int trackType,
        DecoderCounters decoderCounters) {

    }

    @Override
    public void onDecoderInitialized(EventTime eventTime, int trackType, String decoderName,
        long initializationDurationMs) {

    }

    @Override
    public void onDecoderInputFormatChanged(EventTime eventTime, int trackType, Format format) {

    }

    @Override
    public void onDecoderDisabled(EventTime eventTime, int trackType,
        DecoderCounters decoderCounters) {

    }

    @Override
    public void onAudioEnabled(EventTime eventTime, DecoderCounters counters) {
      Log.d("ISSUE #7942", "=> onAudioEnabled            " + new SimpleDateFormat("HH:mm:ss.SSS").format(eventTime.realtimeMs));
    }

    @Override
    public void onAudioDecoderInitialized(EventTime eventTime, String decoderName,
        long initializationDurationMs) {

    }

    @Override
    public void onAudioInputFormatChanged(EventTime eventTime, Format format) {

    }

    @Override
    public void onAudioPositionAdvancing(EventTime eventTime, long playoutStartSystemTimeMs) {

    }

    @Override
    public void onAudioUnderrun(EventTime eventTime, int bufferSize, long bufferSizeMs,
        long elapsedSinceLastFeedMs) {

    }

    @Override
    public void onAudioDisabled(EventTime eventTime, DecoderCounters counters) {

    }

    @Override
    public void onAudioSessionId(EventTime eventTime, int audioSessionId) {

    }

    @Override
    public void onAudioAttributesChanged(EventTime eventTime, AudioAttributes audioAttributes) {

    }

    @Override
    public void onSkipSilenceEnabledChanged(EventTime eventTime, boolean skipSilenceEnabled) {

    }

    @Override
    public void onVolumeChanged(EventTime eventTime, float volume) {

    }

    @Override
    public void onVideoEnabled(EventTime eventTime, DecoderCounters counters) {

    }

    @Override
    public void onVideoDecoderInitialized(EventTime eventTime, String decoderName,
        long initializationDurationMs) {

    }

    @Override
    public void onVideoInputFormatChanged(EventTime eventTime, Format format) {

    }

    @Override
    public void onDroppedVideoFrames(EventTime eventTime, int droppedFrames, long elapsedMs) {

    }

    @Override
    public void onVideoDisabled(EventTime eventTime, DecoderCounters counters) {

    }

    @Override
    public void onVideoFrameProcessingOffset(EventTime eventTime, long totalProcessingOffsetUs,
        int frameCount) {

    }

    @Override
    public void onRenderedFirstFrame(EventTime eventTime, @Nullable Surface surface) {

    }

    @Override
    public void onVideoSizeChanged(EventTime eventTime, int width, int height,
        int unappliedRotationDegrees, float pixelWidthHeightRatio) {

    }

    @Override
    public void onSurfaceSizeChanged(EventTime eventTime, int width, int height) {

    }

    @Override
    public void onDrmSessionAcquired(EventTime eventTime) {

    }

    @Override
    public void onDrmKeysLoaded(EventTime eventTime) {

    }

    @Override
    public void onDrmSessionManagerError(EventTime eventTime, Exception error) {

    }

    @Override
    public void onDrmKeysRestored(EventTime eventTime) {

    }

    @Override
    public void onDrmKeysRemoved(EventTime eventTime) {

    }

    @Override
    public void onDrmSessionReleased(EventTime eventTime) {

    }
  }


}
