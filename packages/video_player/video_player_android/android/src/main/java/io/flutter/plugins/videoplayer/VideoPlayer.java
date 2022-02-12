package io.flutter.plugins.videoplayer;

import static com.google.android.exoplayer2.Player.REPEAT_MODE_ALL;
import static com.google.android.exoplayer2.Player.REPEAT_MODE_OFF;

import android.content.Context;
import android.net.Uri;
import android.view.Surface;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.ExoPlaybackException;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.PlaybackParameters;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.audio.AudioAttributes;
import com.google.android.exoplayer2.ext.ima.ImaAdsLoader;
import com.google.android.exoplayer2.source.DefaultMediaSourceFactory;
import com.google.android.exoplayer2.source.MediaSourceFactory;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory;
import com.google.android.exoplayer2.util.Util;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.flutter.plugin.common.EventChannel;
import io.flutter.view.TextureRegistry;

class VideoPlayer {
  private SimpleExoPlayer exoPlayer;

  private Surface surface;

  private final TextureRegistry.SurfaceTextureEntry textureEntry;

  private QueuingEventSink eventSink = new QueuingEventSink();

  private final EventChannel eventChannel;

  private boolean isInitialized = false;

  private final VideoPlayerOptions options;

  private final String vastTag;

  private final ImaAdsLoader imaAdsLoader;

  VideoPlayer(
    Context context,
    EventChannel eventChannel,
    TextureRegistry.SurfaceTextureEntry textureEntry,
    String dataSource,
    String formatHint,
    Map<String, String> httpHeaders,
    VideoPlayerOptions options,
    String vastTag) {
    this.eventChannel = eventChannel;
    this.textureEntry = textureEntry;
    this.options = options;
    this.vastTag = vastTag;
    this.imaAdsLoader = new ImaAdsLoader.Builder(context).build();

    //Uri uri = Uri.parse(dataSource);
    //exoPlayer = new SimpleExoPlayer.Builder(context).build();

    DataSource.Factory dataSourceFactory =
      new DefaultDataSourceFactory(context, Util.getUserAgent(context, "video_player_plugin"));

    MediaSourceFactory mediaSourceFactory =
      new DefaultMediaSourceFactory(dataSourceFactory)
        .setAdsLoaderProvider(unusedAdTagUri -> imaAdsLoader)
        ;//.setAdViewProvider(playerView);

    exoPlayer = new SimpleExoPlayer.Builder(context)
      .setMediaSourceFactory(mediaSourceFactory)
      .build();

    imaAdsLoader.setPlayer(exoPlayer);

    MediaItem mediaItem = new MediaItem.Builder()
      .setUri(Uri.parse(dataSource))
      .setAdTagUri(Uri.parse(vastTag))
      //.setAdsConfiguration(new MediaItem.AdsConfiguration.Builder(vastTag).build())
      .build();

    exoPlayer.setMediaItem(mediaItem);
    exoPlayer.prepare();
    exoPlayer.setPlayWhenReady(false);

    setupVideoPlayer(eventChannel, textureEntry);
  }

  private static boolean isHTTP(Uri uri) {
    if (uri == null || uri.getScheme() == null) {
      return false;
    }
    String scheme = uri.getScheme();
    return scheme.equals("http") || scheme.equals("https");
  }

  private void setupVideoPlayer(
    EventChannel eventChannel, TextureRegistry.SurfaceTextureEntry textureEntry) {
    eventChannel.setStreamHandler(
      new EventChannel.StreamHandler() {
        @Override
        public void onListen(Object o, EventChannel.EventSink sink) {
          eventSink.setDelegate(sink);
        }

        @Override
        public void onCancel(Object o) {
          eventSink.setDelegate(null);
        }
      });

    surface = new Surface(textureEntry.surfaceTexture());
    exoPlayer.setVideoSurface(surface);
    setAudioAttributes(exoPlayer, options.mixWithOthers);

    exoPlayer.addListener(
      new Player.Listener() {
        private boolean isBuffering = false;

        public void setBuffering(boolean buffering) {
          if (isBuffering != buffering) {
            isBuffering = buffering;
            Map<String, Object> event = new HashMap<>();
            event.put("event", isBuffering ? "bufferingStart" : "bufferingEnd");
            eventSink.success(event);
          }
        }

        @Override
        public void onPlaybackStateChanged(final int playbackState) {
          if (playbackState == Player.STATE_BUFFERING) {
            setBuffering(true);
            sendBufferingUpdate();
          } else if (playbackState == Player.STATE_READY) {
            if (!isInitialized) {
              isInitialized = true;
              sendInitialized();
            }
          } else if (playbackState == Player.STATE_ENDED) {
            Map<String, Object> event = new HashMap<>();
            event.put("event", "completed");
            eventSink.success(event);
          }

          if (playbackState != Player.STATE_BUFFERING) {
            setBuffering(false);
          }
        }

        @Override
        public void onPlayerError(ExoPlaybackException error) {
          setBuffering(false);
          if (eventSink != null) {
            eventSink.error("VideoError", "Video player had error " + error, null);
          }
        }
      });
  }

  @SuppressWarnings("deprecation")
  private static void setAudioAttributes(SimpleExoPlayer exoPlayer, boolean isMixMode) {
    exoPlayer.setAudioAttributes(
      new AudioAttributes.Builder().setContentType(C.CONTENT_TYPE_MOVIE).build(), !isMixMode);
  }

  void sendBufferingUpdate() {
    Map<String, Object> event = new HashMap<>();
    event.put("event", "bufferingUpdate");
    List<? extends Number> range = Arrays.asList(0, exoPlayer.getBufferedPosition());
    // iOS supports a list of buffered ranges, so here is a list with a single range.
    event.put("values", Collections.singletonList(range));
    eventSink.success(event);
  }

  @SuppressWarnings("SuspiciousNameCombination")
  private void sendInitialized() {
    if (isInitialized) {
      Map<String, Object> event = new HashMap<>();
      event.put("event", "initialized");
      event.put("duration", exoPlayer.getDuration());

      if (exoPlayer.getVideoFormat() != null) {
        Format videoFormat = exoPlayer.getVideoFormat();
        int width = videoFormat.width;
        int height = videoFormat.height;
        int rotationDegrees = videoFormat.rotationDegrees;
        // Switch the width/height if video was taken in portrait mode
        if (rotationDegrees == 90 || rotationDegrees == 270) {
          width = exoPlayer.getVideoFormat().height;
          height = exoPlayer.getVideoFormat().width;
        }
        event.put("width", width);
        event.put("height", height);
      }
      eventSink.success(event);
    }
  }

  void play() {
    exoPlayer.setPlayWhenReady(true);
  }

  void pause() {
    exoPlayer.setPlayWhenReady(false);
  }

  void setLooping(boolean value) {
    exoPlayer.setRepeatMode(value ? REPEAT_MODE_ALL : REPEAT_MODE_OFF);
  }

  void setVolume(double value) {
    float bracketedValue = (float) Math.max(0.0, Math.min(1.0, value));
    exoPlayer.setVolume(bracketedValue);
  }

  void setPlaybackSpeed(double value) {
    // We do not need to consider pitch and skipSilence for now as we do not handle them and
    // therefore never diverge from the default values.
    final PlaybackParameters playbackParameters = new PlaybackParameters(((float) value));

    exoPlayer.setPlaybackParameters(playbackParameters);
  }

  void seekTo(int location) {
    exoPlayer.seekTo(location);
  }

  long getPosition() {
    return exoPlayer.getCurrentPosition();
  }

  void dispose() {
    if (isInitialized) {
      exoPlayer.stop();
    }
    textureEntry.release();
    eventChannel.setStreamHandler(null);
    if (surface != null) {
      surface.release();
    }
    if (exoPlayer != null) {
      exoPlayer.release();
    }
  }
}
