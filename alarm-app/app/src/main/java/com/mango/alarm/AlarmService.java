package com.mango.alarm;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

/**
 * Plays the alarm on repeat until the challenge is solved.
 *
 * Phase 1: plays as media, so it follows normal routing (Bluetooth if connected),
 * with the media volume held at 80% or higher.
 *
 * Phase 2 (after 5 minutes without being dismissed): switches to the alarm stream,
 * pins output to the built-in speaker and holds every relevant volume at 100%.
 */
public class AlarmService extends Service {
    private static final String TAG = "MathAlarm";
    private static final String ACTION_START = "com.mango.alarm.START";
    private static final String ACTION_STOP = "com.mango.alarm.STOP";
    private static final String CHANNEL_ID = "alarm_ringing";
    private static final int NOTIFICATION_ID = 1;

    private static final long ESCALATE_AFTER_MS = 5 * 60 * 1000;
    private static final long VOLUME_CHECK_MS = 500;
    private static final float PHASE1_VOLUME = 0.8f;

    private static volatile boolean ringing = false;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private AudioManager audio;
    private MediaPlayer player;
    private PowerManager.WakeLock wakeLock;
    private AudioFocusRequest focusRequest;
    private boolean escalated = false;
    private int savedMusicVolume = -1;
    private int savedAlarmVolume = -1;

    public static boolean isRinging() {
        return ringing;
    }

    public static void start(Context context) {
        Intent i = new Intent(context, AlarmService.class).setAction(ACTION_START);
        context.startForegroundService(i);
    }

    public static void stop(Context context) {
        Intent i = new Intent(context, AlarmService.class).setAction(ACTION_STOP);
        context.startService(i);
    }

    private final Runnable volumeEnforcer = new Runnable() {
        @Override
        public void run() {
            enforceVolume();
            handler.postDelayed(this, VOLUME_CHECK_MS);
        }
    };

    private final Runnable escalate = new Runnable() {
        @Override
        public void run() {
            escalateToSpeaker();
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        audio = (AudioManager) getSystemService(AUDIO_SERVICE);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_STOP.equals(action)) {
            stopAlarm();
            return START_NOT_STICKY;
        }

        // Must call startForeground promptly on every startForegroundService call.
        startInForeground();
        if (ringing) return START_STICKY;
        ringing = true;

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MathAlarm:ringing");
        wakeLock.acquire(60 * 60 * 1000L);

        savedMusicVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
        savedAlarmVolume = audio.getStreamVolume(AudioManager.STREAM_ALARM);

        requestFocus();
        playPhase1();
        handler.post(volumeEnforcer);
        handler.postDelayed(escalate, ESCALATE_AFTER_MS);

        // Works when the app is allowed to start activities; otherwise the
        // full-screen notification brings the challenge up.
        try {
            startActivity(new Intent(this, ChallengeActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            Log.w(TAG, "Could not launch challenge directly", e);
        }
        return START_STICKY;
    }

    private void startInForeground() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Ringing alarm",
                NotificationManager.IMPORTANCE_HIGH);
        channel.setSound(null, null);
        channel.setBypassDnd(true);
        nm.createNotificationChannel(channel);

        PendingIntent challenge = PendingIntent.getActivity(this, 0,
                new Intent(this, ChallengeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification n = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_alarm)
                .setContentTitle("Alarm")
                .setContentText("Tap and solve the problem to turn it off")
                .setCategory(Notification.CATEGORY_ALARM)
                .setOngoing(true)
                .setContentIntent(challenge)
                .setFullScreenIntent(challenge, true)
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }
    }

    private void requestFocus() {
        focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build())
                .setOnAudioFocusChangeListener(change -> { /* never yield */ })
                .build();
        audio.requestAudioFocus(focusRequest);
    }

    private void playPhase1() {
        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build();
        player = createPlayer(attrs, null);
    }

    private void escalateToSpeaker() {
        if (!ringing) return;
        escalated = true;
        releasePlayer();

        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        player = createPlayer(attrs, findBuiltInSpeaker());
        enforceVolume();
    }

    private AudioDeviceInfo findBuiltInSpeaker() {
        for (AudioDeviceInfo d : audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            if (d.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) return d;
        }
        return null;
    }

    private MediaPlayer createPlayer(AudioAttributes attrs, AudioDeviceInfo device) {
        MediaPlayer mp = new MediaPlayer();
        try (AssetFileDescriptor afd = getResources().openRawResourceFd(R.raw.alarm_sound)) {
            mp.setAudioAttributes(attrs);
            mp.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
            if (device != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                mp.setPreferredDevice(device);
            }
            mp.setLooping(true);
            mp.prepare();
            mp.setVolume(1f, 1f);
            mp.start();
        } catch (Exception e) {
            Log.e(TAG, "Failed to play alarm sound", e);
        }
        return mp;
    }

    private void enforceVolume() {
        if (escalated) {
            setAtLeast(AudioManager.STREAM_ALARM, 1f);
        } else {
            setAtLeast(AudioManager.STREAM_MUSIC, PHASE1_VOLUME);
        }
    }

    private void setAtLeast(int stream, float fraction) {
        try {
            int max = audio.getStreamMaxVolume(stream);
            int target = (int) Math.ceil(max * fraction);
            if (audio.isStreamMute(stream)) {
                audio.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, 0);
            }
            if (audio.getStreamVolume(stream) < target) {
                audio.setStreamVolume(stream, target, 0);
            }
        } catch (SecurityException e) {
            Log.w(TAG, "Not allowed to change volume of stream " + stream, e);
        }
    }

    private void releasePlayer() {
        if (player != null) {
            try {
                player.stop();
            } catch (IllegalStateException ignored) {
            }
            player.release();
            player = null;
        }
    }

    private void stopAlarm() {
        handler.removeCallbacks(volumeEnforcer);
        handler.removeCallbacks(escalate);
        releasePlayer();
        if (ringing) {
            try {
                if (savedMusicVolume >= 0) {
                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, savedMusicVolume, 0);
                }
                if (savedAlarmVolume >= 0) {
                    audio.setStreamVolume(AudioManager.STREAM_ALARM, savedAlarmVolume, 0);
                }
            } catch (SecurityException ignored) {
            }
        }
        if (focusRequest != null) {
            audio.abandonAudioFocusRequest(focusRequest);
            focusRequest = null;
        }
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        ringing = false;
        escalated = false;
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        if (ringing) stopAlarm();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
