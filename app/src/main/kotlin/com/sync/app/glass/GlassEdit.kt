package com.sync.app.glass

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import com.kyant.shapes.RoundedRectangle
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 플레이리스트 곡 "편집 상태" — 편집 중인 곡 행과 Context Menu 를 Kyant Liquid Glass(backdrop)로 그린다.
 *
 *  · JS(app.js)는 행의 문서 기준 좌표만 알려 준다(스크롤과 무관한 상수). 스크롤 보정은 여기서 cssScroll 로 직접 한다.
 *  · 드래그는 전부 네이티브에서 처리한다. JS 로는 "목표 슬롯이 바뀔 때"와 "놓았을 때"만 명령을 보낸다
 *    (프레임마다 JS 왕복 없음 → 메인 스레드/WebView 부하 증가 없음).
 *  · 행은 [listTop, listTop + (count-1) * pitch] 범위(= 플레이리스트 그리드)를 벗어나지 못한다.
 */

private const val MENU_W = 204f
private const val MENU_H = 118f

private class DragSession {
    @JvmField var startTop = 0f
    @JvmField var startScroll = 0f
    @JvmField var fingerPx = 0f
}

private val TrashPaths: List<Path> by lazy {
    listOf("M2 3.5h10", "M5.5 3.5V2h3v1.5", "M3.2 3.5l.6 8h6.4l.6-8", "M5.8 6v3.5", "M8.2 6v3.5")
        .map { PathParser().parsePathString(it).toPath() }
}

/** Kyant `DialogContent` 와 같은 구성(colorControls + blur + lens + plain highlight)의 유리 표면. */
private fun Modifier.editGlass(
    backdrop: Backdrop, radius: Int, lensH: Float, lensA: Float, tint: Color,
    layer: GraphicsLayerScope.() -> Unit
): Modifier = this.drawBackdrop(
    backdrop = backdrop,
    shape = { RoundedRectangle(radius.dp) },
    effects = {
        colorControls(brightness = 0f, saturation = 1.5f)
        blur(3f.dp.toPx())
        lens(lensH.dp.toPx(), lensA.dp.toPx(), depthEffect = true)
    },
    highlight = { Highlight.Plain },
    layerBlock = layer,
    onDrawSurface = {
        drawRect(GlassTheme.Container)
        if (tint.alpha > 0f) drawRect(tint)
    }
)

private val SwallowTouches: Modifier = Modifier.pointerInput(Unit) { detectTapGestures { } }

@Composable
internal fun EditGlass(e: EditState, backdrop: Backdrop, c: GlassController) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val d = LocalDensity.current.density
        val viewW = constraints.maxWidth.toFloat()
        val viewH = constraints.maxHeight.toFloat()
        // 편집 세션(곡 + rev)이 바뀔 때만 드래그/애니메이션 상태를 초기화한다
        key(e.id, e.rev) { EditGlassBody(e, backdrop, c, d, viewW, viewH) }
    }
}

@Composable
private fun EditGlassBody(
    e: EditState, backdrop: Backdrop, c: GlassController, d: Float, viewW: Float, viewH: Float
) {
    val cur by rememberUpdatedState(e)
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val accent = LocalGlassAccent.current
    val pop = rememberPopIn()

    val rowTop = remember { Animatable(e.top) }          // 놓여 있는 자리(문서 기준 css px)
    val lift = remember { Animatable(0f) }               // 0 = 놓임, 1 = 들어 올림
    val leave = remember { Animatable(1f) }              // 삭제 시 1 → 0
    var dragging by remember { mutableStateOf(false) }
    var dragTop by remember { mutableFloatStateOf(e.top) }
    var target by remember { mutableIntStateOf(e.index) }
    val session = remember { DragSession() }

    LaunchedEffect(Unit) { haptic.performHapticFeedback(HapticFeedbackType.LongPress) }
    // JS 가 새 자리(드롭 확정/재측정)를 알려 오면 부드럽게 맞춘다 — 드래그 중에는 건드리지 않는다
    LaunchedEffect(e.top, e.index) {
        if (!dragging) {
            target = e.index
            rowTop.animateTo(e.top, spring(0.82f, 420f))
        }
    }
    LaunchedEffect(e.leaving) { if (e.leaving) leave.animateTo(0f, spring(0.9f, 520f)) }

    fun recompute() {
        val s = cur
        val scroll = c.cssScroll(d)
        val raw = session.startTop + session.fingerPx / d + (scroll - session.startScroll)
        val minT = s.listTop
        val maxT = s.listTop + (s.count - 1).coerceAtLeast(0) * s.pitch
        dragTop = raw.coerceIn(minT, maxT)                                   // 그리드 밖으로 못 나간다
        val to = ((dragTop - minT) / s.pitch).roundToInt().coerceIn(0, (s.count - 1).coerceAtLeast(0))
        if (to != target) {
            target = to
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            c.cmd { put("t", "edit"); put("a", "move"); put("to", to) }
        }
    }

    fun finish() {
        if (!dragging) return
        val s = cur
        val to = target
        val from = dragTop
        val rest = s.listTop + to * s.pitch
        scope.launch {
            rowTop.snapTo(from)
            dragging = false
            // 놓는 즉시 JS 가 데이터/DOM 을 확정한다(나머지 행은 이미 최종 위치) — 이 행은 슬롯으로 스프링 정착
            c.cmd { put("t", "edit"); put("a", "drop"); put("to", to) }
            launch { lift.animateTo(0f, spring(0.7f, 380f)) }
            rowTop.animateTo(rest, spring(0.78f, 420f))
        }
    }

    // 화면 위/아래 가장자리로 끌면 목록이 자동으로 스크롤된다 (스크롤 중에도 목표 슬롯이 계속 갱신됨)
    LaunchedEffect(dragging) {
        if (!dragging) return@LaunchedEffect
        while (true) {
            withFrameNanos { }
            val s = cur
            val scrollNow = c.cssScroll(d)
            val screenTop = (dragTop - scrollNow) * d
            val screenBottom = screenTop + s.h * d
            val topEdge = 110f * d
            val bottomEdge = viewH - 170f * d
            val zone = 70f * d
            val step = when {
                screenTop < topEdge -> -((topEdge - screenTop) / zone).coerceIn(0.2f, 1f) * 16f * d
                screenBottom > bottomEdge -> ((screenBottom - bottomEdge) / zone).coerceIn(0.2f, 1f) * 16f * d
                else -> 0f
            }
            if (step != 0f) {
                c.scrollByPx(step.roundToInt())
                recompute()
            }
        }
    }

    val tint = if (e.playing) accent.copy(0.14f) else Color.Transparent
    val radius = 16

    // ── 편집 중인 곡 행 (Liquid Glass) ─────────────────────────────────────────────
    Box(
        Modifier
            .offset {
                val sc = c.cssScroll(density)
                val top = if (dragging) dragTop else rowTop.value
                IntOffset((cur.x * density).roundToInt(), ((top - sc) * density).roundToInt())
            }
            .size(e.w.dp, e.h.dp)
            .editGlass(backdrop, radius, 12f, 18f, tint) {
                val p = pop.value.coerceIn(0f, 1f)
                val s = popScale(p) * (1f + 0.035f * lift.value) * (0.9f + 0.1f * leave.value)
                scaleX = s; scaleY = s
                alpha = p * leave.value.coerceIn(0f, 1f)
            }
            .then(if (e.playing) Modifier.border(1.dp, accent.copy(0.55f), RoundedCornerShape(radius.dp)) else Modifier)
            .pointerInput(e.id, e.rev) {
                detectDragGestures(
                    onDragStart = {
                        session.startTop = rowTop.value
                        session.startScroll = c.cssScroll(d)
                        session.fingerPx = 0f
                        dragTop = rowTop.value
                        dragging = true
                        scope.launch { rowTop.stop() }
                        scope.launch { lift.animateTo(1f, spring(0.6f, 500f)) }
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    },
                    onDrag = { change, amount ->
                        change.consume()
                        session.fingerPx += amount.y        // 세로로만 움직인다
                        recompute()
                    },
                    onDragEnd = { finish() },
                    onDragCancel = { finish() }
                )
            }
    ) {
        val art = rememberRemoteImage(e.art)
        Row(
            Modifier.fillMaxSize().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BasicText(
                (target + 1).toString(), Modifier.width(22.dp),
                style = TextStyle(GlassTheme.Content.copy(0.5f), 11.sp, textAlign = TextAlign.End), maxLines = 1
            )
            Spacer(Modifier.width(10.dp))
            Box(Modifier.size(38.dp).clip(RoundedCornerShape(8.dp)).background(Color.White.copy(0.08f))) {
                if (art != null) Image(art, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
            Column(Modifier.weight(1f).padding(horizontal = 10.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(2.dp)) {
                BasicText(
                    e.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = TextStyle(GlassTheme.Content, 13.sp, FontWeight.SemiBold)
                )
                BasicText(
                    e.channel, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = TextStyle(GlassTheme.Content.copy(0.6f), 11.sp)
                )
            }
            BasicText(e.duration, style = TextStyle(GlassTheme.Content.copy(0.55f), 11.sp), maxLines = 1)
        }
    }

    // ── Context Menu (Liquid Glass): 드래그 중/삭제 중에는 숨기고, 놓으면 행 옆에 다시 나타난다 ──
    if (!dragging && !e.leaving) {
        EditMenu(
            e = e, backdrop = backdrop, c = c, viewW = viewW, viewH = viewH,
            restTop = { rowTop.value }, accent = accent
        )
    }
}

@Composable
private fun EditMenu(
    e: EditState, backdrop: Backdrop, c: GlassController, viewW: Float, viewH: Float,
    restTop: () -> Float, accent: Color
) {
    val pop = rememberPopIn()
    Column(
        Modifier
            .offset {
                val sc = c.cssScroll(density)
                val rowTopPx = (restTop() - sc) * density
                val rowH = e.h * density
                val mw = MENU_W * density
                val mh = MENU_H * density
                val gap = 8f * density
                // 행의 오른쪽 아래에 붙이고, 아래 공간(도크 위)이 모자라면 행 위쪽으로 뒤집는다
                val x = ((e.x + e.w) * density - mw - 10f * density).coerceIn(8f * density, (viewW - mw - 8f * density).coerceAtLeast(8f * density))
                val below = rowTopPx + rowH + gap
                val above = rowTopPx - mh - gap
                val y = when {
                    below + mh <= viewH - 130f * density -> below
                    above >= 70f * density -> above
                    else -> below.coerceAtMost(viewH - mh - 8f * density)
                }
                IntOffset(x.roundToInt(), y.roundToInt())
            }
            .width(MENU_W.dp)
            .editGlass(backdrop, 24, 16f, 32f, Color.Transparent) {
                val p = pop.value.coerceIn(0f, 1f)
                val s = popScale(p)
                scaleX = s; scaleY = s; alpha = p
            }
            .then(SwallowTouches)
            .padding(vertical = 8.dp)
    ) {
        EditMenuRow(
            label = e.favLabel,
            color = if (e.fav) accent else GlassTheme.Content,
            icon = {
                Canvas(Modifier.size(16.dp)) {
                    val col = if (e.fav) accent else GlassTheme.Content
                    if (e.fav) fillIcon(GlassIcons.Heart, 15f, col)
                    else strokeIcon(listOf(GlassIcons.Heart), 15f, col, 1.4f)
                }
            }
        ) { c.cmd { put("t", "edit"); put("a", "fav") } }
        Box(
            Modifier.padding(horizontal = 16.dp, vertical = 2.dp).fillMaxWidth().height(0.5.dp)
                .background(Color.White.copy(0.18f))
        )
        EditMenuRow(
            label = e.delLabel, color = GlassTheme.Danger,
            icon = {
                Canvas(Modifier.size(16.dp)) { strokeIcon(TrashPaths, 14f, GlassTheme.Danger, 1.4f) }
            }
        ) { c.cmd { put("t", "edit"); put("a", "del") } }
    }
}

@Composable
private fun EditMenuRow(label: String, color: Color, icon: @Composable () -> Unit, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().tap(onClick = onClick).padding(horizontal = 20.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) { icon() }
        Spacer(Modifier.width(12.dp))
        BasicText(label, style = TextStyle(color, 15.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
