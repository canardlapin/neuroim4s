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

lazy val image4sRevision = "7885f67798242412800a448eda68acfbfff4f751"
lazy val image4sBuild = {
  Seq("spatial4s", "locus4s", "gale", "ravel").foreach { provider =>
    sys.props.get(s"neuroim4s.$provider.build")
      .foreach(System.setProperty(s"image4s.$provider.build", _))
  }
  pinned("image4s", image4sRevision)
}
lazy val image4sGeometryJVM = ProjectRef(image4sBuild, "image4s-geometryJVM")
lazy val image4sGeometryJS = ProjectRef(image4sBuild, "image4s-geometryJS")

lazy val image4sCoreJVM = ProjectRef(image4sBuild, "image4s-coreJVM")
lazy val image4sCoreJS = ProjectRef(image4sBuild, "image4s-coreJS")
lazy val image4sLocusJVM = ProjectRef(image4sBuild, "image4s-locusJVM")
lazy val image4sLocusJS = ProjectRef(image4sBuild, "image4s-locusJS")
lazy val image4sFilterJVM = ProjectRef(image4sBuild, "image4s-filterJVM")
lazy val image4sFilterJS = ProjectRef(image4sBuild, "image4s-filterJS")
lazy val image4sNiftiJVM = ProjectRef(image4sBuild, "image4s-niftiJVM")
lazy val galeRevision = "779ed3d8169cdd9f4eb9ab10b1ca9defadc27715"
lazy val galeBuild = pinned("gale", galeRevision)
lazy val galeCoreJVM = ProjectRef(galeBuild, "coreJVM")
lazy val galeCoreJS = ProjectRef(galeBuild, "coreJS")
lazy val reframe4sRevision = "e4df73969e1fccb7aa0153a9203927c6dd8b0e3d"
lazy val reframe4sBuild = {
  Seq("image4s", "spatial4s", "gale", "ravel", "locus4s").foreach { provider =>
    sys.props.get(s"neuroim4s.$provider.build")
      .foreach(System.setProperty(s"reframe4s.$provider.build", _))
  }
  pinned("reframe4s", reframe4sRevision)
}
lazy val reframe4sLieJVM = ProjectRef(reframe4sBuild, "reframe4s-lieJVM")
lazy val reframe4sLieJS = ProjectRef(reframe4sBuild, "reframe4s-lieJS")
lazy val reframe4sFieldJVM = ProjectRef(reframe4sBuild, "reframe4s-fieldJVM")
lazy val reframe4sFieldJS = ProjectRef(reframe4sBuild, "reframe4s-fieldJS")
lazy val reframe4sResampleJVM = ProjectRef(reframe4sBuild, "reframe4s-resampleJVM")
lazy val reframe4sResampleJS = ProjectRef(reframe4sBuild, "reframe4s-resampleJS")
lazy val coreBoundaryCheck = taskKey[Unit]("Preserve frame ownership and prevent public provider-state facades")

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

// Neuroimaging policy; image4s remains the sole geometry representation.
// This module does not depend on ScalaFIM or its surface/atlas/MVPA integrations.
lazy val core =
  crossProject(JSPlatform, JVMPlatform)
    .crossType(CrossType.Full)
    .in(file("modules/neuroim4s-core"))
    .dependsOn(spaces)
    .settings(commonSettings)
    .settings(
      name := "neuroim4s-core",
      description := "Neuroimaging values and policy over provider-owned image geometry.",
      libraryDependencies ++= Seq(
        "org.typelevel" %%% "cats-core" % "2.12.0",
        "org.typelevel" %%% "spire" % "0.18.0",
        "org.scalameta" %%% "munit-scalacheck" % "1.1.0" % Test,
        "org.typelevel" %%% "cats-effect" % "3.5.4" % Test
      ),
      Test / scalacOptions += "-Wconf:src=.*NeuroImageErrorContractSuite\\.scala&id=E29:e",
      coreBoundaryCheck := {
        val repository = (LocalRootProject / baseDirectory).value
        FrameErasureGate.selfTest()
        val violations = FrameErasureGate.check(repository)
        if (violations.nonEmpty) sys.error(violations.mkString("Frame erasure:\n", "\n", ""))
        val source = repository / "modules/neuroim4s-core/shared/src/main/scala/neuroim4s/image/SampleSpaces.scala"
        val publicAliases = "(?m)^\\s+(?:inline\\s+)?def\\s+(?:dims|ndim|spatialDims|spatialShape|spacing|origin|orientation|affineD3|inverseAffineD3|gridToIndex|indexToGrid|gridToIndex3D|indexToGrid3D|indexToVoxel3D|spatialSpace|addDim|dropDim|indexToCoord|indexToPoint|voxelToWorld|coordToIndex|coordToIndexPoint|worldToVoxel)\\b".r
        if (publicAliases.findFirstIn(IO.read(source)).nonEmpty) sys.error("Public provider-state facade returned")
        val packageSource = source.getParentFile / "package.scala"
        if ("(?m)^\\s*export SampleSpaces\\.\\*\\s*$".r.findFirstIn(IO.read(packageSource)).nonEmpty)
          sys.error("Package-wide SampleSpaces export returned")
        if (libraryDependencies.value.exists(module => module.organization == "ai.dragonfly" && module.name.contains("slash")))
          sys.error("Obsolete image algebra dependency returned")
      },
      Compile / compile := (Compile / compile).dependsOn(coreBoundaryCheck).value
    )
    .jvmConfigure(_.dependsOn(image4sGeometryJVM, image4sCoreJVM, image4sLocusJVM, image4sFilterJVM, galeCoreJVM, reframe4sLieJVM, reframe4sFieldJVM, reframe4sResampleJVM))
    .jsConfigure(_.dependsOn(image4sGeometryJS, image4sCoreJS, image4sLocusJS, image4sFilterJS, galeCoreJS, reframe4sLieJS, reframe4sFieldJS, reframe4sResampleJS))
    .jsSettings(jsSettings)

// Optional platform IO; shared core has no dependency on file formats.
lazy val ioJVM = project
  .in(file("modules/neuroim4s-io/jvm"))
  .dependsOn(core.jvm % "compile->compile;test->test", image4sNiftiJVM)
  .settings(commonSettings)
  .settings(name := "neuroim4s-io")

lazy val root =
  project
    .in(file("."))
    .aggregate(spaces.jvm, spaces.js, core.jvm, core.js, ioJVM)
    .settings(name := "neuroim4s-root", publish / skip := true)

addCommandAlias("neuroim4sCompileAll", ";root/compile;root/Test/compile")
addCommandAlias("neuroim4sTestAll", ";spacesJVM/test;spacesJS/test;coreJVM/test;coreJS/test;ioJVM/test")
