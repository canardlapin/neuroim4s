addSbtPlugin("org.scala-js" % "sbt-scalajs" % "1.22.0")
addSbtPlugin("org.portable-scala" % "sbt-scalajs-crossproject" % "1.3.2")

// 0.1.1 labels a Java-23 classfile as a Java-22 multi-release entry; pin the
// corrected artifact so formatter resolution works on maintained JDKs.
dependencyOverrides += "io.github.alexarchambault" % "is-terminal" % "0.1.2"
