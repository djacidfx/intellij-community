// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.execution;

import com.intellij.execution.target.LanguageRuntimeType;
import com.intellij.execution.target.TargetEnvironmentConfiguration;
import com.intellij.execution.target.TargetEnvironmentType;
import com.intellij.execution.target.TargetEnvironmentWizard;
import com.intellij.execution.target.TargetEnvironmentsManager;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.ui.ColoredListCellRenderer;
import com.intellij.ui.SeparatorWithText;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.util.ObjectUtils;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.DefaultComboBoxModel;
import javax.swing.JList;
import java.awt.Component;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Supplier;

@ApiStatus.Internal
public final class RunOnTargetComboBox extends ComboBox<Item> {
  public static final Logger LOGGER = Logger.getInstance(RunOnTargetComboBox.class);
  private final @NotNull Project myProject;
  private @Nullable LanguageRuntimeType<?> myDefaultRuntimeType;
  private boolean hasSavedTargets = false;
  private final @NotNull MyRenderer myRenderer = new MyRenderer(() -> hasSavedTargets);

  public RunOnTargetComboBox(@NotNull Project project) {
    super();
    setModel(new MyModel());
    myProject = project;
    setRenderer(myRenderer);
    addActionListener(e -> validateSelectedTarget());
  }

  public void initModel() {
    myRenderer.setProjectDefaultTarget(TargetEnvironmentsManager.getInstance(myProject).getDefaultTarget());

    hasSavedTargets = false;
    MyModel model = (MyModel)getModel();
    model.removeAllElements();
    model.addElement(null);

    Collection<Type<?>> types = new ArrayList<>();
    for (TargetEnvironmentType<?> type : TargetEnvironmentType.getTargetTypesForRunConfigurations()) {
      if (type.isSystemCompatible() && type.providesNewWizard(myProject, myDefaultRuntimeType)) {
        types.add(new Type(type));
      }
    }
    if (!types.isEmpty()) {
      model.addElement(new Separator(ExecutionBundle.message("run.on.targets.label.new.targets")));
      for (Type<?> type : types) {
        model.addElement(type);
      }
    }
  }

  public void setDefaultLanguageRuntimeType(@Nullable LanguageRuntimeType<?> defaultLanguageRuntimeType) {
    myDefaultRuntimeType = defaultLanguageRuntimeType;
  }

  public @Nullable LanguageRuntimeType<?> getDefaultLanguageRuntimeType() {
    return myDefaultRuntimeType;
  }

  public void addTarget(@NotNull TargetEnvironmentConfiguration config, int index) {
    if (!hasSavedTargets) {
      hasSavedTargets = true;
      ((MyModel)getModel()).insertElementAt(new Separator(ExecutionBundle.message("run.on.targets.label.saved.targets")), 1);
      ((MyModel)getModel()).insertElementAt(new LocalTarget(), 2);
    }
    ((MyModel)getModel()).insertElementAt(new SavedTarget(config), index);
  }

  public @Nullable String getSelectedTargetName() {
    return ObjectUtils.doIfCast(getSelectedItem(), Target.class, i -> i.getTargetName());
  }

  public void addTargets(List<? extends TargetEnvironmentConfiguration> configs) {
    int index = 3;
    for (TargetEnvironmentConfiguration config : configs) {
      addTarget(config, index);
      index++;
    }
  }

  public void selectTarget(String configName) {
    if (configName == null) {
      setSelectedItem(null);
      return;
    }
    for (int i = 0; i < getModel().getSize(); i++) {
      Item at = getModel().getElementAt(i);
      if (at instanceof Target && configName.equals(((Target)at).getTargetName())) {
        setSelectedItem(at);
      }
    }
    //todo[remoteServers]: add invalid value
  }

  private void validateSelectedTarget() {
    Object selected = getSelectedItem();
    boolean hasErrors = false;
    if (selected instanceof SavedTarget target) {
      target.revalidateConfiguration();
      hasErrors = target.hasErrors();
    }
    putClientProperty("JComponent.outline", hasErrors ? "error" : null);
  }

  private final class MyModel extends DefaultComboBoxModel<Item> {
    @Override
    public void setSelectedItem(Object anObject) {
      if (anObject instanceof Separator) {
        return;
      }
      if (anObject instanceof Type) {
        hidePopup();
        //noinspection unchecked,rawtypes
        TargetEnvironmentWizard wizard = ((Type)anObject).createWizard(myProject, myDefaultRuntimeType);
        if (wizard != null && wizard.showAndGet()) {
          TargetEnvironmentConfiguration newTarget = wizard.getSubject();
          TargetEnvironmentsManager.getInstance(myProject).addTarget(newTarget);
          addTarget(newTarget, 2);
          setSelectedIndex(2);
        }
        return;
      }
      super.setSelectedItem(anObject);
    }
  }

  private static final class MyRenderer extends ColoredListCellRenderer<Item> {
    /**
     * This is the cached item of the "project default" target.
     * <p>
     * When this field is {@code null} it means that the "project default" is local machine.
     */
    private @Nullable Item myProjectDefaultTargetItem;

    /**
     * We render the project default target item as "Local Machine" without "Project Default" prefix if we do not have any saved targets in
     * the list.
     * <p>
     * We cannot use the size of the model explicitly in {@code customizeCellRenderer(...)} method to determine whether there are
     * "Saved targets" items because the model also contains "New Target" section with the corresponding items.
     */
    private final @NotNull Supplier<Boolean> myHasSavedTargetsSupplier;

    private MyRenderer(@NotNull Supplier<Boolean> hasSavedTargetsSupplier) {
      myHasSavedTargetsSupplier = hasSavedTargetsSupplier;
    }

    public void setProjectDefaultTarget(@Nullable TargetEnvironmentConfiguration projectDefaultTarget) {
      myProjectDefaultTargetItem = projectDefaultTarget != null ? new SavedTarget(projectDefaultTarget) : null;
    }

    @Override
    public Component getListCellRendererComponent(JList<? extends Item> list, Item value, int index, boolean selected, boolean hasFocus) {
      if (value instanceof Separator) {
        SeparatorWithText separator = new SeparatorWithText();
        separator.setCaption(value.getDisplayName());
        return separator;
      }
      return super.getListCellRendererComponent(list, value, index, selected, hasFocus);
    }

    @Override
    protected void customizeCellRenderer(@NotNull JList<? extends Item> list, Item value, int index, boolean selected, boolean hasFocus) {
      if (value == null && !myHasSavedTargetsSupplier.get()) {
        /* `value` is expected to be `null` here */
        append(ExecutionBundle.message("local.machine"));
        setIcon(AllIcons.Nodes.HomeFolder);
      }
      else if (value == null) {
        // this is the project default target
        append(ExecutionBundle.message("targets.details.project.default")).append(" ");
        if (myProjectDefaultTargetItem == null) {
          append(ExecutionBundle.message("local.machine"), SimpleTextAttributes.GRAYED_ATTRIBUTES);
          setIcon(AllIcons.Nodes.HomeFolder);
        }
        else {
          append(myProjectDefaultTargetItem.getDisplayName(), SimpleTextAttributes.GRAYED_ATTRIBUTES);
          setIcon(myProjectDefaultTargetItem.getIcon());
        }
      }
      else {
        append(value.getDisplayName());
        setIcon(value.getIcon());
      }
    }
  }
}
