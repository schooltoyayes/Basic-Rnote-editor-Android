package io.github.kjly.brna.ui.canvas

import android.view.MotionEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke as CanvasStrokeStyle
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import io.github.kjly.brna.model.Affine
import io.github.kjly.brna.model.BrushStyle
import io.github.kjly.brna.model.EraserMode
import io.github.kjly.brna.model.InkPoint
import io.github.kjly.brna.model.NativeBitmapElement
import io.github.kjly.brna.model.NativeCanvasElement
import io.github.kjly.brna.model.NativeShapeElement
import io.github.kjly.brna.model.NativeTextElement
import io.github.kjly.brna.model.NativeVectorImageElement
import io.github.kjly.brna.model.PaperStyle
import io.github.kjly.brna.model.RnoteNativeColor
import io.github.kjly.brna.model.PressureCurve
import io.github.kjly.brna.model.Stroke
import io.github.kjly.brna.model.StrokePoint
import io.github.kjly.brna.model.ToolConfig
import io.github.kjly.brna.model.ToolType
import io.github.kjly.brna.model.ViewportState
import io.github.kjly.brna.render.NativeElementRenderer
import io.github.kjly.brna.render.VectorImageRenderer
import io.github.kjly.brna.storage.NativeEditing
import io.github.kjly.brna.render.composeStrokePath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.IdentityHashMap
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * One background thread for the zoom-detail renders: they are superseded as the view
 * moves, and letting them run side by side would only stack up memory for results that
 * are already out of date.
 */
@OptIn(ExperimentalCoroutinesApi::class)
private val detailDispatcher = Dispatchers.Default.limitedParallelism(1)

/** How long the view has to be still before sharper page renders are made for it. */
private const val DETAIL_SETTLE_MS = 250L

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun DrawingCanvas(
    toolConfig: ToolConfig,
    paperStyle: PaperStyle,
    viewportState: ViewportState,
    strokes: List<Stroke>,
    selectedStrokes: SnapshotStateList<Stroke>,
    onViewportChanged: (ViewportState) -> Unit,
    onAddStroke: (Stroke) -> Unit,
    onEraseStrokes: (List<Stroke>) -> Unit,
    onSelectionDragStart: () -> Unit = {},
    onStrokesModified: (List<Stroke>) -> Unit,
    /** Called once per eraser gesture, before the first stroke of it is removed. */
    onEraseStart: () -> Unit = {},
    /**
     * Elements from a desktop .rnote this app shows but doesn't edit: PDF pages and images
     * (drawn under the ink, as Rnote's document and image layers are) and text and shapes
     * (drawn over it). Brush strokes in here are ignored; [strokes] is what is drawn.
     */
    nativeElements: List<NativeCanvasElement> = emptyList(),
    modifier: Modifier = Modifier,
    /** The desktop elements (text, shapes, images) the selector currently holds. */
    selectedNatives: SnapshotStateList<NativeCanvasElement>? = null,
    /** A shape the Shaper just finished drawing. */
    onAddShape: (NativeShapeElement) -> Unit = {},
    /** Shapes the eraser went over; part of the same gesture as [onEraseStrokes]. */
    onEraseNatives: (List<NativeCanvasElement>) -> Unit = {},
    /** Desktop elements the selector moved: old instance -> moved copy. */
    onNativesMoved: (IdentityHashMap<NativeCanvasElement, NativeCanvasElement>) -> Unit = {},
    /**
     * The splitting eraser cut strokes apart: stroke id -> the pieces that replace it
     * (none if nothing is left). Part of the same gesture as [onEraseStrokes].
     */
    onSplitStrokes: (Map<String, List<Stroke>>) -> Unit = {},
    /** The Typewriter was tapped at this canvas position: start or edit a text box there. */
    onTypewriterTap: (Float, Float) -> Unit = { _, _ -> }
) {
    val underlays = remember(nativeElements) { nativeElements.filter(NativeElementRenderer::isUnderlay) }
    val overlays = remember(nativeElements) { nativeElements.filter(NativeElementRenderer::isOverlay) }
    // One renderer for the canvas's lifetime: its caches are per element, and editing
    // replaces only the elements that changed.
    val nativeRenderer = remember { NativeElementRenderer() }
    LaunchedEffect(overlays) { nativeRenderer.retainOnly(overlays) }

    // Pages and images are turned into bitmaps once, off the main thread, one at a time
    // (a PDF page can be megabytes of SVG). Until then a page shows as a blank sheet.
    // Keyed by the image data, not the element: moving a page makes a new element around
    // the same data, and that must not be rendered all over again.
    val underlayBitmaps = remember { mutableStateMapOf<Any, android.graphics.Bitmap>() }
    LaunchedEffect(underlays) {
        val wanted = underlays.mapNotNull(::imageKey).toSet()
        (underlayBitmaps.keys - wanted).forEach { underlayBitmaps.remove(it) }
        for (el in underlays) {
            val key = imageKey(el) ?: continue
            if (key in underlayBitmaps) continue
            val bitmap = withContext(Dispatchers.Default) {
                when (el) {
                    is NativeVectorImageElement -> VectorImageRenderer.rasterize(el)
                    is NativeBitmapElement -> NativeElementRenderer.decodeBitmap(el)
                    else -> null
                }
            }
            if (bitmap != null) underlayBitmaps[key] = bitmap
        }
    }

    // Sharper renders of the visible part of each PDF page, made once the view has come
    // to rest at a zoom the one-off bitmap can't resolve.
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    val detailTiles = remember { mutableStateMapOf<NativeCanvasElement, VectorImageRenderer.DetailTile>() }
    // underlayBitmaps.size is a key so a page whose base bitmap arrives while the view is
    // already zoomed in still gets its sharp render without waiting for the next pan.
    LaunchedEffect(underlays, viewportState, viewSize, underlayBitmaps.size) {
        delay(DETAIL_SETTLE_MS)
        if (viewSize.width == 0 || viewSize.height == 0) return@LaunchedEffect
        val scale = viewportState.effectiveScale
        val viewLeft = -viewportState.panOffset.x / scale
        val viewTop = -viewportState.panOffset.y / scale
        val viewRight = viewLeft + viewSize.width / scale
        val viewBottom = viewTop + viewSize.height / scale
        val wanted = HashMap<NativeCanvasElement, VectorImageRenderer.DetailTile>()
        underlays.forEach { el ->
            if (el !is NativeVectorImageElement) return@forEach
            val base = underlayBitmaps[el.svgData] ?: return@forEach
            // Device pixels per document unit the base bitmap has to offer.
            val basePxPerUnit = base.width / (el.maxX - el.minX).coerceAtLeast(1f)
            if (scale <= basePxPerUnit * 1.25f) return@forEach
            val l = maxOf(el.minX, viewLeft)
            val t = maxOf(el.minY, viewTop)
            val r = minOf(el.maxX, viewRight)
            val b = minOf(el.maxY, viewBottom)
            if (r <= l || b <= t) return@forEach
            val existing = detailTiles[el]
            if (existing != null && existing.pxPerUnit == scale && existing.covers(l, t, r, b)) {
                wanted[el] = existing
                return@forEach
            }
            val tile = withContext(detailDispatcher) {
                VectorImageRenderer.renderRegion(el, l, t, r, b, scale)
            } ?: return@forEach
            wanted[el] = tile
        }
        (detailTiles.keys - wanted.keys).forEach { detailTiles.remove(it) }
        detailTiles.putAll(wanted)
    }

    val vectorPaint = remember {
        android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG or android.graphics.Paint.ANTI_ALIAS_FLAG)
    }
    val placeholderPaint = remember {
        android.graphics.Paint().apply { color = android.graphics.Color.WHITE }
    }

    // The Shaper's drag, in canvas units; null when no shape is being drawn.
    var shapeStart by remember { mutableStateOf<Offset?>(null) }
    var shapeEnd by remember { mutableStateOf<Offset?>(null) }

    val currentPoints = remember { mutableStateListOf<InkPoint>() }
    /** Memoised stroke outlines, keyed by Stroke identity. See the draw block below. */
    val outlineCache = remember { IdentityHashMap<Stroke, Path>() }
    val lassoPoints = remember { mutableStateListOf<Offset>() }
    // selectedStrokes is owned by the caller (MainActivity) so it can be read for delete

    var isDrawing by remember { mutableStateOf(false) }
    var hoverOffset by remember { mutableStateOf<Offset?>(null) }
    var isMovingSelection by remember { mutableStateOf(false) }
    var selectionDragStart by remember { mutableStateOf(Offset.Zero) }
    // Ensures exactly one undo snapshot is taken per selection drag, on first
    // actual movement — not one per ACTION_MOVE frame, and not on a drag that
    // starts but never moves.
    var selectionMoveSnapshotTaken by remember { mutableStateOf(false) }
    // A scale or rotate by one of the selection's handles; null when there is none.
    var transformDrag by remember { mutableStateOf<TransformDrag?>(null) }
    // Latched at ACTION_DOWN: a gesture that began with the S-Pen side button held (or
    // with the pen's eraser end) erases for its whole duration, even if the button is
    // released halfway through. Deciding per-event instead would switch tools mid-stroke.
    var buttonEraserLatched by remember { mutableStateOf(false) }
    // One undo snapshot per eraser gesture, not one per frame that happens to hit ink.
    var eraseSnapshotTaken by remember { mutableStateOf(false) }
    // Where to paint the eraser square, in canvas units, and whether the pen is touching.
    // Rnote shows it in both states (EraserState::Proximity and ::Down) with different fills.
    var eraserCursor by remember { mutableStateOf<Offset?>(null) }
    var eraserCursorDown by remember { mutableStateOf(false) }

    // 2-finger pan/zoom tracking — done manually inside pointerInteropFilter
    // to avoid the pointerInput vs pointerInteropFilter conflict
    var lastPinchMidpoint by remember { mutableStateOf(Offset.Zero) }
    var lastPinchDistance by remember { mutableStateOf(0f) }
    var isPinching by remember { mutableStateOf(false) }

    // 1-finger pan tracking (used when finger drawing is disabled)
    var lastFingerPanPosition by remember { mutableStateOf<Offset?>(null) }

    // A Typewriter tap: where it went down (screen px, and canvas units), and whether it
    // has since moved too far to be a tap. A finger that pans the view isn't a tap.
    var tapDownScreen by remember { mutableStateOf<Offset?>(null) }
    var tapDownCanvas by remember { mutableStateOf(Offset.Zero) }

    // Rnote's eraser is `width` canvas units across, full stop — no density factor, no
    // 1.5x, no screen-space floor. Those made the tool a different physical size from
    // desktop's and stopped it scaling with zoom the way the ink it erases does.
    val eraserWidth = toolConfig.eraserWidth
    val splitEraser = toolConfig.eraserMode == EraserMode.SPLIT

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { viewSize = it }
            .background(paperStyle.currentBackgroundColor)
            // Single unified pointerInteropFilter handles drawing, erasing, pan, and pinch-zoom.
            // detectTransformGestures is intentionally NOT used — it conflicts with
            // pointerInteropFilter on the same Canvas and causes neither to work.
            .pointerInteropFilter { motionEvent ->

                // ── 2-Finger Pan & Pinch-to-Zoom ─────────────────────────────────────
                if (motionEvent.pointerCount == 2) {
                    tapDownScreen = null
                    transformDrag = null
                    // Cancel any in-progress single-finger stroke
                    if (isDrawing) {
                        isDrawing = false
                        currentPoints.clear()
                        lassoPoints.clear()
                    }

                    val x0 = motionEvent.getX(0)
                    val y0 = motionEvent.getY(0)
                    val x1 = motionEvent.getX(1)
                    val y1 = motionEvent.getY(1)
                    val midpoint = Offset((x0 + x1) / 2f, (y0 + y1) / 2f)
                    val distance = hypot((x1 - x0).toDouble(), (y1 - y0).toDouble()).toFloat()

                    when (motionEvent.actionMasked) {
                        MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_MOVE -> {
                            if (!isPinching) {
                                // First 2-finger frame — capture baseline
                                lastPinchMidpoint = midpoint
                                lastPinchDistance = distance
                                isPinching = true
                            } else {
                                // Pan delta (translation of the centroid itself)
                                val panDelta = midpoint - lastPinchMidpoint
                                // Zoom delta (ratio of current spread to previous spread)
                                val zoomDelta = if (lastPinchDistance > 0f) distance / lastPinchDistance else 1f
                                val newZoom = (viewportState.zoomScale * zoomDelta)
                                    .coerceIn(ViewportState.ZOOM_MIN, ViewportState.ZOOM_MAX)
                                val actualZoomRatio = newZoom / viewportState.zoomScale

                                // Anchor zoom at the pinch midpoint:
                                // The canvas point under lastPinchMidpoint must stay fixed after scaling.
                                // Formula: newPan = centroid + (oldPan - centroid) * zoomRatio + panDelta
                                val newPan = lastPinchMidpoint +
                                    (viewportState.panOffset - lastPinchMidpoint) * actualZoomRatio +
                                    panDelta
                                onViewportChanged(viewportState.update(newPan, newZoom))

                                lastPinchMidpoint = midpoint
                                lastPinchDistance = distance
                            }
                        }
                        MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            isPinching = false
                        }
                    }
                    return@pointerInteropFilter true
                }

                // Reset pinch state when back to 1 pointer
                if (isPinching) {
                    isPinching = false
                }

                // ── Single-Pointer: Stylus / Finger Drawing ───────────────────────────
                val screenX = motionEvent.x
                val screenY = motionEvent.y
                val canvasPos = viewportState.screenToCanvas(Offset(screenX, screenY))
                val x = canvasPos.x
                val y = canvasPos.y
                val toolType = motionEvent.getToolType(0)
                val buttonState = motionEvent.buttonState

                val isStylus = toolType == MotionEvent.TOOL_TYPE_STYLUS || toolType == MotionEvent.TOOL_TYPE_ERASER
                val isFinger = toolType == MotionEvent.TOOL_TYPE_FINGER || toolType == MotionEvent.TOOL_TYPE_UNKNOWN

                // Rnote treats pressure as [0, 1] and substitutes Element::PRESSURE_DEFAULT
                // where the device reports none — which is what desktop records for every
                // mouse stroke. Passing Android's finger "pressure" through instead would
                // make a finger-drawn stroke here twice the width of the same stroke there.
                val rawPressure = if (isStylus) {
                    motionEvent.pressure.coerceIn(0f, 1f)
                } else {
                    StrokePoint.PRESSURE_DEFAULT
                }

                val hasStylusPrimaryButton = (buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0
                val hasStylusSecondaryButton = (buttonState and MotionEvent.BUTTON_STYLUS_SECONDARY) != 0
                val hasSecondaryButton = (buttonState and MotionEvent.BUTTON_SECONDARY) != 0

                // Stylus-only mode: finger pans the viewport instead of drawing
                if (isFinger && !toolConfig.allowFingerDrawing) {
                    when (motionEvent.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            lastFingerPanPosition = Offset(screenX, screenY)
                            // With the Typewriter, a finger tap types too: nobody reaches
                            // for the pen to put a cursor somewhere.
                            tapDownScreen = if (toolConfig.activeTool == ToolType.TYPEWRITER) Offset(screenX, screenY) else null
                            tapDownCanvas = canvasPos
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val last = lastFingerPanPosition
                            if (last != null) {
                                val delta = Offset(screenX, screenY) - last
                                onViewportChanged(viewportState.update(viewportState.panOffset + delta, viewportState.zoomScale))
                            }
                            lastFingerPanPosition = Offset(screenX, screenY)
                            tapDownScreen?.let { if ((Offset(screenX, screenY) - it).getDistance() > TAP_SLOP_PX) tapDownScreen = null }
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            lastFingerPanPosition = null
                            if (motionEvent.actionMasked == MotionEvent.ACTION_UP && tapDownScreen != null) {
                                onTypewriterTap(tapDownCanvas.x, tapDownCanvas.y)
                            }
                            tapDownScreen = null
                        }
                    }
                    return@pointerInteropFilter true
                }

                // Hold the S-Pen side button to erase. Devices disagree on which bit the
                // barrel button sets, so all three are accepted; TOOL_TYPE_ERASER covers
                // styluses that report a flipped-to-eraser end instead of a button.
                val sPenSideButtonPressed =
                    hasStylusPrimaryButton || hasStylusSecondaryButton || hasSecondaryButton
                val eraserTipInUse = toolType == MotionEvent.TOOL_TYPE_ERASER
                val eraserRequestedNow = sPenSideButtonPressed || eraserTipInUse

                // Mid-gesture the latch decides; while hovering, the live state does, so
                // the cursor switches to the eraser square as soon as the button goes down.
                val activeTool = when {
                    isDrawing && buttonEraserLatched -> ToolType.ERASER
                    isDrawing -> toolConfig.activeTool
                    eraserRequestedNow -> ToolType.ERASER
                    else -> toolConfig.activeTool
                }

                // Samsung's One UI does not report a barrel-button-held stylus gesture with
                // the standard action codes. Measured on an SM-T870: DOWN/UP/MOVE arrive as
                // 211/212/213, while buttonState (BUTTON_STYLUS_PRIMARY) and toolType
                // (TOOL_TYPE_STYLUS) are both correct. Unmapped, those codes match no branch
                // below and fall through to `else -> false`, which is why holding the button
                // appeared to do nothing whatsoever -- not a failure to detect the button.
                val action = when (motionEvent.actionMasked) {
                    SAMSUNG_ACTION_PEN_DOWN -> MotionEvent.ACTION_DOWN
                    SAMSUNG_ACTION_PEN_UP -> MotionEvent.ACTION_UP
                    SAMSUNG_ACTION_PEN_MOVE -> MotionEvent.ACTION_MOVE
                    else -> motionEvent.actionMasked
                }

                when (action) {
                    MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> {
                        if (isStylus) {
                            if (activeTool == ToolType.ERASER) {
                                hoverOffset = null
                                eraserCursor = Offset(x, y)
                                eraserCursorDown = false
                            } else {
                                eraserCursor = null
                                hoverOffset = Offset(screenX, screenY)
                            }
                        }
                        true
                    }

                    MotionEvent.ACTION_HOVER_EXIT -> {
                        hoverOffset = null
                        eraserCursor = null
                        true
                    }

                    MotionEvent.ACTION_DOWN -> {
                        hoverOffset = null
                        isDrawing = true
                        buttonEraserLatched = eraserRequestedNow
                        eraseSnapshotTaken = false

                        val boundingBox = selectionBounds(selectedStrokes, selectedNatives)
                        if (activeTool == ToolType.SELECTOR && boundingBox != null) {
                            val handle = handleAt(boundingBox, viewportState, Offset(screenX, screenY))
                            if (handle != null) {
                                val corners = listOf(
                                    Offset(boundingBox.left, boundingBox.top),
                                    Offset(boundingBox.right, boundingBox.top),
                                    Offset(boundingBox.right, boundingBox.bottom),
                                    Offset(boundingBox.left, boundingBox.bottom)
                                )
                                val natives = selectedNatives?.toList() ?: emptyList()
                                transformDrag = TransformDrag(
                                    rotate = handle == ROTATE_HANDLE,
                                    pivot = if (handle == ROTATE_HANDLE) {
                                        Offset((boundingBox.left + boundingBox.right) / 2f, (boundingBox.top + boundingBox.bottom) / 2f)
                                    } else corners[(handle + 2) % 4],
                                    corner = if (handle == ROTATE_HANDLE) Offset.Zero else corners[handle],
                                    start = Offset(x, y),
                                    strokes = selectedStrokes.toList(),
                                    natives = natives,
                                    current = natives
                                )
                                selectionMoveSnapshotTaken = false
                                return@pointerInteropFilter true
                            }
                            val screenBoundingBox = Rect(
                                viewportState.canvasToScreen(boundingBox.topLeft),
                                viewportState.canvasToScreen(boundingBox.bottomRight)
                            )
                            if (screenBoundingBox.contains(Offset(screenX, screenY))) {
                                isMovingSelection = true
                                selectionMoveSnapshotTaken = false
                                selectionDragStart = Offset(x, y)
                                return@pointerInteropFilter true
                            } else {
                                selectedStrokes.clear()
                                selectedNatives?.clear()
                            }
                        }

                        if (activeTool == ToolType.SHAPER) {
                            shapeStart = Offset(x, y)
                            shapeEnd = Offset(x, y)
                        }
                        tapDownScreen = if (activeTool == ToolType.TYPEWRITER) Offset(screenX, screenY) else null
                        tapDownCanvas = Offset(x, y)

                        currentPoints.clear()
                        lassoPoints.clear()
                        currentPoints.add(InkPoint(x, y, rawPressure))
                        lassoPoints.add(Offset(x, y))

                        if (activeTool == ToolType.ERASER) {
                            eraserCursor = Offset(x, y)
                            eraserCursorDown = true
                            eraseAt(Offset(x, y), eraserWidth, splitEraser, strokes, onEraseStrokes, onSplitStrokes, overlays, onEraseNatives) {
                                if (!eraseSnapshotTaken) { onEraseStart(); eraseSnapshotTaken = true }
                            }
                        }
                        true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        if (isDrawing) {
                            val drag = transformDrag
                            if (drag != null) {
                                if (!selectionMoveSnapshotTaken) {
                                    onSelectionDragStart()
                                    selectionMoveSnapshotTaken = true
                                }
                                val m = dragTransform(drag, Offset(x, y), toolConfig.lockAspectRatio)
                                if (drag.strokes.isNotEmpty()) {
                                    val updated = SelectionManager.transformStrokes(drag.strokes, m)
                                    selectedStrokes.clear()
                                    selectedStrokes.addAll(updated)
                                    onStrokesModified(updated)
                                }
                                val selected = selectedNatives
                                if (selected != null && drag.natives.isNotEmpty()) {
                                    val next = drag.natives.map { NativeEditing.transform(it, m) }
                                    val moved = IdentityHashMap<NativeCanvasElement, NativeCanvasElement>()
                                    drag.current.forEachIndexed { i, el -> moved[el] = next[i] }
                                    drag.current = next
                                    selected.clear()
                                    selected.addAll(next)
                                    onNativesMoved(moved)
                                }
                                return@pointerInteropFilter true
                            }
                            val natives = selectedNatives
                            if (isMovingSelection &&
                                (selectedStrokes.isNotEmpty() || !natives.isNullOrEmpty())
                            ) {
                                if (!selectionMoveSnapshotTaken) {
                                    onSelectionDragStart()
                                    selectionMoveSnapshotTaken = true
                                }
                                val delta = Offset(x, y) - selectionDragStart
                                selectionDragStart = Offset(x, y)
                                if (selectedStrokes.isNotEmpty()) {
                                    val updated = SelectionManager.translateStrokes(selectedStrokes, delta)
                                    selectedStrokes.clear()
                                    selectedStrokes.addAll(updated)
                                    onStrokesModified(updated)
                                }
                                if (!natives.isNullOrEmpty()) {
                                    val moved = IdentityHashMap<NativeCanvasElement, NativeCanvasElement>()
                                    val updated = natives.map { old ->
                                        NativeEditing.translate(old, delta.x, delta.y).also { moved[old] = it }
                                    }
                                    natives.clear()
                                    natives.addAll(updated)
                                    onNativesMoved(moved)
                                }
                                return@pointerInteropFilter true
                            }

                            if (activeTool == ToolType.SHAPER && shapeStart != null) {
                                shapeEnd = Offset(x, y)
                                return@pointerInteropFilter true
                            }
                            if (activeTool == ToolType.TYPEWRITER) {
                                tapDownScreen?.let {
                                    if ((Offset(screenX, screenY) - it).getDistance() > TAP_SLOP_PX) tapDownScreen = null
                                }
                                return@pointerInteropFilter true
                            }

                            currentPoints.add(InkPoint(x, y, rawPressure))
                            lassoPoints.add(Offset(x, y))

                            if (activeTool == ToolType.ERASER) {
                                eraserCursor = Offset(x, y)
                                eraserCursorDown = true
                                eraseAt(Offset(x, y), eraserWidth, splitEraser, strokes, onEraseStrokes, onSplitStrokes, overlays, onEraseNatives) {
                                    if (!eraseSnapshotTaken) { onEraseStart(); eraseSnapshotTaken = true }
                                }
                            }
                        }
                        true
                    }

                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (transformDrag != null) {
                            transformDrag = null
                        } else if (isMovingSelection) {
                            isMovingSelection = false
                        } else if (isDrawing) {
                            if (activeTool == ToolType.SELECTOR && lassoPoints.size >= 3) {
                                val found = SelectionManager.findStrokesInLasso(lassoPoints, strokes)
                                selectedStrokes.clear()
                                selectedStrokes.addAll(found)
                                selectedNatives?.let { natives ->
                                    val polygon = lassoPoints.map { it.x to it.y }
                                    natives.clear()
                                    natives.addAll(nativeElements.filter { NativeEditing.insideLasso(it, polygon) })
                                }
                            } else if (activeTool == ToolType.TYPEWRITER) {
                                if (action == MotionEvent.ACTION_UP && tapDownScreen != null) {
                                    onTypewriterTap(tapDownCanvas.x, tapDownCanvas.y)
                                }
                            } else if (activeTool == ToolType.SHAPER) {
                                val start = shapeStart
                                val end = shapeEnd
                                if (start != null && end != null) {
                                    NativeEditing.createShape(
                                        toolConfig.shapeKind, start.x, start.y, end.x, end.y,
                                        nativeColorOf(toolConfig.penColor), toolConfig.shaperWidth
                                    )?.let(onAddShape)
                                }
                            } else if (activeTool == ToolType.BRUSH && currentPoints.isNotEmpty()) {
                                val isMarker = toolConfig.brushStyle == BrushStyle.MARKER

                                onAddStroke(
                                    Stroke(
                                        points = currentPoints.toList(),
                                        color = toolConfig.currentActiveColor,
                                        // The picker's number, stored as-is. Pressure is
                                        // already on every point and gets applied at draw
                                        // time by the curve below; folding it in here too
                                        // made desktop Rnote apply it a second time.
                                        strokeWidth = toolConfig.currentActiveSize,
                                        toolType = activeTool,
                                        isHighlighter = isMarker,
                                        pressureCurve = pressureCurveFor(toolConfig)
                                    )
                                )
                            }
                            currentPoints.clear()
                            lassoPoints.clear()
                        }
                        isDrawing = false
                        tapDownScreen = null
                        shapeStart = null
                        shapeEnd = null
                        buttonEraserLatched = false
                        eraseSnapshotTaken = false
                        eraserCursorDown = false
                        // The pen may still be hovering; the square is repainted by the
                        // next hover event, and hidden if the pen has left entirely.
                        eraserCursor = null
                        true
                    }

                    else -> false
                }
            }
    ) {
        // 1. Render Infinite Paper Background
        PaperBackgroundRenderer.drawPaperBackground(
            drawScope = this,
            paperStyle = paperStyle,
            zoomLevel = viewportState.effectiveScale,
            panOffset = viewportState.panOffset
        )

        // Apply Viewport Transform Matrix (Zoom & Pan)
        withTransform({
            translate(viewportState.panOffset.x, viewportState.panOffset.y)
            scale(viewportState.effectiveScale, viewportState.effectiveScale, Offset.Zero)
        }) {
            // 1b. Imported PDF pages and images (Rnote's document/image layers), beneath all ink.
            if (underlays.isNotEmpty()) {
                drawIntoCanvas { canvas ->
                    val nc = canvas.nativeCanvas
                    underlays.forEach { el ->
                        val bitmap = imageKey(el)?.let { underlayBitmaps[it] }
                        when (el) {
                            is NativeVectorImageElement -> {
                                val dst = android.graphics.RectF(
                                    -el.halfExtentX, -el.halfExtentY, el.halfExtentX, el.halfExtentY
                                )
                                nc.save()
                                nc.concat(VectorImageRenderer.matrixFor(el))
                                if (bitmap != null) nc.drawBitmap(bitmap, null, dst, vectorPaint)
                                else nc.drawRect(dst, placeholderPaint)
                                nc.restore()
                                detailTiles[el]?.let { tile ->
                                    nc.drawBitmap(
                                        tile.bitmap, null,
                                        android.graphics.RectF(tile.left, tile.top, tile.right, tile.bottom),
                                        vectorPaint
                                    )
                                }
                            }
                            is NativeBitmapElement ->
                                bitmap?.let { nativeRenderer.drawBitmap(nc, el, it) }
                            else -> Unit
                        }
                    }
                }
            }

            // 2. Render existing strokes.
            // Filled variable-width outlines, not constant-width stroked paths — see
            // StrokeOutline for why that's the only way the width can match desktop.
            // Outlines are ~4x the work of the old polyline, so they're memoised on the
            // Stroke instance; strokes are immutable, and every edit path (translate,
            // scale) produces a fresh copy, so identity is a safe key.
            if (outlineCache.size > strokes.size * 2 + 64) outlineCache.clear()
            strokes.forEach { stroke ->
                val path = outlineCache.getOrPut(stroke) {
                    composeStrokePath(stroke.points, stroke.strokeWidth, stroke.pressureCurve)
                }
                drawPath(path = path, color = stroke.color)
            }

            // 2b. Desktop text boxes and shapes, over the ink.
            if (overlays.isNotEmpty()) {
                drawIntoCanvas { canvas ->
                    val nc = canvas.nativeCanvas
                    for (el in overlays) {
                        when (el) {
                            is NativeTextElement -> nativeRenderer.drawText(nc, el)
                            is NativeShapeElement -> nativeRenderer.drawShape(nc, el)
                            else -> Unit
                        }
                    }
                }
            }

            // 3. Render active stroke preview
            // Must agree with the input handler's latch, or a gesture that started with
            // the side button held would paint an ink preview while it erased.
            val activeTool =
                if (buttonEraserLatched) ToolType.ERASER else toolConfig.activeTool
            if (isDrawing && currentPoints.isNotEmpty() && activeTool == ToolType.BRUSH) {
                val isMarker = toolConfig.brushStyle == BrushStyle.MARKER
                // Built the same way as a committed stroke, so what's under the nib is
                // what gets saved — the old preview used only the latest pressure and so
                // showed one uniform width for a stroke that would be drawn tapered.
                val path = composeStrokePath(
                    currentPoints,
                    toolConfig.currentActiveSize,
                    pressureCurveFor(toolConfig)
                )
                drawPath(path = path, color = toolConfig.currentActiveColor)
            }

            // 3b. Shape being dragged out with the Shaper.
            val previewStart = shapeStart
            val previewEnd = shapeEnd
            if (isDrawing && activeTool == ToolType.SHAPER && previewStart != null && previewEnd != null) {
                NativeEditing.createShape(
                    toolConfig.shapeKind, previewStart.x, previewStart.y, previewEnd.x, previewEnd.y,
                    nativeColorOf(toolConfig.penColor), toolConfig.shaperWidth
                )?.let { preview ->
                    drawIntoCanvas { canvas -> nativeRenderer.drawShape(canvas.nativeCanvas, preview, cache = false) }
                }
            }

            // 4. Render lasso polygon preview
            if (isDrawing && activeTool == ToolType.SELECTOR && lassoPoints.size >= 2) {
                val lassoPath = Path()
                lassoPath.moveTo(lassoPoints[0].x, lassoPoints[0].y)
                for (i in 1 until lassoPoints.size) {
                    lassoPath.lineTo(lassoPoints[i].x, lassoPoints[i].y)
                }
                drawPath(
                    path = lassoPath,
                    color = Color(0xFFC792EA),
                    style = CanvasStrokeStyle(
                        width = 2f / viewportState.effectiveScale,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)
                    )
                )
            }

            // 5. Render selection bounding box
            val bbox = selectionBounds(selectedStrokes, selectedNatives)
            bbox?.let { box ->
                val scale = viewportState.effectiveScale
                val inflated = box.inflate(HANDLE_INFLATE_PX / scale)
                drawRoundRect(
                    color = SELECTION_COLOR,
                    topLeft = inflated.topLeft,
                    size = inflated.size,
                    cornerRadius = CornerRadius(8f, 8f),
                    style = CanvasStrokeStyle(
                        width = 2.5f / scale,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 12f), 0f)
                    )
                )
                // Handles, a fixed size on screen: the corners scale, the knob above rotates.
                if (toolConfig.activeTool == ToolType.SELECTOR) {
                    val handles = handlePositions(box, scale)
                    val half = HANDLE_SIZE_PX / 2f / scale
                    val outline = CanvasStrokeStyle(width = 2f / scale)
                    val knob = handles[ROTATE_HANDLE]
                    drawLine(
                        color = SELECTION_COLOR,
                        start = Offset(knob.x, inflated.top),
                        end = knob,
                        strokeWidth = 2f / scale
                    )
                    for (i in 0 until 4) {
                        val topLeft = handles[i] - Offset(half, half)
                        val size = Size(half * 2f, half * 2f)
                        drawRect(color = Color.White, topLeft = topLeft, size = size)
                        drawRect(color = SELECTION_COLOR, topLeft = topLeft, size = size, style = outline)
                    }
                    drawCircle(color = Color.White, radius = half * 1.2f, center = knob)
                    drawCircle(color = SELECTION_COLOR, radius = half * 1.2f, center = knob, style = outline)
                }
            }

            // 6. Render the eraser square, in canvas space so it scales with zoom exactly
            // as Rnote's does (Eraser::draw_on_doc). Colours are Rnote's GNOME reds:
            // fill GNOME_REDS[0] at a=160 when down and a=51 in proximity, outline
            // GNOME_REDS[2] at a=240, two screen pixels wide at any zoom.
            eraserCursor?.let { center ->
                val bounds = EraserHitTest.eraserBounds(center, toolConfig.eraserWidth)
                val outlineWidth = 2f / viewportState.effectiveScale
                drawRect(
                    color = if (eraserCursorDown) ERASER_FILL else ERASER_PROXIMITY_FILL,
                    topLeft = bounds.topLeft,
                    size = bounds.size
                )
                val outline = bounds.deflate(outlineWidth * 0.5f)
                drawRect(
                    color = ERASER_OUTLINE,
                    topLeft = outline.topLeft,
                    size = outline.size,
                    style = CanvasStrokeStyle(width = outlineWidth)
                )
            }
        }

        // 7. Veil the canvas outside the document, over the strokes so ink out there is
        // dimmed too — see PaperBackgroundRenderer.drawOutOfBoundsScrim.
        PaperBackgroundRenderer.drawOutOfBoundsScrim(
            drawScope = this,
            paperStyle = paperStyle,
            zoomLevel = viewportState.effectiveScale,
            panOffset = viewportState.panOffset
        )

        // 8. Render the brush hover cursor (screen space). The eraser has its own
        // indicator above, drawn in canvas space because its size is a document size.
        hoverOffset?.let { hoverPos ->
            if (toolConfig.activeTool == ToolType.TYPEWRITER) {
                // A text cursor as tall as a line of the chosen size: tapping here puts the
                // top-left of the new text box at the pen tip.
                val height = toolConfig.textSize * 1.2f * viewportState.effectiveScale
                drawLine(
                    color = toolConfig.penColor,
                    start = hoverPos,
                    end = hoverPos + Offset(0f, height),
                    strokeWidth = 2f
                )
            } else {
                drawCircle(
                    color = toolConfig.currentActiveColor,
                    radius = (toolConfig.currentActiveSize * viewportState.effectiveScale) / 2f,
                    center = hoverPos
                )
            }
        }
    }
}

/**
 * The pressure curve a stroke drawn with [toolConfig] should carry, following how
 * desktop Rnote configures its own brushes in `pensconfig/brushconfig.rs`.
 */
private fun pressureCurveFor(toolConfig: ToolConfig): PressureCurve = when {
    // Rnote's MarkerOptions pin the curve to Const: a marker is a constant-width nib.
    toolConfig.brushStyle == BrushStyle.MARKER -> PressureCurve.CONST
    // Our "pressure sensitivity" switch is the same choice Rnote exposes as the curve.
    !toolConfig.isPressureSensitive -> PressureCurve.CONST
    else -> PressureCurve.LINEAR
}

/**
 * One UI's proprietary MotionEvent actions for a stylus gesture with the barrel button
 * held, which it substitutes for ACTION_DOWN / ACTION_UP / ACTION_MOVE. Not in the SDK;
 * measured on an SM-T870 by logging every event the canvas receives.
 */
private const val SAMSUNG_ACTION_PEN_DOWN = 211
private const val SAMSUNG_ACTION_PEN_UP = 212
private const val SAMSUNG_ACTION_PEN_MOVE = 213

/**
 * Rnote's `Eraser` colours (GNOME palette reds, see Eraser::draw_on_doc).
 */
private val ERASER_OUTLINE = Color(0xE0E01B24)
private val ERASER_FILL = Color(0xA0F66151)
private val ERASER_PROXIMITY_FILL = Color(0x33F66151)

/**
 * Rnote's two eraser styles. `TrashCollidingStrokes` (the default) removes every stroke
 * the eraser square touches; `SplitCollidingStrokes` ([split]) cuts out only the part of
 * each ink stroke under it. Shapes are removed whole either way, and text and images are
 * left alone, as in Rnote. [onFirstHit] fires before the first change of a gesture, so
 * the whole drag collapses into one undo step rather than one per frame.
 */
private fun eraseAt(
    center: Offset,
    eraserWidth: Float,
    split: Boolean,
    strokes: List<Stroke>,
    onEraseStrokes: (List<Stroke>) -> Unit,
    onSplitStrokes: (Map<String, List<Stroke>>) -> Unit,
    overlays: List<NativeCanvasElement>,
    onEraseNatives: (List<NativeCanvasElement>) -> Unit,
    onFirstHit: () -> Unit
) {
    val bounds = EraserHitTest.eraserBounds(center, eraserWidth)
    val hit = if (split) emptyList() else EraserHitTest.collidingStrokes(bounds, strokes)
    val pieces = LinkedHashMap<String, List<Stroke>>()
    if (split) {
        for (stroke in strokes) EraserHitTest.splitStroke(bounds, stroke)?.let { pieces[stroke.id] = it }
    }
    val hitShapes = overlays.filter {
        it is NativeShapeElement &&
            NativeEditing.eraserHits(it, bounds.left, bounds.top, bounds.right, bounds.bottom)
    }
    if (hit.isNotEmpty() || pieces.isNotEmpty() || hitShapes.isNotEmpty()) onFirstHit()
    if (hit.isNotEmpty()) onEraseStrokes(hit)
    if (pieces.isNotEmpty()) onSplitStrokes(pieces)
    if (hitShapes.isNotEmpty()) onEraseNatives(hitShapes)
}

/**
 * A drag on one of the selection's handles. The transform is worked out afresh from
 * where the drag began and applied to the selection as it was then, every frame —
 * adding up small per-frame steps instead would drift and put the rounding into the file.
 */
private class TransformDrag(
    val rotate: Boolean,
    /** Scaling: the corner opposite the one dragged. Rotating: the selection's centre. */
    val pivot: Offset,
    /** Scaling: the selection corner being dragged. */
    val corner: Offset,
    /** Where the drag began, in canvas units. */
    val start: Offset,
    val strokes: List<Stroke>,
    val natives: List<NativeCanvasElement>,
    /** The desktop elements as the document holds them now, in the order of [natives]. */
    var current: List<NativeCanvasElement>
)

private val SELECTION_COLOR = Color(0xFF82AAFF)

/** The dashed box sits this far (screen px) outside the selection; the handles on its corners. */
private const val HANDLE_INFLATE_PX = 12f
private const val HANDLE_SIZE_PX = 14f
/** How close (screen px) the pen has to come to a handle to take it. */
private const val HANDLE_HIT_PX = 32f
/** How far (screen px) the rotate knob stands above the box. */
private const val ROTATE_HANDLE_OFFSET_PX = 36f
/** Index of the rotate knob in [handlePositions]; 0–3 are the corners, clockwise from top-left. */
private const val ROTATE_HANDLE = 4
/** Rnote won't scale a selection flat or inside out; nor will this. */
private const val MIN_SCALE = 0.02f
private const val MAX_SCALE = 50f

/** Where the handles of a selection with bounds [box] are drawn, in canvas units. */
private fun handlePositions(box: Rect, scale: Float): List<Offset> {
    val r = box.inflate(HANDLE_INFLATE_PX / scale)
    return listOf(
        Offset(r.left, r.top), Offset(r.right, r.top), Offset(r.right, r.bottom), Offset(r.left, r.bottom),
        Offset((r.left + r.right) / 2f, r.top - ROTATE_HANDLE_OFFSET_PX / scale)
    )
}

/**
 * The handle nearest the screen point [screen], if one is within reach. A touch inside the
 * selection itself is never a handle: it moves the selection, however small that is.
 */
private fun handleAt(box: Rect, viewport: ViewportState, screen: Offset): Int? {
    val topLeft = viewport.canvasToScreen(Offset(box.left, box.top))
    val bottomRight = viewport.canvasToScreen(Offset(box.right, box.bottom))
    if (screen.x in topLeft.x..bottomRight.x && screen.y in topLeft.y..bottomRight.y) return null
    var best: Int? = null
    var bestDistance = HANDLE_HIT_PX
    handlePositions(box, viewport.effectiveScale).forEachIndexed { i, p ->
        val d = (viewport.canvasToScreen(p) - screen).getDistance()
        if (d <= bestDistance) { best = i; bestDistance = d }
    }
    return best
}

/**
 * The transform a handle drag at [pos] stands for. Scaling moves the dragged corner with
 * the pen and keeps the opposite one where it is — uniformly with [lockAspect], as Rnote's
 * "Lock Aspect Ratio" does. Rotating turns about the centre by the angle swept since the
 * drag began.
 */
private fun dragTransform(drag: TransformDrag, pos: Offset, lockAspect: Boolean): FloatArray {
    val p = drag.pivot
    if (drag.rotate) {
        val from = atan2(drag.start.y - p.y, drag.start.x - p.x)
        val to = atan2(pos.y - p.y, pos.x - p.x)
        return Affine.rotateAbout(p.x, p.y, to - from)
    }
    val moved = drag.corner + (pos - drag.start)
    val w0 = drag.corner.x - p.x
    val h0 = drag.corner.y - p.y
    var sx: Float
    var sy: Float
    if (lockAspect) {
        val len2 = w0 * w0 + h0 * h0
        val s = if (len2 > 0.25f) ((moved.x - p.x) * w0 + (moved.y - p.y) * h0) / len2 else 1f
        sx = s; sy = s
    } else {
        // A selection with no width (a vertical line) can't be stretched sideways, and so on.
        sx = if (abs(w0) > 0.5f) (moved.x - p.x) / w0 else 1f
        sy = if (abs(h0) > 0.5f) (moved.y - p.y) / h0 else 1f
    }
    sx = sx.coerceIn(MIN_SCALE, MAX_SCALE)
    sy = sy.coerceIn(MIN_SCALE, MAX_SCALE)
    return Affine.scaleAbout(p.x, p.y, sx, sy)
}

/** How far (screen px) a Typewriter tap may drift and still count as a tap. */
private const val TAP_SLOP_PX = 24f

/** What a page or image is rendered from; the key its bitmap is kept under. */
private fun imageKey(el: NativeCanvasElement): Any? = when (el) {
    is NativeVectorImageElement -> el.svgData
    is NativeBitmapElement -> el.rgbaBase64 ?: el.pixels
    else -> null
}

private fun nativeColorOf(c: Color) = RnoteNativeColor(c.red, c.green, c.blue, c.alpha)

/** The box around everything selected, ink and desktop elements alike; null if nothing is. */
private fun selectionBounds(strokes: List<Stroke>, natives: List<NativeCanvasElement>?): Rect? {
    var box = SelectionManager.calculateBoundingBox(strokes)
    natives?.forEach { el ->
        val r = Rect(el.minX, el.minY, el.maxX, el.maxY)
        box = box?.let { Rect(minOf(it.left, r.left), minOf(it.top, r.top), maxOf(it.right, r.right), maxOf(it.bottom, r.bottom)) } ?: r
    }
    return box
}

