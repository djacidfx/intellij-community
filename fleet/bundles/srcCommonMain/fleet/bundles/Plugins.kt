package fleet.bundles

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
enum class PluginVendor(val isSafeToReport: Boolean) {
  Platform(true), JetBrains(true), ThirdParty(false)
}

@Serializable(with = PluginNameSerializer::class)
data class PluginName(val name: String)

/**
 * VersionRequirement is what can present in extension's dependencies
 */
@Serializable(with = VersionSerializer::class)
sealed class VersionRequirement {
  abstract val version: PluginVersion

  data class CompatibleWith(override val version: PluginVersion) : VersionRequirement()
  data class Above(override val version: PluginVersion) : VersionRequirement()
}

@Serializable(with = PluginVersionSerializer::class)
data class PluginVersion(
  private val components: List<Int>,
  private val isSnapshot: Boolean,
) : Comparable<PluginVersion> {
  val major: Int = components[0] // for backward compatibility with code reasoning in terms of "major"
  val minor: Int = when { // for backward compatibility with code reasoning in terms of "minor"
    components.size >= 2 -> components[1]
    isSnapshot -> CompatibilityUtils.SNAPSHOT_VALUE
    else -> throw IllegalArgumentException("internal error, components cannot have a single value and not be a snapshot version")
  }

  companion object {
    /**
     * Build numbering of AIR and next products of the Fleet platform, using https://youtrack.jetbrains.com/articles/IJPL-A-109
     */
    fun fromString(s: String): PluginVersion {
      val split = s.split(".", limit = 3)
      val intComponents = split.mapIndexed { index, c ->
        when (c) {
          SNAPSHOT -> when (index) {
            split.size - 1 -> UnifiedVersionComponent.Snapshot
            else -> throw IllegalArgumentException("Cannot parse `$s` as PluginVersion, SNAPSHOT must be the last component")
          }
          else -> when (val v = c.toIntOrNull()) {
            null -> throw IllegalArgumentException("Cannot parse `$s` as PluginVersion, component at index $index is not an integer")
            else -> {
              require(index != 1 || v < CompatibilityUtils.MAX_BUILD_VALUE) { "Cannot parse `$s` as PluginVersion, second component cannot be greater than ${CompatibilityUtils.MAX_BUILD_VALUE}" }
              require(index != 2 || v < CompatibilityUtils.MAX_COMPONENT_VALUE) { "Cannot parse `$s` as PluginVersion, third component be greater than ${CompatibilityUtils.MAX_COMPONENT_VALUE}" }
              UnifiedVersionComponent.IntComponent(v)
            }
          }
        }
      }
      require(intComponents.size in 2..3) { "Cannot parse `$s` as PluginVersion, does not conform to either X.Y or X.Y.Z, where X, Y and Z are integers or 'SNAPSHOT'" }

      return PluginVersion(
        components = intComponents.filterIsInstance<UnifiedVersionComponent.IntComponent>().map { it.value },
        isSnapshot = intComponents.last() is UnifiedVersionComponent.Snapshot,
      )
    }

    private const val SNAPSHOT = "SNAPSHOT"

    private sealed class UnifiedVersionComponent {
      data class IntComponent(val value: Int) : UnifiedVersionComponent()
      data object Snapshot : UnifiedVersionComponent()
    }
  }

  val presentableText: String get() = versionString

  /**
   * String representation of this [PluginVersion] for Marketplace compatibility range fields of the plugin descriptor's JSON
   */
  val marketplaceCompatibilityRangeVersionString: String
    get() = when (components.size) {
      3 -> components
      2 if isSnapshot -> components.plus(CompatibilityUtils.MAX_COMPONENT_VALUE - 1)
      2 -> components.plus(0)
      1 if isSnapshot -> components.plus(@Suppress("DEPRECATION") CompatibilityUtils.MAX_FLEET_BUILD_VALUE)
        .plus(CompatibilityUtils.MAX_COMPONENT_VALUE - 1)

      else -> throw IllegalArgumentException("internal error 'components' must have either 2 or 3 components, as a plugin version must be either XXX.YYY or XXX.YYY.ZZZ")
    }.joinToString(".")

  val versionString: String
    get() = when {
      isSnapshot -> components.plus(SNAPSHOT)
      else -> components
    }.joinToString(".")

  override fun compareTo(other: PluginVersion): Int = toLongForComparison().compareTo(other.toLongForComparison())

  private fun toLongForComparison(): Long {
    val componentForComparaison = when {
      isSnapshot -> components.plus(CompatibilityUtils.SNAPSHOT_VALUE)
      else -> components
    }
    return CompatibilityUtils.versionAsLong(componentForComparaison.toIntArray())
  }

  // should be in sync with https://github.com/JetBrains/intellij-plugin-verifier/blob/6a04cd7c94eb806877e26a093378eaf2b85e0d73/intellij-plugin-structure/structure-fleet/src/main/kotlin/com/jetbrains/plugin/structure/fleet/FleetPluginDescriptor.kt#L146
  fun toLong(): Long = CompatibilityUtils.versionAsLong(components.toIntArray())
}

/**
 * Represents a [PluginDescriptor]'s value
 */
@Serializable(with = PluginDescriptorSerializer::class)
data class PluginDescriptor(
  val formatVersion: Int = 0,
  val name: PluginName,
  val version: PluginVersion,
  val deps: Map<PluginName, VersionRequirement> = emptyMap(),
  val compatibleShipVersionRange: ShipVersionRange? = null,
  val signature: PluginSignature? = null,
  val meta: Map<String, String> = emptyMap(),
) {
  override fun toString(): String = prettyJson.encodeToString(serializer(), this)
}

private val prettyJson = Json {
  prettyPrint = true
}

@OptIn(ExperimentalSerializationApi::class)
private val defaultJson = Json { // we cannot depend on `fleet.util.serialization.DefaultJson` from `fleet.bundles`
  ignoreUnknownKeys = true
  encodeDefaults = true
  explicitNulls = false
}

val PluginDescriptor.partsCoordinates: Coordinates? get() = metaAsCoordinates(KnownMeta.PartsCoordinates)
val PluginDescriptor.defaultIcon: Coordinates? get() = metaAsCoordinates(KnownMeta.DefaultIconCoordinates)
val PluginDescriptor.darkIcon: Coordinates? get() = metaAsCoordinates(KnownMeta.DarkIconCoordinates)

fun PluginDescriptor.metaAsCoordinates(metaKey: String): Coordinates? = meta[metaKey]?.let { serializedCoordinates ->
  defaultJson.decodeFromString(Coordinates.serializer(), serializedCoordinates)
}

private const val JETBRAINS_VENDOR = "JetBrains"

fun PluginDescriptor.getVendorType(): PluginVendor {
  val vendorId = this.meta[KnownMeta.VendorId]
  return when (vendorId) {
    null -> PluginVendor.Platform
    JETBRAINS_VENDOR -> PluginVendor.JetBrains
    else -> {
      PluginVendor.ThirdParty
    }
  }
}

@Serializable(with = PluginSignatureSerializer::class)
data class PluginSignature(val bytes: ByteArray) {
  override fun equals(other: Any?): Boolean =
    other is PluginSignature && other.bytes.contentEquals(bytes)

  override fun hashCode(): Int =
    bytes.contentHashCode()

  override fun toString(): String =
    "PluginSignature(size=${bytes.size}, hash=${hashCode().toString(16)}"
}

@Serializable
data class ShipVersionRange(
  @Serializable(with = PluginVersionForCompatibilityRangeSerializer::class)
  val from: PluginVersion,
  @Serializable(with = PluginVersionForCompatibilityRangeSerializer::class)
  val to: PluginVersion,
)

@Serializable(with = LayerSelectorSerializer::class)
data class LayerSelector(val selector: String)

@Serializable
data class ModuleCoordinates(
  val coordinates: Coordinates,
  val serializedModuleDescriptor: String?,
)

@Serializable(with = PluginLayerSerializer::class)
data class PluginLayer(
  val modulePath: Set<ModuleCoordinates>,
  val modules: Set<String>,
  val resources: Set<Coordinates>,
)

@Serializable(with = PluginPartsSerializer::class)
data class PluginParts(val layers: Map<LayerSelector, PluginLayer>)

@Serializable
sealed interface ResourcesBundle {
  @Serializable
  data class Tar(val path: String) : ResourcesBundle

  @Serializable
  data class Plain(val map: Map<String, ResourcesEntry>) : ResourcesBundle
}

@Serializable
sealed interface ResourcesEntry {
  @Serializable
  data class Content(val content: String) : ResourcesEntry

  @Serializable
  data class RelativePath(val path: String) : ResourcesEntry
}

@Serializable
sealed interface Coordinates {
  val meta: Map<String, String>

  // to reference e.g. a plugin file in marketplace (which might also be in code-cache already)
  @Serializable
  @SerialName("Remote")
  data class Remote(val url: String, val hash: String, override val meta: Map<String, String> = emptyMap()) : Coordinates {
    companion object {
      const val HASH_ALGORITHM: String = "SHA3-256"
    }
  }

  // to reference a folder with classes, should be used when running from sources only
  @Serializable
  @SerialName("Local")
  data class Local(val path: String, override val meta: Map<String, String> = emptyMap()) : Coordinates
}

class KnownCoordinatesMeta {
  companion object {
    const val Platforms: String = "platforms"
  }
}

@Serializable(with = PluginSetSerializer::class)
data class PluginSet(
  val shipVersions: Set<String>,
  val plugins: Set<PluginDescriptor>,
)

private object CompatibilityUtils {
  @Deprecated(message = "Must be removed once Marketplace stops validating on that minor number")
  const val MAX_FLEET_BUILD_VALUE = 8191
  const val MAX_BUILD_VALUE = 100000
  const val MAX_COMPONENT_VALUE = 10000
  const val SNAPSHOT_VALUE = Int.MAX_VALUE
  private val NUMBERS_OF_NINES by lazy { initNumberOfNines() }

  private fun initNumberOfNines(): IntArray {
    val numbersOfNines = ArrayList<Int>()
    var i = 99999
    val maxIntDiv10 = Int.MAX_VALUE / 10
    while (i < maxIntDiv10) {
      i = i * 10 + 9
      numbersOfNines.add(i)
    }

    return numbersOfNines.toIntArray()
  }

  fun versionAsLong(components: IntArray): Long {
    val baselineVersion = components.getOrElse(0) { 0 }
    val build = components.getOrElse(1) { 0 }
    var longVersion = branchBuildAsLong(baselineVersion, build)

    if (components.size >= 3) {
      val component = components[2]
      longVersion += if (component == Int.MAX_VALUE) MAX_COMPONENT_VALUE - 1 else component
    }

    return longVersion
  }

  private fun isNumberOfNines(p: Int) = NUMBERS_OF_NINES.any { it == p }

  private fun branchBuildAsLong(branch: Int, build: Int): Long {
    val result = if (build == Int.MAX_VALUE || isNumberOfNines(build)) {
      MAX_BUILD_VALUE - 1
    }
    else {
      build
    }

    return branch.toLong() * MAX_COMPONENT_VALUE * MAX_BUILD_VALUE + result.toLong() * MAX_COMPONENT_VALUE
  }
}
