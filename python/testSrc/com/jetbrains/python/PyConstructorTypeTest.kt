// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.python

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.idea.TestFor
import com.jetbrains.python.fixtures.PyInspectionTestCase
import com.jetbrains.python.inspections.PyAssertTypeInspection
import com.jetbrains.python.inspections.PyTypeCheckerInspection
import com.jetbrains.python.inspections.unresolvedReference.PyUnresolvedReferencesInspection

internal class PyConstructorTypeTest : PyInspectionTestCase() {
  override fun getInspectionClass(): Class<PyAssertTypeInspection> = PyAssertTypeInspection::class.java

  override fun getAdditionalInspectionClasses(): List<Class<out LocalInspectionTool>> = listOf(
    PyTypeCheckerInspection::class.java,
    PyUnresolvedReferencesInspection::class.java
  )

  fun `test generic class metaclass __call__ with incompatible return type`() {
    doTestByText("""
      from typing import assert_type

      class Meta(type):
          def __call__[T](cls, t: T) -> list[T]: ...

      class MyClass[T](metaclass=Meta): ...

      assert_type(MyClass[int](1), list[int]) # PY-88644
      assert_type(MyClass[int](1.0), list[float]) # PY-88644
      assert_type(MyClass(1), list[int])
      assert_type(MyClass(1.0), list[float])
      """.trimIndent()
    )
  }

  fun `test generic class __new__ with compatible return type`() {
    doTestByText("""
      from typing import assert_type, Self
      
      class MyClass[T]:
          def __new__(cls, _: T) -> Self:
              return super().__new__(cls)

      assert_type(MyClass[int](1), MyClass[int])
      assert_type(MyClass[float](1), MyClass[float])
      MyClass[int](<warning descr="Expected type 'int' (matched generic type 'T'), got 'float' instead">1.0</warning>)

      assert_type(MyClass(1), MyClass[int])
      assert_type(MyClass(1.0), MyClass[float])
      """.trimIndent()
    )
  }

  fun `test generic class __new__ with incompatible return type`() {
    doTestByText("""
      from typing import assert_type
      
      class MyClass[T]:
          def __new__(cls, t: T) -> list[T]:
              return [t]
      
      assert_type(MyClass[int](1), list[int]) # PY-88644
      assert_type(MyClass[float](1), list[float]) # PY-88644
      MyClass[int](<warning descr="Expected type 'int' (matched generic type 'T'), got 'float' instead">1.0</warning>)

      assert_type(MyClass(1), list[int])
      assert_type(MyClass(1.0), list[float])
      """.trimIndent()
    )
  }

  fun `test __init__ solves type vars left unsolved by __new__`() {
    doTestByText("""
      from typing import assert_type, Self

      class MyClass[T]:
          def __new__(cls, *args, **kwargs) -> Self:
              return super().__new__(cls)

          def __init__(self, x: T) -> None:
              pass

      assert_type(MyClass(1), MyClass[int])
      """.trimIndent()
    )
  }

  @TestFor(issues = ["PY-88644"])
  fun `test __new__ overrides generic type parameter`() {
    doTestByText("""
      from typing import assert_type

      class MyClass[T]:
          def __new__(cls, *args, **kwargs) -> "MyClass[list[T]]":
              ...

      assert_type(MyClass[int](), MyClass[list[int]])
      """.trimIndent()
    )
  }

  fun `test generic class __init__`() {
    doTestByText("""
      from typing import assert_type
      
      class MyClass[T]:
          def __init__(self, _: T): ...

      assert_type(MyClass[int](1), MyClass[int])
      assert_type(MyClass[float](1), MyClass[float])
      MyClass[int](<warning descr="Expected type 'int' (matched generic type 'T'), got 'float' instead">1.0</warning>)
      
      assert_type(MyClass(1), MyClass[int])
      assert_type(MyClass(1.0), MyClass[float])
      """.trimIndent()
    )
  }
}
