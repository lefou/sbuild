package de.tototec.sbuild

object Constants {

  /**
   * The package(s) part of the plugins public API.
   * The packages will be part of the current projects classpath.
   * All other packages of that plugin will only be available to the plugin implementation itself.
   */
  @deprecated("Use ManifestSBuildExportPackage instead.", "0.7.1.9000")
  val SBuildPluginExportPackage = ManifestSBuildExportPackage
  /**
   * The package(s) part of the plugins public API.
   * The packages will be part of the current projects classpath.
   * All other packages of that plugin will only be available to the plugin implementation itself.
   */
  val ManifestSBuildExportPackage = "SBuild-ExportPackage"

  /**
   * A pair of full qualified class names denoting the plugin instance class and it factory delimited by an equals (=) sign. The factory class must implement [[Plugin]].
   */
  @deprecated("Use ManifestSBuildPlugin instead.", "0.7.1.9000")
  val SBuildPlugin = ManifestSBuildPlugin
  /**
   * A pair of full qualified class names denoting the plugin instance class and it factory delimited by an equals (=) sign. The factory class must implement [[Plugin]].
   */
  val ManifestSBuildPlugin = "SBuild-Plugin"

  val ManifestSBuildExportClasspath = "SBuild-ExportClasspath"

  /**
   * Addional classpath which will be automatically resolved and used when this header is present in a plugin JAR.
   */
  @deprecated("Use ManifestSBuildClasspath instead.", "0.7.1.9000")
  val SBuildPluginClasspath = ManifestSBuildClasspath
  /**
   * Addional classpath which will be automatically resolved and used when this header is present in a plugin JAR.
   */
  val ManifestSBuildClasspath = "SBuild-Classpath"

  /**
   * The minimal SBuild version required to run this plugin.
   */
  @deprecated("Use ManifestSBuildVersion instead.", "0.7.1.9000")
  val SBuildVersion = ManifestSBuildVersion
  /**
   * The minimal SBuild version required to run this plugin.
   */
  val ManifestSBuildVersion = "SBuild-Version"

}