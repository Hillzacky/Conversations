package eu.siacs.conversations.ui;

import static eu.siacs.conversations.utils.PermissionUtils.getFirstDenied;

import static java.util.Arrays.asList;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.PictureInPictureParams;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.opengl.GLException;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager;
import android.util.Log;
import android.util.Rational;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.StringRes;
import androidx.databinding.DataBindingUtil;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.databinding.ActivityRtpSessionBinding;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Contact;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.services.CallIntegration;
import eu.siacs.conversations.services.CallIntegrationConnectionService;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.ui.util.AvatarWorkerTask;
import eu.siacs.conversations.ui.util.MainThreadExecutor;
import eu.siacs.conversations.ui.util.Rationals;
import eu.siacs.conversations.utils.PermissionUtils;
import eu.siacs.conversations.utils.TimeFrameUtils;
import eu.siacs.conversations.xmpp.Jid;
import eu.siacs.conversations.xmpp.jingle.AbstractJingleConnection;
import eu.siacs.conversations.xmpp.jingle.ContentAddition;
import eu.siacs.conversations.xmpp.jingle.JingleConnectionManager;
import eu.siacs.conversations.xmpp.jingle.JingleRtpConnection;
import eu.siacs.conversations.xmpp.jingle.Media;
import eu.siacs.conversations.xmpp.jingle.OngoingRtpSession;
import eu.siacs.conversations.xmpp.jingle.RtpCapability;
import eu.siacs.conversations.xmpp.jingle.RtpEndUserState;

import org.webrtc.RendererCommon;
import org.webrtc.SurfaceViewRenderer;
import org.webrtc.VideoTrack;

import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

public class RtpSessionActivity extends XmppActivity
        implements XmppConnectionService.OnJingleRtpConnectionUpdate,
                eu.siacs.conversations.ui.widget.SurfaceViewRenderer.OnAspectRatioChanged {

    public static final String EXTRA_WITH = "with";
    public static final String EXTRA_SESSION_ID = "session_id";
    public static final String EXTRA_PROPOSED_SESSION_ID = "proposed_session_id";
    public static final String EXTRA_LAST_REPORTED_STATE = "last_reported_state";
    public static final String EXTRA_LAST_ACTION = "last_action";
    public static final String ACTION_ACCEPT_CALL = "action_accept_call";
    public static final String ACTION_MAKE_VOICE_CALL = "action_make_voice_call";
    public static final String ACTION_MAKE_VIDEO_CALL = "action_make_video_call";

    private static final int CALL_DURATION_UPDATE_INTERVAL = 250;
    private static final int BUTTON_VISIBILITY_TIMEOUT = 10_000;

    private static final List<RtpEndUserState> END_CARD =
            Arrays.asList(
                    RtpEndUserState.APPLICATION_ERROR,
                    RtpEndUserState.SECURITY_ERROR,
                    RtpEndUserState.DECLINED_OR_BUSY,
                    RtpEndUserState.CONTACT_OFFLINE,
                    RtpEndUserState.CONNECTIVITY_ERROR,
                    RtpEndUserState.CONNECTIVITY_LOST_ERROR,
                    RtpEndUserState.RETRACTED);
    private static final List<RtpEndUserState> STATES_SHOWING_HELP_BUTTON =
            Arrays.asList(
                    RtpEndUserState.APPLICATION_ERROR,
                    RtpEndUserState.CONNECTIVITY_ERROR,
                    RtpEndUserState.SECURITY_ERROR);
    private static final List<RtpEndUserState> STATES_SHOWING_SWITCH_TO_CHAT =
            Arrays.asList(
                    RtpEndUserState.CONNECTING,
                    RtpEndUserState.CONNECTED,
                    RtpEndUserState.RECONNECTING,
                    RtpEndUserState.INCOMING_CONTENT_ADD);
    private static final List<RtpEndUserState> STATES_CONSIDERED_CONNECTED =
            Arrays.asList(RtpEndUserState.CONNECTED, RtpEndUserState.RECONNECTING);
    private static final List<RtpEndUserState> STATES_SHOWING_PIP_PLACEHOLDER =
            Arrays.asList(
                    RtpEndUserState.ACCEPTING_CALL,
                    RtpEndUserState.CONNECTING,
                    RtpEndUserState.RECONNECTING);
    private static final List<RtpEndUserState> STATES_SHOWING_SPEAKER_CONFIGURATION =
            new ImmutableList.Builder<RtpEndUserState>()
                    .add(RtpEndUserState.FINDING_DEVICE)
                    .add(RtpEndUserState.RINGING)
                    .add(RtpEndUserState.ACCEPTING_CALL)
                    .add(RtpEndUserState.CONNECTING)
                    .addAll(STATES_CONSIDERED_CONNECTED)
                    .build();
    private static final String PROXIMITY_WAKE_LOCK_TAG = "conversations:in-rtp-session";
    private static final int REQUEST_ACCEPT_CALL = 0x1111;
    private static final int REQUEST_ACCEPT_CONTENT = 0x1112;
    private static final int REQUEST_ADD_CONTENT = 0x1113;
    private WeakReference<JingleRtpConnection> rtpConnectionReference;

    private ActivityRtpSessionBinding binding;
    private PowerManager.WakeLock mProximityWakeLock;

    private final Handler mHandler = new Handler();
    private final Runnable mTickExecutor =
            new Runnable() {
                @Override
                public void run() {
                    updateCallDuration();
                    mHandler.postDelayed(mTickExecutor, CALL_DURATION_UPDATE_INTERVAL);
                }
            };
    private boolean buttonsHiddenAfterTimeout = false;
    private final Runnable mVisibilityToggleExecutor = this::updateButtonInVideoCallVisibility;

    public static Set<Media> actionToMedia(final String action) {
        if (ACTION_MAKE_VIDEO_CALL.equals(action)) {
            return ImmutableSet.of(Media.AUDIO, Media.VIDEO);
        } else if (ACTION_MAKE_VOICE_CALL.equals(action)) {
            return ImmutableSet.of(Media.AUDIO);
        } else {
            Log.w(
                    Config.LOGTAG,
                    "actionToMedia can not get media set from unknown action " + action);
            return Collections.emptySet();
        }
    }

    private static void addSink(
            final VideoTrack videoTrack, final SurfaceViewRenderer surfaceViewRenderer) {
        try {
            videoTrack.addSink(surfaceViewRenderer);
        } catch (final IllegalStateException e) {
            Log.e(
                    Config.LOGTAG,
                    "possible race condition on trying to display video track. ignoring",
                    e);
        }
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow()
                .addFlags(
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        this.binding = DataBindingUtil.setContentView(this, R.layout.activity_rtp_session);
        this.binding.remoteVideo.setOnClickListener(this::onVideoScreenClick);
        this.binding.localVideo.setOnClickListener(this::onVideoScreenClick);
        setSupportActionBar(binding.toolbar);
        Activities.setStatusAndNavigationBarColors(this, binding.getRoot());
    }

    private void onVideoScreenClick(final View view) {
        resetVisibilityExecutorShowButtons();
    }

    @Override
    public boolean onCreateOptionsMenu(final Menu menu) {
        getMenuInflater().inflate(R.menu.activity_rtp_session, menu);
        final MenuItem help = menu.findItem(R.id.action_help);
        final MenuItem gotoChat = menu.findItem(R.id.action_goto_chat);
        final MenuItem switchToVideo = menu.findItem(R.id.action_switch_to_video);
        help.setVisible(Config.HELP != null && isHelpButtonVisible());
        gotoChat.setVisible(isSwitchToConversationVisible());
        switchToVideo.setVisible(isSwitchToVideoVisible());
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onKeyDown(final int keyCode, final KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (xmppConnectionService != null) {
                if (xmppConnectionService.getNotificationService().stopSoundAndVibration()) {
                    return true;
                }
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    private boolean isHelpButtonVisible() {
        try {
            return STATES_SHOWING_HELP_BUTTON.contains(requireRtpConnection().getEndUserState());
        } catch (IllegalStateException e) {
            final Intent intent = getIntent();
            final String state =
                    intent != null ? intent.getStringExtra(EXTRA_LAST_REPORTED_STATE) : null;
            if (state != null) {
                return STATES_SHOWING_HELP_BUTTON.contains(RtpEndUserState.valueOf(state));
            } else {
                return false;
            }
        }
    }

    private boolean isSwitchToConversationVisible() {
        final JingleRtpConnection connection =
                this.rtpConnectionReference != null ? this.rtpConnectionReference.get() : null;
        return connection != null
                && STATES_SHOWING_SWITCH_TO_CHAT.contains(connection.getEndUserState());
    }

    private boolean isSwitchToVideoVisible() {
        final JingleRtpConnection connection =
                this.rtpConnectionReference != null ? this.rtpConnectionReference.get() : null;
        if (connection == null) {
            return false;
        }
        return connection.isSwitchToVideoAvailable();
    }

    private void switchToConversation() {
        final Contact contact = getWith();
        final Conversation conversation =
                xmppConnectionService.findOrCreateConversation(
                        contact.getAccount(), contact.getJid(), false, true);
        switchToConversation(conversation);
    }

    public boolean onOptionsItemSelected(final MenuItem item) {
        final var itemItem = item.getItemId();
        if (itemItem == R.id.action_help) {
            launchHelpInBrowser();
            return true;
        } else if (itemItem == R.id.action_goto_chat) {
            switchToConversation();
            return true;
        } else if (itemItem == R.id.action_switch_to_video) {
            requestPermissionAndSwitchToVideo();
            return true;
        } else {
            return super.onOptionsItemSelected(item);
        }
    }

    private void launchHelpInBrowser() {
        final Intent intent = new Intent(Intent.ACTION_VIEW, Config.HELP);
        try {
            startActivity(intent);
        } catch (final ActivityNotFoundException e) {
            Toast.makeText(this, R.string.no_application_found_to_open_link, Toast.LENGTH_LONG)
                    .show();
        }
    }

    private void endCall(View view) {
        endCall();
    }

    private void endCall() {
        if (this.rtpConnectionReference == null) {
            retractSessionProposal();
            finish();
        } else {
            requireRtpConnection().endCall();
        }
    }

    private void retractSessionProposal() {
        final Intent intent = getIntent();
        final String action = intent.getAction();
        final String lastAction = intent.getStringExtra(EXTRA_LAST_ACTION);
        final Account account = extractAccount(intent);
        final Jid with = Jid.ofEscaped(intent.getStringExtra(EXTRA_WITH));
        final String state = intent.getStringExtra(EXTRA_LAST_REPORTED_STATE);
        if (!Intent.ACTION_VIEW.equals(action)
                || state == null
                || !END_CARD.contains(RtpEndUserState.valueOf(state))) {
            final Set<Media> media = actionToMedia(lastAction == null ? action : lastAction);
            resetIntent(account, with, RtpEndUserState.RETRACTED, media);
        }
        xmppConnectionService
                .getJingleConnectionManager()
                .retractSessionProposal(account, with.asBareJid());
    }

    private void rejectCall(View view) {
        requireRtpConnection().rejectCall();
        finish();
    }

    private void acceptCall(View view) {
        requestPermissionsAndAcceptCall();
    }

    private void acceptContentAdd() {
        try {
            final ContentAddition pendingContentAddition =
                    requireRtpConnection().getPendingContentAddition();
            if (pendingContentAddition == null) {
                Log.d(Config.LOGTAG, "content offer was gone after granting permission");
                return;
            }
            requireRtpConnection().acceptContentAdd(pendingContentAddition.summary);
        } catch (final IllegalStateException e) {
            Toast.makeText(this, Strings.nullToEmpty(e.getMessage()), Toast.LENGTH_SHORT).show();
        }
    }

    private void requestPermissionAndSwitchToVideo() {
        final List<String> permissions = permissions(ImmutableSet.of(Media.VIDEO, Media.AUDIO));
        if (PermissionUtils.hasPermission(this, permissions, REQUEST_ADD_CONTENT)) {
            switchToVideo();
        }
    }

    private void switchToVideo() {
        try {
            requireRtpConnection().addMedia(Media.VIDEO);
        } catch (final IllegalStateException e) {
            Toast.makeText(this, e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void acceptContentAdd(final ContentAddition contentAddition) {
        if (contentAddition == null
                || contentAddition.direction != ContentAddition.Direction.INCOMING) {
            Log.d(Config.LOGTAG, "ignore press on content-accept button");
            return;
        }
        requestPermissionAndAcceptContentAdd(contentAddition);
    }

    private void requestPermissionAndAcceptContentAdd(final ContentAddition contentAddition) {
        final List<String> permissions = permissions(contentAddition.media());
        if (PermissionUtils.hasPermission(this, permissions, REQUEST_ACCEPT_CONTENT)) {
            try {
                requireRtpConnection().acceptContentAdd(contentAddition.summary);
            } catch (final IllegalStateException e) {
                Toast.makeText(this, e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void rejectContentAdd(final View view) {
        requireRtpConnection().rejectContentAdd();
    }

    private void requestPermissionsAndAcceptCall() {
        final List<String> permissions = permissions(getMedia());
        if (PermissionUtils.hasPermission(this, permissions, REQUEST_ACCEPT_CALL)) {
            putScreenInCallMode();
            acceptCall();
        }
    }

    private List<String> permissions(final Set<Media> media) {
        final ImmutableList.Builder<String> permissions = ImmutableList.builder();
        if (media.contains(Media.VIDEO)) {
            permissions.add(Manifest.permission.CAMERA).add(Manifest.permission.RECORD_AUDIO);
        } else {
            permissions.add(Manifest.permission.RECORD_AUDIO);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT);
        }
        return permissions.build();
    }

    private void acceptCall() {
        try {
            requireRtpConnection().acceptCall();
        } catch (final IllegalStateException e) {
            Toast.makeText(this, e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void putScreenInCallMode() {
        putScreenInCallMode(requireRtpConnection().getMedia());
    }

    private void putScreenInCallMode(final Set<Media> media) {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Media.audioOnly(media)) {
            final JingleRtpConnection rtpConnection =
                    rtpConnectionReference != null ? rtpConnectionReference.get() : null;
            final CallIntegration callIntegration =
                    rtpConnection == null ? null : rtpConnection.getCallIntegration();
            if (callIntegration == null
                    || callIntegration.getSelectedAudioDevice()
                            == CallIntegration.AudioDevice.EARPIECE) {
                acquireProximityWakeLock();
            }
        }
        lockOrientation(media);
    }

    private void lockOrientation(final Set<Media> media) {
        if (Media.audioOnly(media)) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        } else {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        }
    }

    @SuppressLint("WakelockTimeout")
    private void acquireProximityWakeLock() {
        final PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (powerManager == null) {
            Log.e(Config.LOGTAG, "power manager not available");
            return;
        }
        if (isFinishing()) {
            Log.e(Config.LOGTAG, "do not acquire wakelock. activity is finishing");
            return;
        }
        if (this.mProximityWakeLock == null) {
            this.mProximityWakeLock =
                    powerManager.newWakeLock(
                            PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, PROXIMITY_WAKE_LOCK_TAG);
        }
        if (!this.mProximityWakeLock.isHeld()) {
            Log.d(Config.LOGTAG, "acquiring proximity wake lock");
            this.mProximityWakeLock.acquire();
        }
    }

    private void releaseProximityWakeLock() {
        if (this.mProximityWakeLock != null && mProximityWakeLock.isHeld()) {
            Log.d(Config.LOGTAG, "releasing proximity wake lock");
            this.mProximityWakeLock.release(PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY);
            this.mProximityWakeLock = null;
        }
    }

    private void putProximityWakeLockInProperState(final CallIntegration.AudioDevice audioDevice) {
        if (audioDevice == CallIntegration.AudioDevice.EARPIECE) {
            acquireProximityWakeLock();
        } else {
            releaseProximityWakeLock();
        }
    }

    @Override
    protected void refreshUiReal() {}

    @Override
    public void onNewIntent(final Intent intent) {
        Log.d(Config.LOGTAG, this.getClass().getName() + ".onNewIntent()");
        super.onNewIntent(intent);
        if (intent == null) {
            return;
        }
        setIntent(intent);
        if (xmppConnectionService == null) {
            Log.d(
                    Config.LOGTAG,
                    "RtpSessionActivity: background service wasn't bound in onNewIntent()");
            return;
        }
        initializeWithIntent(Event.ON_NEW_INTENT, intent);
    }

    @Override
    protected void onBackendConnected() {
        final var intent = getIntent();
        if (intent == null) {
            return;
        }
        initializeWithIntent(Event.ON_BACKEND_CONNECTED, intent);
    }

    private void initializeWithIntent(final Event event, @NonNull final Intent intent) {
        final String action = intent.getAction();
        Log.d(Config.LOGTAG, "initializeWithIntent(" + event + "," + action + ")");
        final Account account = extractAccount(intent);
        final var extraWith = intent.getStringExtra(EXTRA_WITH);
        final Jid with = Strings.isNullOrEmpty(extraWith) ? null : Jid.ofEscaped(extraWith);
        if (with == null || account == null) {
            Log.e(Config.LOGTAG, "intent is missing extras (account or with)");
            return;
        }
        final String sessionId = intent.getStringExtra(EXTRA_SESSION_ID);
        if (sessionId != null) {
            if (initializeActivityWithRunningRtpSession(account, with, sessionId)) {
                return;
            }
            if (ACTION_ACCEPT_CALL.equals(intent.getAction())) {
                Log.d(Config.LOGTAG, "intent action was accept");
                requestPermissionsAndAcceptCall();
                resetIntent(intent.getExtras());
            }
        } else if (Intent.ACTION_VIEW.equals(action)) {
            final String proposedSessionId = intent.getStringExtra(EXTRA_PROPOSED_SESSION_ID);
            final JingleConnectionManager.TerminatedRtpSession terminatedRtpSession =
                    xmppConnectionService
                            .getJingleConnectionManager()
                            .getTerminalSessionState(with, proposedSessionId);
            if (terminatedRtpSession != null) {
                // termination (due to message error or 'busy' was faster than opening the activity
                initializeWithTerminatedSessionState(account, with, terminatedRtpSession);
                return;
            }
            final String extraLastState = intent.getStringExtra(EXTRA_LAST_REPORTED_STATE);
            final RtpEndUserState state =
                    extraLastState == null ? null : RtpEndUserState.valueOf(extraLastState);
            if (state != null) {
                Log.d(Config.LOGTAG, "restored last state from intent extra");
                updateButtonConfiguration(state);
                updateVerifiedShield(false);
                updateStateDisplay(state);
                updateIncomingCallScreen(state);
                invalidateOptionsMenu();
            }
            setWith(account.getRoster().getContact(with), state);
            if (xmppConnectionService
                    .getJingleConnectionManager()
                    .fireJingleRtpConnectionStateUpdates()) {
                return;
            }
            if (END_CARD.contains(state)) {
                return;
            }
            final String lastAction = intent.getStringExtra(EXTRA_LAST_ACTION);
            final Set<Media> media = actionToMedia(lastAction);
            if (xmppConnectionService
                    .getJingleConnectionManager()
                    .hasMatchingProposal(account, with)) {
                putScreenInCallMode(media);
                return;
            }
            Log.d(Config.LOGTAG, "restored state (" + state + ") was not an end card. finishing");
            finish();
        }
    }

    private void setWidth(final RtpEndUserState state) {
        setWith(getWith(), state);
    }

    private void setWith(final Contact contact, final RtpEndUserState state) {
        binding.with.setText(contact.getDisplayName());
        if (Arrays.asList(RtpEndUserState.INCOMING_CALL, RtpEndUserState.ACCEPTING_CALL)
                .contains(state)) {
            binding.withJid.setText(contact.getJid().asBareJid().toEscapedString());
            binding.withJid.setVisibility(View.VISIBLE);
        } else {
            binding.withJid.setVisibility(View.GONE);
        }
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        final PermissionUtils.PermissionResult permissionResult =
                PermissionUtils.removeBluetoothConnect(permissions, grantResults);
        if (PermissionUtils.allGranted(permissionResult.grantResults)) {
            if (requestCode == REQUEST_ACCEPT_CALL) {
                acceptCall();
            } else if (requestCode == REQUEST_ACCEPT_CONTENT) {
                acceptContentAdd();
            } else if (requestCode == REQUEST_ADD_CONTENT) {
                switchToVideo();
            }
        } else {
            @StringRes int res;
            final String firstDenied =
                    getFirstDenied(permissionResult.grantResults, permissionResult.permissions);
            if (firstDenied == null) {
                return;
            }
            if (Manifest.permission.RECORD_AUDIO.equals(firstDenied)) {
                res = R.string.no_microphone_permission;
            } else if (Manifest.permission.CAMERA.equals(firstDenied)) {
                res = R.string.no_camera_permission;
            } else {
                throw new IllegalStateException("Invalid permission result request");
            }
            Toast.makeText(this, getString(res, getString(R.string.app_name)), Toast.LENGTH_SHORT)
                    .show();
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        mHandler.postDelayed(mTickExecutor, CALL_DURATION_UPDATE_INTERVAL);
        mHandler.postDelayed(mVisibilityToggleExecutor, BUTTON_VISIBILITY_TIMEOUT);
        this.binding.remoteVideo.setOnAspectRatioChanged(this);
    }

    @Override
    public void onResume() {
        super.onResume();
        resetVisibilityExecutorShowButtons();
    }

    @Override
    public void onStop() {
        mHandler.removeCallbacks(mTickExecutor);
        mHandler.removeCallbacks(mVisibilityToggleExecutor);
        binding.remoteVideo.release();
        binding.remoteVideo.setOnAspectRatioChanged(null);
        binding.localVideo.release();
        final WeakReference<JingleRtpConnection> weakReference = this.rtpConnectionReference;
        final JingleRtpConnection jingleRtpConnection =
                weakReference == null ? null : weakReference.get();
        if (jingleRtpConnection != null) {
            releaseVideoTracks(jingleRtpConnection);
        }
        releaseProximityWakeLock();
        super.onStop();
    }

    private void releaseVideoTracks(final JingleRtpConnection jingleRtpConnection) {
        final Optional<VideoTrack> remoteVideo = jingleRtpConnection.getRemoteVideoTrack();
        if (remoteVideo.isPresent()) {
            remoteVideo.get().removeSink(binding.remoteVideo);
        }
        final Optional<VideoTrack> localVideo = jingleRtpConnection.getLocalVideoTrack();
        if (localVideo.isPresent()) {
            localVideo.get().removeSink(binding.localVideo);
        }
    }

    @Override
    public void onBackPressed() {
        if (isConnected()) {
            if (switchToPictureInPicture()) {
                return;
            }
        } else {
            endCall();
        }
        super.onBackPressed();
    }

    @Override
    public void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (switchToPictureInPicture()) {
            return;
        }
        // TODO apparently this method is not getting called on Android 10 when using the task
        // switcher
        if (emptyReference(rtpConnectionReference) && xmppConnectionService != null) {
            retractSessionProposal();
        }
    }

    private boolean isConnected() {
        final JingleRtpConnection connection =
                this.rtpConnectionReference != null ? this.rtpConnectionReference.get() : null;
        final RtpEndUserState endUserState =
                connection == null ? null : connection.getEndUserState();
        return STATES_CONSIDERED_CONNECTED.contains(endUserState)
                || endUserState == RtpEndUserState.INCOMING_CONTENT_ADD;
    }

    private boolean switchToPictureInPicture() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && deviceSupportsPictureInPicture()) {
            if (shouldBePictureInPicture()) {
                startPictureInPicture();
                return true;
            }
        }
        return false;
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private void startPictureInPicture() {
        try {
            final Rational rational = this.binding.remoteVideo.getAspectRatio();
            final Rational clippedRational = Rationals.clip(rational);
            Log.d(
                    Config.LOGTAG,
                    "suggested rational " + rational + ". clipped to " + clippedRational);
            enterPictureInPictureMode(
                    new PictureInPictureParams.Builder().setAspectRatio(clippedRational).build());
        } catch (final IllegalStateException e) {
            // this sometimes happens on Samsung phones (possibly when Knox is enabled)
            Log.w(Config.LOGTAG, "unable to enter picture in picture mode", e);
        }
    }

    @Override
    public void onAspectRatioChanged(final Rational rational) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isPictureInPicture()) {
            final Rational clippedRational = Rationals.clip(rational);
            Log.d(
                    Config.LOGTAG,
                    "suggested rational after aspect ratio change "
                            + rational
                            + ". clipped to "
                            + clippedRational);
            setPictureInPictureParams(
                    new PictureInPictureParams.Builder().setAspectRatio(clippedRational).build());
        }
    }

    private boolean deviceSupportsPictureInPicture() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            return getPackageManager().hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE);
        } else {
            return false;
        }
    }

    private boolean shouldBePictureInPicture() {
        try {
            final JingleRtpConnection rtpConnection = requireRtpConnection();
            return rtpConnection.getMedia().contains(Media.VIDEO)
                    && Arrays.asList(
                                    RtpEndUserState.ACCEPTING_CALL,
                                    RtpEndUserState.CONNECTING,
                                    RtpEndUserState.CONNECTED)
                            .contains(rtpConnection.getEndUserState());
        } catch (final IllegalStateException e) {
            return false;
        }
    }

    private boolean isInConnectedVideoCall() {
        final JingleRtpConnection rtpConnection;
        try {
            rtpConnection = requireRtpConnection();
        } catch (final IllegalStateException e) {
            return false;
        }
        return rtpConnection.getMedia().contains(Media.VIDEO)
                && rtpConnection.getEndUserState() == RtpEndUserState.CONNECTED;
    }

    private boolean initializeActivityWithRunningRtpSession(
            final Account account, Jid with, String sessionId) {
        final WeakReference<JingleRtpConnection> reference =
                xmppConnectionService
                        .getJingleConnectionManager()
                        .findJingleRtpConnection(account, with, sessionId);
        if (reference == null || reference.get() == null) {
            final JingleConnectionManager.TerminatedRtpSession terminatedRtpSession =
                    xmppConnectionService
                            .getJingleConnectionManager()
                            .getTerminalSessionState(with, sessionId);
            if (terminatedRtpSession == null) {
                throw new IllegalStateException(
                        "failed to initialize activity with running rtp session. session not found");
            }
            initializeWithTerminatedSessionState(account, with, terminatedRtpSession);
            return true;
        }
        this.rtpConnectionReference = reference;
        final RtpEndUserState currentState = requireRtpConnection().getEndUserState();
        final boolean verified = requireRtpConnection().isVerified();
        if (currentState == RtpEndUserState.ENDED) {
            finish();
            return true;
        }
        final Set<Media> media = getMedia();
        final ContentAddition contentAddition = getPendingContentAddition();
        if (currentState == RtpEndUserState.INCOMING_CALL) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        if (JingleRtpConnection.STATES_SHOWING_ONGOING_CALL.contains(
                requireRtpConnection().getState())) {
            putScreenInCallMode();
        }
        setWidth(currentState);
        updateVideoViews(currentState);
        updateStateDisplay(currentState, media, contentAddition);
        updateVerifiedShield(verified && STATES_SHOWING_SWITCH_TO_CHAT.contains(currentState));
        updateButtonConfiguration(currentState, media, contentAddition);
        updateIncomingCallScreen(currentState);
        invalidateOptionsMenu();
        return false;
    }

    private void initializeWithTerminatedSessionState(
            final Account account,
            final Jid with,
            final JingleConnectionManager.TerminatedRtpSession terminatedRtpSession) {
        Log.d(Config.LOGTAG, "initializeWithTerminatedSessionState()");
        if (terminatedRtpSession.state == RtpEndUserState.ENDED) {
            finish();
            return;
        }
        final RtpEndUserState state = terminatedRtpSession.state;
        resetIntent(account, with, terminatedRtpSession.state, terminatedRtpSession.media);
        updateButtonConfiguration(state);
        updateStateDisplay(state);
        updateIncomingCallScreen(state);
        updateCallDuration();
        updateVerifiedShield(false);
        invalidateOptionsMenu();
        setWith(account.getRoster().getContact(with), state);
    }

    private void reInitializeActivityWithRunningRtpSession(
            final Account account, Jid with, String sessionId) {
        runOnUiThread(() -> initializeActivityWithRunningRtpSession(account, with, sessionId));
        resetIntent(account, with, sessionId);
    }

    private void resetIntent(final Account account, final Jid with, final String sessionId) {
        final Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.putExtra(EXTRA_ACCOUNT, account.getJid().toEscapedString());
        intent.putExtra(EXTRA_WITH, with.toEscapedString());
        intent.putExtra(EXTRA_SESSION_ID, sessionId);
        setIntent(intent);
    }

    private void ensureSurfaceViewRendererIsSetup(final SurfaceViewRenderer surfaceViewRenderer) {
        surfaceViewRenderer.setVisibility(View.VISIBLE);
        try {
            surfaceViewRenderer.init(requireRtpConnection().getEglBaseContext(), null);
        } catch (final IllegalStateException ignored) {
            // SurfaceViewRenderer was already initialized
        } catch (final RuntimeException e) {
            if (Throwables.getRootCause(e) instanceof GLException glException) {
                Log.w(Config.LOGTAG, "could not set up hardware renderer", glException);
            }
        }
        surfaceViewRenderer.setEnableHardwareScaler(true);
    }

    private void updateStateDisplay(final RtpEndUserState state) {
        updateStateDisplay(state, Collections.emptySet(), null);
    }

    private void updateStateDisplay(
            final RtpEndUserState state,
            final Set<Media> media,
            final ContentAddition contentAddition) {
        switch (state) {
            case INCOMING_CALL -> {
                Preconditions.checkArgument(!media.isEmpty(), "Media must not be empty");
                if (media.contains(Media.VIDEO)) {
                    setTitle(R.string.rtp_state_incoming_video_call);
                } else {
                    setTitle(R.string.rtp_state_incoming_call);
                }
            }
            case INCOMING_CONTENT_ADD -> {
                if (contentAddition != null && contentAddition.media().contains(Media.VIDEO)) {
                    setTitle(R.string.rtp_state_content_add_video);
                } else {
                    setTitle(R.string.rtp_state_content_add);
                }
            }
            case CONNECTING -> setTitle(R.string.rtp_state_connecting);
            case CONNECTED -> setTitle(R.string.rtp_state_connected);
            case RECONNECTING -> setTitle(R.string.rtp_state_reconnecting);
            case ACCEPTING_CALL -> setTitle(R.string.rtp_state_accepting_call);
            case ENDING_CALL -> setTitle(R.string.rtp_state_ending_call);
            case FINDING_DEVICE -> setTitle(R.string.rtp_state_finding_device);
            case RINGING -> setTitle(R.string.rtp_state_ringing);
            case DECLINED_OR_BUSY -> setTitle(R.string.rtp_state_declined_or_busy);
            case CONTACT_OFFLINE -> setTitle(R.string.rtp_state_contact_offline);
            case CONNECTIVITY_ERROR -> setTitle(R.string.rtp_state_connectivity_error);
            case CONNECTIVITY_LOST_ERROR -> setTitle(R.string.rtp_state_connectivity_lost_error);
            case RETRACTED -> setTitle(R.string.rtp_state_retracted);
            case APPLICATION_ERROR -> setTitle(R.string.rtp_state_application_failure);
            case SECURITY_ERROR -> setTitle(R.string.rtp_state_security_error);
            case ENDED -> throw new IllegalStateException(
                    "Activity should have called finishAndReleaseWakeLock();");
            default -> throw new IllegalStateException(
                    String.format("State %s has not been handled in UI", state));
        }
    }

    private void updateVerifiedShield(final boolean verified) {
        if (isPictureInPicture()) {
            this.binding.verified.setVisibility(View.GONE);
            return;
        }
        this.binding.verified.setVisibility(verified ? View.VISIBLE : View.GONE);
    }

    private void updateIncomingCallScreen(final RtpEndUserState state) {
        updateIncomingCallScreen(state, null);
    }

    private void updateIncomingCallScreen(final RtpEndUserState state, final Contact contact) {
        if (state == RtpEndUserState.INCOMING_CALL || state == RtpEndUserState.ACCEPTING_CALL) {
            final boolean show = getResources().getBoolean(R.bool.show_avatar_incoming_call);
            if (show) {
                binding.contactPhoto.setVisibility(View.VISIBLE);
                if (contact == null) {
                    AvatarWorkerTask.loadAvatar(
                            getWith(), binding.contactPhoto, R.dimen.publish_avatar_size);
                } else {
                    AvatarWorkerTask.loadAvatar(
                            contact, binding.contactPhoto, R.dimen.publish_avatar_size);
                }
            } else {
                binding.contactPhoto.setVisibility(View.GONE);
            }
            final Account account = contact == null ? getWith().getAccount() : contact.getAccount();
            binding.usingAccount.setVisibility(View.VISIBLE);
            binding.usingAccount.setText(
                    getString(
                            R.string.using_account,
                            account.getJid().asBareJid().toEscapedString()));
        } else {
            binding.usingAccount.setVisibility(View.GONE);
            binding.contactPhoto.setVisibility(View.GONE);
        }
    }

    private Set<Media> getMedia() {
        return requireRtpConnection().getMedia();
    }

    public ContentAddition getPendingContentAddition() {
        return requireRtpConnection().getPendingContentAddition();
    }

    private void updateButtonConfiguration(final RtpEndUserState state) {
        updateButtonConfiguration(state, Collections.emptySet(), null);
    }

    @SuppressLint("RestrictedApi")
    private void updateButtonConfiguration(
            final RtpEndUserState state,
            final Set<Media> media,
            final ContentAddition contentAddition) {
        if (state == RtpEndUserState.ENDING_CALL
                || isPictureInPicture()
                || this.buttonsHiddenAfterTimeout) {
            this.binding.rejectCall.setVisibility(View.INVISIBLE);
            this.binding.endCall.setVisibility(View.INVISIBLE);
            this.binding.acceptCall.setVisibility(View.INVISIBLE);
        } else if (state == RtpEndUserState.INCOMING_CALL) {
            this.binding.rejectCall.setContentDescription(getString(R.string.dismiss_call));
            this.binding.rejectCall.setOnClickListener(this::rejectCall);
            this.binding.rejectCall.setImageResource(R.drawable.ic_call_end_24dp);
            this.binding.rejectCall.setVisibility(View.VISIBLE);
            this.binding.endCall.setVisibility(View.INVISIBLE);
            this.binding.acceptCall.setContentDescription(getString(R.string.answer_call));
            this.binding.acceptCall.setOnClickListener(this::acceptCall);
            this.binding.acceptCall.setImageResource(R.drawable.ic_call_24dp);
            this.binding.acceptCall.setVisibility(View.VISIBLE);
        } else if (state == RtpEndUserState.INCOMING_CONTENT_ADD) {
            this.binding.rejectCall.setContentDescription(
                    getString(R.string.reject_switch_to_video));
            this.binding.rejectCall.setOnClickListener(this::rejectContentAdd);
            this.binding.rejectCall.setImageResource(R.drawable.ic_clear_24dp);
            this.binding.rejectCall.setVisibility(View.VISIBLE);
            this.binding.endCall.setVisibility(View.INVISIBLE);
            this.binding.acceptCall.setContentDescription(getString(R.string.accept));
            this.binding.acceptCall.setOnClickListener((v -> acceptContentAdd(contentAddition)));
            this.binding.acceptCall.setImageResource(R.drawable.ic_check_24dp);
            this.binding.acceptCall.setVisibility(View.VISIBLE);
        } else if (asList(RtpEndUserState.DECLINED_OR_BUSY, RtpEndUserState.CONTACT_OFFLINE)
                .contains(state)) {
            this.binding.rejectCall.setContentDescription(getString(R.string.exit));
            this.binding.rejectCall.setOnClickListener(this::exit);
            this.binding.rejectCall.setImageResource(R.drawable.ic_clear_24dp);
            this.binding.rejectCall.setVisibility(View.VISIBLE);
            this.binding.endCall.setVisibility(View.INVISIBLE);
            this.binding.acceptCall.setContentDescription(getString(R.string.record_voice_mail));
            this.binding.acceptCall.setOnClickListener(this::recordVoiceMail);
            this.binding.acceptCall.setImageResource(R.drawable.ic_voicemail_24dp);
            this.binding.acceptCall.setVisibility(View.VISIBLE);
        } else if (asList(
                        RtpEndUserState.CONNECTIVITY_ERROR,
                        RtpEndUserState.CONNECTIVITY_LOST_ERROR,
                        RtpEndUserState.APPLICATION_ERROR,
                        RtpEndUserState.RETRACTED,
                        RtpEndUserState.SECURITY_ERROR)
                .contains(state)) {
            this.binding.rejectCall.setContentDescription(getString(R.string.exit));
            this.binding.rejectCall.setOnClickListener(this::exit);
            this.binding.rejectCall.setImageResource(R.drawable.ic_clear_24dp);
            this.binding.rejectCall.setVisibility(View.VISIBLE);
            this.binding.endCall.setVisibility(View.INVISIBLE);
            this.binding.acceptCall.setContentDescription(getString(R.string.try_again));
            this.binding.acceptCall.setOnClickListener(this::retry);
            this.binding.acceptCall.setImageResource(R.drawable.ic_replay_24dp);
            this.binding.acceptCall.setVisibility(View.VISIBLE);
        } else {
            this.binding.rejectCall.setVisibility(View.INVISIBLE);
            this.binding.endCall.setContentDescription(getString(R.string.hang_up));
            this.binding.endCall.setOnClickListener(this::endCall);
            this.binding.endCall.setImageResource(R.drawable.ic_call_end_24dp);
            setVisibleAndShow(this.binding.endCall);
            this.binding.acceptCall.setVisibility(View.INVISIBLE);
        }
        updateInCallButtonConfiguration(state, media);
    }

    private static void setVisibleAndShow(final FloatingActionButton button) {
        button.show();
        button.setVisibility(View.VISIBLE);
    }

    private boolean isPictureInPicture() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return isInPictureInPictureMode();
        } else {
            return false;
        }
    }

    private void updateInCallButtonConfiguration() {
        updateInCallButtonConfiguration(
                requireRtpConnection().getEndUserState(), requireRtpConnection().getMedia());
    }

    @SuppressLint("RestrictedApi")
    private void updateInCallButtonConfiguration(
            final RtpEndUserState state, final Set<Media> media) {
        final var showButtons = !isPictureInPicture() && !buttonsHiddenAfterTimeout;
        if (STATES_CONSIDERED_CONNECTED.contains(state) && showButtons) {
            Preconditions.checkArgument(!media.isEmpty(), "Media must not be empty");
            if (media.contains(Media.VIDEO)) {
                final JingleRtpConnection rtpConnection = requireRtpConnection();
                updateInCallButtonConfigurationVideo(
                        rtpConnection.isVideoEnabled(), rtpConnection.isCameraSwitchable());
            } else {
                final CallIntegration callIntegration = requireRtpConnection().getCallIntegration();
                updateInCallButtonConfigurationSpeaker(
                        callIntegration.getSelectedAudioDevice(),
                        callIntegration.getAudioDevices().size());
                this.binding.inCallActionFarRight.setVisibility(View.GONE);
            }
            if (media.contains(Media.AUDIO)) {
                updateInCallButtonConfigurationMicrophone(
                        requireRtpConnection().isMicrophoneEnabled());
            } else {
                this.binding.inCallActionLeft.setVisibility(View.GONE);
            }
        } else if (STATES_SHOWING_SPEAKER_CONFIGURATION.contains(state)
                && showButtons
                && Media.audioOnly(media)) {
            final CallIntegration callIntegration;
            try {
                callIntegration = requireCallIntegration();
            } catch (final IllegalStateException e) {
                Log.e(Config.LOGTAG, "can not update InCallButtonConfiguration in state " + state);
                return;
            }
            updateInCallButtonConfigurationSpeaker(
                    callIntegration.getSelectedAudioDevice(),
                    callIntegration.getAudioDevices().size());
            this.binding.inCallActionFarRight.setVisibility(View.GONE);
        } else {
            this.binding.inCallActionLeft.setVisibility(View.GONE);
            this.binding.inCallActionRight.setVisibility(View.GONE);
            this.binding.inCallActionFarRight.setVisibility(View.GONE);
        }
    }

    @SuppressLint("RestrictedApi")
    private void updateInCallButtonConfigurationSpeaker(
            final CallIntegration.AudioDevice selectedAudioDevice, final int numberOfChoices) {
        switch (selectedAudioDevice) {
            case EARPIECE -> {
                this.binding.inCallActionRight.setImageResource(R.drawable.ic_volume_off_24dp);
                if (numberOfChoices >= 2) {
                    this.binding.inCallActionRight.setContentDescription(
                            getString(R.string.call_is_using_earpiece_tap_to_switch_to_speaker));
                    this.binding.inCallActionRight.setOnClickListener(this::switchToSpeaker);
                } else {
                    this.binding.inCallActionRight.setContentDescription(
                            getString(R.string.call_is_using_earpiece));
                    this.binding.inCallActionRight.setOnClickListener(null);
                    this.binding.inCallActionRight.setClickable(false);
                }
            }
            case WIRED_HEADSET -> {
                this.binding.inCallActionRight.setContentDescription(
                        getString(R.string.call_is_using_wired_headset));
                this.binding.inCallActionRight.setImageResource(R.drawable.ic_headset_mic_24dp);
                this.binding.inCallActionRight.setOnClickListener(null);
                this.binding.inCallActionRight.setClickable(false);
            }
            case SPEAKER_PHONE -> {
                this.binding.inCallActionRight.setImageResource(R.drawable.ic_volume_up_24dp);
                if (numberOfChoices >= 2) {
                    this.binding.inCallActionRight.setContentDescription(
                            getString(R.string.call_is_using_speaker_tap_to_switch_to_earpiece));
                    this.binding.inCallActionRight.setOnClickListener(this::switchToEarpiece);
                } else {
                    this.binding.inCallActionRight.setContentDescription(
                            getString(R.string.call_is_using_speaker));
                    this.binding.inCallActionRight.setOnClickListener(null);
                    this.binding.inCallActionRight.setClickable(false);
                }
            }
            case BLUETOOTH -> {
                this.binding.inCallActionRight.setContentDescription(
                        getString(R.string.call_is_using_bluetooth));
                this.binding.inCallActionRight.setImageResource(R.drawable.ic_bluetooth_audio_24dp);
                this.binding.inCallActionRight.setOnClickListener(null);
                this.binding.inCallActionRight.setClickable(false);
            }
        }
        setVisibleAndShow(this.binding.inCallActionRight);
    }

    @SuppressLint("RestrictedApi")
    private void updateInCallButtonConfigurationVideo(
            final boolean videoEnabled, final boolean isCameraSwitchable) {
        setVisibleAndShow(this.binding.inCallActionRight);
        if (isCameraSwitchable) {
            this.binding.inCallActionFarRight.setImageResource(
                    R.drawable.ic_flip_camera_android_24dp);
            setVisibleAndShow(this.binding.inCallActionFarRight);
            this.binding.inCallActionFarRight.setOnClickListener(this::switchCamera);
            this.binding.inCallActionFarRight.setContentDescription(
                    getString(R.string.flip_camera));
        } else {
            this.binding.inCallActionFarRight.setVisibility(View.GONE);
        }
        if (videoEnabled) {
            this.binding.inCallActionRight.setImageResource(R.drawable.ic_videocam_24dp);
            this.binding.inCallActionRight.setOnClickListener(this::disableVideo);
            this.binding.inCallActionRight.setContentDescription(
                    getString(R.string.video_is_enabled_tap_to_disable));
        } else {
            this.binding.inCallActionRight.setImageResource(R.drawable.ic_videocam_off_24dp);
            this.binding.inCallActionRight.setOnClickListener(this::enableVideo);
            this.binding.inCallActionRight.setContentDescription(
                    getString(R.string.video_is_disabled_tap_to_enable));
        }
    }

    private void switchCamera(final View view) {
        resetVisibilityToggleExecutor();
        Futures.addCallback(
                requireRtpConnection().switchCamera(),
                new FutureCallback<>() {
                    @Override
                    public void onSuccess(@Nullable Boolean isFrontCamera) {
                        binding.localVideo.setMirror(Boolean.TRUE.equals(isFrontCamera));
                    }

                    @Override
                    public void onFailure(@NonNull final Throwable throwable) {
                        Log.d(
                                Config.LOGTAG,
                                "could not switch camera",
                                Throwables.getRootCause(throwable));
                        Toast.makeText(
                                        RtpSessionActivity.this,
                                        R.string.could_not_switch_camera,
                                        Toast.LENGTH_LONG)
                                .show();
                    }
                },
                MainThreadExecutor.getInstance());
    }

    private void enableVideo(final View view) {
        resetVisibilityToggleExecutor();
        try {
            requireRtpConnection().setVideoEnabled(true);
        } catch (final IllegalStateException e) {
            Toast.makeText(this, R.string.unable_to_enable_video, Toast.LENGTH_SHORT).show();
            return;
        }
        updateInCallButtonConfigurationVideo(true, requireRtpConnection().isCameraSwitchable());
    }

    private void disableVideo(final View view) {
        resetVisibilityToggleExecutor();
        final JingleRtpConnection rtpConnection = requireRtpConnection();
        final ContentAddition pending = rtpConnection.getPendingContentAddition();
        if (pending != null && pending.direction == ContentAddition.Direction.OUTGOING) {
            rtpConnection.retractContentAdd();
            return;
        }
        try {
            requireRtpConnection().setVideoEnabled(false);
        } catch (final IllegalStateException e) {
            Toast.makeText(this, R.string.could_not_disable_video, Toast.LENGTH_SHORT).show();
            return;
        }
        updateInCallButtonConfigurationVideo(false, requireRtpConnection().isCameraSwitchable());
    }

    @SuppressLint("RestrictedApi")
    private void updateInCallButtonConfigurationMicrophone(final boolean microphoneEnabled) {
        if (microphoneEnabled) {
            this.binding.inCallActionLeft.setImageResource(R.drawable.ic_mic_24dp);
            this.binding.inCallActionLeft.setOnClickListener(this::disableMicrophone);
        } else {
            this.binding.inCallActionLeft.setImageResource(R.drawable.ic_mic_off_24dp);
            this.binding.inCallActionLeft.setOnClickListener(this::enableMicrophone);
        }
        setVisibleAndShow(this.binding.inCallActionLeft);
    }

    private void updateCallDuration() {
        final JingleRtpConnection connection =
                this.rtpConnectionReference != null ? this.rtpConnectionReference.get() : null;
        if (connection == null || connection.getMedia().contains(Media.VIDEO)) {
            this.binding.duration.setVisibility(View.GONE);
            return;
        }
        if (connection.zeroDuration()) {
            this.binding.duration.setVisibility(View.GONE);
        } else {
            this.binding.duration.setText(
                    TimeFrameUtils.formatElapsedTime(connection.getCallDuration(), false));
            this.binding.duration.setVisibility(View.VISIBLE);
        }
    }

    private void resetVisibilityToggleExecutor() {
        mHandler.removeCallbacks(this.mVisibilityToggleExecutor);
        mHandler.postDelayed(this.mVisibilityToggleExecutor, BUTTON_VISIBILITY_TIMEOUT);
    }

    private void updateButtonInVideoCallVisibility() {
        if (isInConnectedVideoCall()) {
            if (isPictureInPicture()) {
                return;
            }
            Log.d(Config.LOGTAG, "hiding in-call buttons after timeout was reached");
            hideInCallButtons();
        }
    }

    private void hideInCallButtons() {
        binding.inCallActionLeft.hide();
        binding.endCall.hide();
        binding.inCallActionRight.hide();
        binding.inCallActionFarRight.hide();
    }

    private void showInCallButtons() {
        this.buttonsHiddenAfterTimeout = false;
        final JingleRtpConnection rtpConnection;
        try {
            rtpConnection = requireRtpConnection();
        } catch (final IllegalStateException e) {
            return;
        }
        updateButtonConfiguration(
                rtpConnection.getEndUserState(),
                rtpConnection.getMedia(),
                rtpConnection.getPendingContentAddition());
    }

    private void resetVisibilityExecutorShowButtons() {
        resetVisibilityToggleExecutor();
        showInCallButtons();
    }

    private void updateVideoViews(final RtpEndUserState state) {
        if (END_CARD.contains(state) || state == RtpEndUserState.ENDING_CALL) {
            binding.localVideo.setVisibility(View.GONE);
            binding.localVideo.release();
            binding.remoteVideoWrapper.setVisibility(View.GONE);
            binding.remoteVideo.release();
            binding.pipLocalMicOffIndicator.setVisibility(View.GONE);
            if (isPictureInPicture()) {
                binding.appBarLayout.setVisibility(View.GONE);
                binding.pipPlaceholder.setVisibility(View.VISIBLE);
                if (Arrays.asList(
                                RtpEndUserState.APPLICATION_ERROR,
                                RtpEndUserState.CONNECTIVITY_ERROR,
                                RtpEndUserState.SECURITY_ERROR)
                        .contains(state)) {
                    binding.pipWarning.setVisibility(View.VISIBLE);
                    binding.pipWaiting.setVisibility(View.GONE);
                } else {
                    binding.pipWarning.setVisibility(View.GONE);
                    binding.pipWaiting.setVisibility(View.GONE);
                }
            } else {
                binding.appBarLayout.setVisibility(View.VISIBLE);
                binding.pipPlaceholder.setVisibility(View.GONE);
            }
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            return;
        }
        if (isPictureInPicture() && STATES_SHOWING_PIP_PLACEHOLDER.contains(state)) {
            binding.localVideo.setVisibility(View.GONE);
            binding.remoteVideoWrapper.setVisibility(View.GONE);
            binding.appBarLayout.setVisibility(View.GONE);
            binding.pipPlaceholder.setVisibility(View.VISIBLE);
            binding.pipWarning.setVisibility(View.GONE);
            binding.pipWaiting.setVisibility(View.VISIBLE);
            binding.pipLocalMicOffIndicator.setVisibility(View.GONE);
            return;
        }
        final Optional<VideoTrack> localVideoTrack = getLocalVideoTrack();
        if (localVideoTrack.isPresent() && !isPictureInPicture()) {
            ensureSurfaceViewRendererIsSetup(binding.localVideo);
            // paint local view over remote view
            binding.localVideo.setZOrderMediaOverlay(true);
            binding.localVideo.setMirror(requireRtpConnection().isFrontCamera());
            addSink(localVideoTrack.get(), binding.localVideo);
        } else {
            binding.localVideo.setVisibility(View.GONE);
        }
        final Optional<VideoTrack> remoteVideoTrack = getRemoteVideoTrack();
        if (remoteVideoTrack.isPresent()) {
            ensureSurfaceViewRendererIsSetup(binding.remoteVideo);
            addSink(remoteVideoTrack.get(), binding.remoteVideo);
            binding.remoteVideo.setScalingType(
                    RendererCommon.ScalingType.SCALE_ASPECT_FILL,
                    RendererCommon.ScalingType.SCALE_ASPECT_FIT);
            if (state == RtpEndUserState.CONNECTED) {
                binding.appBarLayout.setVisibility(View.GONE);
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
                binding.remoteVideoWrapper.setVisibility(View.VISIBLE);
            } else {
                binding.appBarLayout.setVisibility(View.VISIBLE);
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
                binding.remoteVideoWrapper.setVisibility(View.GONE);
            }
            if (isPictureInPicture() && !requireRtpConnection().isMicrophoneEnabled()) {
                binding.pipLocalMicOffIndicator.setVisibility(View.VISIBLE);
            } else {
                binding.pipLocalMicOffIndicator.setVisibility(View.GONE);
            }
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            binding.remoteVideoWrapper.setVisibility(View.GONE);
            binding.pipLocalMicOffIndicator.setVisibility(View.GONE);
        }
    }

    private Optional<VideoTrack> getLocalVideoTrack() {
        final JingleRtpConnection connection =
                this.rtpConnectionReference != null ? this.rtpConnectionReference.get() : null;
        if (connection == null) {
            return Optional.absent();
        }
        return connection.getLocalVideoTrack();
    }

    private Optional<VideoTrack> getRemoteVideoTrack() {
        final JingleRtpConnection connection =
                this.rtpConnectionReference != null ? this.rtpConnectionReference.get() : null;
        if (connection == null) {
            return Optional.absent();
        }
        return connection.getRemoteVideoTrack();
    }

    private void disableMicrophone(final View view) {
        setMicrophoneEnabled(false);
    }

    private void enableMicrophone(final View view) {
        setMicrophoneEnabled(true);
    }

    private void setMicrophoneEnabled(final boolean enabled) {
        resetVisibilityExecutorShowButtons();
        try {
            final JingleRtpConnection rtpConnection = requireRtpConnection();
            if (rtpConnection.setMicrophoneEnabled(enabled)) {
                updateInCallButtonConfiguration();
            }
        } catch (final IllegalStateException e) {
            Toast.makeText(this, R.string.could_not_modify_call, Toast.LENGTH_SHORT).show();
        }
    }

    private void switchToEarpiece(final View view) {
        try {
            requireCallIntegration().setAudioDevice(CallIntegration.AudioDevice.EARPIECE);
            acquireProximityWakeLock();
        } catch (final IllegalStateException e) {
            Toast.makeText(this, R.string.could_not_modify_call, Toast.LENGTH_SHORT).show();
        }
    }

    private void switchToSpeaker(final View view) {
        try {
            requireCallIntegration().setAudioDevice(CallIntegration.AudioDevice.SPEAKER_PHONE);
            releaseProximityWakeLock();
        } catch (final IllegalStateException e) {
            Toast.makeText(this, R.string.could_not_modify_call, Toast.LENGTH_SHORT).show();
        }
    }

    private void retry(final View view) {
        final Intent intent = getIntent();
        final Account account = extractAccount(intent);
        final Jid with = Jid.ofEscaped(intent.getStringExtra(EXTRA_WITH));
        final String lastAction = intent.getStringExtra(EXTRA_LAST_ACTION);
        final String action = intent.getAction();
        final Set<Media> media = actionToMedia(lastAction == null ? action : lastAction);
        this.rtpConnectionReference = null;
        Log.d(Config.LOGTAG, "attempting retry with " + with.toEscapedString());
        CallIntegrationConnectionService.placeCall(xmppConnectionService, account, with, media);
    }

    private void exit(final View view) {
        finish();
    }

    private void recordVoiceMail(final View view) {
        final Intent intent = getIntent();
        final Account account = extractAccount(intent);
        final Jid with = Jid.ofEscaped(intent.getStringExtra(EXTRA_WITH));
        final Conversation conversation =
                xmppConnectionService.findOrCreateConversation(account, with, false, true);
        final Intent launchIntent = new Intent(this, ConversationsActivity.class);
        launchIntent.setAction(ConversationsActivity.ACTION_VIEW_CONVERSATION);
        launchIntent.putExtra(ConversationsActivity.EXTRA_CONVERSATION, conversation.getUuid());
        launchIntent.setFlags(intent.getFlags() | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        launchIntent.putExtra(
                ConversationsActivity.EXTRA_POST_INIT_ACTION,
                ConversationsActivity.POST_ACTION_RECORD_VOICE);
        startActivity(launchIntent);
        finish();
    }

    private Contact getWith() {
        final AbstractJingleConnection.Id id = requireRtpConnection().getId();
        final Account account = id.account;
        return account.getRoster().getContact(id.with);
    }

    private JingleRtpConnection requireRtpConnection() {
        final JingleRtpConnection connection =
                this.rtpConnectionReference != null ? this.rtpConnectionReference.get() : null;
        if (connection == null) {
            throw new IllegalStateException("No RTP connection found");
        }
        return connection;
    }

    private CallIntegration requireCallIntegration() {
        return requireOngoingRtpSession().getCallIntegration();
    }

    private OngoingRtpSession requireOngoingRtpSession() {
        final JingleRtpConnection connection =
                this.rtpConnectionReference != null ? this.rtpConnectionReference.get() : null;
        if (connection != null) {
            return connection;
        }
        final Intent currentIntent = getIntent();
        final String withExtra =
                currentIntent == null ? null : currentIntent.getStringExtra(EXTRA_WITH);
        final var account = extractAccount(currentIntent);
        if (withExtra == null) {
            throw new IllegalStateException("Current intent has no EXTRA_WITH");
        }
        final var matching =
                xmppConnectionService
                        .getJingleConnectionManager()
                        .matchingProposal(account, Jid.of(withExtra));
        if (matching.isPresent()) {
            return matching.get();
        }
        throw new IllegalStateException("No matching session proposal");
    }

    @Override
    public void onJingleRtpConnectionUpdate(
            Account account, Jid with, final String sessionId, RtpEndUserState state) {
        Log.d(Config.LOGTAG, "onJingleRtpConnectionUpdate(" + state + ")");
        if (END_CARD.contains(state)) {
            Log.d(Config.LOGTAG, "end card reached");
            releaseProximityWakeLock();
            runOnUiThread(
                    () -> getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
        }
        if (with.isBareJid()) {
            // TODO check for ENDED
            updateRtpSessionProposalState(account, with, state);
            return;
        }
        if (emptyReference(this.rtpConnectionReference)) {
            if (END_CARD.contains(state)) {
                Log.d(Config.LOGTAG, "not reinitializing session");
                return;
            }
            // this happens when going from proposed session to actual session
            reInitializeActivityWithRunningRtpSession(account, with, sessionId);
            return;
        }
        final AbstractJingleConnection.Id id = requireRtpConnection().getId();
        final boolean verified = requireRtpConnection().isVerified();
        final Set<Media> media = getMedia();
        lockOrientation(media);
        final ContentAddition contentAddition = getPendingContentAddition();
        final Contact contact = getWith();
        if (account == id.account && id.with.equals(with) && id.sessionId.equals(sessionId)) {
            if (state == RtpEndUserState.ENDED) {
                finish();
                return;
            }
            resetVisibilityToggleExecutor();
            runOnUiThread(
                    () -> {
                        updateStateDisplay(state, media, contentAddition);
                        updateVerifiedShield(
                                verified && STATES_SHOWING_SWITCH_TO_CHAT.contains(state));
                        updateButtonConfiguration(state, media, contentAddition);
                        updateVideoViews(state);
                        updateIncomingCallScreen(state, contact);
                        invalidateOptionsMenu();
                    });
            if (END_CARD.contains(state)) {
                final JingleRtpConnection rtpConnection = requireRtpConnection();
                resetIntent(account, with, state, rtpConnection.getMedia());
                releaseVideoTracks(rtpConnection);
                this.rtpConnectionReference = null;
            }
        } else {
            Log.d(Config.LOGTAG, "received update for other rtp session");
        }
    }

    @Override
    public void onAudioDeviceChanged(
            final CallIntegration.AudioDevice selectedAudioDevice,
            final Set<CallIntegration.AudioDevice> availableAudioDevices) {
        Log.d(
                Config.LOGTAG,
                "onAudioDeviceChanged in activity: selected:"
                        + selectedAudioDevice
                        + ", available:"
                        + availableAudioDevices);
        try {
            final OngoingRtpSession ongoingRtpSession = requireOngoingRtpSession();
            final RtpEndUserState endUserState;
            if (ongoingRtpSession instanceof JingleRtpConnection jingleRtpConnection) {
                endUserState = jingleRtpConnection.getEndUserState();
            } else {
                // for session proposals all end user states are functionally the same
                endUserState = RtpEndUserState.RINGING;
            }
            final Set<Media> media = ongoingRtpSession.getMedia();
            if (END_CARD.contains(endUserState)) {
                Log.d(
                        Config.LOGTAG,
                        "onAudioDeviceChanged() nothing to do because end card has been reached");
            } else {
                if (Media.audioOnly(media)
                        && STATES_SHOWING_SPEAKER_CONFIGURATION.contains(endUserState)) {
                    final CallIntegration callIntegration = requireCallIntegration();
                    updateInCallButtonConfigurationSpeaker(
                            callIntegration.getSelectedAudioDevice(),
                            callIntegration.getAudioDevices().size());
                }
                Log.d(
                        Config.LOGTAG,
                        "put proximity wake lock into proper state after device update");
                putProximityWakeLockInProperState(selectedAudioDevice);
            }
        } catch (final IllegalStateException e) {
            Log.d(Config.LOGTAG, "RTP connection was not available when audio device changed");
        }
    }

    private void updateRtpSessionProposalState(
            final Account account, final Jid with, final RtpEndUserState state) {
        final Intent currentIntent = getIntent();
        final String withExtra =
                currentIntent == null ? null : currentIntent.getStringExtra(EXTRA_WITH);
        if (withExtra == null) {
            return;
        }
        final Set<Media> media = actionToMedia(currentIntent.getStringExtra(EXTRA_LAST_ACTION));
        if (Jid.ofEscaped(withExtra).asBareJid().equals(with)) {
            runOnUiThread(
                    () -> {
                        updateVerifiedShield(false);
                        updateStateDisplay(state);
                        updateButtonConfiguration(state, media, null);
                        updateIncomingCallScreen(state);
                        invalidateOptionsMenu();
                    });
            resetIntent(account, with, state, media);
        }
    }

    private void resetIntent(final Bundle extras) {
        final Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.putExtras(extras);
        setIntent(intent);
    }

    private void resetIntent(
            final Account account, Jid with, final RtpEndUserState state, final Set<Media> media) {
        final Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.putExtra(EXTRA_ACCOUNT, account.getJid().toEscapedString());
        if (RtpCapability.jmiSupport(account.getRoster().getContact(with))) {
            intent.putExtra(EXTRA_WITH, with.asBareJid().toEscapedString());
        } else {
            intent.putExtra(EXTRA_WITH, with.toEscapedString());
        }
        intent.putExtra(EXTRA_LAST_REPORTED_STATE, state.toString());
        intent.putExtra(
                EXTRA_LAST_ACTION,
                media.contains(Media.VIDEO) ? ACTION_MAKE_VIDEO_CALL : ACTION_MAKE_VOICE_CALL);
        setIntent(intent);
    }

    private static boolean emptyReference(final WeakReference<?> weakReference) {
        return weakReference == null || weakReference.get() == null;
    }

    private enum Event {
        ON_BACKEND_CONNECTED,
        ON_NEW_INTENT
    }
}
