// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.devkit.compose.showcase.timber

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.util.lerp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

private val WoodBase = Color(0xFFBD7D38)
private val WoodLight = Color(0xFFD4A05A)
private val WoodDark = Color(0xFF7A4A1C)
private val WoodGrain = Color(0xFF8B5520)
private val WoodShadow = Color(0xFF4A2C0A)
private val StickBase = Color(0xFFD6B06E)
private val EyeWhite = Color(0xFFF5F0E8)
private val EyePupil = Color(0xFF1A0800)
private val TeethWhite = Color(0xFFF0EDE0)
private val MouthInside = Color(0xFF6B2010)

internal const val TRIPLE_T_NANOS_PER_SECOND = 1_000_000_000f
internal const val TRIPLE_T_MAX_FRAME_DELTA_SECONDS = 0.05f

private const val TripleTFrontFaceShiftY = -6f
private const val TripleTSideFaceShiftY = -6f
private const val TripleTFrontArmAnchorY = 6f
private const val TripleTSideRearArmAnchorY = 8f
private const val TripleTSideVisibleArmAnchorY = 9f
private const val TripleTFrontArmUpperLength = 16f
private const val TripleTFrontArmLowerLength = 24f
private const val TripleTSideArmUpperLength = 16f
private const val TripleTSideArmLowerLength = 24f
private const val TripleTSideRearFootAlpha = 0.72f
private const val TripleTSideVisibleFootAlpha = 0.92f

private const val TripleTEpsilon = 1e-3f

private fun Offset.lerpTo(other: Offset, t: Float) = Offset(lerp(x, other.x, t), lerp(y, other.y, t))

private fun clampSelector(selector: Int): Int = when {
  selector > 0 -> 1
  selector < 0 -> -1
  else -> 0
}

internal suspend fun runTripleTAnimationLoop(onFrame: (Float) -> Unit) {
  var lastNanos = withFrameNanos { it }
  while (true) {
    withFrameNanos { nanos ->
      val delta = ((nanos - lastNanos) / TRIPLE_T_NANOS_PER_SECOND).coerceIn(0f, TRIPLE_T_MAX_FRAME_DELTA_SECONDS)
      lastNanos = nanos
      onFrame(delta)
    }
  }
}

internal fun interpolateKeyframes(values: FloatArray, phaseOrdinal: Int, t: Float): Float {
  if (values.isEmpty()) return 0f
  val current = values[phaseOrdinal % values.size]
  val next = values[(phaseOrdinal + 1) % values.size]
  return lerp(current, next, t.coerceIn(0f, 1f))
}

private fun DrawScope.drawOutlinedPolyline(
  points: List<Offset>,
  width: Float,
  outlineExtra: Float,
  fillColor: Color,
  outlineColor: Color,
  path: Path,
  cap: StrokeCap = StrokeCap.Round,
  outlineEndExtension: Float = 0f,
) {
  if (points.size < 2) return
  path.reset()
  path.moveTo(points.first().x, points.first().y)
  points.drop(1).forEachIndexed { index, point ->
    if (index == points.lastIndex - 1 && outlineEndExtension > 0f) {
      val prev = points[points.lastIndex - 1]
      val dx = point.x - prev.x
      val dy = point.y - prev.y
      val length = sqrt(dx * dx + dy * dy).coerceAtLeast(TripleTEpsilon)
      path.lineTo(
        point.x + dx / length * outlineEndExtension,
        point.y + dy / length * outlineEndExtension,
      )
    }
    else {
      path.lineTo(point.x, point.y)
    }
  }
  drawPath(
    path = path,
    color = outlineColor,
    style = Stroke(width = width + outlineExtra, cap = cap, join = StrokeJoin.Round),
  )

  path.reset()
  path.moveTo(points.first().x, points.first().y)
  points.drop(1).forEach { point -> path.lineTo(point.x, point.y) }
  drawPath(
    path = path,
    color = fillColor,
    style = Stroke(width = width, cap = cap, join = StrokeJoin.Round),
  )
}

private fun solveJoint(
  start: Offset,
  end: Offset,
  upperLength: Float,
  lowerLength: Float,
  bendDir: Float,
): Offset {
  val dx = end.x - start.x
  val dy = end.y - start.y
  val distance = sqrt(dx * dx + dy * dy).coerceAtLeast(TripleTEpsilon)
  val dirX = dx / distance
  val dirY = dy / distance
  val reach = (upperLength + lowerLength - TripleTEpsilon).coerceAtLeast(TripleTEpsilon)
  val targetDistance = distance.coerceAtMost(reach)
  val baseDistance = ((upperLength * upperLength - lowerLength * lowerLength + targetDistance * targetDistance) /
                      (2f * targetDistance)).coerceIn(0f, upperLength)
  val jointHeight = sqrt(max(upperLength * upperLength - baseDistance * baseDistance, 0f))
  val basePoint = Offset(start.x + dirX * baseDistance, start.y + dirY * baseDistance)
  val sign = when {
    bendDir > 0f -> 1f
    bendDir < 0f -> -1f
    else -> 0f
  }
  val naturalJoint = Offset(
    basePoint.x - dirY * jointHeight * sign,
    basePoint.y + dirX * jointHeight * sign,
  )
  val straightJoint = Offset(start.x + dirX * upperLength, start.y + dirY * upperLength)
  val blend = abs(bendDir).coerceIn(0f, 1f)
  return straightJoint.lerpTo(naturalJoint, blend)
}

private fun DrawScope.drawFoot(
  ankle: Offset,
  soleCenter: Offset,
  direction: Float,
  scale: Float,
  path: Path,
) {
  val outlineWidth = 2.05f * scale
  val outlinePath = Path()
  fun p(dx: Float, dy: Float) = Offset(soleCenter.x + dx * direction * scale, soleCenter.y + dy * scale)
  fun a(dx: Float, dy: Float) = Offset(ankle.x + dx * direction * scale, ankle.y + dy * scale)

  val ankleLeft = a(-2.55f, -0.62f)
  val ankleRight = a(2.55f, -0.62f)
  val upperLeft = p(-2.95f, -2.2f)
  val upperRight = p(2.95f, -2.2f)
  val sideLeft = p(-4.85f, -0.1f)
  val sideRight = p(4.85f, -0.1f)
  val outlineBlendLeft = p(-2.75f, -1.78f)
  val outlineBlendRight = p(2.75f, -1.78f)
  val toe1 = p(-3.65f, 2.15f)
  val gap1 = p(-2.35f, 1.55f)
  val toe2 = p(-1.15f, 2.72f)
  val gap2 = p(0.0f, 1.62f)
  val toe3 = p(1.15f, 2.72f)
  val gap3 = p(2.35f, 1.55f)
  val toe4 = p(3.65f, 2.15f)

  path.reset()
  path.moveTo(ankleLeft.x, ankleLeft.y)
  path.quadraticTo(p(-2.25f, -1.92f).x, p(-2.25f, -1.92f).y, upperLeft.x, upperLeft.y)
  path.quadraticTo(p(-4.3f, -1.55f).x, p(-4.3f, -1.55f).y, sideLeft.x, sideLeft.y)
  path.quadraticTo(p(-4.75f, 1.5f).x, p(-4.75f, 1.5f).y, toe1.x, toe1.y)
  path.quadraticTo(p(-3.0f, 2.58f).x, p(-3.0f, 2.58f).y, gap1.x, gap1.y)
  path.quadraticTo(p(-1.8f, 2.92f).x, p(-1.8f, 2.92f).y, toe2.x, toe2.y)
  path.quadraticTo(p(-0.55f, 3.08f).x, p(-0.55f, 3.08f).y, gap2.x, gap2.y)
  path.quadraticTo(p(0.55f, 3.08f).x, p(0.55f, 3.08f).y, toe3.x, toe3.y)
  path.quadraticTo(p(1.8f, 2.92f).x, p(1.8f, 2.92f).y, gap3.x, gap3.y)
  path.quadraticTo(p(3.0f, 2.58f).x, p(3.0f, 2.58f).y, toe4.x, toe4.y)
  path.quadraticTo(p(4.75f, 1.5f).x, p(4.75f, 1.5f).y, sideRight.x, sideRight.y)
  path.quadraticTo(p(4.3f, -1.55f).x, p(4.3f, -1.55f).y, upperRight.x, upperRight.y)
  path.quadraticTo(p(2.25f, -1.92f).x, p(2.25f, -1.92f).y, ankleRight.x, ankleRight.y)
  path.quadraticTo(a(0f, -0.22f).x, a(0f, -0.22f).y, ankleLeft.x, ankleLeft.y)
  path.close()

  drawPath(path, WoodBase)

  outlinePath.reset()
  outlinePath.moveTo(outlineBlendLeft.x, outlineBlendLeft.y)
  outlinePath.quadraticTo(p(-4.05f, -1.55f).x, p(-4.05f, -1.55f).y, sideLeft.x, sideLeft.y)
  outlinePath.quadraticTo(p(-4.75f, 1.5f).x, p(-4.75f, 1.5f).y, toe1.x, toe1.y)
  outlinePath.quadraticTo(p(-3.0f, 2.58f).x, p(-3.0f, 2.58f).y, gap1.x, gap1.y)
  outlinePath.quadraticTo(p(-1.8f, 2.92f).x, p(-1.8f, 2.92f).y, toe2.x, toe2.y)
  outlinePath.quadraticTo(p(-0.55f, 3.08f).x, p(-0.55f, 3.08f).y, gap2.x, gap2.y)
  outlinePath.quadraticTo(p(0.55f, 3.08f).x, p(0.55f, 3.08f).y, toe3.x, toe3.y)
  outlinePath.quadraticTo(p(1.8f, 2.92f).x, p(1.8f, 2.92f).y, gap3.x, gap3.y)
  outlinePath.quadraticTo(p(3.0f, 2.58f).x, p(3.0f, 2.58f).y, toe4.x, toe4.y)
  outlinePath.quadraticTo(p(4.75f, 1.5f).x, p(4.75f, 1.5f).y, sideRight.x, sideRight.y)
  outlinePath.quadraticTo(p(4.05f, -1.55f).x, p(4.05f, -1.55f).y, outlineBlendRight.x, outlineBlendRight.y)
  drawPath(
    outlinePath,
    WoodShadow,
    style = Stroke(width = outlineWidth, cap = StrokeCap.Butt, join = StrokeJoin.Round),
  )
}

private fun DrawScope.drawHand(
  palm: Offset,
  direction: Float,
  scale: Float,
  path: Path,
) {
  fun p(dx: Float, dy: Float) = Offset(palm.x + dx * direction * scale, palm.y + dy * scale)

  path.reset()
  path.moveTo(p(-2.3f, -2.1f).x, p(-2.3f, -2.1f).y)
  path.quadraticTo(p(2.7f, -3.2f).x, p(2.7f, -3.2f).y, p(4.1f, -0.8f).x, p(4.1f, -0.8f).y)
  path.quadraticTo(p(4.8f, 1.5f).x, p(4.8f, 1.5f).y, p(3.0f, 3.7f).x, p(3.0f, 3.7f).y)
  path.quadraticTo(p(0.5f, 4.8f).x, p(0.5f, 4.8f).y, p(-1.6f, 2.2f).x, p(-1.6f, 2.2f).y)
  path.quadraticTo(p(-4.0f, 1.0f).x, p(-4.0f, 1.0f).y, p(-2.3f, -2.1f).x, p(-2.3f, -2.1f).y)
  path.close()
  drawPath(path, WoodBase)
  drawPath(path, WoodShadow, style = Stroke(width = 2f * scale, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

private fun DrawScope.drawWoodGrain(
  surfacePath: Path,
  left: Float,
  top: Float,
  right: Float,
  bottom: Float,
  scale: Float,
  path: Path,
) {
  val lineFractions = floatArrayOf(0.12f, 0.19f, 0.27f, 0.34f, 0.43f, 0.52f, 0.61f, 0.69f, 0.78f, 0.86f)
  val midY = (top + bottom) / 2f
  clipPath(surfacePath) {
    lineFractions.forEachIndexed { index, fraction ->
      val x = lerp(left, right, fraction)
      val topInset = (5.5f + (index % 3) * 1.2f) * scale
      val bottomInset = (6.2f + ((index + 1) % 3) * 1.4f) * scale
      val wiggle = if (index % 2 == 0) -1.8f * scale else 1.7f * scale
      path.reset()
      path.moveTo(x, top + topInset)
      path.quadraticTo(x + wiggle, midY, x + wiggle * 0.55f, bottom - bottomInset)
      drawPath(
        path = path,
        color = if (index % 4 == 0) WoodLight.copy(alpha = 0.15f) else WoodGrain.copy(alpha = 0.33f),
        style = Stroke(width = 0.72f * scale, cap = StrokeCap.Round, join = StrokeJoin.Round),
      )
    }

    val scars = listOf(
      floatArrayOf(0.22f, 0.26f, 0.30f, 0.33f, 0.41f, 0.28f),
      floatArrayOf(0.58f, 0.44f, 0.70f, 0.52f, 0.83f, 0.46f),
      floatArrayOf(0.27f, 0.69f, 0.39f, 0.75f, 0.50f, 0.71f),
    )
    scars.forEachIndexed { index, scar ->
      path.reset()
      path.moveTo(lerp(left, right, scar[0]), lerp(top, bottom, scar[1]))
      path.quadraticTo(
        lerp(left, right, scar[2]),
        lerp(top, bottom, scar[3]),
        lerp(left, right, scar[4]),
        lerp(top, bottom, scar[5]),
      )
      drawPath(
        path = path,
        color = if (index == 1) WoodLight.copy(alpha = 0.18f) else WoodShadow.copy(alpha = 0.2f),
        style = Stroke(width = 0.95f * scale, cap = StrokeCap.Round, join = StrokeJoin.Round),
      )
    }
  }
}

private fun DrawScope.drawProp(
  palm: Offset,
  beatProgress: Float,
  side: Int,
  path: Path,
  detailPath: Path,
  scale: Float,
) {
  val sideSign = if (side < 0) -1f else 1f
  val clampedBeat = beatProgress.coerceIn(0f, 1f)
  val raisedAngle = if (sideSign > 0f) -100f else 280f
  val struckAngle = if (sideSign > 0f) 34f else 146f
  val angle = lerp(raisedAngle, struckAngle, clampedBeat) * (PI.toFloat() / 180f)
  val dirX = cos(angle)
  val dirY = sin(angle)
  val perpX = -dirY
  val perpY = dirX

  val totalLength = 59.4f * scale
  val handleStart = -8.1f * scale
  val handleLength = 12.6f * scale
  val barrelMid = 24.3f * scale
  val barrelEnd = totalLength
  val handleHalfWidth = 2f * scale
  val barrelMidHalfWidth = 5.0f * scale
  val barrelEndHalfWidth = 6.2f * scale
  val tipRadius = 6.0f * scale
  val outlineWidth = 2f * scale

  fun local(along: Float, perp: Float) = Offset(
    x = palm.x + dirX * along + perpX * perp,
    y = palm.y + dirY * along + perpY * perp,
  )

  val tipCenter = local(totalLength, 0f)

  path.reset()
  path.moveTo(local(handleStart, handleHalfWidth).x, local(handleStart, handleHalfWidth).y)
  path.lineTo(local(handleLength, handleHalfWidth).x, local(handleLength, handleHalfWidth).y)
  path.quadraticTo(
    local(barrelMid, barrelMidHalfWidth).x,
    local(barrelMid, barrelMidHalfWidth).y,
    local(barrelEnd, barrelEndHalfWidth).x,
    local(barrelEnd, barrelEndHalfWidth).y,
  )
  path.lineTo(local(barrelEnd, -barrelEndHalfWidth).x, local(barrelEnd, -barrelEndHalfWidth).y)
  path.quadraticTo(
    local(barrelMid, -barrelMidHalfWidth).x,
    local(barrelMid, -barrelMidHalfWidth).y,
    local(handleLength, -handleHalfWidth).x,
    local(handleLength, -handleHalfWidth).y,
  )
  path.lineTo(local(handleStart, -handleHalfWidth).x, local(handleStart, -handleHalfWidth).y)
  path.close()

  drawCircle(
    color = WoodShadow,
    radius = tipRadius + outlineWidth * 0.45f,
    center = tipCenter,
  )
  drawPath(path, WoodShadow, style = Stroke(width = outlineWidth, cap = StrokeCap.Round, join = StrokeJoin.Round))

  drawLine(
    color = WoodShadow,
    start = local(handleStart, handleHalfWidth + outlineWidth * 0.55f),
    end = local(handleStart, -handleHalfWidth - outlineWidth * 0.55f),
    strokeWidth = 2.45f * scale,
    cap = StrokeCap.Butt,
  )

  drawPath(path, StickBase)
  drawCircle(
    color = StickBase,
    radius = tipRadius,
    center = tipCenter,
  )
  drawLine(
    color = StickBase,
    start = local(handleStart + 0.25f * scale, handleHalfWidth + 0.2f * scale),
    end = local(handleStart + 0.25f * scale, -handleHalfWidth - 0.2f * scale),
    strokeWidth = 1.35f * scale,
    cap = StrokeCap.Butt,
  )

  detailPath.reset()
  detailPath.moveTo(local(2f * scale, 0.35f * scale).x, local(2f * scale, 0.35f * scale).y)
  detailPath.quadraticTo(
    local(25.2f * scale, 1.55f * scale).x,
    local(25.2f * scale, 1.55f * scale).y,
    local(totalLength - 3.8f * scale, 1.65f * scale).x,
    local(totalLength - 3.8f * scale, 1.65f * scale).y,
  )
  drawPath(
    detailPath,
    WoodLight.copy(alpha = 0.28f),
    style = Stroke(width = 0.9f * scale, cap = StrokeCap.Round, join = StrokeJoin.Round),
  )
  detailPath.reset()
  detailPath.moveTo(local(7f * scale, -0.7f * scale).x, local(7f * scale, -0.7f * scale).y)
  detailPath.quadraticTo(
    local(27f * scale, -1.35f * scale).x,
    local(27f * scale, -1.35f * scale).y,
    local(totalLength - 5.4f * scale, -0.85f * scale).x,
    local(totalLength - 5.4f * scale, -0.85f * scale).y,
  )
  drawPath(
    detailPath,
    WoodLight.copy(alpha = 0.16f),
    style = Stroke(width = 0.72f * scale, cap = StrokeCap.Round, join = StrokeJoin.Round),
  )
  detailPath.reset()
  detailPath.moveTo(local(10f * scale, 2.05f * scale).x, local(10f * scale, 2.05f * scale).y)
  detailPath.quadraticTo(
    local(29.7f * scale, 1.15f * scale).x,
    local(29.7f * scale, 1.15f * scale).y,
    local(totalLength - 8.5f * scale, 1.8f * scale).x,
    local(totalLength - 8.5f * scale, 1.8f * scale).y,
  )
  drawPath(
    detailPath,
    WoodShadow.copy(alpha = 0.12f),
    style = Stroke(width = 0.68f * scale, cap = StrokeCap.Round, join = StrokeJoin.Round),
  )

  drawCircle(WoodDark, radius = 3.8f * scale, center = palm)
}

private fun DrawScope.drawEye(
  center: Offset,
  winkProgress: Float,
  eyeRadiusX: Float,
  eyeRadiusY: Float,
  pupilRadius: Float,
  pupilOffset: Offset,
  eyeWinkDir: Float,
  path: Path,
) {
  val eyeOutline = Stroke(width = eyeRadiusX * 0.18f, cap = StrokeCap.Round, join = StrokeJoin.Round)
  val eyelidStroke = Stroke(width = eyeRadiusY * 0.42f, cap = StrokeCap.Round, join = StrokeJoin.Round)
  val ry = eyeRadiusY * (1f - winkProgress).coerceAtLeast(0f)
  val shiftedCenterY = center.y - eyeRadiusY * winkProgress * eyeWinkDir

  if (winkProgress < 0.8f) {
    drawOval(
      color = EyeWhite,
      topLeft = Offset(center.x - eyeRadiusX, shiftedCenterY - ry),
      size = Size(eyeRadiusX * 2f, ry * 2f),
    )
    drawOval(
      color = WoodShadow,
      topLeft = Offset(center.x - eyeRadiusX, shiftedCenterY - ry),
      size = Size(eyeRadiusX * 2f, ry * 2f),
      style = eyeOutline,
    )
    val pupilCenter = Offset(
      x = center.x + pupilOffset.x,
      y = shiftedCenterY + eyeWinkDir * eyeRadiusY * 0.08f * winkProgress + pupilOffset.y,
    )
    val pupilRy = pupilRadius * (1f - winkProgress * 0.7f).coerceAtLeast(0.25f)
    drawOval(
      color = EyePupil,
      topLeft = Offset(pupilCenter.x - pupilRadius, pupilCenter.y - pupilRy),
      size = Size(pupilRadius * 2f, pupilRy * 2f),
    )
    drawCircle(
      color = Color.White.copy(alpha = 0.92f),
      radius = pupilRadius * 0.28f,
      center = Offset(pupilCenter.x - pupilRadius * 0.32f, pupilCenter.y - pupilRy * 0.48f),
    )
  }
  else {
    val baseT = ((winkProgress - 0.8f) / 0.2f).coerceIn(0f, 1f)
    val endpointY = shiftedCenterY + eyeRadiusY * eyeWinkDir * baseT * 0.5f
    val topCtrlY = endpointY - 2f * ry - eyeRadiusY * eyeWinkDir * baseT
    val botCtrlY = (endpointY + 2f * ry) + (topCtrlY - endpointY - 2f * ry) * baseT

    path.reset()
    path.moveTo(center.x - eyeRadiusX, endpointY)
    path.quadraticTo(center.x, topCtrlY, center.x + eyeRadiusX, endpointY)
    drawPath(path, EyeWhite, style = eyelidStroke)
    drawPath(path, WoodShadow, style = eyeOutline)

    path.reset()
    path.moveTo(center.x + eyeRadiusX, endpointY)
    path.quadraticTo(center.x, botCtrlY, center.x - eyeRadiusX, endpointY)
    drawPath(path, EyeWhite, style = eyelidStroke)
    drawPath(path, WoodShadow, style = eyeOutline)
  }
}

private fun DrawScope.drawTripleTPropFeature(
  palm: Offset,
  beatProgress: Float,
  side: Int,
  path: Path,
  detailPath: Path,
  scale: Float,
) {
  drawProp(
    palm = palm,
    beatProgress = beatProgress,
    side = side,
    path = path,
    detailPath = detailPath,
    scale = scale,
  )
}

private fun DrawScope.drawTripleTEyeFeature(
  center: Offset,
  winkProgress: Float,
  eyeRadiusX: Float,
  eyeRadiusY: Float,
  pupilRadius: Float,
  pupilOffset: Offset,
  eyeWinkDir: Float,
  browStart: Offset,
  browCtrl: Offset,
  browEnd: Offset,
  browStrokeWidth: Float,
  path: Path,
  eyebrowClipPath: Path? = null,
) {
  drawEye(
    center = center,
    winkProgress = winkProgress,
    eyeRadiusX = eyeRadiusX,
    eyeRadiusY = eyeRadiusY,
    pupilRadius = pupilRadius,
    pupilOffset = pupilOffset,
    eyeWinkDir = eyeWinkDir,
    path = path,
  )

  fun drawBrow() {
    path.reset()
    path.moveTo(browStart.x, browStart.y)
    path.quadraticTo(browCtrl.x, browCtrl.y, browEnd.x, browEnd.y)
    drawPath(
      path = path,
      color = WoodShadow,
      style = Stroke(width = browStrokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
  }

  if (eyebrowClipPath != null) {
    clipPath(eyebrowClipPath) {
      drawBrow()
    }
  }
  else {
    drawBrow()
  }
}

private fun DrawScope.drawTaperedMouthEdge(
  center: Offset,
  tangent: Offset,
  sideSign: Float,
  visibility: Float,
  lipLineThickness: Float,
  detailPath: Path,
) {
  if (visibility <= TripleTEpsilon) return
  val tangentLength = sqrt(tangent.x * tangent.x + tangent.y * tangent.y).coerceAtLeast(TripleTEpsilon)
  val tangentUnit = Offset(tangent.x / tangentLength, tangent.y / tangentLength)
  val perpA = Offset(-tangentUnit.y, tangentUnit.x)
  val perpB = Offset(tangentUnit.y, -tangentUnit.x)
  val perpUnit = when {
    perpA.y >= 0f && sideSign * perpA.x >= 0f -> perpA
    else -> perpB
  }
  val halfLength = (lipLineThickness * 2.5f * visibility) / 2f
  val halfThickness = (lipLineThickness * visibility) / 2f
  val start = Offset(center.x - perpUnit.x * halfLength, center.y - perpUnit.y * halfLength)
  val end = Offset(center.x + perpUnit.x * halfLength, center.y + perpUnit.y * halfLength)
  val rightCtrl = Offset(
    center.x + tangentUnit.x * halfThickness * 0.9f,
    center.y + tangentUnit.y * halfThickness * 0.9f,
  )
  val leftCtrl = Offset(
    center.x - tangentUnit.x * halfThickness * 0.9f,
    center.y - tangentUnit.y * halfThickness * 0.9f,
  )

  detailPath.reset()
  detailPath.moveTo(start.x, start.y)
  detailPath.quadraticTo(rightCtrl.x, rightCtrl.y, end.x, end.y)
  detailPath.quadraticTo(leftCtrl.x, leftCtrl.y, start.x, start.y)
  detailPath.close()
  drawPath(detailPath, WoodShadow.copy(alpha = visibility))
}

private fun DrawScope.drawTripleTMouthFeature(
  mouthCenter: Offset,
  mouthHalfWidth: Float,
  mouthCurveDir: Float,
  mouthCurveProgress: Float,
  scale: Float,
  path: Path,
  detailPath: Path,
) {
  val clampedMouthCurveProgress = mouthCurveProgress.coerceIn(0f, 1f)
  val mouthOpen = (1f - clampedMouthCurveProgress).coerceIn(0f, 1f)
  val mouthOpenAmount = mouthOpen * 1.55f
  val upperCtrl = Offset(
    mouthCenter.x,
    mouthCenter.y - (1.9f + 1.1f * mouthOpenAmount) * scale + 1.8f * scale * mouthCurveDir,
  )
  val lowerCtrl = Offset(
    mouthCenter.x,
    mouthCenter.y + (1.7f + 5.8f * mouthOpenAmount) * scale + 2.2f * scale * mouthCurveDir,
  )
  val mouthEdgeShiftY = (-mouthCurveDir).coerceAtLeast(0f) * mouthOpenAmount * 1.85f * scale
  val mouthLeft = Offset(mouthCenter.x - mouthHalfWidth, mouthCenter.y - 0.4f * scale + mouthEdgeShiftY)
  val mouthRight = Offset(mouthCenter.x + mouthHalfWidth, mouthCenter.y - 0.55f * scale + mouthEdgeShiftY)
  val lipCornerInset = 0.95f * scale
  val lipLineThickness = if (mouthOpen > 0.15f) 1.18f * scale else 2f * scale
  val visibleLeftCorner = Offset(
    mouthLeft.x + lipCornerInset,
    mouthLeft.y + if (mouthOpen > 0.15f) 0.02f * scale else 0.4f * scale,
  )
  val visibleRightCorner = Offset(
    mouthRight.x - lipCornerInset,
    mouthRight.y + if (mouthOpen > 0.15f) 0.02f * scale else 0.4f * scale,
  )
  val visibleLipCtrl = if (mouthOpen > 0.15f) {
    upperCtrl
  }
  else {
    Offset(
      mouthCenter.x,
      mouthCenter.y + (2.4f * scale * mouthCurveDir) + 2.2f * scale,
    )
  }

  val chinFollowOffset = (lowerCtrl.y - mouthCenter.y) * 0.5f
  path.reset()
  path.moveTo(mouthCenter.x - 6.9f * scale, mouthCenter.y + 3.55f * scale + chinFollowOffset)
  path.quadraticTo(
    mouthCenter.x - 0.1f * scale,
    mouthCenter.y + 5.95f * scale + chinFollowOffset,
    mouthCenter.x + 6.7f * scale,
    mouthCenter.y + 3.45f * scale + chinFollowOffset,
  )
  drawPath(
    path,
    WoodShadow.copy(alpha = 0.75f),
    style = Stroke(width = 1.18f * scale, cap = StrokeCap.Round, join = StrokeJoin.Round),
  )

  if (mouthOpen > 0.15f) {
    path.reset()
    path.moveTo(mouthLeft.x, mouthLeft.y)
    path.quadraticTo(upperCtrl.x, upperCtrl.y, mouthRight.x, mouthRight.y)
    path.quadraticTo(lowerCtrl.x, lowerCtrl.y, mouthLeft.x, mouthLeft.y)
    path.close()
    drawPath(path, MouthInside)

    clipPath(path) {
      val toothCount = 10
      val toothInset = 0.65f * scale
      val toothWidth = ((mouthHalfWidth * 2f) - toothInset * 2f) / toothCount
      repeat(toothCount) {
        val t = (it + 0.5f) / toothCount.toFloat()
        val invT = 1f - t
        val toothCenterX = mouthLeft.x + toothInset + toothWidth * (it + 0.5f)
        val upperLipY =
          (invT * invT * mouthLeft.y) +
          (2f * invT * t * upperCtrl.y) +
          (t * t * mouthRight.y)
        val lowerLipY =
          (invT * invT * mouthRight.y) +
          (2f * invT * t * lowerCtrl.y) +
          (t * t * mouthLeft.y)
        val toothTop = upperLipY + 0.62f * scale
        val maxVisibleHeight = (lowerLipY - toothTop - 0.28f * scale).coerceAtLeast(0.28f * scale)
        val toothHeight = minOf((0.46f + 1.28f * mouthOpenAmount) * scale, maxVisibleHeight)
        drawRoundRect(
          color = TeethWhite,
          topLeft = Offset(
            x = toothCenterX - toothWidth / 2f,
            y = toothTop,
          ),
          size = Size(toothWidth, toothHeight),
          cornerRadius = CornerRadius(0.24f * scale, 0.24f * scale),
        )
      }
    }

    detailPath.reset()
    detailPath.moveTo(mouthLeft.x - 0.8f * scale, mouthCenter.y + 0.25f * scale)
    detailPath.quadraticTo(lowerCtrl.x, lowerCtrl.y, mouthRight.x + 0.8f * scale, mouthCenter.y + 0.1f * scale)
    detailPath.lineTo(mouthRight.x + 1.2f * scale, lowerCtrl.y + 3.2f * scale)
    detailPath.lineTo(mouthLeft.x - 1.2f * scale, lowerCtrl.y + 3.2f * scale)
    detailPath.close()
    clipPath(path) {
      drawPath(detailPath, MouthInside)
    }

    detailPath.reset()
    detailPath.moveTo(visibleLeftCorner.x, visibleLeftCorner.y)
    detailPath.quadraticTo(visibleLipCtrl.x, visibleLipCtrl.y, visibleRightCorner.x, visibleRightCorner.y)
    drawPath(
      detailPath,
      WoodShadow,
      style = Stroke(width = lipLineThickness, cap = StrokeCap.Butt, join = StrokeJoin.Round),
    )

    detailPath.reset()
    detailPath.moveTo(visibleRightCorner.x, visibleRightCorner.y)
    detailPath.quadraticTo(lowerCtrl.x, lowerCtrl.y, visibleLeftCorner.x, visibleLeftCorner.y)
    drawPath(
      detailPath,
      WoodShadow,
      style = Stroke(width = lipLineThickness, cap = StrokeCap.Butt, join = StrokeJoin.Round),
    )
  }
  else {
    path.reset()
    path.moveTo(visibleLeftCorner.x, visibleLeftCorner.y)
    path.quadraticTo(
      visibleLipCtrl.x,
      visibleLipCtrl.y,
      visibleRightCorner.x,
      visibleRightCorner.y,
    )
    drawPath(
      path = path,
      color = WoodShadow,
      style = Stroke(width = lipLineThickness, cap = StrokeCap.Butt, join = StrokeJoin.Round),
    )
  }

  val leftLipTangent = Offset(visibleLipCtrl.x - visibleLeftCorner.x, visibleLipCtrl.y - visibleLeftCorner.y)
  val rightLipTangent = Offset(visibleRightCorner.x - visibleLipCtrl.x, visibleRightCorner.y - visibleLipCtrl.y)
  val mouthEdgeLineVisibility = 1f - mouthOpen
  val mouthEdgeInset = 0.6f * scale
  val leftMouthEdgeCenter = Offset(mouthLeft.x + mouthEdgeInset, visibleLeftCorner.y)
  val rightMouthEdgeCenter = Offset(mouthRight.x - mouthEdgeInset, visibleRightCorner.y)
  drawTaperedMouthEdge(leftMouthEdgeCenter,
                       leftLipTangent,
                       sideSign = -1f,
                       visibility = mouthEdgeLineVisibility,
                       lipLineThickness = lipLineThickness,
                       detailPath = detailPath)
  drawTaperedMouthEdge(rightMouthEdgeCenter,
                       rightLipTangent,
                       sideSign = 1f,
                       visibility = mouthEdgeLineVisibility,
                       lipLineThickness = lipLineThickness,
                       detailPath = detailPath)
}

private fun DrawScope.drawTripleTCheeks(
  leftCheekLeft: Float,
  rightCheekLeft: Float,
  cheekTop: Float,
  cheekWidth: Float,
  cheekHeight: Float,
  scale: Float,
) {
  drawOval(
    color = WoodBase,
    topLeft = Offset(leftCheekLeft, cheekTop),
    size = Size(cheekWidth, cheekHeight),
  )
  drawOval(
    color = WoodBase,
    topLeft = Offset(rightCheekLeft, cheekTop),
    size = Size(cheekWidth, cheekHeight),
  )

  drawOval(
    color = WoodLight.copy(alpha = 0.4f),
    topLeft = Offset(leftCheekLeft + 1.1f * scale, cheekTop + 1.8f * scale),
    size = Size(4f * scale, 2.2f * scale),
  )
  drawOval(
    color = WoodLight.copy(alpha = 0.5f),
    topLeft = Offset(rightCheekLeft + cheekWidth - 4.2f * scale, cheekTop + 1.8f * scale),
    size = Size(4f * scale, 2.2f * scale),
  )

  drawArc(
    color = WoodShadow,
    startAngle = 108f,
    sweepAngle = 144f,
    useCenter = false,
    topLeft = Offset(leftCheekLeft, cheekTop),
    size = Size(cheekWidth, cheekHeight),
    style = Stroke(width = 1.8f * scale, cap = StrokeCap.Round, join = StrokeJoin.Round),
  )

  drawArc(
    color = WoodShadow,
    startAngle = -72f,
    sweepAngle = 144f,
    useCenter = false,
    topLeft = Offset(rightCheekLeft, cheekTop),
    size = Size(cheekWidth, cheekHeight),
    style = Stroke(width = 1.8f * scale, cap = StrokeCap.Round, join = StrokeJoin.Round),
  )
}

private fun DrawScope.drawTripleTBackCheeks(
  cx: Float,
  bottom: Float,
  scale: Float,
) {
  val backCheekWidth = 17.6f * scale
  val backCheekHeight = 20.2f * scale
  val backCheekTop = bottom - 23.2f * scale
  val backCheeks = listOf(
    Offset(cx - backCheekWidth, backCheekTop),
    Offset(cx, backCheekTop),
  )

  backCheeks.forEachIndexed { index, cheekTopLeft ->
    drawOval(
      color = WoodBase,
      topLeft = cheekTopLeft,
      size = Size(backCheekWidth, backCheekHeight),
    )
    val highlightX = if (index == 0) {
      cheekTopLeft.x + 4.2f * scale
    }
    else {
      cheekTopLeft.x + backCheekWidth - 7.4f * scale
    }
    drawOval(
      color = WoodLight.copy(alpha = 0.35f),
      topLeft = Offset(highlightX - 0.35f * scale, cheekTopLeft.y + 4.1f * scale),
      size = Size(6f * scale, 4f * scale),
    )
  }

  drawArc(
    color = WoodShadow,
    startAngle = -70f,
    sweepAngle = 152f,
    useCenter = false,
    topLeft = backCheeks[0],
    size = Size(backCheekWidth, backCheekHeight),
    style = Stroke(width = 2.1f * scale, cap = StrokeCap.Round, join = StrokeJoin.Round),
  )
  drawArc(
    color = WoodShadow,
    startAngle = 98f,
    sweepAngle = 152f,
    useCenter = false,
    topLeft = backCheeks[1],
    size = Size(backCheekWidth, backCheekHeight),
    style = Stroke(width = 2.1f * scale, cap = StrokeCap.Round, join = StrokeJoin.Round),
  )
}

private fun DrawScope.drawTripleT2D(
  path: Path,
  detailPath: Path,
  surfacePath: Path,
  cx: Float,
  cy: Float,
  bodySize: Float,
  leftPalmWorldPos: Offset,
  rightPalmWorldPos: Offset,
  leftFootWorldPos: Offset,
  rightFootWorldPos: Offset,
  leftArmBendDir: Float,
  rightArmBendDir: Float,
  leftLegBendDir: Float,
  rightLegBendDir: Float,
  bodyRotation: Float,
  leftEyeWinkProgress: Float,
  rightEyeWinkProgress: Float,
  eyeWinkDir: Float,
  mouthCurveDir: Float,
  mouthCurveProgress: Float,
  mouthSizeMultiplier: Float,
  isFront: Boolean,
  propBeatProgress: Float,
  propSide: Int,
) {
  val scale = bodySize / 76f
  val logWidth = 48f * scale
  val logHeight = 82f * scale
  val left = cx - logWidth / 2f
  val right = cx + logWidth / 2f
  val top = cy - logHeight * 0.5f
  val bottom = cy + logHeight * 0.5f

  val armThickness = 5f * scale
  val legThickness = 5f * scale
  val armUpperLength = TripleTFrontArmUpperLength * scale
  val armLowerLength = TripleTFrontArmLowerLength * scale
  val legUpperLength = 26f * scale
  val legLowerLength = 31f * scale
  val faceShiftY = TripleTFrontFaceShiftY * scale

  fun Offset.bodyLocal(): Offset {
    if (bodyRotation == 0f) return this
    val rad = -bodyRotation * (PI.toFloat() / 180f)
    val cosAngle = cos(rad)
    val sinAngle = sin(rad)
    val dx = x - cx
    val dy = y - cy
    return Offset(cx + dx * cosAngle - dy * sinAngle, cy + dx * sinAngle + dy * cosAngle)
  }

  val leftPalm = leftPalmWorldPos.bodyLocal()
  val rightPalm = rightPalmWorldPos.bodyLocal()
  val leftFootGround = leftFootWorldPos.bodyLocal()
  val rightFootGround = rightFootWorldPos.bodyLocal()
  val leftAnkle = Offset(leftFootGround.x + 0.2f * scale, leftFootGround.y - 5.2f * scale)
  val rightAnkle = Offset(rightFootGround.x - 0.2f * scale, rightFootGround.y - 5.2f * scale)

  val leftShoulder = Offset(left + 8.4f * scale, cy + TripleTFrontArmAnchorY * scale)
  val rightShoulder = Offset(right - 8.4f * scale, cy + TripleTFrontArmAnchorY * scale)
  val leftHip = Offset(cx - 6.2f * scale, bottom - 7.8f * scale)
  val rightHip = Offset(cx + 6.2f * scale, bottom - 7.8f * scale)

  val leftElbow = solveJoint(leftShoulder, leftPalm, armUpperLength, armLowerLength, leftArmBendDir)
  val rightElbow = solveJoint(rightShoulder, rightPalm, armUpperLength, armLowerLength, rightArmBendDir)
  val leftKnee = solveJoint(leftHip, leftAnkle, legUpperLength, legLowerLength, leftLegBendDir)
  val rightKnee = solveJoint(rightHip, rightAnkle, legUpperLength, legLowerLength, rightLegBendDir)

  withTransform({ rotate(bodyRotation, Offset(cx, cy)) }) {
    drawOutlinedPolyline(
      points = listOf(leftHip, leftKnee, leftAnkle),
      width = legThickness,
      outlineExtra = 2.2f * scale,
      fillColor = WoodBase,
      outlineColor = WoodShadow,
      path = path,
      cap = StrokeCap.Round,
      outlineEndExtension = 3f * scale,
    )
    drawOutlinedPolyline(
      points = listOf(rightHip, rightKnee, rightAnkle),
      width = legThickness,
      outlineExtra = 2.2f * scale,
      fillColor = WoodBase,
      outlineColor = WoodShadow,
      path = path,
      cap = StrokeCap.Round,
      outlineEndExtension = 3f * scale,
    )
    drawFoot(leftAnkle, leftFootGround, -1f, scale, path)
    drawFoot(rightAnkle, rightFootGround, 1f, scale, path)

    drawOutlinedPolyline(
      points = listOf(leftShoulder, leftElbow, leftPalm),
      width = armThickness,
      outlineExtra = 2.2f * scale,
      fillColor = WoodBase,
      outlineColor = WoodShadow,
      path = path,
    )
    drawOutlinedPolyline(
      points = listOf(rightShoulder, rightElbow, rightPalm),
      width = armThickness,
      outlineExtra = 2.2f * scale,
      fillColor = WoodBase,
      outlineColor = WoodShadow,
      path = path,
    )
    drawHand(leftPalm, -1f, scale, path)
    drawHand(rightPalm, 1f, scale, path)

    val topInsetLeft = 5.1f * scale
    val topInsetRight = 3.8f * scale
    val sideInsetLeft = 3.6f * scale
    val sideInsetRight = 2.8f * scale
    val bottomInsetLeft = 4.7f * scale
    val bottomInsetRight = 3.2f * scale

    surfacePath.reset()
    surfacePath.moveTo(cx, top)
    surfacePath.quadraticTo(
      left + topInsetLeft - 2.4f * scale,
      top + 0.9f * scale,
      left + topInsetLeft,
      top + 1.2f * scale,
    )
    surfacePath.quadraticTo(
      left + sideInsetLeft - 1.7f * scale,
      top + 8.4f * scale,
      left + sideInsetLeft,
      top + 15f * scale,
    )
    surfacePath.lineTo(left + 2.6f * scale, cy + 4f * scale)
    surfacePath.lineTo(left + 3.4f * scale, bottom - 12f * scale)
    surfacePath.quadraticTo(
      left + bottomInsetLeft - 1.8f * scale,
      bottom - 7.2f * scale,
      left + bottomInsetLeft,
      bottom - 1.4f * scale,
    )
    surfacePath.quadraticTo(
      cx - 6.9f * scale,
      bottom + 1.1f * scale,
      cx - 1.4f * scale,
      bottom,
    )
    surfacePath.quadraticTo(
      cx + 5.6f * scale,
      bottom + 0.9f * scale,
      right - bottomInsetRight,
      bottom - 0.7f * scale,
    )
    surfacePath.lineTo(right - 2.1f * scale, bottom - 10.4f * scale)
    surfacePath.lineTo(right - sideInsetRight, cy + 3.4f * scale)
    surfacePath.quadraticTo(
      right - 1.0f * scale,
      top + 11.1f * scale,
      right - 1.8f * scale,
      top + 17f * scale,
    )
    surfacePath.quadraticTo(
      right - topInsetRight + 2.0f * scale,
      top + 6.8f * scale,
      right - topInsetRight,
      top + 0.6f * scale,
    )
    surfacePath.quadraticTo(
      cx + 6.5f * scale,
      top - 1.4f * scale,
      cx + 1.6f * scale,
      top,
    )
    surfacePath.close()
    drawPath(surfacePath, WoodBase)

    clipPath(surfacePath) {
      drawRect(
        color = WoodLight.copy(alpha = 0.07f),
        topLeft = Offset(left + 10.2f * scale, top + 7f * scale),
        size = Size(3.6f * scale, logHeight - 14f * scale),
      )
      drawRect(
        color = WoodDark.copy(alpha = 0.1f),
        topLeft = Offset(right - 8.6f * scale, top + 6.6f * scale),
        size = Size(2.7f * scale, logHeight - 13.2f * scale),
      )
    }
    drawWoodGrain(surfacePath, left, top, right, bottom, scale, detailPath)
    clipPath(surfacePath) {
      drawCircle(
        color = WoodDark.copy(alpha = 0.1f),
        radius = 2.6f * scale,
        center = Offset(cx - 13.5f * scale, cy + 14.5f * scale),
      )
      drawCircle(
        color = WoodDark.copy(alpha = 0.08f),
        radius = 2.2f * scale,
        center = Offset(cx + 12.8f * scale, cy + 3.4f * scale),
      )
    }
    drawPath(surfacePath, WoodShadow, style = Stroke(width = 2f * scale))

    val mouthCenter = Offset(cx - 0.2f * scale, cy + (6f + TripleTFrontFaceShiftY) * scale)
    val mouthHalfWidth = 11.6f * mouthSizeMultiplier * scale
    val mouthOpen = (1f - mouthCurveProgress).coerceIn(0f, 1f)
    val mouthOpenAmount = mouthOpen * 1.55f
    val mouthEdgeShiftY = (-mouthCurveDir).coerceAtLeast(0f) * mouthOpenAmount * 1.85f * scale
    val mouthLeft = Offset(mouthCenter.x - mouthHalfWidth, mouthCenter.y - 0.4f * scale + mouthEdgeShiftY)
    val mouthRight = Offset(mouthCenter.x + mouthHalfWidth, mouthCenter.y - 0.55f * scale + mouthEdgeShiftY)
    val cheekWidth = 8.8f * scale
    val cheekHeight = 10.1f * scale
    val cheekTop = ((mouthLeft.y + mouthRight.y) / 2f) - cheekHeight / 2f + 0.48f * scale
    val leftCheekLeft = left - 0.2f * scale
    val rightCheekLeft = right - cheekWidth + 0.2f * scale
    drawTripleTCheeks(
      leftCheekLeft = leftCheekLeft,
      rightCheekLeft = rightCheekLeft,
      cheekTop = cheekTop,
      cheekWidth = cheekWidth,
      cheekHeight = cheekHeight,
      scale = scale,
    )

    if (isFront) {
      val eyeRadiusX = 7.15f * scale
      val eyeRadiusY = 7.35f * scale
      val pupilRadius = 4.85f * scale
      val leftEyeCenter = Offset(cx - 10.6f * scale, top + 29.1f * scale + faceShiftY)
      val rightEyeCenter = Offset(cx + 10.9f * scale, top + 28.9f * scale + faceShiftY)
      val browStrokeWidth = 4.48f * scale
      val browCenterlineLift = 1.15f * scale
      drawTripleTEyeFeature(
        center = leftEyeCenter,
        winkProgress = leftEyeWinkProgress,
        eyeRadiusX = eyeRadiusX,
        eyeRadiusY = eyeRadiusY,
        pupilRadius = pupilRadius,
        pupilOffset = Offset(0.0f * scale, 0.12f * scale),
        eyeWinkDir = eyeWinkDir,
        browStart = Offset(leftEyeCenter.x - 7.4f * scale, leftEyeCenter.y - 8.8f * scale - browCenterlineLift),
        browCtrl = Offset(leftEyeCenter.x - 0.6f * scale, leftEyeCenter.y - 12.7f * scale - browCenterlineLift),
        browEnd = Offset(leftEyeCenter.x + 7.7f * scale, leftEyeCenter.y - 9.3f * scale - browCenterlineLift),
        browStrokeWidth = browStrokeWidth,
        path = path,
      )
      drawTripleTEyeFeature(
        center = rightEyeCenter,
        winkProgress = rightEyeWinkProgress,
        eyeRadiusX = eyeRadiusX,
        eyeRadiusY = eyeRadiusY,
        pupilRadius = pupilRadius,
        pupilOffset = Offset(-0.08f * scale, 0.08f * scale),
        eyeWinkDir = eyeWinkDir,
        browStart = Offset(rightEyeCenter.x - 7.7f * scale, rightEyeCenter.y - 9.5f * scale - browCenterlineLift),
        browCtrl = Offset(rightEyeCenter.x + 0.6f * scale, rightEyeCenter.y - 12.9f * scale - browCenterlineLift),
        browEnd = Offset(rightEyeCenter.x + 7.3f * scale, rightEyeCenter.y - 8.9f * scale - browCenterlineLift),
        browStrokeWidth = browStrokeWidth,
        path = path,
      )

      val noseScale = 1.2f
      val noseOffsetY = (-3.4f + TripleTFrontFaceShiftY) * scale
      path.reset()
      path.moveTo(cx - 0.4f * noseScale * scale, cy - 3.9f * noseScale * scale + noseOffsetY)
      path.cubicTo(
        cx - 2.3f * noseScale * scale,
        cy - 4.2f * noseScale * scale + noseOffsetY,
        cx - 4.1f * noseScale * scale,
        cy - 1.0f * noseScale * scale + noseOffsetY,
        cx - 3.4f * noseScale * scale,
        cy + 1.9f * noseScale * scale + noseOffsetY,
      )
      path.quadraticTo(
        cx - 1.6f * noseScale * scale,
        cy + 4.6f * noseScale * scale + noseOffsetY,
        cx + 1.2f * noseScale * scale,
        cy + 4.0f * noseScale * scale + noseOffsetY,
      )
      path.cubicTo(
        cx + 3.9f * noseScale * scale,
        cy + 3.2f * noseScale * scale + noseOffsetY,
        cx + 4.2f * noseScale * scale,
        cy - 0.5f * noseScale * scale + noseOffsetY,
        cx + 1.8f * noseScale * scale,
        cy - 3.4f * noseScale * scale + noseOffsetY,
      )
      path.quadraticTo(
        cx + 0.6f * noseScale * scale,
        cy - 4.35f * noseScale * scale + noseOffsetY,
        cx - 0.4f * noseScale * scale,
        cy - 3.9f * noseScale * scale + noseOffsetY,
      )
      path.close()
      drawPath(path, WoodBase)
      drawOval(
        color = WoodLight.copy(alpha = 0.38f),
        topLeft = Offset(cx - 2.2f * scale, cy - 1.55f * scale + noseOffsetY),
        size = Size(2.8f * scale, 1.95f * scale),
      )
      drawPath(
        path = path,
        color = WoodShadow,
        style = Stroke(width = 1.15f * scale, cap = StrokeCap.Round, join = StrokeJoin.Round),
      )
      path.reset()
      path.moveTo(cx - 2.95f * scale, cy + 2.7f * scale + noseOffsetY)
      path.quadraticTo(cx - 1.85f * scale, cy + 4.15f * scale + noseOffsetY, cx - 0.2f * scale, cy + 3.95f * scale + noseOffsetY)
      drawPath(path, WoodShadow, style = Stroke(width = 0.9f * scale, cap = StrokeCap.Round, join = StrokeJoin.Round))
      path.reset()
      path.moveTo(cx + 0.7f * scale, cy + 3.75f * scale + noseOffsetY)
      path.quadraticTo(cx + 2.25f * scale, cy + 4.0f * scale + noseOffsetY, cx + 3.25f * scale, cy + 2.7f * scale + noseOffsetY)
      drawPath(path, WoodShadow, style = Stroke(width = 0.9f * scale, cap = StrokeCap.Round, join = StrokeJoin.Round))

      drawTripleTMouthFeature(
        mouthCenter = mouthCenter,
        mouthHalfWidth = mouthHalfWidth,
        mouthCurveDir = mouthCurveDir,
        mouthCurveProgress = mouthCurveProgress,
        scale = scale,
        path = path,
        detailPath = detailPath,
      )
    }
    else {
      drawTripleTBackCheeks(
        cx = cx,
        bottom = bottom,
        scale = scale,
      )
    }

    if (propSide != 0) {
      drawTripleTPropFeature(
        palm = if (propSide < 0) leftPalm else rightPalm,
        beatProgress = propBeatProgress,
        side = propSide,
        path = path,
        detailPath = detailPath,
        scale = scale,
      )
    }

  }
}

private fun DrawScope.drawSidewaysFoot(
  footCenter: Offset,
  scale: Float,
  path: Path,
  alpha: Float,
  fillColor: Color = WoodBase,
  highlightAlpha: Float = 0.22f,
) {
  val fill = fillColor.copy(alpha = alpha)
  val outline = WoodShadow.copy(alpha = alpha)
  val highlight = WoodLight.copy(alpha = highlightAlpha * alpha)
  val backOutline = WoodShadow.copy(alpha = 0.42f * alpha)
  val outlinePath = Path()
  val toe = Offset(footCenter.x + 10.1f * scale, footCenter.y + 0.55f * scale)
  val heel = Offset(footCenter.x - 6.4f * scale, footCenter.y - 0.15f * scale)
  val top = footCenter.y - 2.1f * scale
  val bottom = footCenter.y + 3.9f * scale
  val outerTop = footCenter.y - 1.45f * scale
  val archBottom = footCenter.y + 2.55f * scale
  val heelTop = Offset(heel.x, footCenter.y - 0.95f * scale)
  val upperFront = Offset(footCenter.x + 4.8f * scale, outerTop)

  path.reset()
  path.moveTo(heelTop.x, heelTop.y)
  path.quadraticTo(footCenter.x - 2.6f * scale, top, footCenter.x + 4.8f * scale, outerTop)
  path.quadraticTo(toe.x + 1.8f * scale, footCenter.y - 0.45f * scale, toe.x, footCenter.y + 1.4f * scale)
  path.quadraticTo(footCenter.x + 6.2f * scale, bottom, footCenter.x - 0.8f * scale, archBottom)
  path.quadraticTo(heel.x - 1.4f * scale, footCenter.y + 2.0f * scale, heelTop.x, heelTop.y)
  path.close()

  outlinePath.reset()
  outlinePath.moveTo(upperFront.x, upperFront.y)
  outlinePath.quadraticTo(toe.x + 1.8f * scale, footCenter.y - 0.45f * scale, toe.x, footCenter.y + 1.4f * scale)
  outlinePath.quadraticTo(footCenter.x + 6.2f * scale, bottom, footCenter.x - 0.8f * scale, archBottom)
  outlinePath.quadraticTo(heel.x - 1.4f * scale, footCenter.y + 2.0f * scale, heelTop.x, heelTop.y)

  drawPath(outlinePath, backOutline, style = Stroke(width = 3.1f * scale, cap = StrokeCap.Round, join = StrokeJoin.Round))
  drawPath(path, fill)
  drawPath(outlinePath, outline, style = Stroke(width = 1.55f * scale, cap = StrokeCap.Round, join = StrokeJoin.Round))
  drawOval(
    color = highlight,
    topLeft = Offset(footCenter.x - 0.2f * scale, footCenter.y - 1.8f * scale),
    size = Size(5.7f * scale, 1.35f * scale),
  )
}

private fun DrawScope.drawSidewaysAnkleConnector(
  ankle: Offset,
  scale: Float,
  fill: Color,
  backOutline: Color,
) {
  val topLeft = Offset(ankle.x - 2.15f * scale, ankle.y - 0.2f * scale)
  val size = Size(4.3f * scale, 5.2f * scale)
  val radius = CornerRadius(2.1f * scale, 2.1f * scale)
  drawRoundRect(
    color = backOutline,
    topLeft = Offset(topLeft.x - 0.55f * scale, topLeft.y - 0.25f * scale),
    size = Size(size.width + 1.1f * scale, size.height + 0.7f * scale),
    cornerRadius = CornerRadius(2.5f * scale, 2.5f * scale),
  )
  drawRoundRect(
    color = fill,
    topLeft = topLeft,
    size = size,
    cornerRadius = radius,
  )
}

private fun DrawScope.drawSidewaysHand(
  palm: Offset,
  scale: Float,
  path: Path,
  alpha: Float,
) {
  val fill = WoodBase.copy(alpha = alpha)
  val outline = WoodShadow.copy(alpha = alpha)
  val highlight = WoodLight.copy(alpha = 0.25f * alpha)

  path.reset()
  path.moveTo(palm.x - 3.4f * scale, palm.y - 2.2f * scale)
  path.quadraticTo(palm.x + 2.0f * scale, palm.y - 3.5f * scale, palm.x + 4.1f * scale, palm.y - 0.7f * scale)
  path.quadraticTo(palm.x + 4.8f * scale, palm.y + 1.9f * scale, palm.x + 2.3f * scale, palm.y + 3.5f * scale)
  path.quadraticTo(palm.x - 1.7f * scale, palm.y + 4.2f * scale, palm.x - 3.6f * scale, palm.y + 1.3f * scale)
  path.quadraticTo(palm.x - 4.2f * scale, palm.y - 0.8f * scale, palm.x - 3.4f * scale, palm.y - 2.2f * scale)
  path.close()
  drawPath(path, fill)
  drawPath(path, outline, style = Stroke(width = 1.45f * scale, cap = StrokeCap.Round, join = StrokeJoin.Round))
  drawOval(
    color = highlight,
    topLeft = Offset(palm.x - 0.8f * scale, palm.y - 1.95f * scale),
    size = Size(2.8f * scale, 1.1f * scale),
  )
}

private fun DrawScope.drawTripleTSideways(
  path: Path,
  detailPath: Path,
  surfacePath: Path,
  cx: Float,
  cy: Float,
  bodySize: Float,
  visiblePalmWorldPos: Offset,
  rearPalmWorldPos: Offset,
  visibleFootWorldPos: Offset,
  rearFootWorldPos: Offset,
  visibleArmBendDir: Float,
  rearArmBendDir: Float,
  visibleLegBendDir: Float,
  rearLegBendDir: Float,
  bodyRotation: Float,
  mouthCurveDir: Float,
  mouthCurveProgress: Float,
  mouthSizeMultiplier: Float,
  propBeatProgress: Float,
) {
  val scale = bodySize / 76f
  val bodyTop = cy - 41f * scale
  val bodyBottom = cy + 40f * scale
  val bodyBack = cx - 18f * scale
  val bodyFront = cx + 18f * scale
  val bodyMidY = (bodyTop + bodyBottom) / 2f
  val clampedMouthCurveProgress = mouthCurveProgress.coerceIn(0f, 1f)
  val clampedMouthSizeMultiplier = mouthSizeMultiplier.coerceAtLeast(0f)
  val faceShiftY = TripleTSideFaceShiftY * scale
  val rearShoulder = Offset(cx - 4.2f * scale, cy + TripleTSideRearArmAnchorY * scale)
  val visibleShoulder = Offset(cx + 2.4f * scale, cy + TripleTSideVisibleArmAnchorY * scale)
  val rearHip = Offset(cx - 1.8f * scale, bodyBottom - 10.8f * scale)
  val visibleHip = Offset(cx + 1.8f * scale, bodyBottom - 10.1f * scale)
  val rearAnkle = Offset(rearFootWorldPos.x, rearFootWorldPos.y - 2.7f * scale)
  val visibleAnkle = Offset(visibleFootWorldPos.x, visibleFootWorldPos.y - 2.7f * scale)

  val rearElbow =
    solveJoint(rearShoulder, rearPalmWorldPos, TripleTSideArmUpperLength * scale, TripleTSideArmLowerLength * scale, rearArmBendDir)
  val visibleElbow = solveJoint(visibleShoulder,
                                visiblePalmWorldPos,
                                TripleTSideArmUpperLength * scale,
                                TripleTSideArmLowerLength * scale,
                                visibleArmBendDir)
  val rearKnee = solveJoint(rearHip, rearAnkle, 25f * scale, 27f * scale, rearLegBendDir)
  val visibleKnee = solveJoint(visibleHip, visibleAnkle, 26f * scale, 28f * scale, visibleLegBendDir)

  withTransform({ rotate(bodyRotation, Offset(cx, cy)) }) {
    val rearFill = WoodDark.copy(alpha = 0.72f)
    val rearOutline = WoodShadow.copy(alpha = 0.72f)
    val rearBackOutline = WoodShadow.copy(alpha = 0.34f)
    val visibleBackOutline = WoodShadow.copy(alpha = 0.38f)
    drawSidewaysAnkleConnector(rearAnkle, scale * 0.92f, rearFill, rearBackOutline)
    drawOutlinedPolyline(
      points = listOf(rearHip, rearKnee, rearAnkle),
      width = 4.2f * scale,
      outlineExtra = 4.0f * scale,
      fillColor = rearBackOutline,
      outlineColor = rearBackOutline,
      path = path,
      outlineEndExtension = 2.4f * scale,
    )
    drawOutlinedPolyline(
      points = listOf(rearHip, rearKnee, rearAnkle),
      width = 4.2f * scale,
      outlineExtra = 1.8f * scale,
      fillColor = rearFill,
      outlineColor = rearOutline,
      path = path,
      outlineEndExtension = 2.1f * scale,
    )
    drawSidewaysFoot(rearFootWorldPos, scale * 0.92f, path, TripleTSideRearFootAlpha, WoodDark, 0.08f)
    drawSidewaysAnkleConnector(
      visibleAnkle,
      scale,
      WoodBase.copy(alpha = 0.88f),
      visibleBackOutline,
    )
    drawOutlinedPolyline(
      points = listOf(visibleHip, visibleKnee, visibleAnkle),
      width = 4.7f * scale,
      outlineExtra = 4.1f * scale,
      fillColor = visibleBackOutline,
      outlineColor = visibleBackOutline,
      path = path,
      outlineEndExtension = 2.6f * scale,
    )
    drawOutlinedPolyline(
      points = listOf(visibleHip, visibleKnee, visibleAnkle),
      width = 4.7f * scale,
      outlineExtra = 1.85f * scale,
      fillColor = WoodBase.copy(alpha = 0.88f),
      outlineColor = WoodShadow.copy(alpha = 0.88f),
      path = path,
      outlineEndExtension = 2.3f * scale,
    )
    drawSidewaysFoot(visibleFootWorldPos, scale, path, TripleTSideVisibleFootAlpha)
    drawOutlinedPolyline(
      points = listOf(rearShoulder, rearElbow, rearPalmWorldPos),
      width = 3.7f * scale,
      outlineExtra = 1.65f * scale,
      fillColor = rearFill,
      outlineColor = rearOutline,
      path = path,
    )
    drawSidewaysHand(rearPalmWorldPos, scale * 0.94f, path, 0.75f)

    surfacePath.reset()
    surfacePath.moveTo(cx - 1.8f * scale, bodyTop)
    surfacePath.lineTo(bodyBack + 4.8f * scale, bodyTop + 2.0f * scale)
    surfacePath.lineTo(bodyBack + 1.7f * scale, bodyTop + 12.0f * scale)
    surfacePath.lineTo(bodyBack + 0.3f * scale, bodyBottom - 12.2f * scale)
    surfacePath.lineTo(bodyBack + 3.2f * scale, bodyBottom - 2.2f * scale)
    surfacePath.quadraticTo(cx - 2.4f * scale, bodyBottom + 0.5f * scale, cx - 0.9f * scale, bodyBottom)
    surfacePath.lineTo(bodyFront + 0.3f * scale, bodyBottom - 2.2f * scale)
    surfacePath.lineTo(bodyFront + 3.2f * scale, bodyBottom - 12.2f * scale)
    surfacePath.lineTo(bodyFront + 4.8f * scale, bodyTop + 12.0f * scale)
    surfacePath.lineTo(bodyFront + 1.7f * scale, bodyTop + 2.0f * scale)
    surfacePath.quadraticTo(cx + 2.4f * scale, bodyTop + 0.5f * scale, cx - 1.8f * scale, bodyTop)
    surfacePath.close()
    drawPath(surfacePath, WoodBase)

    clipPath(surfacePath) {
      drawRect(
        color = WoodLight.copy(alpha = 0.12f),
        topLeft = Offset(cx - 2.0f * scale, bodyTop + 6f * scale),
        size = Size(6.8f * scale, bodyBottom - bodyTop - 13f * scale),
      )
      drawRect(
        color = WoodDark.copy(alpha = 0.12f),
        topLeft = Offset(bodyBack + 5.5f * scale, bodyTop + 4.8f * scale),
        size = Size(3.1f * scale, bodyBottom - bodyTop - 9.4f * scale),
      )
    }
    drawWoodGrain(surfacePath, bodyBack - 1.2f * scale, bodyTop, bodyFront + 2.8f * scale, bodyBottom, scale, detailPath)
    val bodyOutlineStyle = Stroke(width = 1.9f * scale, cap = StrokeCap.Round, join = StrokeJoin.Round)
    drawPath(surfacePath, WoodShadow, style = bodyOutlineStyle)

    clipPath(surfacePath) {
      drawTripleTMouthFeature(
        mouthCenter = Offset(bodyFront, cy + (6f + TripleTSideFaceShiftY) * scale),
        mouthHalfWidth = 11.6f * clampedMouthSizeMultiplier * scale,
        mouthCurveDir = mouthCurveDir,
        mouthCurveProgress = mouthCurveProgress,
        scale = scale,
        path = path,
        detailPath = detailPath,
      )
    }
    drawPath(surfacePath, WoodShadow, style = bodyOutlineStyle)

    val noseTopLeft = Offset(bodyFront - 0.9f * scale, bodyMidY - 9.6f * scale + faceShiftY)
    val noseSize = Size(8.7f * scale, 11.1f * scale)
    drawOval(
      color = WoodBase,
      topLeft = noseTopLeft,
      size = noseSize,
    )
    drawOval(
      color = WoodLight.copy(alpha = 0.34f),
      topLeft = Offset(noseTopLeft.x + 1.45f * scale, noseTopLeft.y + 1.75f * scale),
      size = Size(2.9f * scale, 1.95f * scale),
    )
    drawArc(
      color = WoodShadow,
      startAngle = -82f,
      sweepAngle = 184f,
      useCenter = false,
      topLeft = noseTopLeft,
      size = noseSize,
      style = Stroke(width = 1.25f * scale, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
    path.reset()
    path.moveTo(noseTopLeft.x + 2.9f * scale, noseTopLeft.y + 8.1f * scale)
    path.quadraticTo(noseTopLeft.x + 4.7f * scale,
                     noseTopLeft.y + 9.45f * scale,
                     noseTopLeft.x + 6.3f * scale,
                     noseTopLeft.y + 7.95f * scale)
    drawPath(path, WoodShadow, style = Stroke(width = 0.92f * scale, cap = StrokeCap.Round, join = StrokeJoin.Round))

    val eyeCenter = Offset(bodyFront + 1.3f * scale, bodyTop + 28.4f * scale + faceShiftY)
    drawTripleTEyeFeature(
      center = eyeCenter,
      winkProgress = 0f,
      eyeRadiusX = 6.1f * scale,
      eyeRadiusY = 6.7f * scale,
      pupilRadius = 4.05f * scale,
      pupilOffset = Offset(0.95f * scale, 0.15f * scale),
      eyeWinkDir = 0f,
      browStart = Offset(eyeCenter.x - 7.2f * scale, eyeCenter.y - 8.1f * scale),
      browCtrl = Offset(eyeCenter.x + 1.8f * scale, eyeCenter.y - 12.1f * scale),
      browEnd = Offset(eyeCenter.x + 8.2f * scale, eyeCenter.y - 7.7f * scale),
      browStrokeWidth = 3.8f * scale,
      path = path,
      eyebrowClipPath = surfacePath,
    )

    drawOutlinedPolyline(
      points = listOf(visibleShoulder, visibleElbow, visiblePalmWorldPos),
      width = 4.3f * scale,
      outlineExtra = 1.75f * scale,
      fillColor = WoodBase,
      outlineColor = WoodShadow,
      path = path,
    )
    drawTripleTPropFeature(
      palm = visiblePalmWorldPos,
      beatProgress = propBeatProgress,
      side = 1,
      path = path,
      detailPath = detailPath,
      scale = scale,
    )
    drawSidewaysHand(visiblePalmWorldPos, scale, path, 1f)
  }
}

enum class TripleTSidewaysDirection {
  LEFT,
  RIGHT;
}

@Composable
fun TripleTSideways2D(
  modifier: Modifier = Modifier,
  direction: TripleTSidewaysDirection = TripleTSidewaysDirection.RIGHT,
  bodySizeFraction: Float = 0.58f,
  bodyOffset: Offset = Offset.Zero,
  visiblePalmWorldPos: Offset = Offset(0.11f, 0.35f),
  rearPalmWorldPos: Offset = Offset(0.12f, 0.5f),
  visibleFootWorldPos: Offset = Offset(0.1f, 1.08f),
  rearFootWorldPos: Offset = Offset(-0.08f, 1.09f),
  visibleArmBendDir: Float = 0.12f,
  rearArmBendDir: Float = 0.08f,
  visibleLegBendDir: Float = -0.14f,
  rearLegBendDir: Float = -0.12f,
  bodyRotation: Float = 3.4f,
  mouthCurveDir: Float = -0.08f,
  mouthCurveProgress: Float = 1f,
  mouthSizeMultiplier: Float = 1f,
  propBeatProgress: Float = 0f,
) {
  val path = remember { Path() }
  val detailPath = remember { Path() }
  val surfacePath = remember { Path() }
  val clampedBodySizeFraction = bodySizeFraction.coerceAtLeast(0f)
  val clampedMouthCurveProgress = mouthCurveProgress.coerceIn(0f, 1f)
  val clampedMouthSizeMultiplier = mouthSizeMultiplier.coerceAtLeast(0f)
  val clampedPropBeatProgress = propBeatProgress.coerceIn(0f, 1f)

  Canvas(modifier = modifier) {
    val canvasCx = size.width / 2f
    val canvasCy = size.height / 2f
    val bodySize = minOf(size.width, size.height) * clampedBodySizeFraction
    val cx = canvasCx + bodyOffset.x * bodySize
    val cy = canvasCy + bodyOffset.y * bodySize

    withTransform({
                    if (direction == TripleTSidewaysDirection.LEFT) {
                      scale(scaleX = -1f, scaleY = 1f, pivot = Offset(canvasCx, canvasCy))
                    }
                  }) {
      drawTripleTSideways(
        path = path,
        detailPath = detailPath,
        surfacePath = surfacePath,
        cx = cx,
        cy = cy,
        bodySize = bodySize,
        visiblePalmWorldPos = Offset(canvasCx + visiblePalmWorldPos.x * bodySize, canvasCy + visiblePalmWorldPos.y * bodySize),
        rearPalmWorldPos = Offset(canvasCx + rearPalmWorldPos.x * bodySize, canvasCy + rearPalmWorldPos.y * bodySize),
        visibleFootWorldPos = Offset(canvasCx + visibleFootWorldPos.x * bodySize, canvasCy + visibleFootWorldPos.y * bodySize),
        rearFootWorldPos = Offset(canvasCx + rearFootWorldPos.x * bodySize, canvasCy + rearFootWorldPos.y * bodySize),
        visibleArmBendDir = visibleArmBendDir,
        rearArmBendDir = rearArmBendDir,
        visibleLegBendDir = visibleLegBendDir,
        rearLegBendDir = rearLegBendDir,
        bodyRotation = bodyRotation,
        mouthCurveDir = mouthCurveDir,
        mouthCurveProgress = clampedMouthCurveProgress,
        mouthSizeMultiplier = clampedMouthSizeMultiplier,
        propBeatProgress = clampedPropBeatProgress,
      )
    }
  }
}

@Composable
fun TripleT2D(
  modifier: Modifier = Modifier,
  bodySizeFraction: Float = 0.58f,
  bodyOffset: Offset = Offset.Zero,
  leftPalmWorldPos: Offset = Offset(-0.66f, 0.67f),
  rightPalmWorldPos: Offset = Offset(0.77f, 0.02f),
  leftFootWorldPos: Offset = Offset(-0.14f, 1.11f),
  rightFootWorldPos: Offset = Offset(0.14f, 1.12f),
  leftArmBendDir: Float = 0.2f,
  rightArmBendDir: Float = -0.18f,
  leftLegBendDir: Float = 0.04f,
  rightLegBendDir: Float = -0.04f,
  bodyRotation: Float = 0f,
  leftEyeWinkProgress: Float = 0f,
  rightEyeWinkProgress: Float = 0f,
  eyeWinkDir: Float = 0f,
  mouthCurveDir: Float = -0.08f,
  mouthCurveProgress: Float = 1f,
  mouthSizeMultiplier: Float = 1f,
  isFront: Boolean = true,
  propBeatProgress: Float = 0f,
  propSide: Int = 1,
) {
  val path = remember { Path() }
  val detailPath = remember { Path() }
  val surfacePath = remember { Path() }
  val clampedLeftEyeWinkProgress = leftEyeWinkProgress.coerceIn(0f, 1f)
  val clampedRightEyeWinkProgress = rightEyeWinkProgress.coerceIn(0f, 1f)
  val clampedEyeWinkDir = eyeWinkDir.coerceIn(-1f, 1f)
  val clampedMouthCurveProgress = mouthCurveProgress.coerceIn(0f, 1f)
  val clampedMouthSizeMultiplier = mouthSizeMultiplier.coerceAtLeast(0f)
  val clampedPropBeatProgress = propBeatProgress.coerceIn(0f, 1f)
  val clampedPropSide = clampSelector(propSide)

  Canvas(modifier = modifier) {
    val canvasCx = size.width / 2f
    val canvasCy = size.height / 2f
    val bodySize = minOf(size.width, size.height) * bodySizeFraction.coerceAtLeast(0f)
    val cx = canvasCx + bodyOffset.x * bodySize
    val cy = canvasCy + bodyOffset.y * bodySize
    drawTripleT2D(
      path = path,
      detailPath = detailPath,
      surfacePath = surfacePath,
      cx = cx,
      cy = cy,
      bodySize = bodySize,
      leftPalmWorldPos = Offset(
        canvasCx + leftPalmWorldPos.x * bodySize,
        canvasCy + leftPalmWorldPos.y * bodySize
      ),
      rightPalmWorldPos = Offset(
        canvasCx + rightPalmWorldPos.x * bodySize,
        canvasCy + rightPalmWorldPos.y * bodySize
      ),
      leftFootWorldPos = Offset(
        canvasCx + leftFootWorldPos.x * bodySize,
        canvasCy + leftFootWorldPos.y * bodySize
      ),
      rightFootWorldPos = Offset(
        canvasCx + rightFootWorldPos.x * bodySize,
        canvasCy + rightFootWorldPos.y * bodySize
      ),
      leftArmBendDir = leftArmBendDir,
      rightArmBendDir = rightArmBendDir,
      leftLegBendDir = leftLegBendDir,
      rightLegBendDir = rightLegBendDir,
      bodyRotation = bodyRotation,
      leftEyeWinkProgress = clampedLeftEyeWinkProgress,
      rightEyeWinkProgress = clampedRightEyeWinkProgress,
      eyeWinkDir = clampedEyeWinkDir,
      mouthCurveDir = mouthCurveDir,
      mouthCurveProgress = clampedMouthCurveProgress,
      mouthSizeMultiplier = clampedMouthSizeMultiplier,
      isFront = isFront,
      propBeatProgress = clampedPropBeatProgress,
      propSide = clampedPropSide,
    )
  }
}
