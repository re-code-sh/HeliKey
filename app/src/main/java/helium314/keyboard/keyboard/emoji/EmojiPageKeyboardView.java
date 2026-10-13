/*
 * Copyright (C) 2013 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package helium314.keyboard.keyboard.emoji;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.os.Handler;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.widget.LinearLayout;
import helium314.keyboard.keyboard.PopupTextView;
import helium314.keyboard.latin.utils.Log;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.accessibility.AccessibilityEvent;

import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import helium314.keyboard.accessibility.AccessibilityUtils;
import helium314.keyboard.accessibility.KeyboardAccessibilityDelegate;
import helium314.keyboard.keyboard.Key;
import helium314.keyboard.keyboard.KeyDetector;
import helium314.keyboard.keyboard.Keyboard;
import helium314.keyboard.keyboard.KeyboardView;
import helium314.keyboard.keyboard.PopupKeysKeyboard;
import helium314.keyboard.keyboard.PopupKeysKeyboardView;
import helium314.keyboard.keyboard.PopupKeysPanel;
import helium314.keyboard.keyboard.internal.PopupKeySpec;
import helium314.keyboard.latin.R;
import helium314.keyboard.latin.common.CoordinateUtils;
import helium314.keyboard.latin.settings.Settings;
import kotlin.Unit;

import java.util.WeakHashMap;

/**
 * This is an extended {@link KeyboardView} class that hosts an emoji page keyboard.
 * Multi-touch unsupported. No gesture support.
 */
public final class EmojiPageKeyboardView extends KeyboardView implements
        PopupKeysPanel.Controller {
    private static final String TAG = "EmojiPageKeyboardView";
    private static final boolean LOG = false;
    private static final long KEY_PRESS_DELAY_TIME = 250;  // msec
    private static final long KEY_RELEASE_DELAY_TIME = 30;  // msec

    private static final EmojiViewCallback EMPTY_EMOJI_VIEW_CALLBACK = new EmojiViewCallback() {
        @Override
        public void onPressKey(final Key key) {}
        @Override
        public void onReleaseKey(final Key key) {}
        @Override
        public String getDescription(String emoji) {
            return null;
        }
        @Override
        public void onRemoveRecentsKey(final Key key) {}
    };

    private EmojiViewCallback mEmojiViewCallback = EMPTY_EMOJI_VIEW_CALLBACK;
    private final KeyDetector mKeyDetector = new KeyDetector();
    private KeyboardAccessibilityDelegate<EmojiPageKeyboardView> mAccessibilityDelegate;

    // Touch inputs
    private int mPointerId = MotionEvent.INVALID_POINTER_ID;
    private int mLastX, mLastY;
    private Key mCurrentKey;
    private Runnable mPendingKeyDown;
    private Runnable mPendingLongPress;
    private Runnable mEmojiRepeatRunnable;
    private boolean mIsRepeatingEmoji = false;
    private int mEmojiRepeatCount = 0;
    private final Handler mHandler;
    private final int mTouchSlop;
    private int mDownX, mDownY;
    private long mDownTime;
    private boolean mMenuOpenedBySwipe = false;

    // More keys keyboard
    private final View mPopupKeysKeyboardContainer;
    private final PopupTextView mDescriptionView;
    private final PopupKeysKeyboardView mPopupKeysKeyboardView;
    private final WeakHashMap<Key, Keyboard> mPopupKeysKeyboardCache = new WeakHashMap<>();
    private final boolean mConfigShowPopupKeysKeyboardAtTouchedPoint;
    private final ViewGroup mPopupKeysPlacerView;
    // More keys panel (used by popup keys keyboard view)
    // TODO: Consider extending to support multiple popup keys panels
    private PopupKeysPanel mPopupKeysPanel;

    public EmojiPageKeyboardView(final Context context, final AttributeSet attrs) {
        this(context, attrs, R.attr.keyboardViewStyle);
    }

    public EmojiPageKeyboardView(final Context context, final AttributeSet attrs,
            final int defStyle) {
        super(context, attrs, defStyle);
        mHandler = new Handler();
        mTouchSlop = ViewConfiguration.get(context).getScaledTouchSlop();

        mPopupKeysPlacerView = new FrameLayout(context, attrs);

        final TypedArray keyboardViewAttr = context.obtainStyledAttributes(attrs,
                R.styleable.MainKeyboardView, defStyle, R.style.MainKeyboardView);
        final int popupKeysKeyboardLayoutId = keyboardViewAttr.getResourceId(
                R.styleable.MainKeyboardView_popupKeysKeyboardLayout, 0);
        mConfigShowPopupKeysKeyboardAtTouchedPoint = keyboardViewAttr.getBoolean(
                R.styleable.MainKeyboardView_showPopupKeysKeyboardAtTouchedPoint, false);
        keyboardViewAttr.recycle();

        final LayoutInflater inflater = LayoutInflater.from(getContext());
        mPopupKeysKeyboardContainer = inflater.inflate(popupKeysKeyboardLayoutId, null);
        mDescriptionView = mPopupKeysKeyboardContainer.findViewById(R.id.description_view);
        mPopupKeysKeyboardView = mPopupKeysKeyboardContainer.findViewById(R.id.popup_keys_keyboard_view);
        setFitsSystemWindows(false);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        final Keyboard keyboard = getKeyboard();
        if (keyboard instanceof DynamicGridKeyboard) {
            final int width = keyboard.mOccupiedWidth + getPaddingLeft() + getPaddingRight();
            final int occupiedHeight = ((DynamicGridKeyboard) keyboard).getOccupiedHeight();
            final int height = occupiedHeight + getPaddingTop() + getPaddingBottom();
            setMeasuredDimension(width, height);
            return;
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    @Override
    public void setHardwareAcceleratedDrawingEnabled(final boolean enabled) {
        super.setHardwareAcceleratedDrawingEnabled(enabled);
        if (!enabled) return;
        final Paint layerPaint = new Paint();
        layerPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_OVER));
        mPopupKeysPlacerView.setLayerType(LAYER_TYPE_HARDWARE, layerPaint);
    }

    private void installPopupKeysPlacerView(final boolean uninstall) {
        final View rootView = getRootView();
        if (rootView == null) {
            Log.w(TAG, "Cannot find root view");
            return;
        }
        final ViewGroup windowContentView = rootView.findViewById(android.R.id.content);
        // Note: It'd be very weird if we get null by android.R.id.content.
        if (windowContentView == null) {
            Log.w(TAG, "Cannot find android.R.id.content view to add DrawingPreviewPlacerView");
            return;
        }

        if (uninstall) {
            windowContentView.removeView(mPopupKeysPlacerView);
        } else {
            windowContentView.addView(mPopupKeysPlacerView);
        }
    }

    public void setEmojiViewCallback(final EmojiViewCallback emojiViewCallback) {
        mEmojiViewCallback = emojiViewCallback;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setKeyboard(@NonNull final Keyboard keyboard) {
        super.setKeyboard(keyboard);
        mKeyDetector.setKeyboard(keyboard, 0 /* correctionX */, 0 /* correctionY */);
        mPopupKeysKeyboardCache.clear();
        if (AccessibilityUtils.Companion.getInstance().isAccessibilityEnabled()) {
            if (mAccessibilityDelegate == null) {
                mAccessibilityDelegate = new KeyboardAccessibilityDelegate<>(this, mKeyDetector);
            }
            mAccessibilityDelegate.setKeyboard(keyboard);
        } else {
            mAccessibilityDelegate = null;
        }
    }

    @Nullable
    private PopupKeysPanel showPopupKeysKeyboard(@NonNull final Key key) {
        mPopupKeysKeyboardView.setVisibility(GONE);
        final PopupKeySpec[] popupKeys = key.getPopupKeys();
        if (popupKeys == null) {
            return null;
        }
        Keyboard popupKeysKeyboard = mPopupKeysKeyboardCache.get(key);
        if (popupKeysKeyboard == null) {
            final PopupKeysKeyboard.Builder builder = new PopupKeysKeyboard.Builder(
                    getContext(), key, getKeyboard(), false, 0, 0, newLabelPaint(key));
            popupKeysKeyboard = builder.build();
            mPopupKeysKeyboardCache.put(key, popupKeysKeyboard);
        }

        mPopupKeysKeyboardView.setKeyboard(popupKeysKeyboard);
        mPopupKeysKeyboardView.setVisibility(VISIBLE);
        return mPopupKeysKeyboardView;
    }

    private void dismissPopupKeysPanel() {
        if (isShowingPopupKeysPanel()) {
            mPopupKeysPanel.dismissPopupKeysPanel();
        }
    }

    public boolean isShowingPopupKeysPanel() {
        return mPopupKeysPanel != null;
    }

    @Override
    public void setLayoutGravity(int layoutGravity) {
        var layoutParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        layoutParams.gravity = layoutGravity;
        mPopupKeysKeyboardContainer.setLayoutParams(layoutParams);
        mDescriptionView.setLayoutParams(layoutParams);
    }

    @Override
    public void onShowPopupKeysPanel(final PopupKeysPanel panel) {
        // install placer view only when needed instead of when this
        // view is attached to window
        installPopupKeysPlacerView(false /* uninstall */);
        panel.showInParent(mPopupKeysPlacerView);
        mPopupKeysPanel = panel;
    }

    @Override
    public void onDismissPopupKeysPanel() {
        if (isShowingPopupKeysPanel()) {
            mPopupKeysPanel.removeFromParent();
            mPopupKeysPanel = null;
            installPopupKeysPlacerView(true /* uninstall */);
        }
    }

    @Override
    public void onCancelPopupKeysPanel() {
        if (isShowingPopupKeysPanel()) {
            dismissPopupKeysPanel();
        }
    }

    @Override
    public boolean dispatchPopulateAccessibilityEvent(final AccessibilityEvent event) {
        // Don't populate accessibility event with all Emoji keys.
        return true;
    }

    private int getLongPressTimeout() {
        return Settings.getValues().mKeyLongpressTimeout;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean onHoverEvent(final MotionEvent event) {
        final KeyboardAccessibilityDelegate<EmojiPageKeyboardView> accessibilityDelegate = mAccessibilityDelegate;
        if (accessibilityDelegate != null && AccessibilityUtils.Companion.getInstance().isTouchExplorationEnabled()) {
            return accessibilityDelegate.onHoverEvent(event);
        }
        return super.onHoverEvent(event);
    }

    /**
     * {@inheritDoc}
     */
    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(final MotionEvent e) {
        return switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN -> {
                mPointerId = e.getPointerId(0);
                yield onDown(e);
            }
            case MotionEvent.ACTION_UP -> onUp(e);
            case MotionEvent.ACTION_MOVE -> onMove(e);
            case MotionEvent.ACTION_CANCEL -> onCancel(e);
            default -> false;
        };
    }

    private Key getKey(final int x, final int y) {
        return mKeyDetector.detectHitKey(x, y);
    }

    private void onLongPressed(final Key key) {
        if (isShowingPopupKeysPanel()) {
            return;
        }

        if (key == null) {
            if (LOG) Log.d(TAG, "Long press ignored because detected key is null");
            return;
        }

        var descriptionPanel = showDescription(key);
        final PopupKeysPanel popupKeysPanel = showPopupKeysKeyboard(key);

        final int x = mLastX;
        final int y = mLastY;
        if (popupKeysPanel != null || descriptionPanel != null) {
            mPopupKeysKeyboardContainer.measure(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);

            final int[] lastCoords = CoordinateUtils.newCoordinateArray(1, x, y);
            // The popup keys keyboard is usually horizontally aligned with the center of the parent key.
            // If showPopupKeysKeyboardAtTouchedPoint is true and the key preview is disabled, the more
            // keys keyboard is placed at the touch point of the parent key.
            final int pointX = mConfigShowPopupKeysKeyboardAtTouchedPoint
                    ? CoordinateUtils.x(lastCoords)
                    : key.getX() + key.getWidth() / 2;
            DynamicGridKeyboard keyboard = (DynamicGridKeyboard)getKeyboard();
            int pointY = key.getY() - keyboard.mVerticalGap;
            (popupKeysPanel != null ? popupKeysPanel : descriptionPanel)
                .showPopupKeysPanel(this, this, pointX, pointY,
                    (popupKey) -> {
                        if (keyboard.isRecents()) mEmojiViewCallback.onRemoveRecentsKey(key);
                        else mEmojiViewCallback.onReleaseKey(popupKey);
                        return Unit.INSTANCE;
                    }
                );
        }

        if (popupKeysPanel != null) {
            final int translatedX = popupKeysPanel.translateX(x);
            final int translatedY = popupKeysPanel.translateY(y);
            popupKeysPanel.onDownEvent(translatedX, translatedY, mPointerId, 0 /* nor used for now */);
            // No need of re-allowing parent later as we don't
            // want any scroll to append during this entire input.
            disallowParentInterceptTouchEvent(true);
        }
    }

    private PopupKeysPanel showDescription(Key key) {
        mDescriptionView.setVisibility(GONE);
        var description = mEmojiViewCallback.getDescription(key.getLabel());
        if (description == null) {
            return null;
        }

        mDescriptionView.setText(description);
        mDescriptionView.setKeyDrawParams(key, getKeyDrawParams());
        mDescriptionView.setVisibility(VISIBLE);
        return mDescriptionView;
    }

    private void registerPress(final Key key) {
        // Do not trigger key-down effect right now in case this is actually a fling action.
        mPendingKeyDown = () -> callListenerOnPressKey(key);
        mHandler.postDelayed(mPendingKeyDown, KEY_PRESS_DELAY_TIME);
    }

    private boolean hasPopupMenu(@NonNull final Key key) {
        final Keyboard keyboard = getKeyboard();
        if (keyboard instanceof DynamicGridKeyboard && ((DynamicGridKeyboard) keyboard).isRecents()) {
            return true;
        }
        final PopupKeySpec[] popupKeys = key.getPopupKeys();
        return popupKeys != null && popupKeys.length > 0;
    }

    private boolean isVerticalSwipe(final int deltaX, final int deltaY, final int keyHeight) {
        final int threshold = Math.max(keyHeight / 3, mTouchSlop * 2);
        return Math.abs(deltaY) >= threshold && Math.abs(deltaY) > Math.abs(deltaX) * 1.1f;
    }

    private void stopEmojiRepeat() {
        if (mEmojiRepeatRunnable != null) {
            mHandler.removeCallbacks(mEmojiRepeatRunnable);
            mEmojiRepeatRunnable = null;
        }
    }

    private void registerLongPress(final Key key) {
        cancelLongPress();
        if (Settings.getValues().mHoldForEmoji) {
            final int timeout = getLongPressTimeout();
            final int repeatInterval = Math.max(20, Settings.getValues().mHoldForEmojiRepeatInterval);
            mEmojiRepeatRunnable = new Runnable() {
                @Override
                public void run() {
                    if (mCurrentKey != key) return;
                    mIsRepeatingEmoji = true;
                    mEmojiRepeatCount++;
                    mEmojiViewCallback.onRepeatKey(key);
                    mHandler.postDelayed(this, repeatInterval);
                }
            };
            mPendingLongPress = () -> {
                if (mCurrentKey != key) return;
                disallowParentInterceptTouchEvent(true);
                if (mPendingKeyDown != null) {
                    mHandler.removeCallbacks(mPendingKeyDown);
                    mPendingKeyDown = null;
                    callListenerOnPressKey(key);
                }
                mEmojiRepeatRunnable.run();
            };
            mHandler.postDelayed(mPendingLongPress, timeout);
        } else {
            mPendingLongPress = () -> onLongPressed(key);
            mHandler.postDelayed(mPendingLongPress, getLongPressTimeout());
        }
    }

    void callListenerOnReleaseKey(final Key releasedKey, final boolean withKeyRegistering) {
        releasedKey.onReleased();
        invalidateKey(releasedKey);
        if (withKeyRegistering) {
            mEmojiViewCallback.onReleaseKey(releasedKey);
        }
    }

    void callListenerOnPressKey(final Key pressedKey) {
        mPendingKeyDown = null;
        pressedKey.onPressed();
        invalidateKey(pressedKey);
        mEmojiViewCallback.onPressKey(pressedKey);
    }

    public void releaseCurrentKey(final boolean withKeyRegistering) {
        mHandler.removeCallbacks(mPendingKeyDown);
        mPendingKeyDown = null;
        final Key currentKey = mCurrentKey;
        if (currentKey == null) {
            return;
        }
        callListenerOnReleaseKey(currentKey, withKeyRegistering);
        mCurrentKey = null;
    }

    public void cancelLongPress() {
        if (mPendingLongPress != null) {
            mHandler.removeCallbacks(mPendingLongPress);
            mPendingLongPress = null;
        }
        stopEmojiRepeat();
    }

    public boolean onDown(final MotionEvent e) {
        final int x = (int) e.getX();
        final int y = (int) e.getY();
        final Key key = getKey(x, y);
        releaseCurrentKey(false /* withKeyRegistering */);
        mCurrentKey = key;
        mDownX = x;
        mDownY = y;
        mDownTime = SystemClock.uptimeMillis();
        mIsRepeatingEmoji = false;
        mEmojiRepeatCount = 0;
        mMenuOpenedBySwipe = false;
        if (key == null) {
            return false;
        }
        registerPress(key);

        registerLongPress(key);

        mLastX = x;
        mLastY = y;
        return true;
    }

    public boolean onUp(final MotionEvent e) {
        final int x = (int) e.getX();
        final int y = (int) e.getY();
        final Key key = getKey(x, y);
        final Runnable pendingKeyDown = mPendingKeyDown;
        final Key currentKey = mCurrentKey;
        final boolean wasRepeatingEmoji = mIsRepeatingEmoji;
        final boolean menuOpenedBySwipe = mMenuOpenedBySwipe;
        mIsRepeatingEmoji = false;
        mEmojiRepeatCount = 0;
        mMenuOpenedBySwipe = false;
        releaseCurrentKey(false /* withKeyRegistering */);

        final boolean isShowingPopupKeysPanel = isShowingPopupKeysPanel();
        if (isShowingPopupKeysPanel) {
            final long eventTime = e.getEventTime();
            final int translatedX = mPopupKeysPanel.translateX(x);
            final int translatedY = mPopupKeysPanel.translateY(y);
            mPopupKeysPanel.onUpEvent(translatedX, translatedY, mPointerId, eventTime);
            dismissPopupKeysPanel();
        } else if (menuOpenedBySwipe) {
            // Menu was opened by swipe, do nothing extra
        } else if (wasRepeatingEmoji) {
            if (key != null) {
                mEmojiViewCallback.onRepeatEnded(key);
            }
        } else if (key == currentKey && pendingKeyDown != null) {
            pendingKeyDown.run();
            // Trigger key-release event a little later so that a user can see visual feedback.
            mHandler.postDelayed(() -> callListenerOnReleaseKey(key, true), KEY_RELEASE_DELAY_TIME);
        } else if (key != null) {
            callListenerOnReleaseKey(key, true /* withRegistering */);
        }

        cancelLongPress();
        return true;
    }

    public boolean onCancel(final MotionEvent e) {
        mIsRepeatingEmoji = false;
        mEmojiRepeatCount = 0;
        mMenuOpenedBySwipe = false;
        releaseCurrentKey(false);
        dismissPopupKeysPanel();
        cancelLongPress();
        return true;
    }

    public boolean onMove(final MotionEvent e) {
        final int x = (int) e.getX();
        final int y = (int) e.getY();
        final boolean isShowingPopupKeyboard = isShowingPopupKeysPanel() && mPopupKeysKeyboardView.getVisibility() == VISIBLE;

        if (isShowingPopupKeyboard) {
            final long eventTime = e.getEventTime();
            final int translatedX = mPopupKeysPanel.translateX(x);
            final int translatedY = mPopupKeysPanel.translateY(y);
            mPopupKeysPanel.onMoveEvent(translatedX, translatedY, mPointerId, eventTime);
            mLastX = x;
            mLastY = y;
            return true;
        }

        if (Settings.getValues().mHoldForEmoji && mCurrentKey != null && !mMenuOpenedBySwipe) {
            final int deltaX = x - mDownX;
            final int deltaY = y - mDownY;
            final DynamicGridKeyboard keyboard = (DynamicGridKeyboard) getKeyboard();
            final boolean isRecents = keyboard != null && keyboard.isRecents();
            final long touchDuration = SystemClock.uptimeMillis() - mDownTime;
            final boolean canTriggerSwipeMenu = isRecents || mIsRepeatingEmoji || touchDuration >= 150;

            if (canTriggerSwipeMenu && hasPopupMenu(mCurrentKey) && isVerticalSwipe(deltaX, deltaY, mCurrentKey.getHeight())) {
                mMenuOpenedBySwipe = true;
                stopEmojiRepeat();
                cancelLongPress();
                if (mEmojiRepeatCount > 0) {
                    mEmojiViewCallback.onDeleteRepeatedKeys(mEmojiRepeatCount);
                    mEmojiRepeatCount = 0;
                    mIsRepeatingEmoji = false;
                }
                if (mPendingKeyDown != null) {
                    mHandler.removeCallbacks(mPendingKeyDown);
                    mPendingKeyDown = null;
                }
                disallowParentInterceptTouchEvent(true);
                mLastX = x;
                mLastY = y;
                onLongPressed(mCurrentKey);
                return true;
            }
        }

        final Key key = getKey(x, y);

        // Touched key has changed, release previous key's callbacks and
        // re-register them for the new key.
        if (key != mCurrentKey) {
            if (mIsRepeatingEmoji && mCurrentKey != null) {
                mEmojiViewCallback.onRepeatEnded(mCurrentKey);
            }
            mIsRepeatingEmoji = false;
            mEmojiRepeatCount = 0;
            releaseCurrentKey(false);
            mCurrentKey = key;
            cancelLongPress();
            if (isShowingPopupKeysPanel()) {
                onCancelPopupKeysPanel();
            }
            if (key == null) {
                return false;
            }
            mDownX = x;
            mDownY = y;
            mDownTime = SystemClock.uptimeMillis();
            mMenuOpenedBySwipe = false;
            registerPress(key);
            registerLongPress(key);
        }

        mLastX = x;
        mLastY = y;
        return true;
    }

    private void disallowParentInterceptTouchEvent(final boolean disallow) {
        final ViewParent parent = getParent();
        if (parent == null) {
            Log.w(TAG, "Cannot disallow touch event interception, no parent found.");
            return;
        }
        parent.requestDisallowInterceptTouchEvent(disallow);
    }
}
