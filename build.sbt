import org.scalajs.linker.interface.ModuleKind
import org.scalajs.sbtplugin.ScalaJSPlugin.autoImport.*
import sbtcrossproject.CrossPlugin.autoImport.*
import sbtcrossproject.CrossType
import scalajscrossproject.ScalaJSCrossPlugin.autoImport.*

ThisBuild / organization := "io.github.canardlapin"
ThisBuild / scalaVersion := "3.7.4"
ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / versionScheme := Some("early-semver")
ThisBuild / homepage := Some(url("https://github.com/canardlapin/neuroim4s"))
ThisBuild / licenses := List(
  "Apache-2.0" -> url("https://www.apache.org/licenses/LICENSE-2.0.txt")
)

// Providers are immutable source pins. Each `neuroim4s.<provider>.build`
// property is an explicit sibling-checkout override for coordinated
// development; a consuming build forwards its own overrides here so one
// provider revision resolves throughout the graph.
def pinned(provider: String, revision: String): URI =
  sys.props
    .get(s"neuroim4s.$provider.build")
    .map(path => file(path).getCanonicalFile.toURI)
    .getOrElse(uri(s"https://github.com/canardlapin/$provider.git#$revision"))

lazy val spatial4sRevision = "77037a7e6ced044b92d0a70b713c0a32a94f680d"
lazy val spatial4sBuild = pinned("spatial4s", spatial4sRevision)
lazy val spatial4sCoreJVM = ProjectRef(spatial4sBuild, "spatial4s-coreJVM")
lazy val spatial4sCoreJS = ProjectRef(spatial4sBuild, "spatial4s-coreJS")

lazy val commonSettings = Seq(
  scalacOptions ++= Seq(
    "-deprecation",
    "-feature",
    "-unchecked",
    "-release:17"
  ),
  Test / fork := false,
  libraryDependencies += "org.scalameta" %%% "munit" % "1.2.1" % Test
)

lazy val jsSettings = Seq(
  scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.CommonJSModule)),
  Test / jsEnv := new org.scalajs.jsenv.nodejs.NodeJSEnv()
)

// Anatomical world declarations shared by volume, surface and atlas cores.
// Its only dependency is the spatial4s coordinate kernel.
lazy val spaces =
  crossProject(JSPlatform, JVMPlatform)
    .crossType(CrossType.Full)
    .in(file("modules/neuroim4s-spaces"))
    .settings(commonSettings)
    .settings(
      name := "neuroim4s-spaces",
      description := "Subject, template and declared world identities and their frames."
    )
    .jvmConfigure(_.dependsOn(spatial4sCoreJVM))
    .jsConfigure(_.dependsOn(spatial4sCoreJS))
    .jsSettings(jsSettings)

lazy val root =
  project
    .in(file("."))
    .aggregate(spaces.jvm, spaces.js)
    .settings(name := "neuroim4s-root", publish / skip := true)

addCommandAlias("neuroim4sCompileAll", ";root/compile;root/Test/compile")
addCommandAlias("neuroim4sTestAll", ";spacesJVM/test;spacesJS/test")
