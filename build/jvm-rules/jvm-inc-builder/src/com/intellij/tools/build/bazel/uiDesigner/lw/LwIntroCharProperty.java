// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.tools.build.bazel.uiDesigner.lw;

import com.intellij.tools.build.bazel.org.jdom.Element;
import com.intellij.tools.build.bazel.uiDesigner.UIFormXmlConstants;

public final class LwIntroCharProperty extends LwIntrospectedProperty {
  LwIntroCharProperty(final String name){
    super(name, Character.class.getName());
  }

  @Override
  public Object read(final Element element) throws Exception{
    return Character.valueOf(LwXmlReader.getRequiredString(element, UIFormXmlConstants.ATTRIBUTE_VALUE).charAt(0));
  }
}