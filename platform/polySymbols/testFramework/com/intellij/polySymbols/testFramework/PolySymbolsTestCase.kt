package com.intellij.polySymbols.testFramework

import com.intellij.codeInsight.TargetElementUtil
import com.intellij.codeInsight.highlighting.actions.HighlightUsagesAction
import com.intellij.codeInsight.lookup.Lookup
import com.intellij.codeInsight.navigation.actions.GotoDeclarationOrUsageHandler2
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.injected.editor.EditorWindow
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.application.impl.NonBlockingReadActionImpl
import com.intellij.openapi.application.invokeAndWaitIfNeeded
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.editor.ex.RangeHighlighterEx
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.RootsChangeRescanningInfo
import com.intellij.openapi.roots.ex.ProjectRootManagerEx
import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.EmptyRunnable
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileFilter
import com.intellij.openapi.vfs.VirtualFileVisitor
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiLanguageInjectionHost
import com.intellij.psi.SyntaxTraverser
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.psi.codeStyle.CodeStyleSettings
import com.intellij.psi.codeStyle.CodeStyleSettingsManager
import com.intellij.psi.impl.source.PostprocessReformattingAspect
import com.intellij.psi.search.SearchScope
import com.intellij.refactoring.rename.RenameProcessor
import com.intellij.refactoring.rename.RenamePsiElementProcessor
import com.intellij.testFramework.ExpectedHighlightingData
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.VfsTestUtil
import com.intellij.testFramework.fixtures.CodeInsightTestFixture
import com.intellij.testFramework.fixtures.CompletionAutoPopupTester
import com.intellij.testFramework.fixtures.impl.CodeInsightTestFixtureImpl
import com.intellij.testFramework.runInEdtAndWait
import com.intellij.testFramework.utils.io.createFile
import com.intellij.usageView.UsageInfo
import com.intellij.util.ui.UIUtil
import org.editorconfig.Utils
import org.editorconfig.configmanagement.extended.EditorConfigCodeStyleSettingsModifier
import java.io.FileNotFoundException
import java.nio.file.Path
import java.util.TreeMap
import kotlin.io.path.exists
import kotlin.time.Duration.Companion.minutes

abstract class PolySymbolsTestCase(mode: HybridTestMode = HybridTestMode.BasePlatform) : HybridTestCase(mode) {

  protected abstract val testDataRoot: String

  protected abstract val testCasePath: String

  protected abstract val defaultExtension: String

  protected abstract val defaultDependencies: Map<String, String>

  protected open val defaultConfigurators: List<PolySymbolsTestConfigurator> = emptyList()

  protected open val dirModeByDefault: Boolean = false

  val testName: String get() = getTestName(true)

  @Throws(Exception::class)
  override fun setUp() {
    super.setUp()
    enableAstLoadingFilter()
  }

  final override fun getTestDataPath(): String = "$testDataRoot/${testCasePath}"

  protected open fun beforeConfiguredTest(configuration: TestConfiguration) {

  }

  protected open fun afterConfiguredTest(configuration: TestConfiguration) {

  }

  protected open fun ensureIndexesReady() {
    IndexingTestUtil.waitUntilIndexesAreReady(myFixture.getProject())
  }

  protected open fun waitForAsyncOperationsToCompleteAfterEdit() {

  }

  protected open val directoriesCompareFileFilter: VirtualFileFilter
    get() = { true }

  protected open fun getExpectedItemsLocation(dir: Boolean): String =
    getExpectedDataLocation(dir)

  protected fun withTempCodeStyleSettings(test: CodeInsightTestFixture.(settings: CodeStyleSettings) -> Unit) {
    myFixture.testWithTempCodeStyleSettings { t: CodeStyleSettings ->
      myFixture.test(t)
    }
  }

  fun doConfiguredTest(
    fileContents: String? = null,
    dir: Boolean = dirModeByDefault,
    dirName: String = testName,
    extension: String = defaultExtension,
    configureFile: Boolean = true,
    configureFileName: String = "$testName.$extension",
    configurators: List<PolySymbolsTestConfigurator> = defaultConfigurators,
    additionalFiles: List<String> = emptyList(),
    checkResult: Boolean = false,
    editorConfigEnabled: Boolean = false,
    configureCodeStyleSettings: (CodeStyleSettings.() -> Unit)? = null,
    test: CodeInsightTestFixture.() -> Unit,
  ) {
    if (editorConfigEnabled) {
      EditorConfigCodeStyleSettingsModifier.Handler.setEnabledInTests(true)
      Utils.isEnabledInTests = true
    }
    CodeInsightTestFixtureImpl.ensureIndexesUpToDate(project)
    try {
      myFixture.apply {
        if (dir) {
          if (checkResult) {
            copyDirectoryToProject("$dirName/before", ".")
          }
          else {
            copyDirectoryToProject(dirName, ".")
          }
        }
        else if (additionalFiles.isNotEmpty()) {
          configureByFiles(*additionalFiles.toTypedArray())
        }

        configurators.forEach {
          it.configure(myFixture)
        }
        // After copying the files, some files might have been indexed with incorrect PolyContext,
        // ensure we have all files scanned again and indexed correctly
        WriteAction.run<RuntimeException> {
          ProjectRootManagerEx.getInstanceEx(project)
            .makeRootsChange(EmptyRunnable.getInstance(), RootsChangeRescanningInfo.TOTAL_RESCAN)
        }
        ensureIndexesReady()
        if (configureFile) {
          if (fileContents != null) {
            configureByText(configureFileName, fileContents)
          }
          else if (dir) {
            configureFromTempProjectFile(configureFileName)
              .also {
                it.virtualFile.putUserData(
                  VfsTestUtil.TEST_DATA_FILE_PATH,
                  "$testDataPath/$dirName/${if (checkResult) "before/" else ""}$configureFileName"
                )
              }
          }
          else {
            configureByFile(configureFileName)
          }
        }
        val testConfiguration = TestConfiguration(
          configurators
        )
        if (!editorConfigEnabled && configureCodeStyleSettings != null) {
          testWithTempCodeStyleSettings {
            it.configureCodeStyleSettings()
            beforeConfiguredTest(testConfiguration)
            ensureIndexesReady()
            try {
              test()
            }
            finally {
              afterConfiguredTest(testConfiguration)
            }
          }
        }
        else {
          if (editorConfigEnabled) {
            CodeStyleSettingsManager.getInstance(project).dropTemporarySettings()
          }
          beforeConfiguredTest(testConfiguration)
          ensureIndexesReady()
          try {
            test()
          }
          finally {
            afterConfiguredTest(testConfiguration)
          }
        }
        waitForAsyncOperationsToCompleteAfterEdit()
        if (checkResult) {
          WriteCommandAction.runWriteCommandAction(getProject()) {
            PostprocessReformattingAspect.getInstance(getProject()).doPostponedFormatting()
          }
          FileDocumentManager.getInstance().saveAllDocuments()
          if (dir) {
            val pathAfter = "$testDataPath/$dirName/after"
            val rootAfter = LocalFileSystem.getInstance().findFileByPath(pathAfter)
                            ?: throw FileNotFoundException(pathAfter)
            val results = myFixture.tempDirFixture.findOrCreateDir(".")

            // Trigger any advanced configurators
            configurators.forEach { it.beforeDirectoryComparison(myFixture, results, rootAfter) }

            // Set test data file path, so that comparison works
            val root = tempDirFixture.findOrCreateDir(".")
            val filter = directoriesCompareFileFilter
            VfsUtil.visitChildrenRecursively(root, object : VirtualFileVisitor<Any>() {
              override fun visitFileEx(file: VirtualFile): Result =
                if (!filter.accept(file))
                  SKIP_CHILDREN
                else {
                  file.putUserData(
                    VfsTestUtil.TEST_DATA_FILE_PATH,
                    pathAfter + "/" + VfsUtil.getRelativePath(file, root)
                  )
                  CONTINUE
                }
            })

            PlatformTestUtil.assertDirectoriesEqual(rootAfter, results, filter)
          }
          else {
            val ext = InjectedLanguageManager.getInstance(project).getTopLevelFile(myFixture.file)
              .name.takeLastWhile { it != '.' }
            myFixture.checkResultByFile("${testName}_after.$ext")
          }
        }
      }
    }
    finally {
      if (editorConfigEnabled) {
        EditorConfigCodeStyleSettingsModifier.Handler.setEnabledInTests(false)
        Utils.isEnabledInTests = false
      }
    }
  }

  protected fun doCommentTest(commentStyle: CommentStyle, id: Int? = null, extension: String = defaultExtension) {
    val name = getTestName(true)
    myFixture.configureByFile("$name.$extension")
    try {
      myFixture.performEditorAction(
        if (commentStyle == CommentStyle.LINE) IdeActions.ACTION_COMMENT_LINE
        else IdeActions.ACTION_COMMENT_BLOCK
      )
      myFixture.checkResultByFile("${name}_after${id?.let { "_$it" } ?: ""}.$extension")
    }
    finally {
      myFixture.configureByFile("$name.$extension")
    }
  }

  protected fun doEditorTypingTest(
    dir: Boolean = dirModeByDefault,
    extension: String = defaultExtension,
    configureFileName: String = "$testName.$extension",
    checkResult: Boolean = true,
    configurators: List<PolySymbolsTestConfigurator> = defaultConfigurators,
    before: CodeInsightTestFixture.() -> Unit = {},
    after: CodeInsightTestFixture.() -> Unit = {},
    test: EditorTypingTestFixture.() -> Unit,
  ) {
    doConfiguredTest(
      dir = dir,
      extension = extension,
      configureFileName = configureFileName,
      checkResult = checkResult,
      configurators = configurators,
    ) {
      before()
      val tester = CompletionAutoPopupTester(myFixture)
      var finished = false
      var exception: Throwable? = null
      ApplicationManager.getApplication().executeOnPooledThread {
        try {
          tester.runWithAutoPopupEnabled {
            object : EditorTypingTestFixture {
              private var checkLookupCount = 0

              override fun type(str: String) {
                for (i in str.indices) {
                  myFixture.type(str[i])
                  ApplicationManager.getApplication().invokeAndWait {
                    NonBlockingReadActionImpl.waitForAsyncTaskCompletion()
                    UIUtil.dispatchAllInvocationEvents()
                  }
                  WriteAction.runAndWait<Throwable> { PsiDocumentManager.getInstance(project).commitAllDocuments() }
                  waitForAsyncOperationsToCompleteAfterEdit()
                  tester.joinAutopopup()
                  tester.joinCompletion()
                  tester.joinCommit()
                }
              }

              override fun assertLookupShown() {
                assertNotNull("Lookup should be shown", tester.lookup)
              }

              override fun assertLookupNotShown() {
                assertNull("Lookup should not be shown", tester.lookup)
              }

              override fun completeBasic() {
                myFixture.completeBasic()
              }

              override fun moveToOffsetBySignature(signature: String) {
                invokeAndWaitIfNeeded {
                  myFixture.moveToOffsetBySignature(signature)
                }
              }

              override fun checkLookupItems(
                renderPriority: Boolean,
                renderTypeText: Boolean,
                renderTailText: Boolean,
                renderProximity: Boolean,
                renderDisplayText: Boolean,
                renderDisplayEffects: Boolean,
                lookupItemFilter: (item: LookupElementInfo) -> Boolean,
              ) {
                assertLookupShown()
                tester.joinCompletion()

                val expectedFile = getExpectedItemsLocation(dir) +
                                   (if (dir) "/items" else "$testName.items") +
                                   ".${++checkLookupCount}.txt"

                checkListByFile(
                  actualList = renderLookupItems(
                    renderPriority = renderPriority,
                    renderTypeText = renderTypeText,
                    renderTailText = renderTailText,
                    renderProximity = renderProximity,
                    renderDisplayText = renderDisplayText,
                    renderDisplayEffects = renderDisplayEffects,
                    lookupFilter = lookupItemFilter,
                  ),
                  expectedFile = expectedFile,
                  containsCheck = false,
                )
              }

              override fun assertLookupContains(vararg items: String) {
                assertLookupShown()
                tester.joinCompletion()
                assertContainsElements(myFixture.lookupElementStrings!!, *items)
              }

              override fun assertLookupDoesntContain(vararg items: String) {
                assertLookupShown()
                tester.joinCompletion()
                assertDoesntContain(myFixture.lookupElementStrings!!, *items)
              }

              override fun selectLookupItem(item: String) {
                assertLookupShown()
                tester.joinCompletion()
                invokeAndWaitIfNeeded {
                  myFixture.lookup.let { lookup ->
                    lookup.currentItem = lookup.items.firstOrNull { it.lookupString == item }
                                         ?: throw RuntimeException("Item '$item' not found")
                  }
                }
              }

            }.test()
          }
        }
        catch (@Suppress("IncorrectCancellationExceptionHandling") t: Throwable) {
          exception = t
        }
        finally {
          finished = true
        }
      }
      val start = System.currentTimeMillis()
      while (!finished) {
        if (System.currentTimeMillis() - start > 2.minutes.inWholeMilliseconds) {
          fail("Too long completion auto-popup test.")
        }
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        Thread.sleep(2)
      }
      exception?.let { throw it }
      after()
    }
  }

  private fun getExpectedDataLocation(dir: Boolean): String =
    if (dir) testName else ""

  protected fun doLookupTest(
    fileContents: String? = null,
    dir: Boolean = dirModeByDefault,
    extension: String = defaultExtension,
    configureFileName: String = "$testName.$extension",
    additionalFiles: List<String> = emptyList(),
    configurators: List<PolySymbolsTestConfigurator> = defaultConfigurators,
    renderPriority: Boolean = true,
    renderTypeText: Boolean = true,
    renderTailText: Boolean = false,
    renderProximity: Boolean = false,
    renderPresentedText: Boolean = false,
    checkDocumentation: Boolean = false,
    containsCheck: Boolean = false,
    typeToFinishLookup: String? = null,
    locations: List<String> = emptyList(),
    namedLocations: List<Pair<String, String>> = emptyList(),
    lookupItemFilter: (item: LookupElementInfo) -> Boolean = { true },
  ) {
    doConfiguredTest(
      fileContents = fileContents,
      dir = dir,
      extension = extension,
      configureFileName = configureFileName,
      additionalFiles = additionalFiles,
      configurators = configurators,
      checkResult = typeToFinishLookup != null,
    ) {
      assert(typeToFinishLookup == null || locations.isEmpty())
      checkLookupItems(
        renderPriority = renderPriority,
        renderTypeText = renderTypeText,
        renderTailText = renderTailText,
        renderProximity = renderProximity,
        renderDisplayText = renderPresentedText,
        checkDocumentation = checkDocumentation,
        containsCheck = containsCheck,
        locations = locations,
        namedLocations = namedLocations,
        expectedDataLocation = getExpectedDataLocation(dir),
        expectedItemsLocation = getExpectedItemsLocation(dir),
        lookupItemFilter = lookupItemFilter,
      )
      if (typeToFinishLookup != null) {
        type(typeToFinishLookup.takeWhile { it != '\n' && it != '\t' && it != '\r' })
        finishLookup(typeToFinishLookup.firstOrNull { it == '\n' || it == '\t' || it == '\r' }
                     ?: Lookup.NORMAL_SELECT_CHAR)
      }
    }
  }

  protected fun doFormattingTest(
    dir: Boolean = dirModeByDefault,
    extension: String = defaultExtension,
    configureFileName: String = "$testName.$extension",
    editorConfigEnabled: Boolean = false,
    configurators: List<PolySymbolsTestConfigurator> = defaultConfigurators,
    configureCodeStyleSettings: CodeStyleSettings.() -> Unit = {},
  ) {
    doConfiguredTest(
      dir = dir,
      extension = extension,
      checkResult = true,
      configureFileName = configureFileName,
      configureCodeStyleSettings = configureCodeStyleSettings,
      configurators = configurators,
      editorConfigEnabled = editorConfigEnabled,
    ) {
      val codeStyleManager = CodeStyleManager.getInstance(project)
      WriteCommandAction.runWriteCommandAction(project) { codeStyleManager.reformat(file) }
    }
  }

  protected fun doFoldingTest(
    extension: String = defaultExtension,
    configureFileName: String = "$testName.$extension",
    configurators: List<PolySymbolsTestConfigurator> = defaultConfigurators,
  ) {
    doConfiguredTest(extension = extension, checkResult = false, configureFile = false, configurators = configurators) {
      testFolding("$testDataPath/$configureFileName")
    }
  }

  protected fun doHighlightingTest(
    dir: Boolean = dirModeByDefault,
    extension: String = defaultExtension,
    configureFileName: String = "$testName.$extension",
    configurators: List<PolySymbolsTestConfigurator> = defaultConfigurators,
    inspections: Collection<Class<out LocalInspectionTool>> = emptyList(),
    checkSymbolNames: Boolean = false,
    checkWarnings: Boolean = true,
    checkWeakWarnings: Boolean = true,
    checkInformation: Boolean = checkSymbolNames,
    checkInjections: Boolean = false,
    setup: CodeInsightTestFixture.() -> Unit = {},
  ) {
    doConfiguredTest(
      dir = dir,
      extension = extension,
      configureFileName = configureFileName,
      configurators = configurators,
    ) {
      enableInspections(inspections)
      setup()
      if (checkSymbolNames || checkInjections) {
        checkHighlightingEx(checkWarnings, checkInformation, checkWeakWarnings, checkSymbolNames, checkInjections)
      }
      else {
        this.checkHighlighting(checkWarnings, checkInformation, checkWeakWarnings)
      }
    }
  }

  protected fun CodeInsightTestFixture.checkHighlightingEx(
    checkWarnings: Boolean = true,
    checkInfos: Boolean = false,
    checkWeakWarnings: Boolean = true,
    checkSymbolNames: Boolean = false,
    checkInjections: Boolean = false,
  ) {
    val data = ExpectedHighlightingData(getEditor().getDocument(), checkWarnings, checkWeakWarnings, checkInfos)
    if (checkSymbolNames) {
      data.checkSymbolNames()
    }
    data.init()
    if (checkInjections) {
      runInEdtAndWait { PsiDocumentManager.getInstance(myFixture.getProject()).commitAllDocuments() }
      val injectedLanguageManager = InjectedLanguageManager.getInstance(myFixture.getProject())
      // We need to ensure that injections are cached before we run PolySymbolsInspectionsPass
      SyntaxTraverser.psiTraverser(myFixture.getFile())
        .forEach { if (it is PsiLanguageInjectionHost) injectedLanguageManager.getInjectedPsiFiles(it) }
    }
    (this as CodeInsightTestFixtureImpl).collectAndCheckHighlighting(data)
  }

  protected fun doParameterInfoTest(
    dir: Boolean = dirModeByDefault,
    extension: String = defaultExtension,
    configurators: List<PolySymbolsTestConfigurator> = defaultConfigurators,
  ) {
    doConfiguredTest(dir = dir, extension = extension, configurators = configurators) {
      val info = getParameterInfoAtCaret()
      assertNotNull("Parameter info was not provided", info)
      val fileName = if (dir) "$testName/param-info.html" else "$testName.param-info.html"
      val testFilePath = Path.of("$testDataPath/$fileName")
      if (!testFilePath.exists()) {
        testFilePath.createFile()
        thisLogger().warn("File $testFilePath has been created.")
      }
      checkTextByFile(info!!, fileName)
    }
  }

  protected fun doGotoDeclarationTest(
    declarationSignature: String,
    dir: Boolean = dirModeByDefault,
    extension: String = defaultExtension,
    configureFileName: String = "$testName.$extension",
    expectedFileName: String? = null,
    configurators: List<PolySymbolsTestConfigurator> = defaultConfigurators,
  ) {
    doConfiguredTest(
      dir = dir,
      extension = extension,
      configureFileName = configureFileName,
      configurators = configurators,
    ) {
      checkGotoDeclaration(null, declarationSignature, expectedFileName = expectedFileName)
    }
  }

  protected fun doJumpToSourceTest(
    targetSignature: String,
    dir: Boolean = dirModeByDefault,
    extension: String = defaultExtension,
    configureFileName: String = "$testName.$extension",
    expectedFileName: String? = null,
    configurators: List<PolySymbolsTestConfigurator> = defaultConfigurators,
  ) {
    doConfiguredTest(dir = dir, extension = extension, configureFileName = configureFileName, configurators = configurators) {
      checkJumpToSource(null, targetSignature, expectedFileName = expectedFileName)
    }
  }

  protected fun doUsagesTest(
    extension: String = defaultExtension,
    fileName: String = "$testName.$extension",
    scope: SearchScope? = null,
    configurators: List<PolySymbolsTestConfigurator> = defaultConfigurators,
  ) {
    doConfiguredTest(dir = true, extension = extension, configureFileName = fileName, configurators = configurators) {
      checkGTDUOutcome(GotoDeclarationOrUsageHandler2.GTDUOutcome.SU)
      checkListByFile(usagesAtCaret(scope = scope, usagesTestHelper = usagesTestHelper), "${testName}/usages.txt", false)
    }
  }

  protected fun doFileUsagesTest(
    extension: String = defaultExtension,
    fileName: String = "$testName.$extension",
    scope: SearchScope? = null,
    configurators: List<PolySymbolsTestConfigurator> = defaultConfigurators,
  ) {
    doConfiguredTest(dir = true, extension = extension, configureFileName = fileName, configurators = configurators) {
      checkListByFile(fileUsages(scope = scope, usagesTestHelper = usagesTestHelper).sorted(), "${testName}/usages.txt", false)
    }
  }

  protected fun doUsageHighlightingTest(
    extension: String = defaultExtension,
    dir: Boolean = dirModeByDefault,
    configurators: List<PolySymbolsTestConfigurator> = defaultConfigurators,
  ) {
    doConfiguredTest(extension = extension, dir = dir, configurators = configurators) {
      val file = InjectedLanguageManager.getInstance(project).getTopLevelFile(file)
      val document = getDocument(file)
      val editor = (editor as? EditorWindow)?.delegate ?: editor
      WriteAction.run<Throwable> {
        var indexOf: Int
        WriteCommandAction.runWriteCommandAction(project) {
          while (document.charsSequence.indexOf("<usage>").also { indexOf = it } >= 0) {
            document.replaceString(indexOf, indexOf + "<usage>".length, "")
          }
          while (document.charsSequence.indexOf("</usage>").also { indexOf = it } >= 0) {
            document.replaceString(indexOf, indexOf + "</usage>".length, "")
          }
        }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
      }
      testAction(HighlightUsagesAction())
      val highlighters = editor.getMarkupModel().getAllHighlighters()
      val usages = TreeMap<Int, Int>(Comparator.reverseOrder())
      highlighters.forEach {
        val highlighterEx = it as RangeHighlighterEx
        usages[highlighterEx.getAffectedAreaStartOffset()] = highlighterEx.getAffectedAreaEndOffset()
      }
      WriteAction.run<Throwable> {
        WriteCommandAction.runWriteCommandAction(project) {
          usages.forEach {
            document.replaceString(it.value, it.value, "</usage>")
            document.replaceString(it.key, it.key, "<usage>")
          }
        }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
      }
      checkResultByFile("$testName.$extension")
    }
  }

  protected fun doSymbolRenameTest(
    newName: String,
    searchCommentsAndText: Boolean = false,
    dir: Boolean = true,
    testDialog: TestDialog? = null,
    extension: String = defaultExtension,
    configurators: List<PolySymbolsTestConfigurator> = defaultConfigurators,
  ) {
    doSymbolRenameTest("$testName.$extension", newName,
                       searchCommentsAndText = searchCommentsAndText, dir = dir,
                       testDialog = testDialog, configurators = configurators)
  }

  protected fun doSymbolRenameTest(
    mainFile: String,
    newName: String,
    searchCommentsAndText: Boolean = false,
    dir: Boolean = true,
    testDialog: TestDialog? = null,
    configurators: List<PolySymbolsTestConfigurator> = defaultConfigurators,
  ) {
    setTestDialog(testDialog)
    doConfiguredTest(
      dir = dir,
      checkResult = true,
      configureFileName = mainFile,
      configurators = configurators,
    ) {
      if (canRenamePolySymbolAtCaret()) {
        renamePolySymbol(newName)
      }
      else {
        var targetElement = TargetElementUtil.findTargetElement(
          editor, TargetElementUtil.ELEMENT_NAME_ACCEPTED or TargetElementUtil.REFERENCED_ELEMENT_ACCEPTED)
        if (targetElement == null)
          throw AssertionError("No Symbol or PSI Element to rename at caret position.")
        targetElement = RenamePsiElementProcessor.forElement(targetElement)
          .substituteElementToRename(targetElement, editor)
        val renameProcessor = RenameProcessor(project, targetElement!!, newName, searchCommentsAndText, searchCommentsAndText)
        renameProcessor.run()
      }
    }
  }

  protected fun doFileRenameTest(newName: String, mainFile: String, searchCommentsAndText: Boolean = true, testDialog: TestDialog? = null) {
    setTestDialog(testDialog)
    doConfiguredTest(dir = true, checkResult = true, configureFileName = mainFile) {
      renameElement(file, newName, searchCommentsAndText, searchCommentsAndText)
    }
  }

  protected open val usagesTestHelper: UsagesTestHelper
    get() = UsagesTestHelper.Default

  protected fun usagesAtCaret(scope: SearchScope? = null): List<String> =
    myFixture.usagesAtCaret(scope, usagesTestHelper)

  protected fun checkUsages(
    signature: String,
    goldFileName: String,
    strict: Boolean = true,
    scope: SearchScope? = null,
  ) {
    myFixture.checkUsages(signature, goldFileName, usagesTestHelper, strict, scope)
  }

  @Suppress("unused")
  protected fun checkFileUsages(
    goldFileName: String,
    strict: Boolean = true,
    scope: SearchScope? = null,
  ) {
    myFixture.checkFileUsages(goldFileName, usagesTestHelper, strict, scope)
  }

  protected enum class CommentStyle {
    LINE,
    BLOCK
  }

  protected interface EditorTypingTestFixture {
    fun type(str: String)

    fun assertLookupShown()

    fun assertLookupNotShown()

    fun completeBasic()

    fun moveToOffsetBySignature(signature: String)

    fun checkLookupItems(
      renderPriority: Boolean = true,
      renderTypeText: Boolean = true,
      renderTailText: Boolean = false,
      renderProximity: Boolean = false,
      renderDisplayText: Boolean = false,
      renderDisplayEffects: Boolean = renderPriority,
      lookupItemFilter: (item: LookupElementInfo) -> Boolean = { true },
    )

    fun assertLookupContains(vararg items: String)

    fun assertLookupDoesntContain(vararg items: String)

    fun selectLookupItem(item: String)
  }

  protected data class TestConfiguration(
    val configurators: List<PolySymbolsTestConfigurator>,
  )

  private fun setTestDialog(testDialog: TestDialog? = null) {
    testDialog?.let { TestDialogManager.setTestDialog(testDialog) }
    Disposer.register(testRootDisposable) {
      TestDialogManager.setTestDialog(TestDialog.DEFAULT)
    }
  }

}