// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.devkit.compose.showcase.timber

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import org.jetbrains.compose.ui.tooling.preview.Preview
import androidx.compose.ui.util.lerp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private val TRIPLE_T_IDLE_DURATIONS = floatArrayOf(0.6f, 0.6f)
private val TRIPLE_T_BEATING_DURATIONS = floatArrayOf(0.24f, 0.18f, 0.24f, 0.18f)
private val TRIPLE_T_APPROACH_DURATIONS = floatArrayOf(0.35f, 0.35f, 0.35f, 0.35f)
private val TRIPLE_T_SCARE_DURATIONS = floatArrayOf(0.4f, 0.35f, 0.75f)
private val TRIPLE_T_SIDEWAYS_DURATIONS = floatArrayOf(0.2f, 0.16f, 0.2f, 0.16f)
private val TripleTSideVisiblePalmXTargets = floatArrayOf(0.25f, 0.1f, -0.1f, 0.1f)
private val TripleTSideVisiblePalmYTargets = floatArrayOf(0.2f, 0.35f, 0.4f, 0.35f)
private val TripleTSideRearPalmXTargets = floatArrayOf(0.12f, 0.28f, 0.44f, 0.24f)
private val TripleTSideRearPalmYTargets = floatArrayOf(0.5f, 0.35f, 0.2f, 0.33f)
private val TripleTSideVisibleFootXTargets = floatArrayOf(0.2f, 0.06f, -0.12f, -0.01f)
private val TripleTSideVisibleFootYTargets = floatArrayOf(1.1f, 1.08f, 1.11f, 0.99f)
private val TripleTSideRearFootXTargets = floatArrayOf(-0.14f, 0f, 0.2f, 0.07f)
private val TripleTSideRearFootYTargets = floatArrayOf(1.11f, 0.99f, 1.1f, 1.08f)
private val TripleTSideBodyBobTargets = floatArrayOf(0f, -0.02f, 0f, -0.02f)
private val TripleTSideBodyRotationTargets = floatArrayOf(4.6f, 3.1f, 4.3f, 2.8f)
private val TripleTSideVisibleLegBendTargets = floatArrayOf(-0.16f, -0.62f, -0.12f, -0.58f)
private val TripleTSideRearLegBendTargets = floatArrayOf(-0.12f, -0.54f, -0.16f, -0.62f)
private val TripleTSideVisibleArmBendTargets = floatArrayOf(0.18f, 0.28f, -0.08f, 0.08f)
private val TripleTSideRearArmBendTargets = floatArrayOf(0.08f, 0.22f, -0.12f, 0.05f)
private val TripleTSidePropSwingTargets = floatArrayOf(0.02f, 0.07f, 0.12f, 0.08f)

private fun easeInOut(t: Float) = t * t * (3f - 2f * t)

private fun Offset.lerpTo(other: Offset, t: Float) = Offset(lerp(x, other.x, t), lerp(y, other.y, t))

private fun Offset.rotateDegrees(degrees: Float): Offset {
  val radians = degrees * (PI.toFloat() / 180f)
  val cosAngle = cos(radians)
  val sinAngle = sin(radians)
  return Offset(
    x = x * cosAngle - y * sinAngle,
    y = x * sinAngle + y * cosAngle,
  )
}

private fun loopAnimation(
  phaseOrdinal: Int,
  phaseTime: Float,
  durations: FloatArray,
  phaseCount: Int,
): Pair<Int, Float> {
  val duration = durations[phaseOrdinal]
  val next = phaseTime.coerceAtLeast(0f)
  return if (next >= duration) {
    Pair((phaseOrdinal + 1) % phaseCount, next - duration)
  }
  else {
    Pair(phaseOrdinal, next)
  }
}

private enum class TripleTIdlePhase {
  LEFT_TO_RIGHT,
  RIGHT_TO_LEFT;
}

private enum class TripleTBeatPhase {
  WIND_UP,
  STRIKE,
  RECOVER,
  HOLD;
}

private enum class TripleTApproachPhase {
  STEP_LEFT,
  HOLD_LEFT,
  STEP_RIGHT,
  HOLD_RIGHT;
}

private enum class TripleTScarePhase {
  WIND_UP,
  RAISE,
  TREMBLE;
}

private enum class TripleTSidewaysWalkPhase {
  CONTACT_FRONT,
  PASSING,
  CONTACT_BACK,
  PASSING_OPPOSITE;
}

@Composable
fun TripleTIdle2D(modifier: Modifier = Modifier.fillMaxSize()) {
  var phase by remember { mutableStateOf(TripleTIdlePhase.LEFT_TO_RIGHT) }
  var phaseTime by remember { mutableFloatStateOf(0f) }

  LaunchedEffect(Unit) {
    runTripleTAnimationLoop { delta ->
      val (nextPhaseOrdinal, nextPhaseTime) = loopAnimation(
        phaseOrdinal = phase.ordinal,
        phaseTime = phaseTime + delta,
        durations = TRIPLE_T_IDLE_DURATIONS,
        phaseCount = TripleTIdlePhase.entries.size,
      )
      phase = TripleTIdlePhase.entries[nextPhaseOrdinal]
      phaseTime = nextPhaseTime
    }
  }

  val easedT = easeInOut((phaseTime / TRIPLE_T_IDLE_DURATIONS[phase.ordinal]).coerceIn(0f, 1f))
  val sway = when (phase) {
    TripleTIdlePhase.LEFT_TO_RIGHT -> lerp(-1f, 1f, easedT)
    TripleTIdlePhase.RIGHT_TO_LEFT -> lerp(1f, -1f, easedT)
  }

  TripleT2D(
    modifier = modifier,
    bodyOffset = Offset(0.01f * sway, 0f),
    leftPalmWorldPos = Offset(-0.57f, 0.6f + 0.01f * sway),
    rightPalmWorldPos = Offset(0.67f + 0.01f * sway, 0.08f),
    leftFootWorldPos = Offset(-0.11f, 1.07f),
    rightFootWorldPos = Offset(0.11f, 1.08f),
    leftArmBendDir = 0.16f,
    rightArmBendDir = -0.15f,
    leftLegBendDir = 0.03f,
    rightLegBendDir = -0.03f,
    bodyRotation = 1.1f * sway,
    mouthCurveDir = -0.06f,
    mouthCurveProgress = 1f,
    propBeatProgress = 0.03f,
    propSide = 1,
  )
}

@Composable
fun TripleTBeating2D(modifier: Modifier = Modifier.fillMaxSize()) {
  var phase by remember { mutableStateOf(TripleTBeatPhase.WIND_UP) }
  var phaseTime by remember { mutableFloatStateOf(0f) }

  LaunchedEffect(Unit) {
    runTripleTAnimationLoop { delta ->
      val (nextPhaseOrdinal, nextPhaseTime) = loopAnimation(
        phaseOrdinal = phase.ordinal,
        phaseTime = phaseTime + delta,
        durations = TRIPLE_T_BEATING_DURATIONS,
        phaseCount = TripleTBeatPhase.entries.size,
      )
      phase = TripleTBeatPhase.entries[nextPhaseOrdinal]
      phaseTime = nextPhaseTime
    }
  }

  val phaseProgress = (phaseTime / TRIPLE_T_BEATING_DURATIONS[phase.ordinal]).coerceIn(0f, 1f)
  val easedT = easeInOut(phaseProgress)
  val windPalm = Offset(0.72f, -0.05f)
  val strikePalm = Offset(0.64f, 0.17f)
  val rightPalm = when (phase) {
    TripleTBeatPhase.WIND_UP -> strikePalm.lerpTo(windPalm, easedT)
    TripleTBeatPhase.STRIKE -> windPalm.lerpTo(strikePalm, easedT)
    TripleTBeatPhase.RECOVER -> strikePalm.lerpTo(Offset(0.67f, 0.08f), easedT)
    TripleTBeatPhase.HOLD -> Offset(0.67f, 0.08f)
  }
  val leftPalm = when (phase) {
    TripleTBeatPhase.WIND_UP -> Offset(-0.58f, 0.61f).lerpTo(Offset(-0.61f, 0.57f), easedT)
    TripleTBeatPhase.STRIKE -> Offset(-0.61f, 0.57f).lerpTo(Offset(-0.54f, 0.66f), easedT)
    TripleTBeatPhase.RECOVER -> Offset(-0.54f, 0.66f).lerpTo(Offset(-0.58f, 0.61f), easedT)
    TripleTBeatPhase.HOLD -> Offset(-0.58f, 0.61f)
  }
  val propBeat = when (phase) {
    TripleTBeatPhase.WIND_UP -> 0f
    TripleTBeatPhase.STRIKE -> easedT
    TripleTBeatPhase.RECOVER -> 1f - easedT * 0.75f
    TripleTBeatPhase.HOLD -> 0.15f
  }
  val bodyRotation = when (phase) {
    TripleTBeatPhase.WIND_UP -> lerp(-3f, -8f, easedT)
    TripleTBeatPhase.STRIKE -> lerp(-8f, 4f, easedT)
    TripleTBeatPhase.RECOVER -> lerp(4f, -1.5f, easedT)
    TripleTBeatPhase.HOLD -> -1.5f
  }
  val bodyOffset = when (phase) {
    TripleTBeatPhase.WIND_UP -> Offset(-0.01f * easedT, -0.01f * easedT)
    TripleTBeatPhase.STRIKE -> Offset(0.02f * easedT, 0.01f * sin(phaseProgress * PI.toFloat()).coerceAtLeast(0f))
    TripleTBeatPhase.RECOVER -> Offset(0.02f * (1f - easedT), 0.01f * (1f - easedT))
    TripleTBeatPhase.HOLD -> Offset.Zero
  }

  TripleT2D(
    modifier = modifier,
    bodyOffset = bodyOffset,
    leftPalmWorldPos = leftPalm,
    rightPalmWorldPos = rightPalm,
    leftFootWorldPos = Offset(-0.11f, 1.08f),
    rightFootWorldPos = Offset(0.12f, 1.08f),
    leftArmBendDir = 0.12f,
    rightArmBendDir = -0.08f,
    leftLegBendDir = 0.02f,
    rightLegBendDir = -0.02f,
    bodyRotation = bodyRotation,
    mouthCurveDir = 4f,
    mouthCurveProgress = 0.79f - 0.1f * sin(phaseProgress * PI.toFloat()).coerceAtLeast(0f),
    mouthSizeMultiplier = 1f,
    propBeatProgress = propBeat,
    propSide = 1,
  )
}

@Composable
fun TripleTApproach2D(modifier: Modifier = Modifier.fillMaxSize()) {
  var phase by remember { mutableStateOf(TripleTApproachPhase.STEP_LEFT) }
  var phaseTime by remember { mutableFloatStateOf(0f) }
  var viewCycleTime by remember { mutableFloatStateOf(0f) }

  LaunchedEffect(Unit) {
    runTripleTAnimationLoop { delta ->
      val (nextPhaseOrdinal, nextPhaseTime) = loopAnimation(
        phaseOrdinal = phase.ordinal,
        phaseTime = phaseTime + delta,
        durations = TRIPLE_T_APPROACH_DURATIONS,
        phaseCount = TripleTApproachPhase.entries.size,
      )
      phase = TripleTApproachPhase.entries[nextPhaseOrdinal]
      phaseTime = nextPhaseTime
      viewCycleTime = (viewCycleTime + delta) % 6f
    }
  }

  val phaseProgress = (phaseTime / TRIPLE_T_APPROACH_DURATIONS[phase.ordinal]).coerceIn(0f, 1f)
  val easedT = easeInOut(phaseProgress)
  val leftStepBody = Offset(-0.02f, 0.02f)
  val rightStepBody = Offset(0.02f, 0.02f)
  val bodyOffset = when (phase) {
    TripleTApproachPhase.STEP_LEFT -> rightStepBody.lerpTo(leftStepBody, easedT)
    TripleTApproachPhase.HOLD_LEFT -> leftStepBody
    TripleTApproachPhase.STEP_RIGHT -> leftStepBody.lerpTo(rightStepBody, easedT)
    TripleTApproachPhase.HOLD_RIGHT -> rightStepBody
  }
  val leftFoot = when (phase) {
    TripleTApproachPhase.STEP_LEFT -> Offset(-0.15f, 1.06f).lerpTo(Offset(-0.08f, 1.12f), easedT)
    TripleTApproachPhase.HOLD_LEFT -> Offset(-0.08f, 1.12f)
    TripleTApproachPhase.STEP_RIGHT -> Offset(-0.08f, 1.12f).lerpTo(Offset(-0.15f, 1.06f), easedT)
    TripleTApproachPhase.HOLD_RIGHT -> Offset(-0.15f, 1.06f)
  }
  val rightFoot = when (phase) {
    TripleTApproachPhase.STEP_LEFT -> Offset(0.08f, 1.12f).lerpTo(Offset(0.15f, 1.06f), easedT)
    TripleTApproachPhase.HOLD_LEFT -> Offset(0.15f, 1.06f)
    TripleTApproachPhase.STEP_RIGHT -> Offset(0.15f, 1.06f).lerpTo(Offset(0.08f, 1.12f), easedT)
    TripleTApproachPhase.HOLD_RIGHT -> Offset(0.08f, 1.12f)
  }
  val bodyRotation = when (phase) {
    TripleTApproachPhase.STEP_LEFT, TripleTApproachPhase.HOLD_LEFT -> -3.5f
    TripleTApproachPhase.STEP_RIGHT, TripleTApproachPhase.HOLD_RIGHT -> 3.5f
  }
  val baseBodySizeFraction = 0.58f
  val initialBodySizeFraction = baseBodySizeFraction * 0.7f
  val isFront = viewCycleTime < 3f
  val viewPhaseProgress = easeInOut((viewCycleTime % 3f) / 3f)
  val bodySizeFraction = if (isFront) {
    lerp(initialBodySizeFraction, baseBodySizeFraction, viewPhaseProgress)
  }
  else {
    lerp(baseBodySizeFraction, initialBodySizeFraction, viewPhaseProgress)
  }

  TripleT2D(
    modifier = modifier,
    bodySizeFraction = bodySizeFraction,
    bodyOffset = bodyOffset,
    leftPalmWorldPos = Offset(-0.57f, 0.61f),
    rightPalmWorldPos = Offset(0.66f, 0.09f),
    leftFootWorldPos = leftFoot,
    rightFootWorldPos = rightFoot,
    leftArmBendDir = 0.12f,
    rightArmBendDir = -0.14f,
    leftLegBendDir = 0.32f,
    rightLegBendDir = -0.32f,
    bodyRotation = bodyRotation,
    mouthCurveDir = -0.06f,
    mouthCurveProgress = 1f,
    isFront = isFront,
    propBeatProgress = 0.04f,
    propSide = 1,
  )
}

@Composable
fun TripleTScare2D(modifier: Modifier = Modifier.fillMaxSize()) {
  var phase by remember { mutableStateOf(TripleTScarePhase.WIND_UP) }
  var phaseTime by remember { mutableFloatStateOf(0f) }

  LaunchedEffect(Unit) {
    runTripleTAnimationLoop { delta ->
      val (nextPhaseOrdinal, nextPhaseTime) = loopAnimation(
        phaseOrdinal = phase.ordinal,
        phaseTime = phaseTime + delta,
        durations = TRIPLE_T_SCARE_DURATIONS,
        phaseCount = TripleTScarePhase.entries.size,
      )
      phase = TripleTScarePhase.entries[nextPhaseOrdinal]
      phaseTime = nextPhaseTime
    }
  }

  val phaseProgress = (phaseTime / TRIPLE_T_SCARE_DURATIONS[phase.ordinal]).coerceIn(0f, 1f)
  val easedT = easeInOut(phaseProgress)
  val tremble = if (phase == TripleTScarePhase.TREMBLE) sin(phaseProgress * 20f * PI.toFloat()) else 0f
  val raiseT = when (phase) {
    TripleTScarePhase.WIND_UP -> easedT
    TripleTScarePhase.RAISE, TripleTScarePhase.TREMBLE -> 1f
  }

  TripleT2D(
    modifier = modifier,
    bodyOffset = when (phase) {
      TripleTScarePhase.WIND_UP -> Offset(0f, -0.01f * easedT)
      TripleTScarePhase.RAISE -> Offset(0f, -0.01f - 0.03f * easedT)
      TripleTScarePhase.TREMBLE -> Offset(0.01f * tremble, -0.05f + 0.01f * tremble)
    },
    leftPalmWorldPos = Offset(-0.58f, lerp(0.6f, 0.4f, raiseT)),
    rightPalmWorldPos = Offset(lerp(0.68f, 0.74f, raiseT), lerp(0.08f, -0.03f, raiseT)),
    leftFootWorldPos = Offset(-0.11f, 1.08f),
    rightFootWorldPos = Offset(0.11f, 1.08f),
    leftArmBendDir = 0.03f,
    rightArmBendDir = -0.24f,
    leftLegBendDir = 0.02f,
    rightLegBendDir = -0.02f,
    bodyRotation = 1.8f * tremble,
    mouthCurveDir = -3f,
    mouthCurveProgress = lerp(0.84f, 0.26f, raiseT),
    mouthSizeMultiplier = lerp(1f, 1.04f, raiseT),
    propBeatProgress = 0f,
    propSide = 1,
  )
}

@Composable
fun TripleTSidewaysRight2D(modifier: Modifier = Modifier.fillMaxSize()) {
  var phase by remember { mutableStateOf(TripleTBeatPhase.WIND_UP) }
  var phaseTime by remember { mutableFloatStateOf(0f) }

  LaunchedEffect(Unit) {
    runTripleTAnimationLoop { delta ->
      val (nextPhaseOrdinal, nextPhaseTime) = loopAnimation(
        phaseOrdinal = phase.ordinal,
        phaseTime = phaseTime + delta,
        durations = TRIPLE_T_BEATING_DURATIONS,
        phaseCount = TripleTBeatPhase.entries.size,
      )
      phase = TripleTBeatPhase.entries[nextPhaseOrdinal]
      phaseTime = nextPhaseTime
    }
  }

  val phaseProgress = (phaseTime / TRIPLE_T_BEATING_DURATIONS[phase.ordinal]).coerceIn(0f, 1f)
  val easedT = easeInOut(phaseProgress)
  val windPalm = Offset(-0.2f, -0.3f)
  val strikePalm = Offset(0.56f, 0.2f)
  val recoverPalm = Offset(0.44f, -0.06f)
  val visiblePalm = when (phase) {
    TripleTBeatPhase.WIND_UP -> recoverPalm.lerpTo(windPalm, easedT)
    TripleTBeatPhase.STRIKE -> windPalm.lerpTo(strikePalm, easedT)
    TripleTBeatPhase.RECOVER -> strikePalm.lerpTo(recoverPalm, easedT)
    TripleTBeatPhase.HOLD -> recoverPalm
  }
  val rearPalm = when (phase) {
    TripleTBeatPhase.WIND_UP -> Offset(0.17f, 0.48f).lerpTo(Offset(0.14f, 0.5f), easedT)
    TripleTBeatPhase.STRIKE -> Offset(0.14f, 0.5f).lerpTo(Offset(0.2f, 0.45f), easedT)
    TripleTBeatPhase.RECOVER -> Offset(0.2f, 0.45f).lerpTo(Offset(0.17f, 0.48f), easedT)
    TripleTBeatPhase.HOLD -> Offset(0.17f, 0.48f)
  }
  val bodyRotation = when (phase) {
    TripleTBeatPhase.WIND_UP -> lerp(1.8f, -3.2f, easedT)
    TripleTBeatPhase.STRIKE -> lerp(-3.2f, 7.2f, easedT)
    TripleTBeatPhase.RECOVER -> lerp(7.2f, 3.8f, easedT)
    TripleTBeatPhase.HOLD -> 3.8f
  }
  val bodyOffset = Offset.Zero
  val visibleArmBend = when (phase) {
    TripleTBeatPhase.WIND_UP -> lerp(0.28f, 0.42f, easedT)
    TripleTBeatPhase.STRIKE -> lerp(0.42f, -0.14f, easedT)
    TripleTBeatPhase.RECOVER -> lerp(-0.14f, 0.06f, easedT)
    TripleTBeatPhase.HOLD -> 0.06f
  }
  val rearArmBend = when (phase) {
    TripleTBeatPhase.WIND_UP -> lerp(0.08f, 0.14f, easedT)
    TripleTBeatPhase.STRIKE -> lerp(0.14f, -0.02f, easedT)
    TripleTBeatPhase.RECOVER -> lerp(-0.02f, 0.08f, easedT)
    TripleTBeatPhase.HOLD -> 0.08f
  }
  val propBeat = when (phase) {
    TripleTBeatPhase.WIND_UP -> 0f
    TripleTBeatPhase.STRIKE -> lerp(0f, 0.7f, easedT)
    TripleTBeatPhase.RECOVER -> lerp(0.7f, 0.35f, easedT)
    TripleTBeatPhase.HOLD -> 0.35f
  }
  val plantedVisibleFoot = Offset(0.14f, 1.08f)
  val plantedRearFoot = Offset(-0.08f, 1.09f)
  val visibleFoot = plantedVisibleFoot.rotateDegrees(-bodyRotation)
  val rearFoot = plantedRearFoot.rotateDegrees(-bodyRotation)

  TripleTSideways2D(
    modifier = modifier,
    direction = TripleTSidewaysDirection.RIGHT,
    bodyOffset = bodyOffset,
    visiblePalmWorldPos = visiblePalm,
    rearPalmWorldPos = rearPalm,
    visibleFootWorldPos = visibleFoot,
    rearFootWorldPos = rearFoot,
    visibleArmBendDir = visibleArmBend,
    rearArmBendDir = rearArmBend,
    visibleLegBendDir = -0.12f,
    rearLegBendDir = -0.1f,
    bodyRotation = bodyRotation,
    propBeatProgress = propBeat,
  )
}

@Composable
fun TripleTSidewaysWalk2D(
  modifier: Modifier = Modifier.fillMaxSize(),
  direction: TripleTSidewaysDirection = TripleTSidewaysDirection.RIGHT,
  mouthCurveDir: Float = -0.08f,
  mouthCurveProgress: Float = 1f,
  mouthSizeMultiplier: Float = 1f,
) {
  var phase by remember { mutableStateOf(TripleTSidewaysWalkPhase.CONTACT_FRONT) }
  var phaseTime by remember { mutableFloatStateOf(0f) }

  LaunchedEffect(Unit) {
    runTripleTAnimationLoop { delta ->
      val (nextPhaseOrdinal, nextPhaseTime) = loopAnimation(
        phaseOrdinal = phase.ordinal,
        phaseTime = phaseTime + delta,
        durations = TRIPLE_T_SIDEWAYS_DURATIONS,
        phaseCount = TripleTSidewaysWalkPhase.entries.size,
      )
      phase = TripleTSidewaysWalkPhase.entries[nextPhaseOrdinal]
      phaseTime = nextPhaseTime
    }
  }

  val phaseProgress = (phaseTime / TRIPLE_T_SIDEWAYS_DURATIONS[phase.ordinal]).coerceIn(0f, 1f)
  val easedT = easeInOut(phaseProgress)

  TripleTSideways2D(
    modifier = modifier,
    direction = direction,
    bodyOffset = Offset(0f, interpolateKeyframes(TripleTSideBodyBobTargets, phase.ordinal, easedT)),
    visiblePalmWorldPos = Offset(
      interpolateKeyframes(TripleTSideVisiblePalmXTargets, phase.ordinal, easedT),
      interpolateKeyframes(TripleTSideVisiblePalmYTargets, phase.ordinal, easedT),
    ),
    rearPalmWorldPos = Offset(
      interpolateKeyframes(TripleTSideRearPalmXTargets, phase.ordinal, easedT),
      interpolateKeyframes(TripleTSideRearPalmYTargets, phase.ordinal, easedT),
    ),
    visibleFootWorldPos = Offset(
      interpolateKeyframes(TripleTSideVisibleFootXTargets, phase.ordinal, easedT),
      interpolateKeyframes(TripleTSideVisibleFootYTargets, phase.ordinal, easedT),
    ),
    rearFootWorldPos = Offset(
      interpolateKeyframes(TripleTSideRearFootXTargets, phase.ordinal, easedT),
      interpolateKeyframes(TripleTSideRearFootYTargets, phase.ordinal, easedT),
    ),
    visibleArmBendDir = interpolateKeyframes(TripleTSideVisibleArmBendTargets, phase.ordinal, easedT),
    rearArmBendDir = interpolateKeyframes(TripleTSideRearArmBendTargets, phase.ordinal, easedT),
    visibleLegBendDir = interpolateKeyframes(TripleTSideVisibleLegBendTargets, phase.ordinal, easedT),
    rearLegBendDir = interpolateKeyframes(TripleTSideRearLegBendTargets, phase.ordinal, easedT),
    bodyRotation = interpolateKeyframes(TripleTSideBodyRotationTargets, phase.ordinal, easedT),
    mouthCurveDir = mouthCurveDir,
    mouthCurveProgress = mouthCurveProgress,
    mouthSizeMultiplier = mouthSizeMultiplier,
    propBeatProgress = interpolateKeyframes(TripleTSidePropSwingTargets, phase.ordinal, easedT),
  )
}

@Preview
@Composable
private fun TripleTPreview() {
  BoxWithConstraints(Modifier.fillMaxSize()) {
    if (maxWidth >= maxHeight) {
      Row(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxHeight()) {
          TripleTIdle2D(Modifier.fillMaxWidth().weight(1f))
          TripleTApproach2D(Modifier.fillMaxWidth().weight(1f))
        }
        Column(Modifier.weight(1f).fillMaxHeight()) {
          TripleTBeating2D(Modifier.fillMaxWidth().weight(1f))
          TripleTScare2D(Modifier.fillMaxWidth().weight(1f))
        }
        Column(Modifier.weight(1f).fillMaxHeight()) {
          TripleTSidewaysRight2D(Modifier.fillMaxWidth().weight(1f))
          TripleTSidewaysWalk2D(
            modifier = Modifier.fillMaxWidth().weight(1f),
            direction = TripleTSidewaysDirection.LEFT,
            mouthCurveDir = 4.5f,
            mouthCurveProgress = 0.42f,
          )
        }
      }
    }
    else {
      Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().weight(1f)) {
          TripleTIdle2D(Modifier.weight(1f).fillMaxHeight())
          TripleTBeating2D(Modifier.weight(1f).fillMaxHeight())
        }
        Row(Modifier.fillMaxWidth().weight(1f)) {
          TripleTApproach2D(Modifier.weight(1f).fillMaxHeight())
          TripleTScare2D(Modifier.weight(1f).fillMaxHeight())
        }
        Row(Modifier.fillMaxWidth().weight(1f)) {
          TripleTSidewaysRight2D(Modifier.weight(1f).fillMaxHeight())
          TripleTSidewaysWalk2D(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            direction = TripleTSidewaysDirection.LEFT,
            mouthCurveDir = 1.5f,
            mouthCurveProgress = 0.42f,
          )
        }
      }
    }
  }
}
