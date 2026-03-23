// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

package com.intellij.codeInsight.template.impl;

import com.intellij.codeInsight.template.Expression;
import com.intellij.codeInsight.template.ExpressionContext;
import com.intellij.codeInsight.template.Result;
import com.intellij.codeInsight.template.TextResult;
import com.intellij.modcommand.ModCommand;
import com.intellij.modcommand.ModPsiUpdater;
import com.intellij.modcommand.ModTemplateBuilder;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.RangeMarker;
import com.intellij.openapi.options.SchemeElement;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.NlsContexts;
import com.intellij.openapi.util.NlsSafe;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.util.DocumentUtil;
import com.intellij.util.SmartList;
import com.intellij.util.containers.ContainerUtil;
import one.util.streamex.StreamEx;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

public class TemplateImpl extends TemplateBase implements SchemeElement {
  private @NlsSafe String myKey;
  private @NlsContexts.DetailedDescription String myDescription;
  private String myGroupName;
  private char myShortcutChar = TemplateConstants.DEFAULT_CHAR;
  private final List<Variable> myVariables = new SmartList<>();
  private @NonNls String myId;

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof TemplateImpl template)) return false;

    if (myId != null && myId.equals(template.myId)) return true;

    if (isToReformat != template.isToReformat) return false;
    if (isToShortenLongNames != template.isToShortenLongNames) return false;
    if (myShortcutChar != template.myShortcutChar) return false;
    return Objects.equals(myDescription, template.myDescription) &&
           Objects.equals(myGroupName, template.myGroupName) &&
           Objects.equals(myKey, template.myKey) &&
           string().equals(template.string()) &&
           getValue(Property.USE_STATIC_IMPORT_IF_POSSIBLE) == template.getValue(Property.USE_STATIC_IMPORT_IF_POSSIBLE) &&
           (templateText() != null ? templateText().equals(template.templateText()) : template.templateText() == null) &&
           new HashSet<>(myVariables).equals(new HashSet<>(template.myVariables)) && isDeactivated == template.isDeactivated;
  }

  @Override
  public int hashCode() {
    if (myId != null) {
      return myId.hashCode();
    }
    int result;
    result = myKey.hashCode();
    result = 29 * result + string().hashCode();
    result = 29 * result + myGroupName.hashCode();
    return result;
  }

  private boolean isToReformat;
  private boolean isToShortenLongNames = true;
  private TemplateContext myTemplateContext = new TemplateContext();

  private static final @NonNls String SELECTION_START = "SELECTION_START";
  private static final @NonNls String SELECTION_END = "SELECTION_END";
  public static final @NonNls String ARG = "ARG";

  public static final Set<String> INTERNAL_VARS_SET = Set.of(
    END, SELECTION, SELECTION_START, SELECTION_END);

  private boolean isDeactivated;

  public boolean isInline() {
    return myIsInline;
  }

  private boolean isToIndent = true;


  @Override
  public void setInline(boolean isInline) {
    myIsInline = isInline;
  }

  private boolean myIsInline;

  public TemplateImpl(@NotNull @NlsSafe String key, @NotNull @NonNls String group) {
    this(key, null, group);
    setToParseSegments(false);
    setTemplateText("");
  }

  public TemplateImpl(@NotNull @NlsSafe String key, @Nullable @NlsSafe String string, @NotNull @NonNls String group) {
    this(key, string, group, true);
  }

  @ApiStatus.Internal
  public TemplateImpl(@NotNull @NlsSafe String key, @NlsSafe String string, @NotNull @NonNls String group, boolean storeBuildingStacktrace) {
    super(StringUtil.convertLineSeparators(StringUtil.notNullize(string)));
    myKey = key;
    myGroupName = group;
    setBuildingTemplateTrace(storeBuildingStacktrace ? new Throwable() : null);
  }

  @Override
  public @NotNull Variable addVariable(@NotNull Expression expression, boolean isAlwaysStopAt) {
    return addVariable("__Variable" + myVariables.size(), expression, isAlwaysStopAt);
  }

  @Override
  public @NotNull Variable addVariable(@NotNull @NlsSafe String name,
                                       Expression expression,
                                       Expression defaultValueExpression,
                                       boolean isAlwaysStopAt,
                                       boolean skipOnStart) {
    if (isParsed() || !isToParseSegments()) {
      addVariableSegment(name);
    }
    Variable variable = new Variable(name, expression, defaultValueExpression, isAlwaysStopAt, skipOnStart);
    myVariables.add(variable);
    return variable;
  }

  @Override
  public @NotNull Variable addVariable(@NotNull @NlsSafe String name, @NlsSafe String expression, @NlsSafe String defaultValue, boolean isAlwaysStopAt) {
    Variable variable = new Variable(name, expression, defaultValue, isAlwaysStopAt);
    myVariables.add(variable);
    return variable;
  }

  @Override
  public void addVariable(@NotNull Variable variable) {
    myVariables.add(variable);
  }

  @Override
  public void addEndVariable() {
    addVariableSegment(END);
  }

  @Override
  public void addSelectionStartVariable() {
    addVariableSegment(SELECTION_START);
  }

  @Override
  public void addSelectionEndVariable() {
    addVariableSegment(SELECTION_END);
  }

  @Override
  public @NonNls String getId() {
    return myId;
  }

  @Override
  public @NotNull TemplateImpl copy() {
    TemplateImpl template = new TemplateImpl(myKey, string(), myGroupName);
    template.resetFrom(this);
    return template;
  }

  public void resetFrom(TemplateImpl another) {
    removeAllParsed();
    setToParseSegments(another.isToParseSegments());

    myKey = another.getKey();
    setString(another.string());
    setTemplateText(another.templateText());
    myGroupName = another.myGroupName;
    myId = another.myId;
    myDescription = another.myDescription;
    myShortcutChar = another.myShortcutChar;
    isToReformat = another.isToReformat;
    isToShortenLongNames = another.isToShortenLongNames;
    myIsInline = another.myIsInline;
    myTemplateContext = another.myTemplateContext.createCopy();
    isDeactivated = another.isDeactivated;
    for (Property property : Property.values()) {
      boolean value = another.getValue(property);
      if (value != getDefaultValue(property)) {
        setValue(property, true);
      }
    }
    for (Variable variable : another.myVariables) {
      myVariables.add(new Variable(variable));
    }
  }

  @Override
  public boolean isToReformat() {
    return isToReformat;
  }

  @Override
  public void setToReformat(boolean toReformat) {
    isToReformat = toReformat;
  }

  @Override
  public void setToIndent(boolean toIndent) {
    isToIndent = toIndent;
  }

  public boolean isToIndent() {
    return isToIndent;
  }

  @Override
  public boolean isToShortenLongNames() {
    return isToShortenLongNames;
  }

  @Override
  public void setToShortenLongNames(boolean toShortenLongNames) {
    isToShortenLongNames = toShortenLongNames;
  }

  public void setDeactivated(boolean isDeactivated) {
    this.isDeactivated = isDeactivated;
  }

  public boolean isDeactivated() {
    return isDeactivated;
  }

  public @NotNull TemplateContext getTemplateContext() {
    return myTemplateContext;
  }

  public int getEndSegmentNumber() {
    return getVariableSegmentNumber(END);
  }

  int getSelectionStartSegmentNumber() {
    return getVariableSegmentNumber(SELECTION_START);
  }

  int getSelectionEndSegmentNumber() {
    return getVariableSegmentNumber(SELECTION_END);
  }

  public void removeAllParsed() {
    myVariables.clear();
    clearSegments();
    setToParseSegments(true);
    setBuildingTemplateTrace(new Throwable());
  }

  public void removeVariable(int i) {
    myVariables.remove(i);
  }

  public int getVariableCount() {
    return myVariables.size();
  }

  public @NotNull @NlsSafe String getVariableNameAt(int i) {
    return myVariables.get(i).getName();
  }

  public @NotNull @NlsSafe String getExpressionStringAt(int i) {
    return myVariables.get(i).getExpressionString();
  }

  @NotNull
  Expression getExpressionAt(int i) {
    return myVariables.get(i).getExpression();
  }

  public @NotNull @NlsSafe String getDefaultValueStringAt(int i) {
    return myVariables.get(i).getDefaultValueString();
  }

  @NotNull
  Expression getDefaultValueAt(int i) {
    return myVariables.get(i).getDefaultValueExpression();
  }

  public boolean isAlwaysStopAt(int i) {
    return myVariables.get(i).isAlwaysStopAt();
  }

  @Override
  public @NlsSafe String getKey() {
    return myKey;
  }

  public void setKey(@NlsSafe String key) {
    myKey = key;
  }

  @Override
  public @NlsContexts.DetailedDescription String getDescription() {
    return myDescription;
  }

  public void setDescription(@NlsContexts.DetailedDescription @Nullable String value) {
    value = StringUtil.notNullize(value).trim();
    if (!StringUtil.equals(value, myDescription)) {
      myDescription = value;
    }
  }

  public char getShortcutChar() {
    return myShortcutChar;
  }

  public void setShortcutChar(char shortcutChar) {
    myShortcutChar = shortcutChar;
  }

  public @NonNls String getGroupName() {
    return myGroupName;
  }

  @Override
  public void setGroupName(@NotNull @NonNls String groupName) {
    myGroupName = groupName;
  }
  

  public boolean hasArgument() {
    for (Variable v : myVariables) {
      if (v.getName().equals(ARG)) return true;
    }
    return false;
  }

  public void setId(final @Nullable String id) {
    myId = id;
  }

  public Map<TemplateOptionalProcessor, Boolean> createOptions() {
    Map<TemplateOptionalProcessor, Boolean> context = new LinkedHashMap<>();
    for (TemplateOptionalProcessor processor : TemplateOptionalProcessor.EP_NAME.getExtensionList()) {
      context.put(processor, processor.isEnabled(this));
    }
    return context;
  }

  public TemplateContext createContext() {
    return getTemplateContext().createCopy();
  }

  public void applyOptions(final Map<TemplateOptionalProcessor, Boolean> context) {
    TemplateContext templateContext = getTemplateContext();
    for (Map.Entry<TemplateOptionalProcessor, Boolean> entry : context.entrySet()) {
      TemplateOptionalProcessor key = entry.getKey();
      if (key.isVisible(this, templateContext)) {
        key.setEnabled(this, entry.getValue().booleanValue());
      }
    }
  }

  public void applyContext(final TemplateContext context) {
    myTemplateContext = context.createCopy();
  }

  boolean skipOnStart(int i) {
    return myVariables.get(i).skipOnStart();
  }

  @Override
  public ArrayList<Variable> getVariables() {
    return new ArrayList<>(myVariables);
  }

  @ApiStatus.Internal
  public void dropParsedData() {
    for (Variable variable : myVariables) {
      variable.dropParsedData();
    }
  }

  @SuppressWarnings("unused")
  //used is cases when building templates without PSI and TemplateBuilder
  public void setPrimarySegment(int segmentNumber) {
    Collections.swap(getSegments(), 0, segmentNumber);
  }

  /**
   * Performs a template execution within ModCommand context. The template is not actually executed, but
   * contributes to {@link ModPsiUpdater} to form the final {@link ModCommand}.
   * <p>
   *   Note that not all the template behavior is implemented yet, and not everything is supported in ModCommands at all,
   *   so expect that complex templates that use rare features may not work correctly.
   * </p>
   * 
   * @param updater {@link ModPsiUpdater} to use.
   */
  @ApiStatus.Internal
  public void update(@NotNull ModPsiUpdater updater, @NotNull TemplateStateProcessor processor) {
    parseSegments();
    int start = updater.getCaretOffset();
    String text = getTemplateText();
    Document document = updater.getDocument();
    document.insertString(start, text);
    RangeMarker wholeTemplate = document.createRangeMarker(start, start + text.length());
    wholeTemplate.setGreedyToLeft(true);
    wholeTemplate.setGreedyToRight(true);
    Map<String, Variable> variableMap = StreamEx.of(getVariables()).toMap(Variable::getName, Function.identity());
    Project project = updater.getProject();
    PsiDocumentManager manager = PsiDocumentManager.getInstance(project);
    List<Segment> segments = getSegments();
    List<MarkerInfo> markers = ContainerUtil.map(segments, segment -> {
      RangeMarker marker = document.createRangeMarker(start + segment.offset, start + segment.offset);
      if (!END.equals(segment.name)) {
        // can be a conflict if EXPR and END are together
        marker.setGreedyToRight(true);
      }
      return new MarkerInfo(segment, marker);
    });
    // Resolve non-editable (isAlwaysStopAt=false) variables upfront so their computed values
    // can be substituted into the document before the interactive template session starts.
    Map<String, String> calculatedValues =
      preCalculateNonEditableVariables(variableMap, markers, manager, updater);

    // Pass 1: substitute non-editable variables and resolve END marker.
    RangeMarker endMarker = null;
    for (MarkerInfo info : markers) {
      Segment segment = info.segment;
      if (segment.name.equals(END)) {
        int endOffset = info.marker.getStartOffset();
        // If the END marker landed at the start of a non-editable variable's range
        // (happens when END and a non-editable variable share the same original template offset),
        // move END to after that variable's substituted value.
        for (MarkerInfo other : markers) {
          if (other == info) continue;
          Variable v = variableMap.get(other.segment.name);
          if (v != null && !v.isAlwaysStopAt() && calculatedValues.containsKey(other.segment.name)) {
            int otherStart = other.marker.getStartOffset();
            int otherEnd = other.marker.getEndOffset();
            if (otherStart <= endOffset && endOffset < otherEnd) {
              endOffset = otherEnd;
            }
          }
        }
        TextRange range = processor.insertNewLineIndentMarker(updater.getPsiFile(), document, endOffset);
        if (range != null) {
          endMarker = document.createRangeMarker(range);
        } else {
          endMarker = document.createRangeMarker(endOffset, endOffset);
        }
        continue;
      }
      Variable variable = variableMap.get(segment.name);
      if (variable != null && !variable.isAlwaysStopAt()) {
        String value = calculatedValues.get(segment.name);
        if (value != null) {
          document.replaceString(info.marker.getStartOffset(), info.marker.getEndOffset(), value);
        }
      }
    }
    for (TemplateOptionalProcessor proc : DumbService.getDumbAwareExtensions(project, TemplateOptionalProcessor.EP_NAME)) {
      if (proc instanceof ModCommandAwareTemplateOptionalProcessor mcProcessor) {
        mcProcessor.processText(this, updater, wholeTemplate);
      }
    }

    if (isToIndent()) {
      smartIndent(document, wholeTemplate.getStartOffset(), wholeTemplate.getEndOffset(), this, markers);
    }
    if (isToReformat()) {
      List<MarkerInfo> emptyValues = new ArrayList<>();
      for (MarkerInfo info : markers) {
        if (END.equals(info.segment.name)) continue;
        if (info.marker.getStartOffset() == info.marker.getEndOffset()) {
          document.insertString(info.marker.getStartOffset(), "a");
          emptyValues.add(info);
        }
      }
      manager.commitDocument(document);
      PsiFile psiFileForReformat = updater.getPsiFile();
      int reformatStart = wholeTemplate.getStartOffset();
      int reformatEnd = wholeTemplate.getEndOffset();
      // Extend reformat range to include leading whitespace on the line so the formatter can adjust indentation
      int lineStart = document.getLineStartOffset(document.getLineNumber(reformatStart));
      if (document.getCharsSequence().subSequence(lineStart, reformatStart).toString().isBlank()) {
        reformatStart = lineStart;
      }
      CodeStyleManager.getInstance(project)
        .reformatText(psiFileForReformat, reformatStart, reformatEnd);
      for (MarkerInfo value : emptyValues) {
        document.deleteString(value.marker.getStartOffset(), value.marker.getEndOffset());
      }
    }
    if (endMarker == null) {
      endMarker = document.createRangeMarker(wholeTemplate.getEndOffset(), wholeTemplate.getEndOffset());
    }
    document.deleteString(endMarker.getStartOffset(), endMarker.getEndOffset());
    PsiDocumentManager.getInstance(project).commitDocument(document);
    // if $END$ is at line start, put it at correct indentation
    if (isToReformat()) {
      int offset = endMarker.getStartOffset();
      int lineStart = document.getLineStartOffset(document.getLineNumber(offset));
      if (document.getCharsSequence().subSequence(lineStart, offset).toString().trim().isEmpty()) {
        CodeStyleManager style = CodeStyleManager.getInstance(updater.getProject());
        style.adjustLineIndent(updater.getPsiFile(), offset);
      }
    }
    // Pass 2: create template fields for editable variables.
    // Done after formatting so that field ranges match the final document state.
    ModTemplateBuilder builder = null;
    for (MarkerInfo info : markers) {
      Segment segment = info.segment;
      if (segment.name.equals(END)) continue;
      Variable variable = variableMap.get(segment.name);
      if (variable != null && variable.isAlwaysStopAt()) {
        manager.commitDocument(document);
        PsiElement element = updater.getPsiFile().findElementAt(info.marker.getStartOffset());
        if (element != null) {
          if (builder == null) builder = updater.templateBuilder();
          builder.field(element, info.marker.getTextRange().shiftLeft(element.getTextRange().getStartOffset()), segment.name,
                        variable.getExpression());
        }
      }
    }
    if (builder != null) {
      builder.finishAt(endMarker.getStartOffset());
    } else {
      updater.moveCaretTo(endMarker.getStartOffset());
    }
    endMarker.dispose();
    for (MarkerInfo info : markers) {
      info.marker.dispose();
    }
    wholeTemplate.dispose();
  }

  @Override
  public String toString() {
    return myGroupName +"/" + myKey;
  }

  private record MarkerInfo(Segment segment, RangeMarker marker) {}

  /**
   * Pre-calculates values for non-editable ({@code isAlwaysStopAt=false}) variables before processing segments.
   * Iterates multiple passes to resolve inter-variable dependencies (e.g., {@code escapeString(EXPR)} depending on {@code EXPR}).
   */
  private static @NotNull Map<String, String> preCalculateNonEditableVariables(
    @NotNull Map<String, Variable> variableMap,
    @NotNull List<MarkerInfo> markers,
    @NotNull PsiDocumentManager manager,
    @NotNull ModPsiUpdater updater
  ) {
    Map<String, String> calculatedValues = new LinkedHashMap<>();
    Set<String> nonEditableVarNames = new HashSet<>();
    for (Variable variable : variableMap.values()) {
      if (!variable.isAlwaysStopAt()) {
        nonEditableVarNames.add(variable.getName());
      }
    }
    if (nonEditableVarNames.isEmpty()) {
      return calculatedValues;
    }
    manager.commitDocument(updater.getDocument());
    // Insert placeholder "a" for editable variables to ensure syntactically valid PSI
    // (e.g., resource variable name in try-with-resources must be present for catch parameter resolution).
    Document document = updater.getDocument();
    boolean insertedEditablePlaceholders = false;
    for (MarkerInfo info : markers) {
      String name = info.segment.name;
      if (INTERNAL_VARS_SET.contains(name)) continue;
      if (nonEditableVarNames.contains(name)) continue;
      Variable variable = variableMap.get(name);
      if (variable == null) continue;
      if (info.marker.getStartOffset() != info.marker.getEndOffset()) continue;
      info.marker.setGreedyToLeft(true);
      document.insertString(info.marker.getStartOffset(), "a");
      info.marker.setGreedyToLeft(false);
      insertedEditablePlaceholders = true;
    }
    if (insertedEditablePlaceholders) {
      manager.commitDocument(document);
    }
    for (int pass = 0; pass < nonEditableVarNames.size(); pass++) {
      int resolvedBefore = calculatedValues.size();
      for (MarkerInfo info : markers) {
        String name = info.segment.name;
        if (!nonEditableVarNames.contains(name) || calculatedValues.containsKey(name)) continue;
        Variable variable = variableMap.get(name);
        if (variable == null) {
          continue;
        }
        PsiElement element = updater.getPsiFile().findElementAt(info.marker.getStartOffset());
        if (element == null) {
          continue;
        }
        Result result = variable.getExpression().calculateResult(
          new DummyContext(info.marker.getTextRange(), element, updater.getPsiFile(), calculatedValues));
        if (result != null) {
          calculatedValues.put(name, result.toString());
        }
      }
      if (calculatedValues.size() == resolvedBefore) break;
      // Substitute newly resolved values into the document so the next pass sees correct PSI.
      // Also insert placeholder "a" for unresolved non-editable variables so the PSI tree
      // is syntactically valid (e.g., catch parameter needs both type and name for proper parsing).
      Document doc = updater.getDocument();
      for (MarkerInfo info : markers) {
        String name = info.segment.name;
        if (!nonEditableVarNames.contains(name)) continue;
        if (info.marker.getStartOffset() != info.marker.getEndOffset()) continue; // already has text
        String value = calculatedValues.get(name);
        String textToInsert = value != null ? value : "a";
        info.marker.setGreedyToLeft(true);
        doc.insertString(info.marker.getStartOffset(), textToInsert);
        info.marker.setGreedyToLeft(false);
      }
      manager.commitDocument(doc);
    }
    // Clean up placeholders for variables that were never resolved
    Document doc = updater.getDocument();
    boolean cleaned = false;
    for (MarkerInfo info : markers) {
      String name = info.segment.name;
      if (nonEditableVarNames.contains(name) && !calculatedValues.containsKey(name) &&
          info.marker.getStartOffset() != info.marker.getEndOffset()) {
        doc.deleteString(info.marker.getStartOffset(), info.marker.getEndOffset());
        cleaned = true;
      }
    }
    if (cleaned) {
      manager.commitDocument(doc);
    }
    return calculatedValues;
  }

  /**
   * similar to {@link TemplateState#smartIndent(int, int)}
   */
  private static void smartIndent(@NotNull Document document, int startOffset, int endOffset,
                                   @NotNull TemplateBase template, @NotNull List<MarkerInfo> markers) {
    int startLineNum = document.getLineNumber(startOffset);
    int endLineNum = document.getLineNumber(endOffset);
    if (endLineNum == startLineNum) {
      return;
    }

    int selectionIndent = -1;
    int selectionStartLine = -1;
    int selectionEndLine = -1;
    int selectionSegment = template.getVariableSegmentNumber(SELECTION);
    if (selectionSegment >= 0) {
      int selectionStart = template.getSegmentOffset(selectionSegment);
      selectionIndent = 0;
      String templateText = template.getTemplateText();
      while (selectionStart > 0 && templateText.charAt(selectionStart - 1) == ' ') {
        selectionIndent++;
        selectionStart--;
      }
      for (MarkerInfo info : markers) {
        if (SELECTION.equals(info.segment.name)) {
          selectionStartLine = document.getLineNumber(info.marker.getStartOffset());
          selectionEndLine = document.getLineNumber(info.marker.getEndOffset());
          break;
        }
      }
    }

    int indentLineNum = startLineNum;
    int lineLength = 0;
    for (; indentLineNum >= 0; indentLineNum--) {
      lineLength = document.getLineEndOffset(indentLineNum) - document.getLineStartOffset(indentLineNum);
      if (lineLength > 0) {
        break;
      }
    }
    if (indentLineNum < 0) {
      return;
    }
    StringBuilder buffer = new StringBuilder();
    CharSequence text = document.getCharsSequence();
    for (int i = 0; i < lineLength; i++) {
      char ch = text.charAt(document.getLineStartOffset(indentLineNum) + i);
      if (ch != ' ' && ch != '\t') {
        break;
      }
      buffer.append(ch);
    }
    if (buffer.isEmpty() && selectionIndent <= 0 || startLineNum >= endLineNum) {
      return;
    }
    String stringToInsert = buffer.toString();
    int finalSelectionStartLine = selectionStartLine;
    int finalSelectionEndLine = selectionEndLine;
    int finalSelectionIndent = selectionIndent;
    DocumentUtil.executeInBulk(document, () -> {
      for (int i = startLineNum + 1; i <= endLineNum; i++) {
        if (i > finalSelectionStartLine && i <= finalSelectionEndLine) {
          document.insertString(document.getLineStartOffset(i), StringUtil.repeatSymbol(' ', finalSelectionIndent));
        }
        else {
          document.insertString(document.getLineStartOffset(i), stringToInsert);
        }
      }
    });
  }

  @ApiStatus.Internal
  public static class DummyContext implements ExpressionContext {
    private final @NotNull TextRange myRange;
    private final @NotNull PsiElement myElement;
    private final @NotNull PsiFile myFile;
    private final @Nullable Map<String, String> myVariableValues;

    public DummyContext(@NotNull TextRange range, @NotNull PsiElement element, @NotNull PsiFile file) {
      this(range, element, file, null);
    }

    public DummyContext(@NotNull TextRange range, @NotNull PsiElement element, @NotNull PsiFile file,
                        @Nullable Map<String, String> variableValues) {
      myRange = range;
      myElement = element;
      myFile = file;
      myVariableValues = variableValues;
    }

    @Override
    public Project getProject() { return myFile.getProject(); }

    @Override
    public @Nullable PsiFile getPsiFile() {
      return myFile;
    }

    @Override
    public @Nullable Editor getEditor() { return null; }

    @Override
    public int getStartOffset() { return myRange.getStartOffset(); }

    @Override
    public int getTemplateStartOffset() { return myRange.getStartOffset(); }

    @Override
    public int getTemplateEndOffset() { return myRange.getEndOffset(); }

    @Override
    public <T> T getProperty(Key<T> key) { return null; }

    @Override
    public @Nullable PsiElement getPsiElementAtStartOffset() { return myElement.isValid() ? myElement : null; }

    @Override
    public @Nullable TextResult getVariableValue(String variableName) {
      if (myVariableValues != null) {
        String value = myVariableValues.get(variableName);
        if (value != null) {
          return new TextResult(value);
        }
      }
      return null;
    }
  }
}
