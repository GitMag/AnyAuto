package com.mag.anyauto;

import android.graphics.Rect;
import android.os.Handler;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;

/**
 * Turns the car's surface gestures back into touch streams for the virtual display.
 *
 * <p>Android Auto does not pass raw touches to a navigation app: it runs its own gesture
 * detector and calls {@code SurfaceCallback} with a tap position, scroll <i>distances</i>
 * (previous minus current, as {@code GestureDetector}), a fling velocity and pinch steps, but no
 * finger position for drags. So:
 * <ul>
 * <li><b>tap</b>: down and up at the point, {@value #TAP_MS} ms apart;</li>
 * <li><b>scroll</b>: one finger put down at the centre of the visible area, first moved past the
 *     touch slop in the scroll direction (views subtract the slop, so no distance is lost), then
 *     by every distance; lifted {@value #DRAG_END_MS} ms after the last step, or at the edge of
 *     the display (the next step starts a new drag from the centre);</li>
 * <li><b>fling</b>: a few fast moves at the given velocity, then up, so the app's VelocityTracker
 *     sees the fling;</li>
 * <li><b>scale</b>: two fingers around the focus, their distance multiplied by each factor,
 *     lifted {@value #PINCH_END_MS} ms after the last step.</li>
 * </ul>
 * Everything runs on the handler's thread, in order; each new gesture first ends the one before.
 */
public final class Touch {
    interface Sink {
        /** Takes the event (and recycles it). */
        void inject(MotionEvent e);
    }

    static final long TAP_MS = 40;
    static final long DRAG_END_MS = 150;
    static final long PINCH_END_MS = 200;
    private static final long FLING_STEP_MS = 8;
    private static final int FLING_STEPS = 4;

    private static final int IDLE = 0, TAP = 1, DRAG = 2, PINCH = 3;

    private final Handler h;
    private final Sink sink;
    private int width = 1, height = 1;
    private float slop = 10, cx, cy; // slop in px; centre of the visible area
    private int mode = IDLE;
    private int gen; // gesture number, so stale fling steps stop
    private long downTime;
    private float x, y;     // finger (tap/drag) or pinch focus
    private float span;     // half the distance between the pinch fingers
    private final Runnable end = this::endNow;
    private final MotionEvent.PointerProperties[] props = {new MotionEvent.PointerProperties(),
            new MotionEvent.PointerProperties()};
    private final MotionEvent.PointerCoords[] coords = {new MotionEvent.PointerCoords(),
            new MotionEvent.PointerCoords()};

    Touch(Handler h, Sink sink) {
        this.h = h;
        this.sink = sink;
        for (int i = 0; i < 2; i++) {
            props[i].id = i;
            props[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
            coords[i].pressure = 1;
            coords[i].size = 1;
        }
    }

    /** Display size and dpi, and the part of it the car does not cover with its own buttons. */
    void area(int w, int hgt, int dpi, Rect visible) {
        h.post(() -> {
            endNow();
            width = Math.max(1, w);
            height = Math.max(1, hgt);
            slop = 10f * dpi / 160f; // a bit over ViewConfiguration's 8 dp touch slop
            Rect r = visible != null && !visible.isEmpty() ? visible : new Rect(0, 0, width, height);
            cx = clampX(r.exactCenterX());
            cy = clampY(r.exactCenterY());
        });
    }

    public void click(float px, float py) {
        h.post(() -> {
            endNow();
            x = clampX(px);
            y = clampY(py);
            begin(TAP);
            h.postDelayed(end, TAP_MS);
        });
    }

    public void scroll(float dx, float dy) {
        h.post(() -> {
            if (mode != DRAG) startDrag(-dx, -dy);
            h.removeCallbacks(end);
            if (moveBy(-dx, -dy)) endNow();
            else h.postDelayed(end, DRAG_END_MS);
        });
    }

    public void fling(float vx, float vy) {
        h.post(() -> {
            if (mode != DRAG) startDrag(vx, vy);
            h.removeCallbacks(end);
            flingStep(gen, vx, vy, 0);
        });
    }

    public void scale(float fx, float fy, float factor) {
        h.post(() -> {
            if (factor <= 0 || Float.isNaN(factor)) return;
            if (mode != PINCH) {
                endNow();
                x = clampX(fx);
                y = clampY(fy);
                span = Math.max(slop * 4, Math.min(width, height) / 8f);
                begin(PINCH);
            }
            x = clampX(fx);
            y = clampY(fy);
            span = Math.max(slop, Math.min(span * factor, Math.max(width, height) / 2f));
            emit2(MotionEvent.ACTION_MOVE);
            h.removeCallbacks(end);
            h.postDelayed(end, PINCH_END_MS);
        });
    }

    /**
     * One mouse-wheel event at (x, y): {@code v} notches vertically (positive scrolls up, as
     * {@code AXIS_VSCROLL}) and {@code h} horizontally (positive scrolls right). Views scroll by
     * their own wheel step, about 64 dp a notch. Unlike a drag, it can't turn into a tap.
     */
    static MotionEvent wheel(float x, float y, float v, float h) {
        MotionEvent.PointerProperties[] pp = {new MotionEvent.PointerProperties()};
        pp[0].id = 0;
        pp[0].toolType = MotionEvent.TOOL_TYPE_MOUSE;
        MotionEvent.PointerCoords[] pc = {new MotionEvent.PointerCoords()};
        pc[0].x = x;
        pc[0].y = y;
        pc[0].setAxisValue(MotionEvent.AXIS_VSCROLL, v);
        pc[0].setAxisValue(MotionEvent.AXIS_HSCROLL, h);
        long now = SystemClock.uptimeMillis();
        return MotionEvent.obtain(now, now, MotionEvent.ACTION_SCROLL, 1, pp, pc, 0, 0, 1f, 1f, 0, 0,
                InputDevice.SOURCE_MOUSE, 0);
    }

    /** Lifts any finger now (e.g. when the surface goes away). */
    public void cancel() {
        h.post(this::endNow);
    }

    // ------------------------------------------------------------------ handler thread only

    private void startDrag(float dirX, float dirY) {
        endNow();
        x = cx;
        y = cy;
        begin(DRAG);
        float len = (float) Math.hypot(dirX, dirY);
        if (len > 0) moveBy(dirX / len * slop, dirY / len * slop);
    }

    private void flingStep(int g, float vx, float vy, int i) {
        if (g != gen || mode != DRAG) return;
        float dt = FLING_STEP_MS / 1000f;
        if (moveBy(vx * dt, vy * dt) || i + 1 >= FLING_STEPS) {
            endNow();
        } else {
            h.postDelayed(() -> flingStep(g, vx, vy, i + 1), FLING_STEP_MS);
        }
    }

    /** Moves the drag finger; true when it hit the display edge. */
    private boolean moveBy(float dx, float dy) {
        float nx = x + dx, ny = y + dy;
        x = clampX(nx);
        y = clampY(ny);
        emit1(MotionEvent.ACTION_MOVE);
        return x != nx || y != ny;
    }

    private void begin(int m) {
        gen++;
        mode = m;
        downTime = SystemClock.uptimeMillis();
        if (m == PINCH) {
            emit1(MotionEvent.ACTION_DOWN, x - span, y);
            emit2(MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT));
        } else {
            emit1(MotionEvent.ACTION_DOWN);
        }
    }

    private void endNow() {
        h.removeCallbacks(end);
        if (mode == PINCH) {
            emit2(MotionEvent.ACTION_POINTER_UP | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT));
            emit1(MotionEvent.ACTION_UP, clampX(x - span), y);
        } else if (mode != IDLE) {
            emit1(MotionEvent.ACTION_UP);
        }
        mode = IDLE;
        gen++;
    }

    private void emit1(int action) {
        emit1(action, x, y);
    }

    private void emit1(int action, float px, float py) {
        coords[0].x = clampX(px);
        coords[0].y = clampY(py);
        send(action, 1);
    }

    private void emit2(int action) {
        coords[0].x = clampX(x - span);
        coords[0].y = y;
        coords[1].x = clampX(x + span);
        coords[1].y = y;
        send(action, 2);
    }

    private void send(int action, int pointers) {
        MotionEvent e = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, pointers,
                props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
        sink.inject(e);
    }

    private float clampX(float v) {
        return Math.max(0, Math.min(width - 1, v));
    }

    private float clampY(float v) {
        return Math.max(0, Math.min(height - 1, v));
    }
}
