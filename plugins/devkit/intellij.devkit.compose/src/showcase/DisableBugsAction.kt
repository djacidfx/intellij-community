// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.devkit.compose.showcase

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import org.jetbrains.compose.ui.tooling.preview.Preview
import com.intellij.devkit.compose.showcase.timber.TRIPLE_T_MAX_FRAME_DELTA_SECONDS
import com.intellij.devkit.compose.showcase.timber.TRIPLE_T_NANOS_PER_SECOND
import com.intellij.devkit.compose.showcase.timber.TripleT2D
import com.intellij.devkit.compose.showcase.timber.TripleTSideways2D
import com.intellij.devkit.compose.showcase.timber.TripleTSidewaysDirection
import com.intellij.devkit.compose.showcase.timber.interpolateKeyframes
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.NlsContexts
import com.intellij.ui.PaintingParent.Wrapper
import kotlinx.coroutines.delay
import org.jetbrains.jewel.bridge.compose
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.CheckboxRow
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import java.awt.Dimension
import javax.swing.Action
import javax.swing.JComponent
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private val TTT_WALK_DURATIONS = floatArrayOf(0.2f, 0.16f, 0.2f, 0.16f)
private val TTT_APPROACH_STEP_DURATIONS = floatArrayOf(0.35f, 0.35f, 0.35f, 0.35f)
private val TTT_SIDE_BODY_BOB = floatArrayOf(0f, -0.02f, 0f, -0.02f)
private val TTT_SIDE_BODY_ROT = floatArrayOf(4.6f, 3.1f, 4.3f, 2.8f)
private val TTT_SIDE_VIS_PALM_X = floatArrayOf(0.25f, 0.1f, -0.1f, 0.1f)
private val TTT_SIDE_VIS_PALM_Y = floatArrayOf(0.2f, 0.35f, 0.4f, 0.35f)
private val TTT_SIDE_REAR_PALM_X = floatArrayOf(0.12f, 0.28f, 0.44f, 0.24f)
private val TTT_SIDE_REAR_PALM_Y = floatArrayOf(0.5f, 0.35f, 0.2f, 0.33f)
private val TTT_SIDE_VIS_FOOT_X = floatArrayOf(0.2f, 0.06f, -0.12f, -0.01f)
private val TTT_SIDE_VIS_FOOT_Y = floatArrayOf(1.1f, 1.08f, 1.11f, 0.99f)
private val TTT_SIDE_REAR_FOOT_X = floatArrayOf(-0.14f, 0f, 0.2f, 0.07f)
private val TTT_SIDE_REAR_FOOT_Y = floatArrayOf(1.11f, 0.99f, 1.1f, 1.08f)
private val TTT_SIDE_VIS_LEG_BEND = floatArrayOf(-0.16f, -0.62f, -0.12f, -0.58f)
private val TTT_SIDE_REAR_LEG_BEND = floatArrayOf(-0.12f, -0.54f, -0.16f, -0.62f)
private val TTT_SIDE_VIS_ARM_BEND = floatArrayOf(0.18f, 0.28f, -0.08f, 0.08f)
private val TTT_SIDE_REAR_ARM_BEND = floatArrayOf(0.08f, 0.22f, -0.12f, 0.05f)
private val TTT_SIDE_PROP_SWING = floatArrayOf(0.02f, 0.07f, 0.12f, 0.08f)

private enum class TttAnimPhase {
  HIDDEN,
  APPROACHING,
  BOX_ARRIVING,
  TTT_REACHING,
  TTT_TAKING_PROP,
  TTT_KICKING,
  TTT_DEPARTING,
  TTT_WALKING_LEFT,
  TTT_TURNING_SMILING,
  TTT_BEATING,
  TTT_WAITING,
  TTT_TURNING_FRONT,
  TTT_FLYING_UP,
}

private val ANIMATION_SEQUENCE = listOf(
  TttAnimPhase.APPROACHING,
  TttAnimPhase.BOX_ARRIVING,
  TttAnimPhase.TTT_REACHING,
  TttAnimPhase.TTT_TAKING_PROP,
  TttAnimPhase.TTT_KICKING,
  TttAnimPhase.TTT_DEPARTING,
  TttAnimPhase.TTT_WALKING_LEFT,
  TttAnimPhase.TTT_TURNING_SMILING,
  TttAnimPhase.TTT_BEATING,
  TttAnimPhase.TTT_WAITING,
  TttAnimPhase.TTT_TURNING_FRONT,
  TttAnimPhase.TTT_FLYING_UP,
)

private val PHASE_DURATIONS = mapOf(
  TttAnimPhase.APPROACHING to 2.5f,
  TttAnimPhase.BOX_ARRIVING to 0.5f,
  TttAnimPhase.TTT_REACHING to 1.0f,
  TttAnimPhase.TTT_TAKING_PROP to 0.8f,
  TttAnimPhase.TTT_KICKING to 1.2f,
  TttAnimPhase.TTT_DEPARTING to 2.0f,
  TttAnimPhase.TTT_WALKING_LEFT to 5.0f,
  TttAnimPhase.TTT_TURNING_SMILING to 1.3f,
  TttAnimPhase.TTT_BEATING to 0.84f,
  TttAnimPhase.TTT_WAITING to 0.5f,
  TttAnimPhase.TTT_TURNING_FRONT to 3.3f,
  TttAnimPhase.TTT_FLYING_UP to 5f,
)

private fun easeOut(t: Float): Float {
  val c = t.coerceIn(0f, 1f)
  return 1f - (1f - c) * (1f - c)
}

private fun easeInOut(t: Float): Float {
  val c = t.coerceIn(0f, 1f)
  return c * c * (3f - 2f * c)
}

private fun lerpOffset(a: Offset, b: Offset, t: Float) = Offset(lerp(a.x, b.x, t), lerp(a.y, b.y, t))

private fun Offset.rotateDegrees(degrees: Float): Offset {
  val rad = degrees * (PI.toFloat() / 180f)
  val c = cos(rad)
  val s = sin(rad)
  return Offset(x * c - y * s, x * s + y * c)
}

private val TTT_BEAT_PLANTED_VISIBLE_FOOT = Offset(0.14f, 1.08f)
private val TTT_BEAT_PLANTED_REAR_FOOT = Offset(-0.08f, 1.09f)

private fun advanceWalkCycle(ordinal: Int, time: Float, durations: FloatArray = TTT_WALK_DURATIONS): Pair<Int, Float> {
  val duration = durations[ordinal]
  val next = time.coerceAtLeast(0f)
  return if (next >= duration) Pair((ordinal + 1) % durations.size, next - duration)
  else Pair(ordinal, next)
}

internal class DisableBugsAction : DumbAwareAction() {
  override fun getActionUpdateThread() = ActionUpdateThread.BGT
  override fun update(e: AnActionEvent) {
    e.presentation.isEnabledAndVisible = e.project != null
  }

  override fun actionPerformed(e: AnActionEvent) {
    DisableBugsDialog(e.project, e.presentation.text).show()
  }
}

private class DisableBugsDialog(project: Project?, @NlsContexts.DialogTitle title: String) :
  DialogWrapper(project, null, true, IdeModalityType.MODELESS, false) {
  init {
    this.title = title
    isResizable = false
    init()
  }

  override fun createCenterPanel(): JComponent =
    Wrapper(compose { DisableBugsPanel(onClose = { close(OK_EXIT_CODE) }) }).apply {
      minimumSize = Dimension(550, 280)
      preferredSize = Dimension(750, 300)
    }

  override fun createActions(): Array<Action> = emptyArray()
}

@Composable
private fun RandomProgressBar(progress: Float) {
  val trackColor = JewelTheme.globalColors.borders.normal
  val fillColor = JewelTheme.globalColors.outlines.focused
  Box(
    Modifier
      .fillMaxWidth()
      .height(6.dp)
      .background(trackColor, RoundedCornerShape(3.dp)),
  ) {
    Box(
      Modifier
        .fillMaxHeight()
        .fillMaxWidth(progress.coerceIn(0f, 1f))
        .background(fillColor, RoundedCornerShape(3.dp)),
    )
  }
}

@Composable
private fun DisableBugsPanel(onClose: () -> Unit) {
  var checkboxChecked by remember { mutableStateOf(false) }
  var bugDisablingStarted by remember { mutableStateOf(false) }
  var progressValue by remember { mutableFloatStateOf(0f) }

  var animPhase by remember { mutableStateOf(TttAnimPhase.HIDDEN) }

  LaunchedEffect(bugDisablingStarted) {
    if (!bugDisablingStarted) return@LaunchedEffect
    delay(3.seconds)
    animPhase = TttAnimPhase.APPROACHING
  }

  LaunchedEffect(bugDisablingStarted) {
    if (!bugDisablingStarted) return@LaunchedEffect
    val rng = kotlin.random.Random(System.currentTimeMillis())
    var p = 0f
    while (p < 0.98f) {
      delay(rng.nextLong(800, 2000).milliseconds)
      p = (p + rng.nextFloat() * 0.065f + 0.003f).coerceAtMost(0.98f)
      progressValue = p
    }
  }

  Box(Modifier.fillMaxSize().padding(16.dp)) {
    Column(Modifier.align(Alignment.CenterStart)) {
      CheckboxRow(
        text = "Disable all bugs in the IDE",
        checked = checkboxChecked,
        enabled = !bugDisablingStarted,
        onCheckedChange = {
          if (!bugDisablingStarted) {
            checkboxChecked = it
            bugDisablingStarted = it
          }
        },
        modifier = Modifier,
        textStyle = TextStyle(fontSize = 18.sp),
      )
      AnimatedVisibility(
        visible = bugDisablingStarted && checkboxChecked,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
      ) {
        Column {
          Spacer(Modifier.height(12.dp))
          Text("Disabling bugs,\nPlease do not close this window or turn off your device.", style = TextStyle(fontSize = 14.sp))
          Spacer(Modifier.height(10.dp))
          RandomProgressBar(progress = progressValue)
        }
      }
    }

    Row(
      Modifier.align(Alignment.BottomStart),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      DefaultButton(onClick = onClose, enabled = !bugDisablingStarted) { Text("Save") }
      OutlinedButton(onClick = onClose) { Text("Cancel") }
    }

    if (animPhase != TttAnimPhase.HIDDEN) {
      DisableBugsAnimation(
        phase = animPhase,
        onPhaseChange = { animPhase = it },
        onCheckboxOff = { checkboxChecked = false },
        onClose = onClose,
      )
    }
  }
}

private fun DrawScope.drawCardboardBox(
  centerX: Float,
  bottomY: Float,
  width: Float,
  height: Float,
  rotation: Float = 0f,
) {
  val pivot = Offset(centerX, bottomY - height / 2f)
  withTransform({ rotate(rotation, pivot) }) {
    val left = centerX - width / 2f
    val top = bottomY - height
    val right = centerX + width / 2f
    val strokeW = 2.dp.toPx()
    val brown = Color(0xFFBF8040)
    val dark = Color(0xFF7A5020)

    // Main face
    drawRect(color = brown, topLeft = Offset(left, top), size = Size(width, height))

    // Corrugated texture: horizontal ridge lines simulating flutes
    val ridgeCount = 15
    val ridgeStride = height / ridgeCount
    for (i in 1 until ridgeCount) {
      val y = top + ridgeStride * i
      val alpha = if (i % 3 == 2) 0.12f else 0.045f
      drawLine(
        color = dark.copy(alpha = alpha),
        start = Offset(left + strokeW, y),
        end = Offset(right - strokeW, y),
        strokeWidth = ridgeStride * 0.38f,
      )
    }

    // Surface highlight (light patch for 3-D look)
    drawRect(
      color = Color(0xFFCB9550).copy(alpha = 0.28f),
      topLeft = Offset(left + width * 0.54f, top + height * 0.08f),
      size = Size(width * 0.36f, height * 0.52f),
    )

    // Outline
    drawRect(
      color = dark, topLeft = Offset(left, top), size = Size(width, height),
      style = Stroke(width = strokeW, join = StrokeJoin.Round),
    )

    // Box flap cross lines
    drawLine(color = dark.copy(alpha = 0.4f), start = Offset(left, top), end = Offset(right, bottomY), strokeWidth = strokeW * 0.6f)
    drawLine(color = dark.copy(alpha = 0.4f), start = Offset(right, top), end = Offset(left, bottomY), strokeWidth = strokeW * 0.6f)
    val midY = (top + bottomY) / 2f
    drawLine(color = dark.copy(alpha = 0.3f), start = Offset(left, midY), end = Offset(right, midY), strokeWidth = strokeW)

    // Note (cream paper, tilted, off-center)
    val noteW = width * 0.38f
    val noteH = height * 0.46f
    val noteCX = left + width * 0.62f
    val noteCY = top + height * 0.36f
    withTransform({ rotate(-11f, Offset(noteCX, noteCY)) }) {
      val noteLeft = noteCX - noteW / 2f
      val noteTop = noteCY - noteH / 2f
      // Drop shadow
      drawRect(
        color = dark.copy(alpha = 0.18f),
        topLeft = Offset(noteLeft + 1.8.dp.toPx(), noteTop + 1.8.dp.toPx()),
        size = Size(noteW, noteH),
      )
      // Paper body
      drawRect(color = Color(0xFFF6F0DA), topLeft = Offset(noteLeft, noteTop), size = Size(noteW, noteH))
      // Paper outline
      drawRect(
        color = dark.copy(alpha = 0.28f),
        topLeft = Offset(noteLeft, noteTop),
        size = Size(noteW, noteH),
        style = Stroke(width = 0.7.dp.toPx()),
      )
      // Handwriting: 5 rows of wavy lines
      val inkColor = Color(0xFF1A3A99).copy(alpha = 0.72f)
      val inkStroke = Stroke(width = 1.0.dp.toPx(), cap = StrokeCap.Round)
      val rowFractions = floatArrayOf(0.62f, 0.80f, 0.52f, 0.75f, 0.44f)
      val rowSpacing = noteH / 6.4f
      val rowStartX = noteLeft + noteW * 0.09f
      for (row in 0 until 5) {
        val baseY = noteTop + rowSpacing * (row + 1.3f)
        val rowLen = noteW * rowFractions[row]
        val path = Path()
        val segLen = 3.2.dp.toPx()
        val segs = (rowLen / segLen).toInt().coerceAtLeast(3)
        val actualSegW = rowLen / segs
        path.moveTo(rowStartX, baseY)
        for (s in 1..segs) {
          val x = rowStartX + s * actualSegW
          val yOff = sin(s * 0.85f + row * 1.7f) * 1.1.dp.toPx()
          path.lineTo(x, baseY + yOff)
        }
        drawPath(path = path, color = inkColor, style = inkStroke)
      }
    }
  }
}

@Composable
private fun DisableBugsAnimation(
  phase: TttAnimPhase,
  onPhaseChange: (TttAnimPhase) -> Unit,
  onCheckboxOff: () -> Unit,
  onClose: () -> Unit,
) {
  var phaseElapsed by remember { mutableFloatStateOf(0f) }
  var bodySizeFraction by remember { mutableFloatStateOf(0.05f) }
  var rightPalmWorldPos by remember { mutableStateOf(Offset(0.66f, 0.09f)) }
  var propBeatProgress by remember { mutableFloatStateOf(0.04f) }
  var mouthCurveDir by remember { mutableFloatStateOf(-0.06f) }
  var mouthCurveProgress by remember { mutableFloatStateOf(1f) }
  var isFront by remember { mutableStateOf(true) }
  var tttXOffset by remember { mutableStateOf(0.dp) }
  var tttYOffset by remember { mutableStateOf(0.dp) }
  var walkOrdinal by remember { mutableIntStateOf(0) }
  var walkTime by remember { mutableFloatStateOf(0f) }
  var boxVisible by remember { mutableStateOf(false) }
  var boxYFraction by remember { mutableFloatStateOf(0f) }
  var boxXOffset by remember { mutableFloatStateOf(0f) }
  var boxRotation by remember { mutableFloatStateOf(0f) }
  var approachStepOrdinal by remember { mutableIntStateOf(0) }
  var approachStepTime by remember { mutableFloatStateOf(0f) }
  var kickRightFoot by remember { mutableStateOf(Offset(0.11f, 1.08f)) }
  var sidewaysBeatVisiblePalm by remember { mutableStateOf(Offset(0.11f, 0.35f)) }
  var sidewaysBeatPropProgress by remember { mutableFloatStateOf(0.04f) }
  var sidewaysBeatBodyRot by remember { mutableFloatStateOf(3.8f) }
  var sidewaysBeatVisibleArmBend by remember { mutableFloatStateOf(0.06f) }
  var sidewaysBeatRearArmBend by remember { mutableFloatStateOf(0.08f) }
  var sidewaysBeatRearPalm by remember { mutableStateOf(Offset(0.17f, 0.48f)) }
  var flyingUpView by remember { mutableIntStateOf(0) }

  val currentPhaseState = rememberUpdatedState(phase)
  val onPhaseChangeState = rememberUpdatedState(onPhaseChange)

  LaunchedEffect(Unit) {
    var lastNanos = withFrameNanos { it }
    while (true) {
      withFrameNanos { nanos ->
        val delta = ((nanos - lastNanos) / TRIPLE_T_NANOS_PER_SECOND)
          .coerceIn(0f, TRIPLE_T_MAX_FRAME_DELTA_SECONDS)
        lastNanos = nanos

        val currentPhase = currentPhaseState.value
        val duration = PHASE_DURATIONS[currentPhase] ?: return@withFrameNanos
        val newElapsed = phaseElapsed + delta
        val t = (newElapsed / duration).coerceIn(0f, 1f)
        val easedT = easeInOut(t)

        when (currentPhase) {
          TttAnimPhase.APPROACHING -> {
            val (nextOrd, nextTime) = advanceWalkCycle(approachStepOrdinal, approachStepTime + delta, TTT_APPROACH_STEP_DURATIONS)
            approachStepOrdinal = nextOrd
            approachStepTime = nextTime
            isFront = true
            bodySizeFraction = lerp(0.05f, 0.58f, easeOut(t))
          }
          TttAnimPhase.BOX_ARRIVING -> {
            isFront = true
            bodySizeFraction = 0.58f
            boxVisible = true
            boxYFraction = easeOut(t)
          }
          TttAnimPhase.TTT_REACHING -> {
            rightPalmWorldPos = Offset(lerp(0.66f, 0.4f, easedT), lerp(0.1f, 0.58f, easedT))
            propBeatProgress = 0.746f
          }
          TttAnimPhase.TTT_TAKING_PROP -> {
            rightPalmWorldPos = Offset(lerp(0.4f, 0.66f, easedT), lerp(0.58f, 0.1f, easedT))
            propBeatProgress = lerp(0.746f, 0.15f, easedT)
          }
          TttAnimPhase.TTT_KICKING -> {
            if (newElapsed < 0.7f) {
              val kt = easeInOut(newElapsed / 0.7f)
              rightPalmWorldPos = Offset(lerp(0.66f, 0.52f, kt), lerp(0.09f, 0.22f, kt))
              kickRightFoot = lerpOffset(Offset(0.11f, 1.08f), Offset(0.55f, 0.55f), kt)
              boxXOffset = lerp(0f, 60f, kt)
              boxRotation = lerp(0f, 18f, kt)
            }
            else {
              val ft = easeInOut((newElapsed - 0.7f) / 0.5f)
              kickRightFoot = lerpOffset(Offset(0.55f, 0.55f), Offset(0.11f, 1.08f), ft)
              boxXOffset = lerp(60f, 500f, ft)
              boxRotation = lerp(18f, 95f, ft)
              boxYFraction = lerp(1.0f, -0.5f, ft)
              if (newElapsed > 1.075f) boxVisible = false
            }
          }
          TttAnimPhase.TTT_DEPARTING -> {
            val (nextOrd, nextTime) = advanceWalkCycle(approachStepOrdinal, approachStepTime + delta, TTT_APPROACH_STEP_DURATIONS)
            approachStepOrdinal = nextOrd
            approachStepTime = nextTime
            isFront = false
            bodySizeFraction = lerp(0.58f, 0.50f, easeInOut(t))
            val armT = easeInOut((newElapsed / 0.4f).coerceIn(0f, 1f))
            rightPalmWorldPos = Offset(lerp(0.52f, 0.42f, armT), lerp(0.22f, 0.38f, armT))
          }
          TttAnimPhase.TTT_WALKING_LEFT -> {
            val (nextOrd, nextTime) = advanceWalkCycle(walkOrdinal, walkTime + delta)
            walkOrdinal = nextOrd
            walkTime = nextTime
            tttXOffset = lerp(0f, -440f, t).dp
            isFront = false
          }
          TttAnimPhase.TTT_TURNING_SMILING -> {
            sidewaysBeatVisiblePalm = lerpOffset(Offset(0.10f, 0.35f), Offset(0.44f, -0.06f), easedT)
            sidewaysBeatRearPalm = lerpOffset(Offset(0.28f, 0.33f), Offset(0.17f, 0.48f), easedT)
            sidewaysBeatBodyRot = lerp(3.5f, 3.8f, easedT)
            sidewaysBeatVisibleArmBend = lerp(0.18f, 0.06f, easedT)
            sidewaysBeatRearArmBend = 0.08f
            sidewaysBeatPropProgress = lerp(0.08f, 0.35f, easedT)
          }
          TttAnimPhase.TTT_BEATING -> {
            val windPalm = Offset(-0.2f, -0.3f)
            val strikePalm = Offset(0.56f, 0.2f)
            val recoverPalm = Offset(0.44f, -0.06f)
            val beatT = newElapsed.coerceIn(0f, 0.84f)
            when {
              beatT < 0.24f -> {
                val bT = easeInOut(beatT / 0.24f)
                sidewaysBeatVisiblePalm = lerpOffset(recoverPalm, windPalm, bT)
                sidewaysBeatRearPalm = lerpOffset(Offset(0.17f, 0.48f), Offset(0.14f, 0.5f), bT)
                sidewaysBeatBodyRot = lerp(1.8f, -3.2f, bT)
                sidewaysBeatVisibleArmBend = lerp(0.28f, 0.42f, bT)
                sidewaysBeatRearArmBend = lerp(0.08f, 0.14f, bT)
                sidewaysBeatPropProgress = 0f
              }
              beatT < 0.42f -> {
                val bT = easeInOut((beatT - 0.24f) / 0.18f)
                sidewaysBeatVisiblePalm = lerpOffset(windPalm, strikePalm, bT)
                sidewaysBeatRearPalm = lerpOffset(Offset(0.14f, 0.5f), Offset(0.2f, 0.45f), bT)
                sidewaysBeatBodyRot = lerp(-3.2f, 7.2f, bT)
                sidewaysBeatVisibleArmBend = lerp(0.42f, -0.14f, bT)
                sidewaysBeatRearArmBend = lerp(0.14f, -0.02f, bT)
                sidewaysBeatPropProgress = lerp(0f, 0.7f, bT)
              }
              beatT < 0.66f -> {
                val bT = easeInOut((beatT - 0.42f) / 0.24f)
                sidewaysBeatVisiblePalm = lerpOffset(strikePalm, recoverPalm, bT)
                sidewaysBeatRearPalm = lerpOffset(Offset(0.2f, 0.45f), Offset(0.17f, 0.48f), bT)
                sidewaysBeatBodyRot = lerp(7.2f, 3.8f, bT)
                sidewaysBeatVisibleArmBend = lerp(-0.14f, 0.06f, bT)
                sidewaysBeatRearArmBend = lerp(-0.02f, 0.08f, bT)
                sidewaysBeatPropProgress = lerp(0.7f, 0.35f, bT)
              }
              else -> {
                sidewaysBeatVisiblePalm = recoverPalm
                sidewaysBeatRearPalm = Offset(0.17f, 0.48f)
                sidewaysBeatBodyRot = 3.8f
                sidewaysBeatVisibleArmBend = 0.06f
                sidewaysBeatRearArmBend = 0.08f
                sidewaysBeatPropProgress = 0.35f
              }
            }
          }
          TttAnimPhase.TTT_WAITING -> {}
          TttAnimPhase.TTT_TURNING_FRONT -> {
            isFront = newElapsed >= 0.3f
            when {
              newElapsed >= 1.6f -> {
                val laughCycleT = ((newElapsed - 1.6f) % 0.3f) / 0.3f
                mouthCurveDir = 6f
                mouthCurveProgress = (0.65f - 0.15f * sin(laughCycleT * PI.toFloat())).coerceAtLeast(0.5f)
              }
              newElapsed >= 1.3f -> {
                val transitionT = easeInOut((newElapsed - 1.3f) / 0.3f)
                mouthCurveDir = lerp(-0.06f, 6f, transitionT)
                mouthCurveProgress = lerp(1f, 0.65f, transitionT)
              }
            }
          }
          TttAnimPhase.TTT_FLYING_UP -> {
            tttYOffset = lerp(0f, -600f, easeInOut(t)).dp
            flyingUpView = (newElapsed / 0.15f).toInt() % 4  // 0=front, 1=left, 2=back, 3=right
            isFront = flyingUpView == 0
          }
          else -> {}
        }

        if (newElapsed >= duration) {
          val nextPhase = ANIMATION_SEQUENCE.getOrNull(ANIMATION_SEQUENCE.indexOf(currentPhase) + 1)
          if (nextPhase != null) {
            onPhaseChangeState.value(nextPhase)
            phaseElapsed = newElapsed - duration
          }
        }
        else {
          phaseElapsed = newElapsed
        }
      }
    }
  }

  LaunchedEffect(phase) {
    when (phase) {
      TttAnimPhase.TTT_WAITING -> onCheckboxOff()
      TttAnimPhase.TTT_FLYING_UP -> {
        delay(7.seconds)
        onClose()
      }
      else -> {}
    }
  }

  val tttSize = 180.dp
  val isSideways = phase == TttAnimPhase.TTT_WALKING_LEFT
  val isFlyingLeft = phase == TttAnimPhase.TTT_FLYING_UP && flyingUpView == 1
  val isFlyingRight = phase == TttAnimPhase.TTT_FLYING_UP && flyingUpView == 3
  val isSidewaysLeft = phase == TttAnimPhase.TTT_TURNING_SMILING ||
                       phase == TttAnimPhase.TTT_BEATING || phase == TttAnimPhase.TTT_WAITING ||
                       (phase == TttAnimPhase.TTT_TURNING_FRONT && !isFront) ||
                       isFlyingLeft
  val isBeat = phase == TttAnimPhase.TTT_BEATING ||
               phase == TttAnimPhase.TTT_WAITING ||
               phase == TttAnimPhase.TTT_TURNING_SMILING
  val isApproach = phase == TttAnimPhase.APPROACHING || phase == TttAnimPhase.TTT_DEPARTING
  val isKicking = phase == TttAnimPhase.TTT_KICKING

  Box(Modifier.fillMaxSize()) {
    Box(
      Modifier
        .align(Alignment.CenterEnd)
        .offset { IntOffset((tttXOffset - tttSize * 0.4f).roundToPx(), tttYOffset.roundToPx()) },
    ) {
      when {
        isSideways -> {
          val walkT = (walkTime / TTT_WALK_DURATIONS[walkOrdinal]).coerceIn(0f, 1f)
          val walkEased = easeInOut(walkT)
          TripleTSideways2D(
            modifier = Modifier.size(tttSize),
            direction = TripleTSidewaysDirection.LEFT,
            bodySizeFraction = bodySizeFraction,
            bodyOffset = Offset(0f, interpolateKeyframes(TTT_SIDE_BODY_BOB, walkOrdinal, walkEased)),
            visiblePalmWorldPos = Offset(
              interpolateKeyframes(TTT_SIDE_VIS_PALM_X, walkOrdinal, walkEased),
              interpolateKeyframes(TTT_SIDE_VIS_PALM_Y, walkOrdinal, walkEased),
            ),
            rearPalmWorldPos = Offset(
              interpolateKeyframes(TTT_SIDE_REAR_PALM_X, walkOrdinal, walkEased),
              interpolateKeyframes(TTT_SIDE_REAR_PALM_Y, walkOrdinal, walkEased),
            ),
            visibleFootWorldPos = Offset(
              interpolateKeyframes(TTT_SIDE_VIS_FOOT_X, walkOrdinal, walkEased),
              interpolateKeyframes(TTT_SIDE_VIS_FOOT_Y, walkOrdinal, walkEased),
            ),
            rearFootWorldPos = Offset(
              interpolateKeyframes(TTT_SIDE_REAR_FOOT_X, walkOrdinal, walkEased),
              interpolateKeyframes(TTT_SIDE_REAR_FOOT_Y, walkOrdinal, walkEased),
            ),
            visibleArmBendDir = interpolateKeyframes(TTT_SIDE_VIS_ARM_BEND, walkOrdinal, walkEased),
            rearArmBendDir = interpolateKeyframes(TTT_SIDE_REAR_ARM_BEND, walkOrdinal, walkEased),
            visibleLegBendDir = interpolateKeyframes(TTT_SIDE_VIS_LEG_BEND, walkOrdinal, walkEased),
            rearLegBendDir = interpolateKeyframes(TTT_SIDE_REAR_LEG_BEND, walkOrdinal, walkEased),
            bodyRotation = interpolateKeyframes(TTT_SIDE_BODY_ROT, walkOrdinal, walkEased),
            propBeatProgress = interpolateKeyframes(TTT_SIDE_PROP_SWING, walkOrdinal, walkEased),
          )
        }
        isSidewaysLeft -> {
          TripleTSideways2D(
            modifier = Modifier.size(tttSize),
            direction = TripleTSidewaysDirection.LEFT,
            bodySizeFraction = bodySizeFraction,
            visiblePalmWorldPos = if (isBeat) sidewaysBeatVisiblePalm else Offset(0.44f, -0.06f),
            rearPalmWorldPos = if (isBeat) sidewaysBeatRearPalm else Offset(0.17f, 0.48f),
            visibleFootWorldPos = if (isBeat) TTT_BEAT_PLANTED_VISIBLE_FOOT.rotateDegrees(-sidewaysBeatBodyRot) else Offset(0.06f, 1.08f),
            rearFootWorldPos = if (isBeat) TTT_BEAT_PLANTED_REAR_FOOT.rotateDegrees(-sidewaysBeatBodyRot) else Offset(0.0f, 1.09f),
            visibleArmBendDir = if (isBeat) sidewaysBeatVisibleArmBend else 0.06f,
            rearArmBendDir = if (isBeat) sidewaysBeatRearArmBend else 0.08f,
            visibleLegBendDir = -0.12f,
            rearLegBendDir = -0.1f,
            bodyRotation = if (isBeat) sidewaysBeatBodyRot else 3.8f,
            mouthCurveDir = mouthCurveDir,
            mouthCurveProgress = mouthCurveProgress,
            propBeatProgress = if (isBeat) sidewaysBeatPropProgress else 0.35f,
          )
        }
        isFlyingRight -> {
          TripleTSideways2D(
            modifier = Modifier.size(tttSize),
            direction = TripleTSidewaysDirection.RIGHT,
            bodySizeFraction = bodySizeFraction,
            visiblePalmWorldPos = Offset(0.44f, -0.06f),
            rearPalmWorldPos = Offset(0.17f, 0.48f),
            visibleArmBendDir = 0.06f,
            rearArmBendDir = 0.08f,
            visibleLegBendDir = -0.12f,
            rearLegBendDir = -0.1f,
            bodyRotation = 3.8f,
            mouthCurveDir = mouthCurveDir,
            mouthCurveProgress = mouthCurveProgress,
            propBeatProgress = 0.35f,
          )
        }
        else -> {
          val approachStepT = easeInOut((approachStepTime / TTT_APPROACH_STEP_DURATIONS[approachStepOrdinal]).coerceIn(0f, 1f))
          val approachBodyOffset = when (approachStepOrdinal) {
            0 -> lerpOffset(Offset(0.02f, 0.02f), Offset(-0.02f, 0.02f), approachStepT)
            1 -> Offset(-0.02f, 0.02f)
            2 -> lerpOffset(Offset(-0.02f, 0.02f), Offset(0.02f, 0.02f), approachStepT)
            else -> Offset(0.02f, 0.02f)
          }
          val approachLeftFoot = when (approachStepOrdinal) {
            0 -> lerpOffset(Offset(-0.15f, 1.06f), Offset(-0.08f, 1.12f), approachStepT)
            1 -> Offset(-0.08f, 1.12f)
            2 -> lerpOffset(Offset(-0.08f, 1.12f), Offset(-0.15f, 1.06f), approachStepT)
            else -> Offset(-0.15f, 1.06f)
          }
          val approachRightFoot = when (approachStepOrdinal) {
            0 -> lerpOffset(Offset(0.08f, 1.12f), Offset(0.15f, 1.06f), approachStepT)
            1 -> Offset(0.15f, 1.06f)
            2 -> lerpOffset(Offset(0.15f, 1.06f), Offset(0.08f, 1.12f), approachStepT)
            else -> Offset(0.08f, 1.12f)
          }
          val approachBodyRotation = if (approachStepOrdinal < 2) -3.5f else 3.5f

          val palmXFlip = if (isFront) 1f else -1f
          TripleT2D(
            modifier = Modifier.size(tttSize),
            bodySizeFraction = bodySizeFraction,
            isFront = isFront,
            bodyOffset = when {
              isApproach -> approachBodyOffset
              else -> Offset.Zero
            },
            leftPalmWorldPos = Offset(palmXFlip * -0.57f, 0.30f),
            rightPalmWorldPos = Offset(palmXFlip * rightPalmWorldPos.x, rightPalmWorldPos.y),
            leftFootWorldPos = if (isApproach) approachLeftFoot else Offset(-0.11f, 1.07f),
            rightFootWorldPos = if (isApproach) approachRightFoot else if (isKicking) kickRightFoot else Offset(0.11f, 1.08f),
            leftArmBendDir = 0.12f,
            rightArmBendDir = -0.14f,
            leftLegBendDir = 0.32f,
            rightLegBendDir = -0.32f,
            bodyRotation = if (isApproach) approachBodyRotation else 0f,
            mouthCurveDir = mouthCurveDir,
            mouthCurveProgress = mouthCurveProgress,
            propBeatProgress = propBeatProgress,
            propSide = when (phase) {
              TttAnimPhase.APPROACHING,
              TttAnimPhase.BOX_ARRIVING,
              TttAnimPhase.TTT_REACHING,
                -> 0
              else -> if (isFront) 1 else -1
            },
          )
        }
      }
    }

    if (boxVisible) {
      Canvas(Modifier.fillMaxSize()) {
        val boxW = 128.dp.toPx()
        val boxH = 78.dp.toPx()
        val tttSizePx = tttSize.toPx()
        val boxHomeX = size.width - tttSizePx * 0.4f
        val landedBottomY = size.height * 0.94f
        val topY = -boxH - 5.dp.toPx()
        drawCardboardBox(
          centerX = boxHomeX + boxXOffset,
          bottomY = lerp(topY, landedBottomY, boxYFraction),
          width = boxW,
          height = boxH,
          rotation = boxRotation,
        )
      }
    }
  }
}


@Preview
@Composable
private fun DisableBugsPanelPreview() {
  Box(Modifier.width(750.dp).height(300.dp)) {
    DisableBugsPanel(onClose = {})
  }
}