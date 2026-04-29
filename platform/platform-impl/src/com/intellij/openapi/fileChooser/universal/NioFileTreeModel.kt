// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.fileChooser.universal

import com.intellij.openapi.actionSystem.DataKey
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.fileTypes.FileTypeRegistry
import com.intellij.openapi.util.Pair
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.tree.MapBasedTree
import com.intellij.ui.tree.MapBasedTree.Entry
import com.intellij.util.PlatformIcons
import com.intellij.util.concurrency.Invoker
import com.intellij.util.concurrency.InvokerSupplier
import com.intellij.util.ui.tree.AbstractTreeModel
import org.jetbrains.annotations.ApiStatus
import java.io.IOException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.util.function.Predicate
import javax.swing.tree.TreePath
import kotlin.io.path.invariantSeparatorsPathString

/**
 * A tree model backed by Java NIO [Path] instead of [com.intellij.openapi.vfs.VirtualFile].
 * Provides the same public interface as [com.intellij.openapi.fileChooser.tree.FileTreeModel]
 * but does not use the Virtual File System (VFS) internally.
 */
@ApiStatus.Internal
class NioFileTreeModel @JvmOverloads constructor(
  descriptor: FileChooserDescriptor,
  sortDirectories: Boolean = true,
) : AbstractTreeModel(), InvokerSupplier {

  companion object {
    @JvmField
    val SYSTEM_ROOTS_FILTER: DataKey<Predicate<Path>> = DataKey.create("nio.file.tree.model.system.roots.filter")

    private val LOG = Logger.getInstance(NioFileTreeModel::class.java)

    private fun fileName(path: Path): String {
      val name = path.getFileName()
      return name?.toString() ?: path.toString()
    }
  }

  private val invoker: Invoker = Invoker.forBackgroundThreadWithoutReadAction(this)
  private val state: State = State(descriptor, sortDirectories, this)
  @Volatile
  private var roots: List<Root>? = null

  fun invalidate() {
    invoker.invoke {
      roots?.forEach { it.tree.invalidate() }
      treeStructureChanged(state.path, null, null)
    }
  }

  override fun getInvoker(): Invoker = invoker

  override fun getRoot(): Any? {
    if (state.path != null) return state
    if (roots == null) roots = state.getRoots()
    val r = roots!!
    return if (r.size == 1) r.first() else null
  }

  override fun getChild(parent: Any?, index: Int): Any? {
    if (parent === state) {
      if (roots == null) roots = state.getRoots()
      val r = roots!!
      if (index in r.indices) return r[index]
    }
    else if (parent is Node) {
      val entry = getEntry(parent, true)
      if (entry != null) return entry.getChild(index)
    }
    return null
  }

  override fun getChildCount(parent: Any?): Int {
    if (parent === state) {
      if (roots == null) roots = state.getRoots()
      return roots!!.size
    }
    else if (parent is Node) {
      val entry = getEntry(parent, true)
      if (entry != null) return entry.childCount
    }
    return 0
  }

  override fun isLeaf(node: Any?): Boolean {
    if (node is Node) {
      val entry = getEntry(node, false)
      if (entry != null) return entry.isLeaf
    }
    return false
  }

  override fun getIndexOfChild(parent: Any?, child: Any?): Int {
    if (parent === state) {
      if (roots == null) roots = state.getRoots()
      val r = roots!!
      for (i in r.indices) {
        if (child === r[i]) return i
      }
    }
    else if (parent is Node && child is Node) {
      val entry = getEntry(parent, true)
      if (entry != null) return entry.getIndexOf(child)
    }
    return -1
  }

  private fun getEntry(node: Node, loadChildren: Boolean): Entry<Node>? {
    val r = roots ?: return null
    for (root in r) {
      val entry = root.tree.getEntry(node)
      if (entry != null) {
        if (loadChildren && entry.isLoadingRequired) {
          root.updateChildren(state, entry)
        }
        return entry
      }
    }
    return null
  }

  fun resetRoots() {
    invoker.invoke {
      roots = null
      treeStructureChanged(state.path, null, null)
    }
  }


  fun matchRoot(path: Path): Path? =
    state.getRoots().firstOrNull { root -> root.path.invariantSeparatorsPathString == path.invariantSeparatorsPathString }?.path


  // -- Inner classes ------------------------------------------------------------------------------------------------

  private class State(
    val descriptor: FileChooserDescriptor,
    private val sortDirectories: Boolean,
    val model: NioFileTreeModel,
  ) {
    val descriptorRoots: List<Path>? = getRoots(descriptor)
    val path: TreePath? = if (descriptorRoots != null && descriptorRoots.size == 1) null else TreePath(this)

    fun compare(one: Path, two: Path): Int {
      if (sortDirectories) {
        val isDirectory = Files.isDirectory(one)
        if (isDirectory != Files.isDirectory(two)) return if (isDirectory) -1 else 1
      }
      return StringUtil.naturalCompare(fileName(one), fileName(two))
    }

    fun isVisible(path: Path): Boolean {
      if (!isValid(path)) return false
      if (!descriptor.isShowHiddenFiles) {
        if (NioFileChooserUtil.isHidden(path)) return false
      }
      return true
    }

    fun getChildren(path: Path): List<Path>? {
      if (!isValid(path)) return null
      if (!Files.isDirectory(path)) return null
      return try {
        Files.newDirectoryStream(path).use { stream -> stream.toList() }
      }
      catch (e: IOException) {
        LOG.debug("Cannot list directory: $path", e)
        null
      }
    }

    fun getRoots(): List<Root> {
      if (!model.invoker.isValidThread) {
        LOG.error(IllegalStateException(Thread.currentThread().name))
      }
      val files = descriptorRoots ?: getSystemRoots()
      if (files.isEmpty()) return emptyList()
      return files.map { file -> Root(this, file) }
    }

    private fun getSystemRoots(): List<Path> {
      val rootsFilter = descriptor.getUserData(SYSTEM_ROOTS_FILTER)
      val systemRoots = FileSystems.getDefault().rootDirectories.toList()
      return if (rootsFilter != null) systemRoots.filter { rootsFilter.test(it) } else systemRoots
    }

    private fun removeRoots(roots: List<Root>, indicesToRemove: IntArray) {
      if (indicesToRemove.isNotEmpty()) {
        val rootsToRemove = indicesToRemove.map { roots[it] }
        if (LOG.isDebugEnabled) {
          LOG.debug("Removing ${toRootPaths(rootsToRemove)}")
        }
        model.roots = roots.filter { root -> !rootsToRemove.contains(root) }
        model.treeNodesRemoved(path, indicesToRemove, rootsToRemove.toTypedArray())
      }
    }

    private fun addRoots(roots: List<Root>, rootsToAdd: List<Root>) {
      if (rootsToAdd.isNotEmpty()) {
        if (LOG.isDebugEnabled) {
          LOG.debug("Adding ${toRootPaths(rootsToAdd)}")
        }
        model.roots = (roots + rootsToAdd).toList()
        model.treeNodesInserted(path, IntArray(rootsToAdd.size) { roots.size + it }, rootsToAdd.toTypedArray())
      }
    }

    override fun toString(): String = descriptor.title

    companion object {
      fun isValid(path: Path?): Boolean = path != null && Files.exists(path)

      fun isLeaf(path: Path?): Boolean = path != null && !Files.isDirectory(path)

      fun getRoots(descriptor: FileChooserDescriptor): List<Path>? {
        val list = descriptor.roots
          .mapNotNull { vf ->
            try {
              vf.toNioPath()
            }
            catch (_: UnsupportedOperationException) {
              null
            }
          }
          .filter { isValid(it) }
        return if (list.isEmpty() && descriptor.isShowFileSystemRoots) null else list
      }

      fun toRootPaths(roots: List<Root>): List<Path> = roots.map { it.path }

      fun <E> findNewElementIndices(a: List<E>, b: List<E>): IntArray =
        a.indices.filter { a[it] !in b }.toIntArray()
    }
  }

  private open class Node(state: State, path: Path) : NioFileNode(path) {
    init {
      updateContent()
    }

    private fun updateContent() {
      val p = path
      updateName(fileName(p))
      updateIcon(NioFileChooserUtil.getIcon(p))
      updateValid(Files.exists(p))
      updateHidden(NioFileChooserUtil.isHidden(p))
      updateSymlink(Files.isSymbolicLink(p))
      updateWritable(Files.isWritable(p))
    }

    override fun toString(): String = name ?: ""
  }

  private class Root(state: State, path: Path) : Node(state, path) {
    val tree: MapBasedTree<Path, Node> = MapBasedTree(false, { it.path }, state.path)

    init {
      tree.updateRoot(Pair.create(this, State.isLeaf(path)))
    }

    fun updateChildren(state: State, parent: Entry<Node>): MapBasedTree.UpdateResult<Node> {
      val children = state.getChildren(parent.node.path)
      if (children == null) return tree.update(parent, null)
      if (children.isEmpty()) return tree.update(parent, emptyList())
      return tree.update(parent, children
        .filter { state.isVisible(it) }
        .sortedWith { a, b -> state.compare(a, b) }
        .map { childPath ->
          val entry = tree.findEntry(childPath)
          if (entry != null && parent === entry.parentPath)
            Pair.create(entry.node, State.isLeaf(childPath))
          else
            Pair.create(Node(state, childPath), State.isLeaf(childPath))
        })
    }
  }
}
