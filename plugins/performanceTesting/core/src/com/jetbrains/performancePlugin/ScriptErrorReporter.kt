// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.performancePlugin

import com.intellij.diagnostic.AbstractMessage
import com.intellij.diagnostic.MessagePool
import com.intellij.openapi.application.ApplicationNamesInfo
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.application.ex.ApplicationInfoEx
import com.intellij.openapi.diagnostic.Attachment
import com.intellij.openapi.diagnostic.Logger
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

private val LOG: Logger
  get() = Logger.getInstance("PerformancePlugin")

private const val ERRORS_DIR = "errors"
private const val ERROR_DIR_PREFIX = "error-"
private const val MESSAGE_FILE = "message.txt"
private const val SYNTHETIC_TEST_NAME_FILE = "syntheticTestName.txt"
private const val STACKTRACE_FILE = "stacktrace.txt"
private const val PRODUCT_INFO_FILE = "product_info.txt"

fun reportErrorsFromMessagePool() {
  val messagePool = MessagePool.getInstance()
  val ideErrors = messagePool.getFatalErrors(false, true)
  for (message in ideErrors) {
    try {
      reportScriptError(message)
    }
    catch (e: IOException) {
      LOG.error(e)
    }
    finally {
      message.isRead = true
    }
  }
}

@Throws(IOException::class)
private fun reportScriptError(errorMessage: AbstractMessage) {
  val throwable = errorMessage.throwable
  var cause: Throwable? = throwable
  var causeMessage: String? = ""
  val maxSyntheticTestNameLength = 250
  var syntheticTestName: String? = throwable.javaClass.name + ": " + throwable.message
  while (cause!!.cause != null) {
    cause = cause.cause
    causeMessage = cause?.message?.let { "${cause.javaClass.name}: $it" } ?: causeMessage
  }
  if (!causeMessage.isNullOrEmpty()) {
    syntheticTestName = causeMessage
  }
  if (causeMessage.isNullOrEmpty()) {
    causeMessage = errorMessage.message
    if (causeMessage.isNullOrEmpty()) {
      val throwableMessage = getNonEmptyThrowableMessage(throwable)
      val index = throwableMessage.indexOf("\tat ")
      causeMessage = if (index == -1) throwableMessage else throwableMessage.take(index)
    }
  }
  val scriptErrorsDir = Path.of(PathManager.getLogPath(), ERRORS_DIR)
  Files.createDirectories(scriptErrorsDir)
  Files.walk(scriptErrorsDir).use { stream ->
    val finalCauseMessage = causeMessage
    val isDuplicated = stream
      .filter { path -> path.fileName.toString() == MESSAGE_FILE }
      .anyMatch { path ->
        try {
          return@anyMatch Files.readString(path) == finalCauseMessage
        }
        catch (e: IOException) {
          LOG.error(e.message)
          return@anyMatch false
        }
      }
    if (isDuplicated) {
      return
    }
  }

  val appInfo = ApplicationInfoEx.getInstanceEx()
  val namesInfo = ApplicationNamesInfo.getInstance()
  val build = appInfo.build

  for (i in 1..999) {
    val errorDir = scriptErrorsDir.resolve("$ERROR_DIR_PREFIX$i")
    if (Files.exists(errorDir)) {
      continue
    }

    Files.createDirectories(errorDir)
    Files.writeString(errorDir.resolve(MESSAGE_FILE), causeMessage)
    Files.writeString(errorDir.resolve(SYNTHETIC_TEST_NAME_FILE), (syntheticTestName ?: causeMessage).take(maxSyntheticTestNameLength))
    Files.writeString(errorDir.resolve(STACKTRACE_FILE), errorMessage.throwableText)
    Files.writeString(errorDir.resolve(PRODUCT_INFO_FILE), buildString {
      appendLine("app.name=${namesInfo.productName}")
      appendLine("app.name.full=${namesInfo.fullProductName}")
      appendLine("app.product.code=${build.productCode}")
    })
    val attachments = errorMessage.allAttachments
    val nameConflicts = attachments.groupBy { it.name }.filter { it.value.size > 1 }.keys

    for (j in attachments.indices) {
      val attachment = attachments[j]
      val fileName = if (attachment.name in nameConflicts) {
        addSuffixBeforeExtension(attachment.name, "-$j")
      } else {
        attachment.name
      }
      writeAttachmentToErrorDir(attachment, errorDir.resolve(fileName))
    }
    return
  }

  LOG.error("Too many errors have been reported during script execution. See $scriptErrorsDir")
}

private fun addSuffixBeforeExtension(fileName: String, suffix: String): String {
  val lastDotIndex = fileName.lastIndexOf('.')
  return if (lastDotIndex != -1) {
    fileName.take(lastDotIndex) + suffix + fileName.substring(lastDotIndex)
  } else {
    fileName + suffix
  }
}

private fun writeAttachmentToErrorDir(attachment: Attachment, path: Path) {
  try {
    Files.writeString(path, attachment.displayText, StandardOpenOption.APPEND, StandardOpenOption.CREATE)
    Files.writeString(path, System.lineSeparator(), StandardOpenOption.APPEND, StandardOpenOption.CREATE)
  }
  catch (e: Exception) {
    LOG.warn("Failed to write attachment `display text`", e)
  }
}

private fun getNonEmptyThrowableMessage(throwable: Throwable): String {
  if (throwable.message != null && !throwable.message!!.isEmpty()) {
    return throwable.message!!
  }
  else {
    return throwable.javaClass.name
  }
}